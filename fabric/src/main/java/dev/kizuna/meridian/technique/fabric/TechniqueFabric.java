package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapNetworking;
import dev.kizuna.meridian.technique.fabric.world.TechniqueSounds;
import dev.kizuna.meridian.technique.fabric.world.TechniqueCastStatusSync;
import dev.kizuna.meridian.technique.fabric.world.TechniqueHitFeel;
import dev.kizuna.meridian.technique.fabric.world.TechniqueInfusionStatuses;
import dev.kizuna.meridian.technique.fabric.world.TechniqueBreathingStatuses;
import dev.kizuna.meridian.technique.fabric.world.TechniqueInputBuffer;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponHeat;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponBinds;
import dev.kizuna.meridian.technique.fabric.world.TechniqueSplashDrops;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStaggers;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.fabric.world.TechniqueTissueEffects;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueHostDirectory;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.EffectHandling;
import dev.kizuna.meridian.technique.server.TechniqueEffectEvent;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueAddonResources;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.technique.server.TechniqueSoundCues;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
import net.minecraft.world.InteractionResult;
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
    /** 一次施放结束后通知附属释放方块、实体或物品接管。 */
    public static final Event<CastEndedCallback> CAST_ENDED = EventFactory.createArrayBacked(
            CastEndedCallback.class,
            callbacks -> event -> {
                for (CastEndedCallback callback : callbacks) {
                    callback.onCastEnded(event);
                }
            });
    /** 附属模组执行 block_work/use_brew；处理顺序由注册顺序决定。 */
    public static final Event<EffectHandlerCallback> EFFECT_HANDLER = EventFactory.createArrayBacked(
            EffectHandlerCallback.class,
            callbacks -> event -> {
                for (EffectHandlerCallback callback : callbacks) {
                    EffectHandling result = callback.onEffect(event);
                    if (result.status() != EffectHandling.Status.NOT_APPLICABLE) {
                        return result;
                    }
                }
                return EffectHandling.rejected("没有接受该效果的附属处理器");
            });

    private static TechniqueService service;
    /** 服务装配完毕后校验附属与最终目录的跨仓契约，拒绝会中止服务器启动。 */
    public static final Event<ServiceStartedCallback> SERVICE_STARTED = EventFactory.createArrayBacked(
            ServiceStartedCallback.class,
            callbacks -> created -> {
                for (ServiceStartedCallback callback : callbacks) {
                    callback.onServiceStarted(created);
                }
            });
    private TechniqueCatalogPlan catalogs;

    public static TechniqueService service() {
        // 宿主和界面只在服务端主线程访问当前服务器服务，未启动时明确拒绝。
        if (service == null) {
            throw new IllegalStateException("功法服务不可用：服务器尚未启动、已经停止或没有功法目录");
        }
        return service;
    }

    public static boolean running() {
        // 掉落条件在服务未起时直接判不会，不抛出到原版掉落流程。
        return service != null;
    }

    @Override
    public void onInitialize() {
        // 两端在注册表冻结前固定卷轴身份；身体服务仍只由服务端生命周期创建。
        var resources = new TechniqueAddonResources(TechniqueFabric.class.getClassLoader());
        catalogs = TechniqueCatalogDiscovery.prepare(resources::registry);
        TechniqueCodex.register();
        TechniqueScrollItems.register(new TechniqueScrollCatalog(catalogs.earlySources()));
        TechniqueStatusBenefits.register(catalogs.earlySources());
        UseItemCallback.EVENT.register((player, level, hand) ->
                TechniqueWeaponBinds.blocksWeaponUse(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS);
        TechniqueInfusionStatuses.register(catalogs.earlySources());
        for (var source : catalogs.earlySources()) {
            for (var definition : source.catalog().getDefinitionsList()) {
                if (definition.hasMaintainedBreathing()) {
                    TechniqueBreathingStatuses.register(definition.getMaintainedBreathing());
                }
            }
        }
        TechniqueScrollDrops.register();
        VolumeLearnedTrigger.register();
        BookCompletedTrigger.register();
        CodexCompletedTrigger.register();
        TechniqueCodexProgress.initialize();
        TechniqueVolumeSources.initialize();
        TechniqueLeapNetworking.initialize();
        // 服务端发往客户端的通道也要在公共初始化里登记，客户端才能解码。
        TechniqueStaggers.CHANNEL.registerClientbound();
        TechniqueHitFeel.initialize();
        TechniqueCastStatusSync.CHANNEL.registerClientbound();
        TechniqueScrollNetworking.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerTickEvents.END_SERVER_TICK.register(TechniqueSplashDrops::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> disconnect(handler.player));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> unload(entity));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (service != null) {
                service.cancelOnDeath(entity.getUUID());
            }
        });
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
        created.addKnownChangedListener(event -> TechniqueCodexProgress.knownChanged(server, event.entityUuid(),
                event.known()));
        created.addCastListener(event -> CAST_OBSERVED.invoker()
                .onCastObserved(event.entityUuid(), event.outcome()));
        created.addCastEndListener(event -> CAST_ENDED.invoker().onCastEnded(event));
        created.addCastEndListener(event -> reportFailure(server, event));
        created.addStaggerListener(TechniqueStaggers.listener(server));
        created.addInfusionListener(TechniqueInfusionStatuses.listener(server));
        TechniqueCastStatusSync.listen(server, created);
        TechniqueInputBuffer.start();
        created.addSoundListener(TechniqueSounds.listener(server));
        created.setEffectHandler(event -> {
            var intent = event.intent();
            if (intent.hasStatusBenefit() && intent.getStatusBenefit().hasWeaponHeat()) {
                return TechniqueWeaponHeat.apply(server, event);
            }
            return EFFECT_HANDLER.invoker().onEffect(event);
        });
        warnUnregisteredSounds(registry);
        service = created;
        SERVICE_STARTED.invoker().onServiceStarted(created);
        TechniqueVolumeSources.collect(server);
    }

    private static void reportFailure(MinecraftServer server, TechniqueCastEnded event) {
        // 周期效果的失败晚于起手回执，仍使用施放失败的动作栏消息送回同一玩家。
        CastOutcome outcome = event.outcome();
        if (outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(event.entityUuid());
        if (player != null && player.connection != null) {
            player.sendSystemMessage(Component.literal(outcome.getReason()), true /* overlay */);
        }
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
            TechniqueTissueEffects.suspendAll();
            service.shutdown();
            service = null;
        }
    }

    private void tick(MinecraftServer server) {
        // TechniqueService 内部按已登记施放推进，不按毫秒创建定时任务；推进完再替玩家重试记着的按键。
        if (service != null) {
            service.tick();
            TechniqueInputBuffer.tick(server);
        }
    }

    private void disconnect(ServerPlayer player) {
        // 玩家断线事件在宿主撤销身体访问前清除当前动作。
        if (service != null) {
            TechniqueTissueEffects.of(player).suspend();
            TechniqueInputBuffer.clear(player.getUUID());
            service.cancelOnDisconnect(player.getUUID());
        }
    }

    private void unload(Entity entity) {
        // 跨维度等卸载也包含玩家；会话按施放身份去重，断线后重复卸载不会重发通知。
        if (service != null) {
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
            } catch (TechniqueService.PrerequisiteVolumeException | TechniqueService.BodyTemplateMismatchException
                    | TechniqueService.InnateLockedException exception) {
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

    @FunctionalInterface
    public interface ServiceStartedCallback {
        /** 附属在正式目录与服务就绪后验证接线；失败不得静默继续运行。 */
        void onServiceStarted(TechniqueService created);
    }

    @FunctionalInterface
    public interface EffectHandlerCallback {
        /** 附属模组执行一次声明式世界效果并返回是否接受。 */
        EffectHandling onEffect(TechniqueEffectEvent event);
    }

    @FunctionalInterface
    public interface CastEndedCallback {
        /** 向附属模组转发一次施放的最终结束事实。 */
        void onCastEnded(TechniqueCastEnded event);
    }
}
