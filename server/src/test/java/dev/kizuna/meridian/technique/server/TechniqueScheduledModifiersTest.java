package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.*;

/** 延迟曲线使用真实 Framework 事务验证取消、迟到与重试。 */
public final class TechniqueScheduledModifiersTest {
    private static final long FIRST = 50;
    private static final long BEFORE_FIRST = 25;
    private static final long SECOND = 75;
    private static final long END = WARMUP + ACTIVE + RECOVERY;
    private static final long LATE_OFFSET = 750;
    private static final double HALF_CURVE_VALUE = 0.5;
    private static final double EPSILON = 0.000001;
    private static final long FOLLOW_UP_WINDOW = 500;
    private static final double FOLLOW_UP_RATIO = 0.5;
    private static final double WORK_SECONDS = 0.05;
    private static final int TWO_CURVES = 2;

    @Test
    public void interruptionBeforeRegistrationDropsCurveButKeepsQiCost() {
        // 起手未给身体加曲线；打断后未来增益与虚脱均不发生，真元不退。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "early")).getPhase());
        assertTrue(host.current().modifiers().isEmpty());
        TechniqueProgress started = TechniqueState.read(host.current());
        assertEquals(1, started.getActiveCast().getPendingModifiersCount());
        assertEquals(0, started.getCurveEndsOrDefault(TECHNIQUE_ID, 0));
        var paidQi = host.current().meridianStates();
        host.nowMillis = BEFORE_FIRST;
        service.stagger(ENTITY, HIT_STAGGER);
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertEquals(paidQi, host.current().meridianStates());
        host.nowMillis = FIRST + CURVE_MIDDLE;
        service.tick();
        assertTrue(host.current().modifiers().isEmpty());
    }

    @Test
    public void registeredCurveSurvivesCancellationAndKeepsOriginalExpiry() {
        // 登记之后下线不抹掉能力曲线，峰值与到期都沿用原时钟。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "registered"));
        host.nowMillis = FIRST;
        service.tick();
        assertEquals(1, host.current().modifiers().size());
        assertEquals(0, TechniqueState.read(host.current()).getActiveCast().getPendingModifiersCount());
        service.cancelOnDisconnect(ENTITY);
        assertEquals(FIRST + CURVE_END, TechniqueState.read(host.current()).getCurveEndsOrThrow(TECHNIQUE_ID));
        host.nowMillis = FIRST + CURVE_MIDDLE;
        assertEquals(STRENGTH * PEAK, strength(host), EPSILON);
        host.nowMillis = FIRST + CURVE_END;
        assertEquals(STRENGTH, strength(host), EPSILON);
        assertTrue(host.current().modifiers().isEmpty());
    }

    @Test
    public void cancellationAtActivationBoundaryRegistersCurveFirst() {
        // 取消发生在登记时刻时先结算已经到时的曲线，不能通过下线逃过其后虚脱。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "boundary_cancel"));
        host.nowMillis = FIRST;
        service.cancelOnDisconnect(ENTITY);
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertEquals(1, host.current().modifiers().size());
        assertEquals(FIRST + CURVE_END,
                TechniqueState.read(host.current()).getCurveEndsOrThrow(TECHNIQUE_ID));
    }

    @Test
    public void refusedRegistrationDoesNotPreventCancellation() {
        // 登记被宿主拒绝时仍清掉动作和待办，不能留下无法取消的施放。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "rejected_cancel"));
        host.nowMillis = FIRST;
        host.rejectNextActivity();
        service.cancelOnUnload(ENTITY);
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertTrue(host.current().modifiers().isEmpty());
        assertTrue(host.current().actions().isEmpty());
    }

    @Test
    public void deadBodyRecoveryDiscardsPendingCurveWithoutRegistration() {
        // 死亡后的恢复只清理旧施放，不在死体上新增已经到时的修正。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "dead"));
        host.nowMillis = FIRST;
        assertEquals(FIRST, host.inspect().body().settledAt().elapsedMillis());
        host.die();
        int submissions = host.activitySubmissions;
        service.recoverOnLoad(ENTITY, host);
        assertEquals(submissions, host.activitySubmissions);
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertTrue(host.current().modifiers().isEmpty());
    }

