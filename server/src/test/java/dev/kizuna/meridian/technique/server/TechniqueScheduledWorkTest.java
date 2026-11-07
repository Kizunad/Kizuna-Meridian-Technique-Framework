package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.ActivityMessages.ActivityEffort;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.*;

/** 分段费用通过真实 Framework 事务和功法服务事件循环结算。 */
public final class TechniqueScheduledWorkTest {
    private static final long FIRST = 50;
    private static final long SECOND = 500;
    private static final long END = WARMUP + ACTIVE + RECOVERY;
    private static final double SECONDS = 0.05;
    private static final double EPSILON = 0.000001;
    private static final double EXHAUSTING_POWER = 100;
    private static final double EXHAUSTING_SECONDS = 9;
    private static final int TWO_SEGMENTS = 2;
    private static final double FOLLOW_UP_RATIO = 0.5;
    private static final long FOLLOW_UP_WINDOW = 500;
    private static final long SEGMENT_SPACING = 10;
    private static final double SHORT_SEGMENT_SECONDS = 0.001;
    private static final int START_AND_FINISH_SUBMISSIONS = 2;

    @Test
    public void fullTimelineUsesOriginalIndicesAndPerBoundaryActivityLimits() {
        // 声音也占原始下标；多段累计可超过单笔组织上限，迟到时仍须处理完全部边界。
        TechniqueDefinition definition = fullTimeline();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.PAIRED), progress(definition)));
        TechniqueService service = service(host, registry(definition));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "full_timeline")).getPhase());
        host.nowMillis = END;
        service.tick();
        assertEquals(TechniqueValues.MAX_EFFECTS + START_AND_FINISH_SUBMISSIONS, host.activitySubmissions);
        assertTrue(host.findActivity("technique_work_full_timeline_" + TechniqueValues.MAX_SOUNDS).isPresent());
        TechniqueProgress finished = TechniqueState.read(host.current());
        assertEquals(CastPhase.FINISHED, finished.getLastCast().getEnding());
        assertFalse(finished.hasActiveCast());
    }

    @Test
    public void zeroTimeWorkSharesStartTransactionAndReplay() {
        // 零时刻段与真元、动作原子提交，重复施放请求不能再次付费。
        TechniqueDefinition definition = segmented().toBuilder().setEffects(0, workEffect(0, segment())).build();
        MemoryHost host = host(definition);
        TechniqueCasting casting = new TechniqueCasting(registry(definition));
        CastRequest request = request(host.current(), "zero");
        assertEquals(CastPhase.WARMUP, casting.cast(host, request).getPhase());
        var paid = host.current();
        assertEquals(1, host.activitySubmissions);
        assertEquals(List.of(0), TechniqueState.read(paid).getActiveCast().getPaidWorkSequencesList());
        assertTrue(casting.cast(host, request).getReplayed());
        assertEquals(paid, host.current());
    }

    @Test
    public void followUpPaysAtClipStartAndProjectsLaterWork() {
        // 承接跳过的段不补扣，起播点上的段随新起手支付，后段按剩余剪辑时间触发。
        TechniqueDefinition previous = definition();
        TechniqueDefinition next = followUp(previous);
        TechniqueCatalog catalog = catalog(previous).toBuilder().addDefinitions(next).build();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(catalog));
        TechniqueProgress known = progress(previous).toBuilder().addAllKnown(progress(next).getKnownList()).build();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), known));
        TechniqueCasting casting = new TechniqueCasting(registry);
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(host.current(), "previous")).getPhase());
        host.nowMillis = END;
        assertEquals(CastPhase.FINISHED, casting.advance(host).getPhase());
        CastRequest nextRequest = request(host.current(), "follow").toBuilder()
                .setTechnique(TechniqueRegistry.reference(next))
                .setEntry(CastEntry.FOLLOW_UP)
                .build();
        assertEquals(CastPhase.WARMUP, casting.cast(host, nextRequest).getPhase());
        assertEquals(List.of(1), TechniqueState.read(host.current()).getActiveCast().getPaidWorkSequencesList());
        host.nowMillis = END + SECOND - FIRST;
        casting.advance(host, END);
        assertEquals(List.of(1, TWO_SEGMENTS),
                TechniqueState.read(host.current()).getActiveCast().getPaidWorkSequencesList());
    }

    @Test
    public void disconnectAndDeadBodyRecoveryDropUnstartedSegments() {
        // 生命周期取消不推进费用，死亡恢复也不尝试在死体补交未来做功。
        TechniqueDefinition definition = segmented();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "disconnect"));
        host.nowMillis = FIRST;
        service.tick();
        service.cancelOnDisconnect(ENTITY);
        double paid = fatigue(host);
        host.nowMillis = SECOND;
        service.tick();
        assertEquals(paid, fatigue(host), 0);
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());

        MemoryHost dead = host(definition);
        TechniqueService restoredService = service(dead, registry(definition));
        restoredService.cast(ENTITY, request(dead.current(), "death"));
        dead.nowMillis = FIRST;
        restoredService.tick();
        double beforeDeath = fatigue(dead);
        dead.die();
        restoredService.recoverOnLoad(ENTITY, dead);
        dead.nowMillis = END;
        restoredService.tick();
        assertEquals(beforeDeath, fatigue(dead), 0);
        assertFalse(TechniqueState.read(dead.current()).hasActiveCast());
    }

    @Test
    public void startPrechecksButOnlyDueSegmentsSpendOnce() {
        // 起手只收真元，首段到时才扣；重复 tick 不再扣款，后段仍待开始。
        TechniqueDefinition definition = segmented();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "segments")).getPhase());
        assertEquals(0, fatigue(host), 0);
        host.nowMillis = FIRST;
        service.tick();
        assertEquals(COST_POWER * SECONDS, fatigue(host), EPSILON);
        int submissions = host.activitySubmissions;
        service.tick();
        assertEquals(submissions, host.activitySubmissions);
        assertEquals(List.of(0), TechniqueState.read(host.current()).getActiveCast().getPaidWorkSequencesList());
        host.nowMillis = SECOND;
        service.tick();
        assertEquals(TWO_SEGMENTS, TechniqueState.read(host.current()).getActiveCast().getPaidWorkSequencesCount());
        assertEquals(1, host.effects.size());
    }

    @Test
    public void startRejectsUnaffordableFutureWorkWithoutChargingQi() {
        // 将来才开始的费用也必须起手预检，失败保持整具身体和真元不变。
        Quantity force = quantity(STRENGTH + 1, Unit.NEWTON);
        WorkCost impossible = segment().toBuilder().setForce(force).build();
        TechniqueEffect future = workEffect(SECOND, impossible);
        TechniqueDefinition definition = segmented().toBuilder().setEffects(1, future).build();
        MemoryHost host = host(definition);
        var before = host.current();
        CastOutcome outcome = new TechniqueCasting(registry(definition)).cast(host, request(before, "too_much"));
        assertEquals(TechniqueFailure.INSUFFICIENT_CAPABILITY, outcome.getFailure());
        assertEquals(before, host.current());
    }

    @Test
    public void cancellationKeepsPaidSegmentAndDiscardsFutureWork() {
        // 仍在起手时受击打断，已付不退，后续到时也不产生做功或世界效果。
        TechniqueDefinition definition = segmented();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "interrupt"));
        host.nowMillis = FIRST;
        service.tick();
        service.stagger(ENTITY, HIT_STAGGER);
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertTrue(fatigue(host) > 0);
        int submissions = host.activitySubmissions;
        host.nowMillis = END;
        service.tick();
        assertEquals(submissions, host.activitySubmissions);
        assertTrue(host.effects.stream().noneMatch(EffectIntent::hasImpulse));
    }

    @Test
    public void capabilityLossStopsBeforeSameBoundaryWorldEffect() {
        // 施放中其他负载耗尽能力，第二段失败必须取消，不能先发出同刻打击再补费。
        TechniqueDefinition definition = segmented();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "loss"));
        host.nowMillis = FIRST;
        service.tick();
        ActivityEffort effort = ActivityEffort.newBuilder()
                .setTissueId("tendril")
                .setWatts(EXHAUSTING_POWER)
                .setSeconds(EXHAUSTING_SECONDS)
                .build();
        BodyActivity extra = BodyActivity.newBuilder().addEfforts(effort).build();
        host.submit(new TechniqueHost.Submission("other_load", host.current().revision(), extra));
        host.nowMillis = SECOND;
        service.tick();
        TechniqueProgress cancelled = TechniqueState.read(host.current());
        assertFalse(cancelled.hasActiveCast());
        assertEquals(CastPhase.CANCELLED, cancelled.getLastCast().getEnding());
        assertTrue(host.effects.stream().noneMatch(EffectIntent::hasImpulse));
    }

    @Test
    public void lateTickPaysInOrderAndFinishesAfterEffects() {
        // 一次跨过两段与收势，第一击只看到首段已付，第二击才看到两段已付。
        TechniqueDefinition original = segmented();
        TechniqueDefinition definition = original.toBuilder()
                .clearEffects()
                .addEffects(original.getEffects(0))
                .addEffects(impulse(FIRST))
                .addEffects(original.getEffects(1))
                .addEffects(impulse(SECOND))
                .build();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        var observed = new ArrayList<Integer>();
        host.effectObserver = submission -> {
            if (submission.effect().hasImpulse()) {
                observed.add(TechniqueState.read(host.current()).getActiveCast().getPaidWorkSequencesCount());
            }
        };
        service.cast(ENTITY, request(host.current(), "late"));
        host.nowMillis = END;
        service.tick();
        assertEquals(List.of(1, TWO_SEGMENTS), observed);
        TechniqueProgress finished = TechniqueState.read(host.current());
        assertFalse(finished.hasActiveCast());
        assertEquals(CastPhase.FINISHED, finished.getLastCast().getEnding());
    }

    @Test
    public void cancellationFromWorldEffectStopsLaterSegmentsInLateTick() {
        // 世界回调取消时，服务先处理取消再跨下一个边界，不能继续预付后段。
        TechniqueDefinition original = segmented();
        TechniqueDefinition definition = original.toBuilder()
                .clearEffects()
                .addEffects(original.getEffects(0))
                .addEffects(impulse(FIRST))
                .addEffects(original.getEffects(1))
                .addEffects(impulse(SECOND))
                .build();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        host.effectObserver = submission -> {
            if (submission.effect().hasImpulse()) {
                service.cancelOnUnload(ENTITY);
            }
        };
        service.cast(ENTITY, request(host.current(), "reentrant_cancel"));
        host.nowMillis = END;
        service.tick();
        assertEquals(COST_POWER * SECONDS, fatigue(host), EPSILON);
        assertEquals(1, host.effects.stream().filter(EffectIntent::hasImpulse).count());
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
    }

    @Test
    public void sameTimeSegmentsAreAtomicAndEffectRetryDoesNotPayAgain() {
        // 同刻两段共用一次活动；效果暂拒后只重试世界效果，不重复消耗身体耐力。
        TechniqueDefinition definition = segmented().toBuilder()
                .setEffects(1, workEffect(FIRST, segment()))
                .setEffects(2, impulse(FIRST))
                .build();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "same_time"));
        host.rejectNextEffect();
        host.nowMillis = FIRST;
        service.tick();
        assertEquals(TWO_SEGMENTS, host.activitySubmissions);
        double fatigue = fatigue(host);
        service.tick();
        assertEquals(TWO_SEGMENTS, host.activitySubmissions);
        assertEquals(fatigue, fatigue(host), 0);
        assertEquals(1, host.effects.size());
    }

    @Test
    public void lostPaymentReceiptRetriesWithoutChargingAgain() {
        // 活动已保存而响应丢失时，下一次从身体已付进度恢复，不能重复扣款。
        TechniqueDefinition definition = segmented();
        MemoryHost host = host(definition);
        TechniqueCasting casting = new TechniqueCasting(registry(definition));
        casting.cast(host, request(host.current(), "lost_receipt"));
        host.nowMillis = FIRST;
        var rejected = casting.advance(losingReceipt(host), 0);
        assertEquals(FailureCode.INTERNAL_ERROR, rejected.outcome().getFrameworkFailure());
        var paid = host.current();
        int submissions = host.activitySubmissions;
        casting.advance(host, 0);
        assertEquals(paid, host.current());
        assertEquals(submissions, host.activitySubmissions);
    }

    @Test
    public void restoredBodySkipsPaidWorkAndRejectsInvalidIntervals() {
        // 身体进度与稳定活动身份跨宿主重建去重；非法区间不能进入正式目录。
        TechniqueDefinition definition = segmented();
        MemoryHost host = host(definition);
        TechniqueCasting casting = new TechniqueCasting(registry(definition));
        casting.cast(host, request(host.current(), "restore"));
        host.nowMillis = FIRST;
        casting.advance(host, 0);
        MemoryHost restored = host.restore();
        double paid = fatigue(restored);
        casting.advance(restored, 0);
        assertEquals(paid, fatigue(restored), 0);
        assertEquals(0, restored.activitySubmissions);
        TechniqueDefinition invalid = definition.toBuilder()
                .setEffects(1, workEffect(END, segment()))
                .setEffects(2, impulse(END))
                .build();
        assertThrows(IllegalArgumentException.class, () -> registry(invalid));
    }

    private static TechniqueDefinition followUp(TechniqueDefinition previous) {
        // 三段分别位于跳过区、起播点和后续时刻，覆盖投影后的边界。
        FollowUp transition = FollowUp.newBuilder()
                .setPrevious(TechniqueRegistry.reference(previous))
                .setRecoveryStartRatio(quantity(FOLLOW_UP_RATIO, Unit.RATIO))
                .setAfterRecoveryMillis(FOLLOW_UP_WINDOW)
                .setStartMillis(FIRST)
                .build();
        FollowUpRules rules = FollowUpRules.newBuilder().addTransitions(transition).build();
        WorkCost work = segment();
        return segmented().toBuilder()
                .setId("sample:next")
                .setFollowUp(rules)
                .clearEffects()
                .addEffects(workEffect(0, work))
                .addEffects(workEffect(FIRST, work))
                .addEffects(workEffect(SECOND, work))
                .build();
    }

    private static TechniqueDefinition fullTimeline() {
        // 前段声音与后段费用覆盖目录允许的全部事件，不修改正式规模上限。
        var builder = segmented().toBuilder()
                .clearEffects()
                .addAllEffects(timelineSounds());
        WorkCost shortWork = segment().toBuilder()
                .setDuration(quantity(SHORT_SEGMENT_SECONDS, Unit.SECOND))
                .build();
        for (int index = 0; index < TechniqueValues.MAX_EFFECTS; index++) {
            builder.addEffects(workEffect(WARMUP + index * SEGMENT_SPACING, shortWork));
        }
        return builder.build();
    }

    private static List<TechniqueEffect> timelineSounds() {
        // 声音占据前部目录序号，后续做功必须继续使用未压缩的原始下标。
        SoundCue cue = SoundCue.newBuilder()
                .setSoundId("sample:sound")
                .setAnchor(SoundAnchor.CASTER)
                .build();
        var sounds = new ArrayList<TechniqueEffect>();
        for (int index = 0; index < TechniqueValues.MAX_SOUNDS; index++) {
            TechniqueEffect sound = TechniqueEffect.newBuilder()
                    .setAtMillis(index + 1)
                    .setSound(cue)
                    .build();
            sounds.add(sound);
        }
        return List.copyOf(sounds);
    }

    private static TechniqueDefinition segmented() {
        // 保留真实真元费用，仅将组织做功换为两个开始事件。
        TechniqueDefinition original = definition();
        TechniqueCost cost = original.getCost().toBuilder().clearWork().build();
        WorkCost work = segment();
        return original.toBuilder()
                .setCost(cost)
                .addEffects(workEffect(FIRST, work))
                .addEffects(workEffect(SECOND, work))
                .addEffects(impulse(SECOND))
                .build();
    }

    private static WorkCost segment() {
        // 用短段让费用结束早于收势，重点验证事务与事件顺序。
        Quantity duration = quantity(SECONDS, Unit.SECOND);
        return work("actuator").toBuilder().setDuration(duration).build();
    }

    private static TechniqueEffect workEffect(long millis, WorkCost work) {
        // 开始时刻属于时间线事件，费用本身没有未来偏移字段。
        return TechniqueEffect.newBuilder()
                .setAtMillis(millis)
                .setWork(work)
                .build();
    }

    private static TechniqueEffect impulse(long millis) {
        // 世界效果代表付费后才允许执行的动作，观察器记录其发生顺序。
        Quantity speed = quantity(1, Unit.METERS_PER_SECOND);
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.FORWARD)
                .setSpeed(speed)
                .build();
        return TechniqueEffect.newBuilder()
                .setAtMillis(millis)
                .setImpulse(impulse)
                .build();
    }

    private static MemoryHost host(TechniqueDefinition definition) {
        // 唯一身体账本由 Framework 测试宿主提供。
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
    }

    private static TechniqueHost losingReceipt(MemoryHost host) {
        // 仅丢弃已经提交的响应，底层事务和存储保持真实实现。
        return new TechniqueHost() {
            @Override
            public Inspection inspect() {
                // 身体与时钟仍由实际宿主结算。
                return host.inspect();
            }

            @Override
            public Optional<Commit> findActivity(String requestId) {
                // 已保存的回执不因网络失败而丢失。
                return host.findActivity(requestId);
            }

            @Override
            public Commit submit(Submission submission) {
                // 先提交再报告暂时失败，模拟响应未送达。
                host.submit(submission);
                throw new TechniqueErrors.HostRejected(FailureCode.INTERNAL_ERROR);
            }

            @Override
            public EffectCommit submitEffect(EffectSubmission submission) {
                // 世界效果仍沿用原宿主的稳定身份去重。
                return host.submitEffect(submission);
            }
        };
    }

    private static double fatigue(MemoryHost host) {
        // 只读已提交疲劳，避免查询时推进时间干扰去重断言。
        return host.current().tissueStates().getFirst().fatigue();
    }
}
