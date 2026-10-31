package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapNetworking;
import dev.kizuna.meridian.technique.fabric.world.TechniqueSounds;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStaggers;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.server.TechniqueHostDirectory;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueAddonResources;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueSoundCues;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Fabric 服务端装配；身体和世界效果由 meridian_technique_host 实现。 */
public final class TechniqueFabric implements ModInitializer {
    public static final String REGISTRY_ENTRYPOINT = "meridian_technique_catalog";
    private static final int LEARN_PERMISSION_LEVEL = 2;
    private static final Logger LOG = LoggerFactory.getLogger(TechniqueFabric.class);
    public static final Event<KnownChangedCallback> KNOWN_CHANGED = EventFactory.createArrayBacked(
            KnownChangedCallback.class,
            callbacks -> (entityUuid, known) -> {
                for (KnownChangedCallback callback : callbacks) {
                    callback.onKnownChanged(entityUuid, known);
                }
            });
    /** 调试用途，不进正式玩法：每份施放回执按实体 UUID 转发，注册一次即跨服务器重启有效。 */
    public static final Event<CastObservedCallback> CAST_OBSERVED = EventFactory.createArrayBacked(
            CastObservedCallback.class,
            callbacks -> (entityUuid, outcome) -> {
                for (CastObservedCallback callback : callbacks) {
                    callback.onCastObserved(entityUuid, outcome);
                }
            });

    private static TechniqueService service;
    private TechniqueCatalogPlan catalogs;

    public static TechniqueService service() {
        // 宿主和界面只在服务端主线程访问当前服务器服务，未启动时明确拒绝。
        if (service == null) {
            throw new IllegalStateException("功法服务不可用：服务器尚未启动、已经停止或没有功法目录");
        }
        return service;
    }

