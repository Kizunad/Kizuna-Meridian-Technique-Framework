package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.*;

/** Fabric 服务端主线程上的功法服务旅程与生命周期取消。 */
public final class TechniqueServiceGameTest {
    private static final long FINISHED_MILLIS = WARMUP + ACTIVE + RECOVERY;
    private static final long EVENT_WAIT_TICKS = 2;
    private static final int START_AND_IMPULSE_COUNT = 2;
    private static final int ALL_EFFECTS_COUNT = 3;
    private static final int VOLUME_TECHNIQUE_COUNT = 2;
    private static final int LOCKED_EFFECTS_COUNT = 4;
    private static final double QI_TOLERANCE = 1e-9;
    private static final String COMBAT_FIRST_CAST = "combat_first";
    private static final String COMBAT_NEXT_CAST = "combat_next";
    private static final long NEXT_HIT = WINDOW_START + WARMUP - CLIP_OFFSET;
    private static final long NEXT_SECOND_HIT = WINDOW_START + SECOND_STRIKE - CLIP_OFFSET;
    private static final long NEXT_END = WINDOW_START + CAST_END - CLIP_OFFSET;
    private static final int SWEEP_HIT_COUNT = 2;

    @GameTest
    public void discoversManifestAlongsideEntrypointAndLearnsItsVolume(GameTestHelper context) {
        // 清单仅由测试模组元数据声明，服务启动后与旧入口目录一起可学习。
        Prepared prepared = prepare(context, "sample_manifest:technique");
        TechniqueService service = TechniqueFabric.service();
        boolean discovered = service.registry().sources().stream()
                .anyMatch(source -> source.source().equals(
                        "meridian_technique_test:data/sample_manifest/catalog.txtpb"));
        context.assertTrue(discovered, "清单未通过模组元数据发现");
        context.assertTrue(service.registry().findLatest(TECHNIQUE_ID).isPresent(), "旧 Java 入口目录未合并");
        UUID entityUuid = prepared.entity().getUUID();
        var learned = service.learn(entityUuid, "sample_manifest:volume");
        context.assertTrue(learned.size() == 1 && service.known(entityUuid).size() == 1, "清单卷未写入已学进度");
        release(prepared);
        context.succeed();
    }

