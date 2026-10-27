package dev.kizuna.meridian.technique.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.kizuna.meridian.technique.server.TechniqueHostDirectory;
import dev.kizuna.meridian.technique.server.TechniqueHostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import dev.kizuna.meridian.technique.server.TechniqueRegistryEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fabric 服务端装配；身体和世界效果由 meridian_technique_host 实现。 */
public final class TechniqueFabric implements ModInitializer {
    public static final String REGISTRY_ENTRYPOINT = "meridian_technique_catalog";
    private static final int LEARN_PERMISSION_LEVEL = 2;
    public static final Event<KnownChangedCallback> KNOWN_CHANGED = EventFactory.createArrayBacked(
            KnownChangedCallback.class,
            callbacks -> (entityUuid, known) -> {
                for (KnownChangedCallback callback : callbacks) {
                    callback.onKnownChanged(entityUuid, known);
                }
            });

    private static TechniqueService service;

    public static TechniqueService service() {
        // 宿主和界面只在服务端主线程访问当前服务器服务，未启动时明确拒绝。
        if (service == null) {
            throw new IllegalStateException("功法服务不可用：服务器尚未启动、已经停止或没有功法目录");
        }
        return service;
    }

    @Override
    public void onInitialize() {
        // 所有状态变化都在服务端事件线程进入纯 Java TechniqueService。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> start());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> disconnect(handler.player.getUuid()));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> unload(entity));
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    private void start() {
        // 服务启动后读取所有 meridian_technique_host 入口；没有目录时命令仍安全拒绝。
        TechniqueRegistry registry = registry();
        if (registry == null) {
            service = null;
            return;
        }
        List<TechniqueHostEntrypoint> entrypoints = FabricLoader.getInstance()
                .getEntrypoints(TechniqueHostEntrypoint.ENTRYPOINT_ID, TechniqueHostEntrypoint.class);
        TechniqueService created = new TechniqueService(registry, new TechniqueHostDirectory(entrypoints));
        created.addKnownChangedListener(event -> KNOWN_CHANGED.invoker()
                .onKnownChanged(event.entityUuid(), event.known()));
        service = created;
    }

    private TechniqueRegistry registry() {
        // 内容附属通过唯一目录入口提供不可变目录。
        List<TechniqueRegistryEntrypoint> providers = FabricLoader.getInstance()
                .getEntrypoints(REGISTRY_ENTRYPOINT, TechniqueRegistryEntrypoint.class);
        if (providers.isEmpty()) {
            return null;
        }
        if (providers.size() > 1) {
            throw new IllegalStateException("功法目录 entrypoint 只能有一个");
        }
        return providers.get(0).registry();
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
        if (service != null && !(entity instanceof ServerPlayerEntity)) {
            service.cancelOnUnload(entity.getUuid());
        }
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                  CommandRegistryAccess registryAccess,
                                  CommandManager.RegistrationEnvironment environment) {
        // 管理命令只开放给权限 2，功法 ID 从服务端不可变目录解析。
        dispatcher.register(CommandManager.literal("meridian_technique")
                .requires(source -> source.hasPermissionLevel(LEARN_PERMISSION_LEVEL))
                .then(CommandManager.literal("learn")
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .then(CommandManager.argument("id", IdentifierArgumentType.identifier())
                                        .executes(context -> learn(context.getSource(),
                                                EntityArgumentType.getEntities(context, "targets"),
                                                IdentifierArgumentType.getIdentifier(context, "id").toString()))))));
    }

    private int learn(ServerCommandSource source, Collection<? extends Entity> entities, String id) {
        // 每个目标单独经过宿主认领和 Framework 原子进度活动，部分失败不影响其他目标。
        if (service == null) {
            source.sendError(Text.literal("功法目录尚未就绪"));
            return 0;
        }
        Optional<TechniqueDefinition> definition = service.registry().findLatest(id);
        if (definition.isEmpty()) {
            source.sendError(Text.literal("目录中没有这门功法"));
            return 0;
        }
        int learned = 0;
        for (Entity entity : entities) {
            try {
                service.learn(entity.getUuid(), definition.get());
                learned++;
            } catch (TechniqueService.NoHostException exception) {
                source.sendError(Text.literal("目标没有可用的功法宿主"));
            } catch (RuntimeException exception) {
                source.sendError(Text.literal("这门功法暂时无法学会"));
            }
        }
        return learned;
    }

    @FunctionalInterface
    public interface KnownChangedCallback {
        /** 向界面或其他附属发布当前实体的完整已学列表。 */
        void onKnownChanged(UUID entityUuid, List<TechniqueService.KnownEntry> known);
    }
}
