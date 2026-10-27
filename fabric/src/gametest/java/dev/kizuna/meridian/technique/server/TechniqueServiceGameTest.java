package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.*;

/** Fabric 服务端主线程上的功法服务旅程与生命周期取消。 */
public final class TechniqueServiceGameTest implements FabricGameTest {
    private static final long FINISHED_MILLIS = WARMUP + ACTIVE + RECOVERY;
    private static final long EVENT_WAIT_TICKS = 2;
    private static final int START_AND_IMPULSE_COUNT = 2;
    private static final int ALL_EFFECTS_COUNT = 3;
    private static final double QI_TOLERANCE = 1e-9;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void learnCommandPublishesKnownChangedAndTicksToFinish(TestContext context) {
        // 管理命令、公开服务入口、Fabric tick 与宿主效果使用同一服务器服务。
        Prepared prepared = prepare(context);
        List<TechniqueService.KnownEntry> changes = new ArrayList<>();
        UUID entityUuid = prepared.entity().getUuid();
        TechniqueFabric.KNOWN_CHANGED.register((changedUuid, known) -> {
            if (changedUuid.equals(entityUuid)) {
                changes.addAll(known);
            }
        });
        MinecraftServer server = context.getWorld().getServer();
        int learned = server.getCommandManager().executeWithPrefix(server.getCommandSource(),
                "meridian_technique learn " + entityUuid + " " + TECHNIQUE_ID);
        TechniqueService service = TechniqueFabric.service();
        context.assertTrue(learned == 1 && changes.size() == 1, "学会命令或 KNOWN_CHANGED 事件未生效");
        context.assertTrue(service.known(entityUuid).equals(changes), "known 没有返回已提交的学会记录");
        context.assertTrue(service.cast(entityUuid, request(prepared.host().current(), "gametest"))
                        .getPhase() == CastPhase.WARMUP,
                "学会后施放未进入起手");
        prepared.host().nowMillis = WARMUP;
        context.waitAndRun(EVENT_WAIT_TICKS, () -> assertEffectsAndFinish(context, prepared));
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void damageInterruptCancels(TestContext context) {
        // 宿主可从公开访问点取得服务，在可信伤害事件中打断起手。
        Prepared prepared = prepare(context);
        UUID entityUuid = prepared.entity().getUuid();
        TechniqueService service = TechniqueFabric.service();
        service.learn(entityUuid, prepared.reference());
        CastOutcome cast = service.cast(entityUuid, request(prepared.host().current(), "gametest_damage"));
        CastOutcome interrupted = service.interruptByDamage(entityUuid).orElseThrow();
        context.assertTrue(cast.getPhase() == CastPhase.WARMUP, "伤害测试未进入起手");
        context.assertTrue(interrupted.getPhase() == CastPhase.INTERRUPTED, "伤害没有打断起手");
        assertCancelledWithoutRefund(context, prepared);
        release(prepared);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void discardTriggersEntityUnloadAndClearsSession(TestContext context) {
        // discard 触发真实 ENTITY_UNLOAD，不能用直接调用服务来代替事件接线。
        Prepared prepared = prepare(context);
        UUID entityUuid = prepared.entity().getUuid();
        TechniqueService service = TechniqueFabric.service();
        service.learn(entityUuid, prepared.reference());
        CastOutcome cast = service.cast(entityUuid, request(prepared.host().current(), "gametest_unload"));
        context.assertTrue(cast.getPhase() == CastPhase.WARMUP, "卸载测试未进入起手");
        prepared.entity().discard();
        context.waitAndRun(EVENT_WAIT_TICKS, () -> {
            assertCancelledWithoutRefund(context, prepared);
            context.assertTrue(service.cancelOnUnload(entityUuid).isEmpty(), "卸载事件没有移除会话");
            TechniqueGameTestHost.unbind(entityUuid);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void learnCommandRejectsUnknownIdAndInsufficientPermission(TestContext context) {
        // 管理命令拒绝目录外 ID 和权限不足的调用，身体知识保持不变。
        Prepared prepared = prepare(context);
        MinecraftServer server = context.getWorld().getServer();
        String command = "meridian_technique learn " + prepared.entity().getUuid() + " ";
        int unknown = server.getCommandManager().executeWithPrefix(server.getCommandSource(),
                command + "sample:missing");
        int denied = server.getCommandManager().executeWithPrefix(server.getCommandSource().withLevel(1),
                command + TECHNIQUE_ID);
        context.assertTrue(unknown == 0 && denied == 0, "非法学会命令没有拒绝");
        context.assertTrue(TechniqueFabric.service().known(prepared.entity().getUuid()).isEmpty(),
                "非法命令写入了知识");
        release(prepared);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void unclaimedSavedCastRecoversWithoutEffects(TestContext context) {
        // 身体已经从实体目录撤销，公开服务仍可经可信恢复入口取消旧档中的施放。
        Prepared prepared = prepare(context);
        TechniqueService service = TechniqueFabric.service();
        UUID entityUuid = prepared.entity().getUuid();
        service.learn(entityUuid, prepared.reference());
        context.assertTrue(service.cast(entityUuid, request(prepared.host().current(), "saved_live"))
                .getPhase() == CastPhase.WARMUP, "没有开始施放");
        MemoryHost restored = prepared.host().restore();
        assertRecovered(context, prepared, restored);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void unclaimedDeadCastRecoversWithoutRefund(TestContext context) {
        // Framework 死亡清空动作后仍可恢复附属进度，死体不再永久保留施放和会话。
        Prepared prepared = prepare(context);
        TechniqueService service = TechniqueFabric.service();
        UUID entityUuid = prepared.entity().getUuid();
        service.learn(entityUuid, prepared.reference());
        context.assertTrue(service.cast(entityUuid, request(prepared.host().current(), "saved_dead"))
                .getPhase() == CastPhase.WARMUP, "没有开始施放");
        prepared.host().die();
        MemoryHost restored = prepared.host().restore();
        context.assertTrue(TechniqueState.read(restored.current()).hasActiveCast(), "没有形成死体残留");
        assertRecovered(context, prepared, restored);
        context.assertTrue(restored.current().vitality().dead(), "恢复改变了死亡事实");
        context.complete();
    }

    private static void assertRecovered(TestContext context, Prepared prepared, MemoryHost restored) {
        // 真实存档恢复后保留费用、冷却与知识；恢复本身无世界效果且只提交一次。
        UUID entityUuid = prepared.entity().getUuid();
        TechniqueGameTestHost.unbind(entityUuid);
        var before = TechniqueState.read(restored.current());
        var released = restored.store.environmentQi();
        TechniqueService service = TechniqueFabric.service();
        service.recoverOnLoad(entityUuid, restored);
        context.assertTrue(TechniqueState.read(restored.current())
                .equals(before.toBuilder().clearActiveCast().build()), "恢复改变了冷却或学会记录");
        context.assertTrue(restored.current().actions().isEmpty(), "恢复遗留动作");
        context.assertTrue(restored.store.environmentQi().equals(released), "恢复退费或改变释放");
        context.assertTrue(restored.effects.isEmpty(), "恢复补发了效果");
        var recovered = restored.current();
        service.recoverOnLoad(entityUuid, restored);
        context.assertTrue(restored.current().equals(recovered), "恢复重试产生重复提交");
        context.assertTrue(service.cancelOnUnload(entityUuid).isEmpty(), "恢复保留了旧会话");
        release(prepared);
    }

    private static Prepared prepare(TestContext context) {
        // 每项测试生成独立实体并经测试 entrypoint 绑定空知识身体。
        Entity entity = context.spawnMob(EntityType.PIG, 1, 1, 1);
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), TechniqueState.empty());
        MemoryHost host = new MemoryHost(initial);
        TechniqueGameTestHost.bind(entity.getUuid(), host);
        TechniqueDefinition definition = TechniqueFabric.service().registry().findLatest(TECHNIQUE_ID).orElseThrow();
        return new Prepared(entity, host, TechniqueRegistry.reference(definition));
    }

    private static void assertEffectsAndFinish(TestContext context, Prepared prepared) {
        // 真实服务端 tick 应下发持续表现与冲量，随后再跨 tick 完成收势和 STOP。
        MemoryHost host = prepared.host();
        context.assertTrue(host.effects.size() == START_AND_IMPULSE_COUNT, "tick 未下发全部到期意图");
        context.assertTrue(host.effects.get(0).getPresentation().getAction() == PresentationAction.START,
                "持续表现没有开始");
        context.assertTrue(host.effects.get(1).getImpulse().getDirection() == ImpulseDirection.FORWARD,
                "零输入没有按朝向解释");
        host.nowMillis = FINISHED_MILLIS;
        context.waitAndRun(EVENT_WAIT_TICKS, () -> {
            context.assertTrue(!TechniqueState.read(host.current()).hasActiveCast(), "收势没有清除当前施放");
            context.assertTrue(host.effects.size() == ALL_EFFECTS_COUNT, "收势没有下发停止意图");
            context.assertTrue(host.effects.get(ALL_EFFECTS_COUNT - 1).getPresentation().getAction()
                    == PresentationAction.STOP, "持续表现没有停止");
            context.assertTrue(TechniqueFabric.service().cancelOnUnload(prepared.entity().getUuid()).isEmpty(),
                    "收势后仍保留会话");
            release(prepared);
            context.complete();
        });
    }

    private static void assertCancelledWithoutRefund(TestContext context, Prepared prepared) {
        // 取消必须清施放和动作，同时保留起手费用及冷却。
        MemoryHost host = prepared.host();
        TechniqueProgress progress = TechniqueState.read(host.current());
        context.assertTrue(!progress.hasActiveCast() && host.current().actions().isEmpty(), "取消没有清除动作");
        context.assertTrue(Math.abs(host.store.environmentQi().totalQi() - COST_QI) <= QI_TOLERANCE,
                "取消退回了已支付费用");
        context.assertTrue(progress.getCooldownsOrThrow(TECHNIQUE_ID) == COOLDOWN, "取消丢失了冷却");
    }

    private static void release(Prepared prepared) {
        // 先经真实卸载路径清理实体，再撤销宿主认领。
        prepared.entity().discard();
        TechniqueGameTestHost.unbind(prepared.entity().getUuid());
    }

    private record Prepared(Entity entity, MemoryHost host, TechniqueReference reference) { }
}