    @Override
    public void onInitialize() {
        // 两端在注册表冻结前固定卷轴身份；身体服务仍只由服务端生命周期创建。
        var resources = new TechniqueAddonResources(TechniqueFabric.class.getClassLoader());
        catalogs = TechniqueCatalogDiscovery.prepare(resources::registry);
        TechniqueScrollItems.register(new TechniqueScrollCatalog(catalogs.earlySources()));
        TechniqueStatusBenefits.register(catalogs.earlySources());
        TechniqueLeapNetworking.initialize();
        // 服务端发往客户端的通道也要在公共初始化里登记，客户端才能解码。
        TechniqueStaggers.CHANNEL.registerClientbound();
        TechniqueScrollNetworking.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> disconnect(handler.player.getUUID()));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> unload(entity));
        registerCommands();
    }

    private void start(MinecraftServer server) {
        // 服务启动后读取所有 meridian_technique_host 入口；没有目录时命令仍安全拒绝。
        TechniqueRegistry registry = catalogs.registry();
        if (registry == null) {
            service = null;
            return;
        }
        List<TechniqueHostEntrypoint> entrypoints = FabricLoader.getInstance()
                .getEntrypoints(TechniqueHostEntrypoint.ENTRYPOINT_ID, TechniqueHostEntrypoint.class);
        TechniqueService created = new TechniqueService(registry, new TechniqueHostDirectory(entrypoints));
        created.addKnownChangedListener(event -> KNOWN_CHANGED.invoker()
                .onKnownChanged(event.entityUuid(), event.known()));
        created.addCastListener(event -> CAST_OBSERVED.invoker()
                .onCastObserved(event.entityUuid(), event.outcome()));
        created.addStaggerListener(TechniqueStaggers.listener(server));
        created.addSoundListener(TechniqueSounds.listener(server));
        warnUnregisteredSounds(registry);
        service = created;
    }

    private static void warnUnregisteredSounds(TechniqueRegistry registry) {
        // 纯数据功法书可在客户端资源里声明声音事件；服务端注册表没有时只提示，播放时照常内联下发。
        Predicate<String> registered = id -> BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse(id));
        for (String id : TechniqueSoundCues.unregistered(registry.definitions(), registered)) {
            LOG.warn("功法目录引用的声音 {} 不在服务端声音注册表中，将以内联事件下发；客户端缺少该资源时无声", id);
        }
    }

    private void stop() {
        // 服务器销毁世界前取消活动，宿主可在仍有权限时写回清理活动。
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    private void tick() {
        // TechniqueService 内部按已登记施放推进，不按毫秒创建定时任务。
        if (service != null) {
            service.tick();
        }
    }

    private void disconnect(UUID entityUuid) {
        // 玩家断线事件在宿主撤销身体访问前清除当前动作。
        if (service != null) {
            service.cancelOnDisconnect(entityUuid);
        }
    }

    private void unload(Entity entity) {
        // 玩家断线由 DISCONNECT 处理，实体卸载事件只取消其他实体，避免重复提交。
        if (service != null && !(entity instanceof ServerPlayer)) {
            service.cancelOnUnload(entity.getUUID());
        }
    }

    private void registerCommands() {
        // /meridian technique learn [<targets>] <id>：只开放给权限 2，省略目标时学给执行者本人；
        // 功法 ID 从服务端不可变目录解析，书、卷和式都可补全。
        MeridianCommands.register(MeridianCommands.Branch.TECHNIQUE, "learn", LEARN_PERMISSION_LEVEL, (node, context) ->
                MeridianCommands.optionalEntities(node, (parent, targets) -> parent.then(
                        Commands.argument("id", IdentifierArgument.id())
                                .suggests((command, builder) ->
                                        SharedSuggestionProvider.suggestResource(learnableIds(), builder))
                                .executes(command -> learn(command.getSource(), targets.resolve(command),
                                        IdentifierArgument.getId(command, "id").toString())))));
    }

    private static List<Identifier> learnableIds() {
        // 从当前服务器目录同时提供书、卷和式，命名空间过滤交给原版补全器。
        if (service == null) {
            return List.of();
        }
        var registry = service.registry();
        return Stream.of(registry.books().stream().map(book -> book.getId()),
                        registry.volumes().stream().map(volume -> volume.getId()),
                        registry.definitions().stream().map(definition -> definition.getId()))
                .flatMap(ids -> ids)
                .distinct()
                .sorted()
                .map(Identifier::parse)
                .toList();
    }

    private int learn(CommandSourceStack source, Collection<? extends Entity> entities, String id) {
        // 每个目标单独经过宿主认领和 Framework 原子进度活动，部分失败不影响其他目标。
        if (service == null) {
            source.sendFailure(Component.literal("功法目录尚未就绪"));
            return 0;
        }
        var registry = service.registry();
        if (registry.findVolume(id).isEmpty() && registry.findLatest(id).isEmpty()
                && registry.books().stream().noneMatch(book -> book.getId().equals(id))) {
            source.sendFailure(Component.literal("目录中没有这本书、卷或式"));
            return 0;
        }
        int learned = 0;
        for (Entity entity : entities) {
            try {
                service.learn(entity.getUUID(), id);
                learned++;
            } catch (TechniqueService.PrerequisiteVolumeException exception) {
                source.sendFailure(Component.literal(exception.getMessage()));
            } catch (TechniqueService.NoHostException exception) {
                source.sendFailure(Component.literal("目标没有可用的功法宿主"));
            } catch (RuntimeException exception) {
                source.sendFailure(Component.literal("这门功法暂时无法学会"));
            }
        }
        return learned;
    }

    @FunctionalInterface
    public interface KnownChangedCallback {
        /** 向界面或其他附属发布当前实体的完整已学列表。 */
        void onKnownChanged(UUID entityUuid, List<TechniqueService.KnownEntry> known);
    }

    @FunctionalInterface
    public interface CastObservedCallback {
        /** 调试用途，不进正式玩法：转述功法服务返回给施放方的同一份回执。 */
        void onCastObserved(UUID entityUuid, CastOutcome outcome);
    }
}