    @Test
    public void lateRegistrationKeepsCurrentValueAndDoesNotExtendCurve() {
        // 晚于峰值的 tick 只提交剩余衰减段，原开始和结束边界不后移。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "late"));
        host.nowMillis = FIRST + LATE_OFFSET;
        service.tick();
        assertEquals(1, host.current().modifiers().size());
        var curve = host.current().modifiers().getFirst().curve();
        assertEquals(host.nowMillis, curve.startMillis());
        assertEquals(HALF_CURVE_VALUE, curve.points().getFirst().value(), EPSILON);
        assertEquals(FIRST + CURVE_END, curve.endMillis());
        double expected = STRENGTH * (1 + (PEAK - 1) * HALF_CURVE_VALUE);
        assertEquals(expected, strength(host), EPSILON);
        host.nowMillis = FIRST + CURVE_END;
        assertEquals(STRENGTH, strength(host), EPSILON);
    }

    @Test
    public void tickAfterExpiryDiscardsPendingCurveWithoutRegisteringIt() {
        // 整条曲线已经过去时不向 Framework 提交过去的 startMillis 或延期曲线。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "expired"));
        host.nowMillis = FIRST + CURVE_END;
        service.tick();
        assertTrue(host.current().modifiers().isEmpty());
        TechniqueProgress finished = TechniqueState.read(host.current());
        assertFalse(finished.hasActiveCast());
        assertEquals(CastPhase.FINISHED, finished.getLastCast().getEnding());
        var receipt = host.findActivity("technique_modifier_expired_1").orElseThrow();
        assertEquals(0, receipt.activity().getAddModifiersCount());
    }