    @GameTest
    public void learnCommandPublishesKnownChangedAndTicksToFinish(GameTestHelper context) {
        // 管理命令、公开服务入口、Fabric tick 与宿主效果使用同一服务器服务。
        Prepared prepared = prepare(context);
        List<TechniqueService.KnownEntry> changes = new ArrayList<>();
        UUID entityUuid = prepared.entity().getUUID();
        TechniqueFabric.KNOWN_CHANGED.register((changedUuid, known) -> {
            if (changedUuid.equals(entityUuid)) {
                changes.addAll(known);
            }
        });
        MinecraftServer server = context.getLevel().getServer();
        int learned = GameTestCommands.run(server.createCommandSourceStack(),
                "meridian technique learn " + entityUuid + " " + TECHNIQUE_ID);
        TechniqueService service = TechniqueFabric.service();
        context.assertTrue(learned == 1 && changes.size() == 1, "学会命令或 KNOWN_CHANGED 事件未生效");
        context.assertTrue(service.known(entityUuid).equals(changes), "known 没有返回已提交的学会记录");
        context.assertTrue(service.cast(entityUuid, request(prepared.host().current(), "gametest"))
                        .getPhase() == CastPhase.WARMUP,
                "学会后施放未进入起手");
        prepared.host().nowMillis = WARMUP;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> assertEffectsAndFinish(context, prepared));
    }

    @GameTest
    public void staggerInterruptCancels(GameTestHelper context) {
        // 伤害附属可从公开访问点取得服务，以僵直判定打断起手。
        Prepared prepared = prepare(context);
        UUID entityUuid = prepared.entity().getUUID();
        TechniqueService service = TechniqueFabric.service();
        service.learn(entityUuid, prepared.reference());
        CastOutcome cast = service.cast(entityUuid, request(prepared.host().current(), "gametest_stagger"));
        CastOutcome interrupted = service.stagger(entityUuid, HIT_STAGGER).orElseThrow();
        context.assertTrue(cast.getPhase() == CastPhase.WARMUP, "僵直测试未进入起手");
        context.assertTrue(interrupted.getPhase() == CastPhase.INTERRUPTED, "僵直没有打断起手");
        assertCancelledWithoutRefund(context, prepared);
        release(prepared);
        context.succeed();
    }

    @GameTest
    public void discardTriggersEntityUnloadAndClearsSession(GameTestHelper context) {
        // discard 触发真实 ENTITY_UNLOAD，不能用直接调用服务来代替事件接线。
        Prepared prepared = prepare(context);
        UUID entityUuid = prepared.entity().getUUID();
        TechniqueService service = TechniqueFabric.service();
        service.learn(entityUuid, prepared.reference());
        CastOutcome cast = service.cast(entityUuid, request(prepared.host().current(), "gametest_unload"));
        context.assertTrue(cast.getPhase() == CastPhase.WARMUP, "卸载测试未进入起手");
        prepared.entity().discard();
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> {
            assertCancelledWithoutRefund(context, prepared);
            context.assertTrue(service.cancelOnUnload(entityUuid).isEmpty(), "卸载事件没有移除会话");
            TechniqueGameTestHost.unbind(entityUuid);
            context.succeed();
        });
    }

    @GameTest
    public void learnCommandRejectsUnknownIdAndInsufficientPermission(GameTestHelper context) {
        // 管理命令拒绝目录外 ID 和权限不足的调用，身体知识保持不变。
        Prepared prepared = prepare(context);
        MinecraftServer server = context.getLevel().getServer();
        String command = "meridian technique learn " + prepared.entity().getUUID() + " ";
        int unknown = GameTestCommands.run(server.createCommandSourceStack(), command + "sample:missing");
        int denied = GameTestCommands.run(
                server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.MODERATOR),
                command + TECHNIQUE_ID);
        context.assertTrue(unknown == 0 && denied == 0, "非法学会命令没有拒绝");
        context.assertTrue(TechniqueFabric.service().known(prepared.entity().getUUID()).isEmpty(),
                "非法命令写入了知识");
        release(prepared);
        context.succeed();
    }

    @GameTest
    public void volumeCommandSelectsOffHandLocksAndUnlocksOnServerTick(GameTestHelper context) {
        // 真实命令按卷学会，字节 inspect 选副手，Fabric tick 在收势结束清理移动约束。
        Prepared prepared = prepare(context, TechniqueGameTestHost.HELD_TECHNIQUE_ID);
        MinecraftServer server = context.getLevel().getServer();
        String command = "meridian technique learn " + prepared.entity().getUUID() + " ";
        int rejected = GameTestCommands.run(server.createCommandSourceStack(), command + HeldTechniqueFixtures.LOWER);
        context.assertTrue(rejected == 0, "未学前置卷仍学会了下卷");
        int learned = GameTestCommands.run(server.createCommandSourceStack(), command + HeldTechniqueFixtures.UPPER);
        TechniqueService service = TechniqueFabric.service();
        context.assertTrue(learned == 1 && service.known(prepared.entity().getUUID()).size()
                == VOLUME_TECHNIQUE_COUNT, "按卷学习没有保存全部单式");
        MemoryHost host = prepared.host();
        host.heldItems = HeldTechniqueFixtures.hands(HeldItem.getDefaultInstance(),
                HeldTechniqueFixtures.durableItem("minecraft:iron_axe", HeldTechniqueFixtures.AXES));
        var castRequest = request(host.current(), "gametest_held").toBuilder()
                .setTechnique(prepared.reference())
                .build();
        context.assertTrue(service.cast(prepared.entity().getUUID(), castRequest).getPhase() == CastPhase.WARMUP,
                "副手持物没有进入起手");
        context.assertTrue(host.effects.get(0).getSelectedHand() == Hand.OFF_HAND
                && host.effects.get(0).getMovement().getAction() == MovementAction.LOCK, "起手未立即锁移动");
        context.assertTrue(host.effects.get(1).getPresentation().getStartMillis() > 0, "剪辑起播时刻丢失");
        host.nowMillis = FINISHED_MILLIS;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> {
            context.assertTrue(host.effects.size() == LOCKED_EFFECTS_COUNT
                    && host.effects.get(LOCKED_EFFECTS_COUNT - 1).getMovement().getAction() == MovementAction.UNLOCK,
                    "收势结束未解锁移动");
            release(prepared);
            context.succeed();
        });
    }

    @GameTest
    public void unclaimedSavedCastRecoversWithCleanupOnly(GameTestHelper context) {
        // 身体已经从实体目录撤销，公开服务仍可经可信恢复入口取消旧档中的施放。
        Prepared prepared = prepare(context);
        TechniqueService service = TechniqueFabric.service();
        UUID entityUuid = prepared.entity().getUUID();
        service.learn(entityUuid, prepared.reference());
        context.assertTrue(service.cast(entityUuid, request(prepared.host().current(), "saved_live"))
                .getPhase() == CastPhase.WARMUP, "没有开始施放");
        MemoryHost restored = prepared.host().restore();
        assertRecovered(context, prepared, restored);
        context.succeed();
    }

    @GameTest
    public void unclaimedDeadCastRecoversWithoutRefund(GameTestHelper context) {
        // Framework 死亡清空动作后仍可恢复附属进度，死体不再永久保留施放和会话。
        Prepared prepared = prepare(context);
        TechniqueService service = TechniqueFabric.service();
        UUID entityUuid = prepared.entity().getUUID();
        service.learn(entityUuid, prepared.reference());
        context.assertTrue(service.cast(entityUuid, request(prepared.host().current(), "saved_dead"))
                .getPhase() == CastPhase.WARMUP, "没有开始施放");
        prepared.host().die();
        MemoryHost restored = prepared.host().restore();
        context.assertTrue(TechniqueState.read(restored.current()).hasActiveCast(), "没有形成死体残留");
        assertRecovered(context, prepared, restored);
        context.assertTrue(restored.current().vitality().dead(), "恢复改变了死亡事实");
        context.succeed();
    }

    @GameTest
    public void combatFollowUpDispatchesTimedStrikesAndDoorsOnServerTicks(GameTestHelper context) {
        // 真实 Fabric tick 交付前式效果后原子承接，后式不重放预备冲量。
        Prepared prepared = prepare(context, TechniqueGameTestHost.COMBAT_ID);
        TechniqueService service = TechniqueFabric.service();
        service.learn(prepared.entity().getUUID(), TechniqueGameTestHost.COMBAT_VOLUME_ID);
        MemoryHost host = prepared.host();
        host.heldItems = HeldTechniqueFixtures.hands(
                HeldTechniqueFixtures.durableItem("minecraft:iron_axe", HeldTechniqueFixtures.AXES),
                HeldItem.getDefaultInstance());
        var request = request(host.current(), COMBAT_FIRST_CAST).toBuilder()
                .setTechnique(prepared.reference())
                .build();
        context.assertTrue(service.cast(prepared.entity().getUUID(), request).getPhase() == CastPhase.WARMUP,
                "前式未开始");
        host.nowMillis = WINDOW_START;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> startCombatFollowUp(context, prepared));
    }

    private static void startCombatFollowUp(GameTestHelper context, Prepared prepared) {
        // 起播偏移改变后式边界，前式曲线与正常冷却保持原时刻。
        TechniqueService service = TechniqueFabric.service();
        MemoryHost host = prepared.host();
        TechniqueDefinition next = service.registry().findLatest(TechniqueGameTestHost.COMBAT_NEXT_ID).orElseThrow();
        var request = request(host.current(), COMBAT_NEXT_CAST).toBuilder()
                .setTechnique(TechniqueRegistry.reference(next))
                .setEntry(CastEntry.FOLLOW_UP)
                .build();
        context.assertTrue(service.cast(prepared.entity().getUUID(), request).getPhase() == CastPhase.WARMUP,
                "窗口内没有承接");
        BodySnapshot body = host.current();
        TechniqueProgress progress = TechniqueState.read(body);
        var active = progress.getActiveCast();
        context.assertTrue(active.getClipStartMillis() == CLIP_OFFSET
                && active.getWarmupEndMillis() == NEXT_HIT, "后式起播或起手未缩短");
        context.assertTrue(body.actions().size() == 1
                && progress.getLastCast().getCastId().equals(COMBAT_FIRST_CAST), "前式动作没有原子替换");
        context.assertTrue(progress.getCooldownsOrThrow(TechniqueGameTestHost.COMBAT_ID) == CAST_END + COOLDOWN
                && progress.getCurveEndsOrThrow(TechniqueGameTestHost.COMBAT_ID) == CURVE_END,
                "承接改变了前式冷却或曲线");
        host.nowMillis = NEXT_HIT;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> assertCombatFirstHit(context, prepared));
    }

    private static void assertCombatFirstHit(GameTestHelper context, Prepared prepared) {
        // 缩短起手后的首击在五百毫秒交付，并保留原时刻和完整持物规则。
        MemoryHost host = prepared.host();
        List<EffectIntent> effects = followUpEffects(host);
        List<EffectIntent> strikes = effects.stream()
                .filter(EffectIntent::hasWeaponStrike).toList();
        context.assertTrue(strikes.size() == 1, "承接首击数量错误");
        EffectIntent strike = strikes.get(0);
        WeaponStrike parameters = strike.getWeaponStrike().getStrike();
        context.assertTrue(strike.getScheduledMillis() == NEXT_HIT, "承接首击时刻错误");
        context.assertTrue(parameters.getCastTargetLimit() == TARGET_LIMIT,
                "整次目标预算丢失");
        context.assertTrue(parameters.getDedupGroup().equals(SWEEP_GROUP),
                "横扫首击去重组丢失");
        context.assertTrue(effects.stream().noneMatch(EffectIntent::hasImpulse),
                "重放了后式预备冲量");
        host.nowMillis = NEXT_SECOND_HIT;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> assertCombatLastHit(context, prepared));
    }

    private static void assertCombatLastHit(GameTestHelper context, Prepared prepared) {
        // 后半圈末击和劈门共享正确时刻；真实 tick 随后清理新式动作与移动锁。
        MemoryHost host = prepared.host();
        List<EffectIntent> effects = followUpEffects(host);
        List<EffectIntent> strikes = effects.stream().filter(EffectIntent::hasWeaponStrike).toList();
        context.assertTrue(strikes.size() == SWEEP_HIT_COUNT, "末击数量错误");
        EffectIntent strike = strikes.get(1);
        WeaponStrike parameters = strike.getWeaponStrike().getStrike();
        context.assertTrue(strike.getScheduledMillis() == NEXT_SECOND_HIT
                && parameters.getLastStrike(), "末击或耐久时刻错误");
        String frontGroup = strikes.get(0).getWeaponStrike().getStrike().getDedupGroup();
        context.assertTrue(frontGroup.equals(parameters.getDedupGroup()),
                "横扫两半圈未使用同一去重组");
        EffectIntent doors = effects.stream().filter(EffectIntent::hasBreakBlocks).findFirst().orElseThrow();
        context.assertTrue(doors.getScheduledMillis() == NEXT_SECOND_HIT
                && doors.getBreakBlocks().getBlocks().getMaximumDoors() == DOOR_LIMIT, "劈门参数或时刻错误");
        host.nowMillis = NEXT_END;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> {
            BodySnapshot body = host.current();
            context.assertTrue(!TechniqueState.read(body).hasActiveCast()
                    && body.actions().isEmpty(), "后式未收势");
            List<EffectIntent> finished = followUpEffects(host);
            EffectIntent last = finished.get(finished.size() - 1);
            context.assertTrue(last.getMovement().getAction() == MovementAction.UNLOCK, "后式未解锁");
            release(prepared);
            context.succeed();
        });
    }

    private static List<EffectIntent> followUpEffects(MemoryHost host) {
        // 按真实宿主收到的施放身份筛选，不直接调用调度器伪造 tick 结果。
        return host.effects.stream().filter(effect -> effect.getCastId().equals(COMBAT_NEXT_CAST)).toList();
    }

    private static void assertRecovered(GameTestHelper context, Prepared prepared, MemoryHost restored) {
        // 存档恢复保留费用与知识，提前终止按恢复身体时刻重算冷却且只提交一次。
        UUID entityUuid = prepared.entity().getUUID();
        TechniqueGameTestHost.unbind(entityUuid);
        var before = TechniqueState.read(restored.current());
        var released = restored.store.environmentQi();
        TechniqueService service = TechniqueFabric.service();
        service.recoverOnLoad(entityUuid, restored);
        context.assertTrue(TechniqueState.read(restored.current())
                .equals(TechniqueActivities.endedProgress(before, restored.current().settledAt().elapsedMillis())),
                "恢复没有按终止时刻保存冷却或改变了学会记录");
        context.assertTrue(restored.current().actions().isEmpty(), "恢复遗留动作");
        context.assertTrue(restored.store.environmentQi().equals(released), "恢复退费或改变释放");
        context.assertTrue(restored.effects.size() == 1
                && restored.effects.get(0).hasPresentation()
                && restored.effects.get(0).getPresentation().getAction() == PresentationAction.STOP,
                "恢复没有只清理表现");
        var recovered = restored.current();
        service.recoverOnLoad(entityUuid, restored);
        context.assertTrue(restored.current().equals(recovered), "恢复重试产生重复提交");
        context.assertTrue(restored.effects.size() == 1, "恢复重试重复清理了表现");
        context.assertTrue(service.cancelOnUnload(entityUuid).isEmpty(), "恢复保留了旧会话");
        release(prepared);
    }

    private static Prepared prepare(GameTestHelper context) {
        // 每项测试生成独立实体并经测试 entrypoint 绑定空知识身体。
        return prepare(context, TECHNIQUE_ID);
    }

    private static Prepared prepare(GameTestHelper context, String techniqueId) {
        // 测试实体与具体目录引用分离，保持命令和施放使用同一个版本。
        Entity entity = context.spawnWithNoFreeWill(EntityTypes.PIG, 1, 1, 1);
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), TechniqueState.empty());
        MemoryHost host = new MemoryHost(initial);
        TechniqueGameTestHost.bind(entity.getUUID(), host);
        TechniqueDefinition definition = TechniqueFabric.service().registry().findLatest(techniqueId).orElseThrow();
        return new Prepared(entity, host, TechniqueRegistry.reference(definition));
    }

    private static void assertEffectsAndFinish(GameTestHelper context, Prepared prepared) {
        // 真实服务端 tick 应下发持续表现与冲量，随后再跨 tick 完成收势和 STOP。
        MemoryHost host = prepared.host();
        context.assertTrue(host.effects.size() == START_AND_IMPULSE_COUNT, "tick 未下发全部到期意图");
        context.assertTrue(host.effects.get(0).getPresentation().getAction() == PresentationAction.START,
                "持续表现没有开始");
        context.assertTrue(host.effects.get(1).getImpulse().getDirection() == ImpulseDirection.FORWARD,
                "零输入没有按朝向解释");
        host.nowMillis = FINISHED_MILLIS;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> {
            context.assertTrue(!TechniqueState.read(host.current()).hasActiveCast(), "收势没有清除当前施放");
            context.assertTrue(host.effects.size() == ALL_EFFECTS_COUNT, "收势没有下发停止意图");
            context.assertTrue(host.effects.get(ALL_EFFECTS_COUNT - 1).getPresentation().getAction()
                    == PresentationAction.STOP, "持续表现没有停止");
            context.assertTrue(TechniqueFabric.service().cancelOnUnload(prepared.entity().getUUID()).isEmpty(),
                    "收势后仍保留会话");
            release(prepared);
            context.succeed();
        });
    }

    private static void assertCancelledWithoutRefund(GameTestHelper context, Prepared prepared) {
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
        TechniqueGameTestHost.unbind(prepared.entity().getUUID());
    }

    private record Prepared(Entity entity, MemoryHost host, TechniqueReference reference) { }
}
