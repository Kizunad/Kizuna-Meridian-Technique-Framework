package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;
import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public class TechniqueCastingTest {
    private static final double TOLERANCE = 1e-9;
    private static final long CAST_END = WARMUP + ACTIVE + RECOVERY;
    private static final double EXCESS_QI = QI_AMOUNT + WATER_AMOUNT;
    private static final double EXCESS_POWER = POWER + 1;

    @Test
    public void startSubmitsOneAtomicActivityWithAllCostsAndProgress() {
        // 组织、器官、真元、动作和进度一起提交，身体只增加一次 revision。
        TechniqueDefinition definition = definition();
        BodySnapshot before = body(Shape.PAIRED);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition));
        CastOutcome outcome = casting.cast(host, request(before, "atomic"));
        assertSuccess(outcome, CastPhase.WARMUP);
        assertEquals(before.revision() + 1, host.current().revision());
        assertEquals(1, host.activitySubmissions);
        BodyActivity activity = host.store.receipts().get(0).getCommand().getExecuteActivity();
        assertEquals(1, activity.getAccountsCount());
        assertEquals(definition.getCost().getWorkCount(), activity.getEffortsCount());
        assertEquals(1, activity.getStartActionsCount());
        assertEquals(1, activity.getProgressCount());
        assertEquals(COST_QI, BodyProtobuf.fromInventory(activity.getReleasedQi()).totalQi(), TOLERANCE);
        assertEquals(COST_POWER * COST_SECONDS, host.current().tissueStates().get(0).fatigue(), TOLERANCE);
        assertEquals(COST_POWER * COST_SECONDS, host.current().organStates().get(0).fatigueJoules(), TOLERANCE);
        assertEquals(before.meridianStates().get(0).wallQi(), host.current().meridianStates().get(0).wallQi());
        TechniqueProgress state = TechniqueState.read(host.current());
        assertTrue(state.hasActiveCast());
        assertEquals(COOLDOWN, state.getCooldownsOrThrow(TECHNIQUE_ID));
        assertEquals(TechniqueState.read(before).getKnownList(), state.getKnownList());
    }

    @Test
    public void insufficientQiRejectsWholeActivity() {
        // 第一项余额足够、第二项不足时，第一项也不能先扣除。
        TechniqueDefinition base = definition();
        Quantity amount = quantity(EXCESS_QI, Unit.QI);
        QiCost second = base.getCost().getQi(0).toBuilder()
                .setAttribute("meridian:water")
                .setPhase(QiPhase.LIQUID)
                .setAmount(amount)
                .build();
        TechniqueCost cost = base.getCost().toBuilder().addQi(second).build();
        TechniqueDefinition definition = base.toBuilder().setCost(cost).build();
        BodySnapshot before = body(Shape.PAIRED);
        var host = new MemoryHost(before);
        CastOutcome outcome = new TechniqueCasting(registry(definition)).cast(host, request(before, "poor"));
        assertFailure(outcome, INSUFFICIENT_QI, "真元不足");
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
        assertEquals(0, host.store.environmentQi().totalQi(), 0);
    }

    @Test
    public void insufficientWorkBudgetRollsBackQiActionsAndProgress() {
        // 器官在组织做功之后失败，预检产生的候选不能泄漏到身体或冷却。
        TechniqueDefinition base = definition();
        Quantity power = quantity(EXCESS_POWER, Unit.WATT);
        WorkCost expensive = base.getCost().getWork(1).toBuilder().setPower(power).build();
        TechniqueCost cost = base.getCost().toBuilder().setWork(1, expensive).build();
        TechniqueDefinition definition = base.toBuilder().setCost(cost).build();
        BodySnapshot before = body(Shape.PAIRED);
        var host = new MemoryHost(before);
        CastOutcome outcome = new TechniqueCasting(registry(definition)).cast(host, request(before, "tired"));
        assertFailure(outcome, INSUFFICIENT_CAPABILITY, "无法承担");
        assertEquals(before, host.current());
        assertEquals(0, host.store.receipts().size());
    }

    @Test
    public void onlyOpenedMeridiansCanFundCasting() {
        // 即使闭合内腔有足够库存，也不能绕过本库的开拓条件。
        var message = BodyProtobuf.toMessage(body(Shape.TENDRIL));
        MeridianOpening opening = MeridianOpening.newBuilder()
                .setStage(MeridianOpeningStage.UNOPENED)
                .setProgress(0)
                .build();
        MeridianState state = message.getMeridianStates(0).toBuilder().setOpening(opening).build();
        BodySnapshot before = BodyProtobuf.fromMessage(message.toBuilder().setMeridianStates(0, state).build());
        var host = new MemoryHost(before);
        CastOutcome outcome = new TechniqueCasting(registry(definition())).cast(host, request(before, "closed"));
        assertFailure(outcome, MERIDIAN_NOT_OPENED, "未开拓");
        assertEquals(before, host.current());
    }

    @Test
    public void duplicateCastIdAfterRestartAndCompletionDoesNotCharge() {
        // 成功回执跨库实例、宿主恢复与施放结束保留，不能依赖 active_cast 做短期去重。
        TechniqueRegistry registry = registry(definition());
        BodySnapshot before = body(Shape.TENDRIL);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry);
        CastRequest request = request(before, "retry");
        CastOutcome first = casting.cast(host, request);
        assertSuccess(first, CastPhase.WARMUP);
        BodySnapshot after = host.current();
        MemoryHost restored = host.restore();
        CastOutcome duplicate = new TechniqueCasting(registry).cast(restored, request);
        assertTrue(duplicate.getReplayed());
        assertEquals(first.getRevision(), duplicate.getRevision());
        assertEquals(after, restored.current());
        restored.nowMillis = CAST_END;
        assertSuccess(casting.advance(restored), CastPhase.FINISHED);
        MemoryHost finished = restored.restore();
        BodySnapshot settled = finished.current();
        CastOutcome lateRetry = casting.cast(finished, request);
        assertTrue(lateRetry.getReplayed());
        assertEquals(settled, finished.current());
        assertFalse(TechniqueState.read(finished.current()).hasActiveCast());
        assertEquals(COST_QI, finished.store.environmentQi().totalQi(), TOLERANCE);
    }

    @Test
    public void sameCastIdWithDifferentTargetOrDirectionIsRejected() {
        // 幂等键不能用于偷偷更换目标或方向，原始请求保存在起手活动回执。
        BodySnapshot before = body(Shape.TENDRIL);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition()));
        CastRequest request = request(before, "conflict");
        assertSuccess(casting.cast(host, request), CastPhase.WARMUP);
        BodySnapshot after = host.current();
        CastRequest target = request.toBuilder().setTargetId("different").build();
        assertFailure(casting.cast(host, target), CAST_ID_CONFLICT, "不同施放");
        HorizontalDirection direction = HorizontalDirection.newBuilder().setX(1).build();
        CastRequest directed = request.toBuilder().setDirection(direction).build();
        assertFailure(casting.cast(host, directed), CAST_ID_CONFLICT, "不同施放");
        assertEquals(after, host.current());
    }

    @Test
    public void qiConservedAcrossAllAccountsAndHostRelease() {
        // 两条经脉、两种相态费用同时扣除，外壁与未选择明细保持，释放保留实际密度。
        TechniqueDefinition base = definition();
        TechniqueSlot source = base.getSlots(0).toBuilder()
                .setMinimumCount(2)
                .setMaximumCount(2)
                .build();
        QiCost water = base.getCost().getQi(0).toBuilder()
                .setAttribute("meridian:water")
                .setPhase(QiPhase.LIQUID)
                .build();
        TechniqueCost cost = base.getCost().toBuilder().addQi(water).build();
        TechniqueDefinition definition = base.toBuilder()
                .setSlots(0, source)
                .setCost(cost)
                .build();
        BodySnapshot before = body(Shape.PAIRED);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition));
        assertSuccess(casting.cast(host, request(before, "conserved")), CastPhase.WARMUP);
        double total = host.current().totalQi() + host.store.environmentQi().totalQi();
        assertEquals(before.totalQi(), total, TOLERANCE);
        assertEquals(GAS_DENSITY, host.store.environmentQi().entries().get(0).density(), TOLERANCE);
        assertEquals(LIQUID_DENSITY, host.store.environmentQi().entries().get(1).density(), TOLERANCE);
        assertEquals(before.meridianStates().stream().map(dev.kizuna.meridian.core.MeridianState::wallQi).toList(),
                host.current().meridianStates().stream().map(dev.kizuna.meridian.core.MeridianState::wallQi).toList());
    }

    @Test
    public void cooldownAndSnapshotRoundTripUseExactBodyTimeBoundary() {
        // 冷却不使用系统墙钟；恢复后边界前拒绝，恰好到期可以再次支付费用。
        BodySnapshot before = body(Shape.TENDRIL);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition()));
        assertSuccess(casting.cast(host, request(before, "first")), CastPhase.WARMUP);
        TechniqueProgress active = TechniqueState.read(host.current());
        MemoryHost restored = host.restore();
        assertEquals(active, TechniqueState.read(restored.current()));
        restored.nowMillis = CAST_END;
        assertSuccess(casting.advance(restored), CastPhase.FINISHED);
        assertEquals(0, restored.current().actions().size());
        restored.nowMillis = COOLDOWN - 1;
        assertFailure(casting.cast(restored, request(before, "early")), COOLING_DOWN, "冷却中");
        restored.nowMillis = COOLDOWN;
        assertSuccess(casting.cast(restored, request(before, "ready")), CastPhase.WARMUP);
        assertEquals(COST_QI + COST_QI, restored.store.environmentQi().totalQi(), TOLERANCE);
    }

    @Test
    public void warmupDamageInterruptsWithoutRefundAndRetainsCooldown() {
        // 起手受伤取消只解除动作，不归还真元、组织做功或器官疲劳。
        BodySnapshot before = body(Shape.TENDRIL);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry(definition()));
        assertSuccess(casting.cast(host, request(before, "interrupted")), CastPhase.WARMUP);
        BodySnapshot paid = host.current();
        assertSuccess(casting.interruptByDamage(host), CastPhase.INTERRUPTED);
        assertEquals(paid.totalQi(), host.current().totalQi(), 0);
        assertEquals(paid.tissueStates(), host.current().tissueStates());
        assertEquals(paid.organStates(), host.current().organStates());
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertEquals(COOLDOWN, TechniqueState.read(host.current()).getCooldownsOrThrow(TECHNIQUE_ID));
        assertEquals(0, host.current().actions().size());
        assertEquals(COST_QI, host.store.environmentQi().totalQi(), TOLERANCE);
    }

    @Test
    public void nonInterruptibleAndPostWarmupDamageContinueThroughPhases() {
        // 不可打断起手继续；起手结束边界上的伤害也不能取消已生效施放。
        TechniqueDefinition base = definition();
        BodySnapshot body = body(Shape.TENDRIL);
        CastPhases phases = base.getPhases().toBuilder()
                .setInterruptibleByDamage(false /* interruptibleByDamage */)
                .build();
        TechniqueDefinition uninterruptible = base.toBuilder().setPhases(phases).build();
        var host = new MemoryHost(body);
        var casting = new TechniqueCasting(registry(uninterruptible));
        assertSuccess(casting.cast(host, request(host.current(), "steady")), CastPhase.WARMUP);
        assertSuccess(casting.interruptByDamage(host), CastPhase.WARMUP);
        var regular = new TechniqueCasting(registry(base));
        var regularHost = new MemoryHost(body);
        assertSuccess(regular.cast(regularHost, request(regularHost.current(), "boundary")), CastPhase.WARMUP);
        regularHost.nowMillis = WARMUP;
        assertSuccess(regular.interruptByDamage(regularHost), CastPhase.EFFECTIVE);
        regularHost.nowMillis = WARMUP + ACTIVE;
        assertSuccess(regular.advance(regularHost), CastPhase.RECOVERING);
        regularHost.nowMillis = CAST_END;
        assertSuccess(regular.advance(regularHost), CastPhase.FINISHED);
    }

    @Test
    public void zeroWarmupAndActiveDurationsEnterRecoveryImmediately() {
        // 瞬时起手和生效段不创建虚假的可打断窗口，直接进入实际收势区间。
        TechniqueDefinition base = definition();
        CastPhases phases = base.getPhases().toBuilder()
                .setWarmupMillis(0)
                .setActiveMillis(0)
                .build();
        TechniqueDefinition definition = base.toBuilder().setPhases(phases).build();
        var host = new MemoryHost(body(Shape.TENDRIL));
        var casting = new TechniqueCasting(registry(definition));
        assertSuccess(casting.cast(host, request(host.current(), "instant")), CastPhase.RECOVERING);
        assertSuccess(casting.interruptByDamage(host), CastPhase.RECOVERING);
        host.nowMillis = RECOVERY;
        assertSuccess(casting.advance(host), CastPhase.FINISHED);
    }

    @Test
    public void onlyOneCastIsAllowedEvenForDifferentTechniques() {
        // 两个都已学会且无共享冷却的功法仍受身体单施放限制。
        TechniqueDefinition base = definition();
        TechniqueDefinition second = base.toBuilder().setId("sample:second").build();
        TechniqueCatalog catalog = catalog(base).toBuilder().addDefinitions(second).build();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(catalog));
        TechniqueProgress progress = progress(base).toBuilder()
                .addAllKnown(progress(second).getKnownList())
                .build();
        BodySnapshot before = withProgress(body(Shape.TENDRIL), progress);
        var host = new MemoryHost(before);
        var casting = new TechniqueCasting(registry);
        assertSuccess(casting.cast(host, request(before, "one")), CastPhase.WARMUP);
        BodySnapshot paid = host.current();
        TechniqueReference reference = TechniqueRegistry.reference(second);
        CastRequest secondRequest = request(before, "two").toBuilder().setTechnique(reference).build();
        assertFailure(casting.cast(host, secondRequest), ALREADY_CASTING, "已有施放");
        assertEquals(paid, host.current());
    }

    @Test
    public void unloadAndDisconnectCancelWithoutRefundAndKeepCooldown() {
        // 两种生命周期退出都保留已付费用、已学记录和冷却，并停止占用。
        for (Exit exit : Exit.values()) {
            var host = new MemoryHost(body(Shape.TENDRIL));
            var casting = new TechniqueCasting(registry(definition()));
            assertSuccess(casting.cast(host, request(host.current(), "exit")), CastPhase.WARMUP);
            CastOutcome cancelled;
            if (exit == Exit.UNLOAD) {
                cancelled = casting.cancelOnUnload(host);
            } else {
                cancelled = casting.cancelOnDisconnect(host);
            }
            assertSuccess(cancelled, CastPhase.CANCELLED);
            assertEquals(COST_QI, host.store.environmentQi().totalQi(), TOLERANCE);
            assertEquals(COOLDOWN, TechniqueState.read(host.current()).getCooldownsOrThrow(TECHNIQUE_ID));
            assertEquals(0, host.current().actions().size());
            assertEquals(TechniqueState.read(host.current()), TechniqueState.read(host.restore().current()));
        }
    }

    @Test
    public void unknownUnlearnedAndDeadBodiesAreRejectedWithChineseReasons() {
        // 来自客户端的功法身份不授予施放权限；死亡也不能继续消费资源。
        BodySnapshot before = body(Shape.TENDRIL);
        var casting = new TechniqueCasting(registry(definition()));
        var unlearned = new MemoryHost(withProgress(before, TechniqueState.empty()));
        assertFailure(casting.cast(unlearned, request(before, "unlearned")), NOT_KNOWN, "未学会");
        TechniqueReference unknown = TechniqueReference.newBuilder()
                .setId("sample:missing")
                .setVersion(1)
                .build();
        CastRequest missing = request(before, "unknown").toBuilder().setTechnique(unknown).build();
        assertFailure(casting.cast(new MemoryHost(before), missing), UNKNOWN_TECHNIQUE, "没有该功法");
        // 死亡身体必须清空所有资源，使用核心死亡算子生成合法状态。
        var deadHost = new MemoryHost(before);
        deadHost.nowMillis = java.util.concurrent.TimeUnit.SECONDS.toMillis((long) LIFESPAN);
        BodySnapshot deadBody = deadHost.inspect();
        assertFailure(casting.cast(new MemoryHost(deadBody), request(deadBody, "dead")), BODY_UNAVAILABLE, "死亡");
        assertEquals(TechniqueState.read(before).getKnownList(), TechniqueState.read(deadBody).getKnownList());
    }

    @Test
    public void excessiveForceIsRejectedBeforeActivitySubmission() {
        // Framework 活动未携带力字段，本库必须在同一 revision 提交前拒绝超力负载。
        TechniqueDefinition base = definition();
        Quantity force = quantity(STRENGTH + 1, Unit.NEWTON);
        WorkCost work = base.getCost().getWork(0).toBuilder().setForce(force).build();
        TechniqueCost cost = base.getCost().toBuilder().setWork(0, work).build();
        TechniqueDefinition definition = base.toBuilder().setCost(cost).build();
        BodySnapshot before = body(Shape.TENDRIL);
        var host = new MemoryHost(before);
        CastOutcome outcome = new TechniqueCasting(registry(definition)).cast(host, request(before, "force"));
        assertFailure(outcome, INSUFFICIENT_CAPABILITY, "无法承担");
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
    }

    @Test
    public void rejectedStopRetainsActiveCastForRetry() {
        // 宿主停止失败时不能只清内存状态，重试仍能找到原施放并完成取消。
        var host = new MemoryHost(body(Shape.TENDRIL));
        var casting = new TechniqueCasting(registry(definition()));
        assertSuccess(casting.cast(host, request(host.current(), "stop_retry")), CastPhase.WARMUP);
        BodySnapshot active = host.current();
        host.rejectNextActivity();
        assertFailure(casting.cancelOnUnload(host), FRAMEWORK_REJECTED, "没有授权");
        assertEquals(active, host.current());
        assertSuccess(casting.cancelOnUnload(host), CastPhase.CANCELLED);
        assertEquals(COST_QI, host.store.environmentQi().totalQi(), TOLERANCE);
    }

    @Test
    public void deathPreservesKnowledgeAndCooldownButReportsActivityCleanupLimit() {
        // 死亡清动作但保留附属进度；死体活动拒绝必须明确报告，不能假装已清施放。
        var host = new MemoryHost(body(Shape.TENDRIL));
        var casting = new TechniqueCasting(registry(definition()));
        assertSuccess(casting.cast(host, request(host.current(), "death")), CastPhase.WARMUP);
        TechniqueProgress active = TechniqueState.read(host.current());
        host.nowMillis = java.util.concurrent.TimeUnit.SECONDS.toMillis((long) LIFESPAN);
        CastOutcome outcome = casting.advance(host);
        assertEquals(FRAMEWORK_REJECTED, outcome.getFailure());
        assertEquals(FailureCode.BODY_DEAD, outcome.getFrameworkFailure());
        assertTrue(host.current().vitality().dead());
        assertTrue(host.current().actions().isEmpty());
        assertEquals(active, TechniqueState.read(host.current()));
    }

    @Test
    public void hostRejectionAndStaleRevisionLeaveWholeActivityUnchanged() {
        // 计划生成成功后宿主拒绝，仍不能留下扣费、动作或冷却。
        TechniqueDefinition definition = definition();
        BodySnapshot before = body(Shape.TENDRIL);
        var host = new MemoryHost(before);
        host.rejectNextActivity();
        var casting = new TechniqueCasting(registry(definition));
        CastOutcome failure = casting.cast(host, request(before, "denied"));
        assertFailure(failure, FRAMEWORK_REJECTED, "没有授权");
        assertEquals(FailureCode.ACCESS_DENIED, failure.getFrameworkFailure());
        assertEquals(before, host.current());
        BindingOutcome binding = TechniqueBinder.bind(before, definition);
        var start = new TechniqueActivities.Start(before, definition, request(before, "stale"),
                TechniqueState.read(before), binding.getBinding());
        BodyActivity activity = TechniqueActivities.start(start);
        var stale = new TechniqueHost.Submission("stale", before.revision() + 1, activity);
        TechniqueErrors.HostRejected rejected = assertThrows(TechniqueErrors.HostRejected.class,
                () -> host.submit(stale));
        assertEquals(FailureCode.REVISION_CONFLICT, rejected.failure());
        assertEquals(before, host.current());
    }

    @Test
    public void proficiencyDoesNotChangeCostsAndNoStageThreeEffectIsExecuted() {
        // 熟练度只保存为数据，目录内合法修正声明也不在施放阶段被提前执行。
        TechniqueEffect effect = TechniqueRegistryTest.effect(modifier(), WARMUP);
        TechniqueDefinition definition = definition().toBuilder().addEffects(effect).build();
        TechniqueProgress progress = progress(definition);
        Quantity proficiency = quantity(1, Unit.RATIO);
        KnownTechnique expert = progress.getKnown(0).toBuilder().setProficiency(proficiency).build();
        TechniqueProgress expertProgress = progress.toBuilder().setKnown(0, expert).build();
        BodySnapshot body = body(Shape.TENDRIL);
        var novice = new MemoryHost(withProgress(body, progress));
        var master = new MemoryHost(withProgress(body, expertProgress));
        var casting = new TechniqueCasting(registry(definition));
        assertSuccess(casting.cast(novice, request(novice.current(), "novice")), CastPhase.WARMUP);
        assertSuccess(casting.cast(master, request(master.current(), "master")), CastPhase.WARMUP);
        assertEquals(novice.current().totalQi(), master.current().totalQi(), 0);
        assertEquals(novice.current().tissueStates(), master.current().tissueStates());
        assertTrue(master.current().modifiers().isEmpty());
        master.nowMillis = CAST_END;
        assertSuccess(casting.advance(master), CastPhase.FINISHED);
        for (var receipt : master.store.receipts()) {
            BodyActivity activity = receipt.getCommand().getExecuteActivity();
            assertEquals(0, activity.getAddModifiersCount());
            assertEquals(0, activity.getRemoveModifiersCount());
        }
    }

    private static void assertSuccess(CastOutcome outcome, CastPhase phase) {
        // 成功不能夹带失败码，便于宿主直接使用状态推进结果。
        assertEquals(outcome.getReason(), TECHNIQUE_FAILURE_UNSPECIFIED, outcome.getFailure());
        assertEquals(phase, outcome.getPhase());
    }

    private static void assertFailure(CastOutcome outcome, TechniqueFailure failure, String reason) {
        // 原因码和一句中文说明同时属于公开失败契约。
        assertEquals(outcome.getReason(), failure, outcome.getFailure());
        assertTrue(outcome.getReason(), outcome.getReason().contains(reason));
    }

    private enum Exit { UNLOAD, DISCONNECT }
}