    @Test
    public void followUpSkipsEarlierCurveAndRegistersClipStartAtomically() {
        // 起播点之前的曲线不补发，起播点上的随起手登记，其后曲线按剩余时钟触发。
        TechniqueDefinition previous = deferred().toBuilder().clearEffects().build();
        TechniqueDefinition next = followUp(previous);
        TechniqueCatalog catalog = catalog(previous).toBuilder().addDefinitions(next).build();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(catalog));
        TechniqueProgress known = progress(previous).toBuilder().addAllKnown(progress(next).getKnownList()).build();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), known));
        TechniqueCasting casting = new TechniqueCasting(registry);
        casting.cast(host, request(host.current(), "previous"));
        host.nowMillis = END;
        casting.advance(host);
        CastRequest request = request(host.current(), "follow").toBuilder()
                .setTechnique(TechniqueRegistry.reference(next))
                .setEntry(CastEntry.FOLLOW_UP)
                .build();
        assertEquals(CastPhase.WARMUP, casting.cast(host, request).getPhase());
        assertEquals(1, host.current().modifiers().size());
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        assertEquals(END, host.current().modifiers().getFirst().curve().startMillis());
        assertEquals(END + SECOND - FIRST, active.getPendingModifiers(0).getStartMillis());
        host.nowMillis = END + SECOND - FIRST;
        casting.advance(host, END);
        assertEquals(TWO_CURVES, host.current().modifiers().size());
    }

    @Test
    public void sameBoundaryRegistersCurveBeforeWorkAndWorldEffects() {
        // 做功和世界效果读取已经登记的曲线，重复推进不重复登记。
        TechniqueDefinition original = deferred();
        Quantity duration = quantity(WORK_SECONDS, Unit.SECOND);
        WorkCost work = work("actuator").toBuilder().setDuration(duration).build();
        TechniqueEffect workEffect = TechniqueEffect.newBuilder()
                .setAtMillis(FIRST)
                .setWork(work)
                .build();
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.FORWARD)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
        TechniqueEffect impulseEffect = TechniqueEffect.newBuilder()
                .setAtMillis(FIRST)
                .setImpulse(impulse)
                .build();
        TechniqueDefinition definition = original.toBuilder()
                .addEffects(workEffect)
                .addEffects(impulseEffect)
                .build();
        MemoryHost host = host(definition);
        TechniqueService service = service(host, registry(definition));
        host.effectObserver = submission -> assertEquals(1, host.current().modifiers().size());
        service.cast(ENTITY, request(host.current(), "ordered"));
        host.nowMillis = FIRST;
        service.tick();
        var modifierReceipt = host.findActivity("technique_modifier_ordered_1").orElseThrow();
        var workReceipt = host.findActivity("technique_work_ordered_1").orElseThrow();
        assertTrue(modifierReceipt.revision() < workReceipt.revision());
        assertEquals(1, host.effects.size());
        int submissions = host.activitySubmissions;
        service.tick();
        assertEquals(submissions, host.activitySubmissions);
    }

    @Test
    public void lostRegistrationResponseAndRestoredBodyDoNotDuplicateCurve() {
        // 登记和清除待办已保存时，丢响应与宿主重建均不能再次加入同一修正。
        TechniqueDefinition definition = deferred();
        MemoryHost host = host(definition);
        TechniqueCasting casting = new TechniqueCasting(registry(definition));
        casting.cast(host, request(host.current(), "lost"));
        host.nowMillis = FIRST;
        var failed = casting.advance(losingReceipt(host), 0);
        assertEquals(FailureCode.INTERNAL_ERROR, failed.outcome().getFrameworkFailure());
        var registered = host.current();
        casting.advance(host, 0);
        assertEquals(registered, host.current());
        MemoryHost restored = host.restore();
        casting.advance(restored, 0);
        assertEquals(0, restored.activitySubmissions);
        assertEquals(1, restored.current().modifiers().size());
    }

    private static TechniqueDefinition deferred() {
        // 保留真元费用，排除普通做功疲劳对曲线倍率断言的影响。
        TechniqueDefinition original = definition();
        TechniqueCost cost = original.getCost().toBuilder().clearWork().build();
        return original.toBuilder().setCost(cost).addEffects(modifierEffect(FIRST)).build();
    }

    private static TechniqueEffect modifierEffect(long atMillis) {
        // 登记时机由目录显式声明，其他曲线数值复用通用夹具。
        TissueModifier modifier = modifier().toBuilder().setActivation(ModifierActivation.AT_EFFECT_TIME).build();
        return TechniqueEffect.newBuilder()
                .setAtMillis(atMillis)
                .setTissueModifier(modifier)
                .build();
    }

    private static TechniqueDefinition followUp(TechniqueDefinition previous) {
        // 三条曲线分别覆盖跳过区、起播点和后续时刻。
        FollowUp transition = FollowUp.newBuilder()
                .setPrevious(TechniqueRegistry.reference(previous))
                .setRecoveryStartRatio(quantity(FOLLOW_UP_RATIO, Unit.RATIO))
                .setAfterRecoveryMillis(FOLLOW_UP_WINDOW)
                .setStartMillis(FIRST)
                .build();
        FollowUpRules rules = FollowUpRules.newBuilder().addTransitions(transition).build();
        return deferred().toBuilder()
                .setId("sample:next")
                .setFollowUp(rules)
                .clearEffects()
                .addEffects(modifierEffect(0))
                .addEffects(modifierEffect(FIRST))
                .addEffects(modifierEffect(SECOND))
                .build();
    }

    private static MemoryHost host(TechniqueDefinition definition) {
        // 身体与附属状态通过真实 Framework 存储及协议提交。
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
    }

    private static double strength(MemoryHost host) {
        // 通过 Framework 当前能力查询验证曲线值，避免仅核对消息外形。
        return TissueCapabilities.available(host.inspect().body(), new Anatomy.TissueId("tendril")).strengthNewtons();
    }

    private static TechniqueHost losingReceipt(MemoryHost host) {
        // 丢掉成功响应，权威事务仍使用真实宿主。
        return new TechniqueHost() {
            @Override
            public Inspection inspect() {
                // 与原宿主共享身体时钟。
                return host.inspect();
            }

            @Override
            public Optional<Commit> findActivity(String requestId) {
                // 活动回执仍保存在实际存储。
                return host.findActivity(requestId);
            }

            @Override
            public Commit submit(Submission submission) {
                // 先原子提交，再模拟响应传输失败。
                host.submit(submission);
                throw new TechniqueErrors.HostRejected(FailureCode.INTERNAL_ERROR);
            }

            @Override
            public EffectCommit submitEffect(EffectSubmission submission) {
                // 世界效果沿用原宿主的稳定身份。
                return host.submitEffect(submission);
            }
        };
    }
}
