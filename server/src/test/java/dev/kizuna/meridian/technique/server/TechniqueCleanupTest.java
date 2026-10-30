package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 业务拒绝、部分交付和同步回调都不能遗失实体约束的清理责任。 */
public class TechniqueCleanupTest {
    private static final long FINISH_MILLIS = 600;

    @Test
    public void missingTargetTerminatesAndCleansStartedEffects() {
        // 目标消失返回永久业务拒绝，动作和已开始的表现仍必须清理。
        Fixture fixture = fixture(targetedDefinition());
        fixture.host.effectObserver = submission -> {
            if (submission.effect().hasImpulse()) {
                throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
            }
        };
        fixture.host.nowMillis = WARMUP;
        fixture.service.tick();
        assertCleaned(fixture);
        assertEquals(0, fixture.host.effects.stream().filter(EffectIntent::hasImpulse).count());
        assertTrue(fixture.service.cancelOnUnload(ENTITY).isEmpty());
    }

    @Test
    public void partialBatchSuccessRetainsStopWithoutReplayingBusiness() {
        // 同一批次中 START 已成功、后续目标效果失败，STOP 被拒仍保留原身份供后续清理。
        TechniqueDefinition definition = targetedDefinition().toBuilder()
                .clearMovementSpeedMultiplier()
                .build();
        Fixture fixture = fixture(definition);
        List<String> stopRequests = new ArrayList<>();
        fixture.host.effectObserver = submission -> {
            EffectIntent effect = submission.effect();
            if (effect.hasImpulse() || isStop(effect)) {
                if (isStop(effect)) {
                    stopRequests.add(submission.requestId());
                }
                throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
            }
        };
        fixture.host.nowMillis = WARMUP;
        fixture.service.tick();
        assertFalse(TechniqueState.read(fixture.host.current()).hasActiveCast());
        assertEquals(1, count(fixture, PresentationAction.START));
        fixture.service.tick();
        assertEquals(1, stopRequests.stream().distinct().count());
        fixture.host.effectObserver = submission -> { /* 恢复表现通道，允许原身份清理。 */ };
        fixture.service.tick();
        assertEquals(1, count(fixture, PresentationAction.START));
        assertEquals(1, count(fixture, PresentationAction.STOP));
        assertTrue(fixture.service.cancelOnUnload(ENTITY).isEmpty());
    }

    @Test
    public void cleanupRejectedAfterBodyFinishRetriesWithoutActiveCast() {
        // 身体已正常收势仍保存外部清理；STOP 失败不能阻塞 UNLOCK。
        Fixture fixture = fixture(HeldTechniqueFixtures.movementDefinition());
        fixture.host.effectObserver = submission -> {
            if (isStop(submission.effect())) {
                throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
            }
        };
        fixture.host.nowMillis = FINISH_MILLIS;
        fixture.service.tick();
        assertFalse(TechniqueState.read(fixture.host.current()).hasActiveCast());
        assertTrue(fixture.host.effects.stream().anyMatch(effect -> effect.hasMovement()
                && effect.getMovement().getAction() == MovementAction.UNLOCK));
        fixture.service.tick();
        assertEquals(0, count(fixture, PresentationAction.STOP));
        fixture.host.effectObserver = submission -> { /* 清理恢复后不重放已经成功的 START。 */ };
        fixture.service.tick();
        assertCleaned(fixture);
    }

    @Test
    public void unloadRetriesRejectedCleanupAndKeepsStableIdentities() {
        // 卸载多次请求和 tick 共用原始清理身份，不能将拒绝等同于完成。
        Fixture fixture = fixture(HeldTechniqueFixtures.movementDefinition());
        List<TechniqueHost.EffectSubmission> attempts = new ArrayList<>();
        fixture.host.effectObserver = submission -> {
            attempts.add(submission);
            throw new TechniqueErrors.HostRejected(FailureCode.MODULE_UNAVAILABLE);
        };
        fixture.service.cancelOnUnload(ENTITY);
        fixture.service.cancelOnUnload(ENTITY);
        assertEquals(2, attempts.stream().distinct().count());
        assertThrows(IllegalStateException.class, fixture.service::shutdown);
        fixture.host.effectObserver = submission -> { /* 生命周期接线恢复后继续原清理。 */ };
        fixture.service.tick();
        assertCleaned(fixture);
        fixture.service.shutdown();
    }

    @Test
    public void startCallbackQueuesCancelUntilDeliveryReturns() {
        // START 回调请求取消时仍可看到原活动，外层回执完成后才允许 STOP。
        TechniqueDefinition definition = HeldTechniqueFixtures.movementDefinition();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        host.effectObserver = submission -> {
            if (submission.effect().hasPresentation()
                    && submission.effect().getPresentation().getAction() == PresentationAction.START) {
                assertTrue(service.cancelOnUnload(ENTITY).isEmpty());
                assertTrue(TechniqueState.read(host.current()).hasActiveCast());
                service.tick();
            }
        };
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "callback")).getPhase());
        assertCleaned(new Fixture(host, service));
        assertTrue(service.cancelOnUnload(ENTITY).isEmpty());
    }

    private static Fixture fixture(TechniqueDefinition definition) {
        // 起手及效果传输都走真实 Framework 和宿主字节协议。
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        CastRequest request = request(host.current(), "cleanup").toBuilder()
                .setTargetId(ENTITY.toString())
                .build();
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request).getPhase());
        return new Fixture(host, service);
    }

    private static TechniqueDefinition targetedDefinition() {
        // 表现先开始，再在起手结束时尝试朝目标移动。
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.TOWARD_TARGET)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setImpulse(impulse)
                .build();
        return HeldTechniqueFixtures.movementDefinition().toBuilder().addEffects(effect).build();
    }

    private static boolean isStop(EffectIntent effect) {
        // 清理断言只识别停止意图，不把开始表现计入。
        return effect.hasPresentation() && effect.getPresentation().getAction() == PresentationAction.STOP;
    }

    private static long count(Fixture fixture, PresentationAction action) {
        // 对宿主真正接受的副作用计数，拒绝尝试不计成功。
        return fixture.host.effects.stream().filter(effect -> effect.hasPresentation()
                && effect.getPresentation().getAction() == action).count();
    }

    private static void assertCleaned(Fixture fixture) {
        // 身体动作和外部持续表现都清理，不依赖仅有的服务返回值。
        assertFalse(TechniqueState.read(fixture.host.current()).hasActiveCast());
        assertTrue(fixture.host.current().actions().isEmpty());
        assertEquals(1, count(fixture, PresentationAction.START));
        assertEquals(1, count(fixture, PresentationAction.STOP));
    }

    private record Fixture(MemoryHost host, TechniqueService service) { }
}
