package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static org.junit.Assert.*;

/** 附属世界接管必须在每种施放结束路径收到一次最终通知。 */
public class TechniqueEffectLifecycleTest {
    private static final long FIRST_EVENT = 1_000;
    private static final long FINISH = 32_000;
    private static final String REJECTION = "巫釜结构缺少炉座";

    @Test
    public void rejectedOrMissingHandlerPublishesStructuredFailureAndNeverFallsThrough() {
        // 两种失败都走施放回执和结束通知，不能转交普通宿主假装执行。
        for (HandlerSetup setup : HandlerSetup.values()) {
            Fixture fixture = fixture();
            if (setup == HandlerSetup.REJECTING) {
                fixture.service().setEffectHandler(event -> EffectHandling.rejected(REJECTION));
            }
            var outcomes = new ArrayList<TechniqueService.CastObserved>();
            fixture.service().addCastListener(outcomes::add);
            fixture.host().nowMillis = FIRST_EVENT;
            fixture.service().tick();
            assertEquals(1, fixture.ended().size());
            TechniqueCastEnded ended = fixture.ended().getFirst();
            assertEquals(TechniqueCastEndReason.FAILED, ended.reason());
            assertEquals(TechniqueFailure.EFFECT_REJECTED, ended.outcome().getFailure());
            assertFalse(ended.outcome().getReason().isBlank());
            if (setup == HandlerSetup.REJECTING) {
                assertEquals(REJECTION, ended.outcome().getReason());
            }
            assertEquals(ended.outcome(), outcomes.getFirst().outcome());
            assertTrue(fixture.host().effects.stream().noneMatch(effect -> effect.hasBlockWork()));
            assertFalse(TechniqueState.read(fixture.host().current()).hasActiveCast());
            assertEndsOnce(fixture);
        }
    }

    @Test
    public void completionUnloadDisconnectStaggerAndDeathReleaseExactlyOnce() {
        // 生命周期结束保留施放身份；未退费用的真实身体活动已经在起手落账。
        for (TechniqueCastEndReason reason : List.of(TechniqueCastEndReason.COMPLETED,
                TechniqueCastEndReason.UNLOADED, TechniqueCastEndReason.DISCONNECTED,
                TechniqueCastEndReason.STAGGERED, TechniqueCastEndReason.DIED)) {
            Fixture fixture = fixture();
            fixture.service().setEffectHandler(event -> EffectHandling.handled());
            fixture.host().nowMillis = FIRST_EVENT;
            fixture.service().tick();
            double paidQi = fixture.host().current().totalQi();
            switch (reason) {
                case COMPLETED -> {
                    fixture.host().nowMillis = FINISH;
                    fixture.service().tick();
                }
                case UNLOADED -> fixture.service().cancelOnUnload(ENTITY);
                case DISCONNECTED -> fixture.service().cancelOnDisconnect(ENTITY);
                case STAGGERED -> fixture.service().stagger(ENTITY, HIT_STAGGER);
                case DIED -> {
                    fixture.host().die();
                    fixture.service().tick();
                }
                default -> throw new AssertionError(reason);
            }
            assertEquals(1, fixture.ended().size());
            TechniqueCastEnded event = fixture.ended().getFirst();
            assertEquals(reason, event.reason());
            assertEquals(ENTITY, event.entityUuid());
            assertEquals("lifecycle", event.castId());
            if (reason != TechniqueCastEndReason.DIED) {
                assertEquals(paidQi, fixture.host().current().totalQi(), 0);
            }
            assertEndsOnce(fixture);
        }
    }

    @Test
    public void effectFailureAtFinalBoundaryStillReportsFailureAfterBodyAlreadyFinished() {
        // 迟到 tick 先提交收势再交付周期事件时，拒绝仍须覆盖原先的成功结果。
        Fixture fixture = fixture();
        fixture.service().setEffectHandler(event -> EffectHandling.rejected(REJECTION));
        fixture.host().nowMillis = FINISH;
        fixture.service().tick();
        TechniqueCastEnded event = fixture.ended().getFirst();
        assertEquals(TechniqueCastEndReason.FAILED, event.reason());
        assertEquals(TechniqueFailure.EFFECT_REJECTED, event.outcome().getFailure());
        assertEquals(REJECTION, event.outcome().getReason());
        assertFalse(TechniqueState.read(fixture.host().current()).hasActiveCast());
    }

    private static Fixture fixture() {
        // 默认样本有周期效果，起手时不依赖世界处理器。
        return fixture(TechniqueBrewEffectsTest.blockDefinition());
    }

    private static Fixture fixture(TechniqueDefinition definition) {
        // 使用公开服务入口和真实 Framework 活动，附属只负责返回世界效果处理结果。
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        var ended = new ArrayList<TechniqueCastEnded>();
        service.addCastEndListener(ended::add);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "lifecycle")).getPhase());
        return new Fixture(host, service, ended);
    }

    @Test
    public void cleanupRetryAndLaterDeathRecoveryDoNotRepeatEndNotification() {
        // 表现通道失败不能拖住世界接管，恢复死体进度也不能重复通知已结束的施放。
        TechniqueDefinition definition = TechniqueBrewEffectsTest.blockDefinition().toBuilder()
                .setMovementSpeedMultiplier(HeldTechniqueFixtures.MOVEMENT_MULTIPLIER)
                .build();
        Fixture fixture = fixture(definition);
        fixture.service().setEffectHandler(event -> EffectHandling.rejected(REJECTION));
        fixture.host().effectObserver = submission -> {
            throw new TechniqueErrors.HostRejected(FailureCode.INTERNAL_ERROR);
        };
        fixture.host().nowMillis = FIRST_EVENT;
        fixture.service().tick();
        assertEquals(1, fixture.ended().size());
        fixture.service().tick();
        assertEquals(1, fixture.ended().size());
        fixture.host().effectObserver = submission -> { /* 恢复表现交付，清理原请求。 */ };
        assertEndsOnce(fixture);

        Fixture dead = fixture();
        dead.host().die();
        dead.service().tick();
        dead.service().recoverOnLoad(ENTITY, dead.host());
        assertEquals(1, dead.ended().size());
    }

    private static void assertEndsOnce(Fixture fixture) {
        // 重复 tick 和卸载不能重发结束通知或恢复已结束的接管。
        fixture.service().tick();
        fixture.service().cancelOnUnload(ENTITY);
        fixture.service().cancelOnDisconnect(ENTITY);
        assertEquals(1, fixture.ended().size());
    }

    private enum HandlerSetup { MISSING, REJECTING }
    private record Fixture(MemoryHost host, TechniqueService service, List<TechniqueCastEnded> ended) { }
}
