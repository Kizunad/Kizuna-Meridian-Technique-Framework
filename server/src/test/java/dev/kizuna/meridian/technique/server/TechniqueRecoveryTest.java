package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

/** 异常退出和死亡恢复必须通过 Framework 保存，不能重复施加世界效果或退费。 */
public class TechniqueRecoveryTest {
    private static final UUID ENTITY_UUID = UUID.randomUUID();
    private static final long EARLY_RECOVERY_MILLIS = 50;
    private static final long LATE_RECOVERY_MILLIS = 4000;
    private static final long EARLY_COOLDOWN_END = 2050;
    private static final long LATE_COOLDOWN_END = 6000;
    private static final long NORMAL_COOLDOWN_END = 2600;

    @Test
    public void unclaimedLivingSaveRecoversOnceAndCanCastAgainAfterCooldown() {
        // 新服务没有实体认领，也能终止旧档中的活动，且未来施放不再被 active_cast 卡住。
        MemoryHost original = activeHost();
        MemoryHost restored = original.restore();
        var before = restored.current();
        var progress = TechniqueState.read(before);
        TechniqueService service = service();
        service.recoverOnLoad(ENTITY_UUID, restored);
        BodySnapshot recovered = restored.current();
        assertFalse(TechniqueState.read(recovered).hasActiveCast());
        assertTrue(recovered.actions().isEmpty());
        assertEquals(COOLDOWN, (long) TechniqueState.read(recovered).getCooldownsOrThrow(definition().getId()));
        assertEquals(progress.getKnownList(), TechniqueState.read(recovered).getKnownList());
        assertEquals(before.totalQi(), recovered.totalQi(), 0);
        assertEquals(before.revision() + 1, recovered.revision());
        assertTrue(restored.effects.isEmpty());
        service.recoverOnLoad(ENTITY_UUID, restored);
        assertEquals(recovered, restored.current());
        restored.nowMillis = COOLDOWN;
        assertEquals(CastPhase.WARMUP, new TechniqueCasting(registry(definition()))
                .cast(restored, request(restored.current(), "after_recovery")).getPhase());
    }

    @Test
    public void deadSaveClearsOnlyActiveAndRetainsDeathReleaseAndReceipts() {
        // 死体普通活动被拒绝，但可信进度替换允许结束残留，且重新加载不会复活施放。
        MemoryHost original = activeHost();
        original.die();
        MemoryHost restored = original.restore();
        BodySnapshot before = restored.current();
        var progress = TechniqueState.read(before);
        var released = restored.store.environmentQi();
        service().recoverOnLoad(ENTITY_UUID, restored);
        assertTrue(restored.current().vitality().dead());
        var recoveredProgress = TechniqueState.read(restored.current());
        assertEquals(COOLDOWN, (long) recoveredProgress.getCooldownsOrThrow(definition().getId()));
        assertEquals(progress.getKnownList(), recoveredProgress.getKnownList());
        assertEquals(progress.getCurveEndsMap(), recoveredProgress.getCurveEndsMap());
        assertEquals(before.vitality(), restored.current().vitality());
        assertEquals(before.structure(), restored.current().structure());
        assertEquals(released, restored.store.environmentQi());
        assertFalse(TechniqueState.read(restored.restore().current()).hasActiveCast());
        assertTrue(restored.effects.isEmpty());
    }

    @Test
    public void livingRecoveryBeforeOriginalFinishUsesActualTerminationTime() {
        // 早于原收势的取消从实际恢复时刻开始计冷却。
        assertRecoveryCooldown(EARLY_RECOVERY_MILLIS, EARLY_COOLDOWN_END, SavedVitality.LIVING);
    }

    @Test
    public void livingRecoveryAfterOriginalFinishUsesActualTerminationTime() {
        // 晚恢复不能借用已经过去的原收势边界缩短冷却。
        assertRecoveryCooldown(LATE_RECOVERY_MILLIS, LATE_COOLDOWN_END, SavedVitality.LIVING);
    }

    @Test
    public void deadRecoveryBeforeOriginalFinishUsesActualTerminationTime() {
        // 死体可信进度替换沿用与活体相同的取消时钟。
        assertRecoveryCooldown(EARLY_RECOVERY_MILLIS, EARLY_COOLDOWN_END, SavedVitality.DEAD);
    }

    @Test
    public void deadRecoveryAfterOriginalFinishUsesActualTerminationTime() {
        // 死体晚恢复也保留完整取消冷却，不退回原收势时刻。
        assertRecoveryCooldown(LATE_RECOVERY_MILLIS, LATE_COOLDOWN_END, SavedVitality.DEAD);
    }

    @Test
    public void lateNormalFinishKeepsOriginalRecoveryBoundary() {
        // 正常推进即使晚处理，仍从固定收势边界计冷却。
        MemoryHost host = activeHost();
        host.nowMillis = LATE_RECOVERY_MILLIS;
        var result = new TechniqueCasting(registry(definition())).advance(host, 0);
        assertEquals(CastPhase.FINISHED, result.outcome().getPhase());
        assertEquals(NORMAL_COOLDOWN_END,
                (long) TechniqueState.read(host.current()).getCooldownsOrThrow(definition().getId()));
    }

    private static void assertRecoveryCooldown(long recoveredMillis, long cooldownEnd, SavedVitality vitality) {
        // 先通过真实结时保存时刻，再以新服务恢复，期望值全部来自独立具名常量。
        MemoryHost original = activeHost();
        original.nowMillis = recoveredMillis;
        original.inspect();
        if (vitality == SavedVitality.DEAD) {
            original.die();
        }
        MemoryHost restored = original.restore();
        service().recoverOnLoad(ENTITY_UUID, restored);
        var recovered = TechniqueState.read(restored.current());
        assertFalse(recovered.hasActiveCast());
        assertEquals(CastPhase.CANCELLED, recovered.getLastCast().getEnding());
        assertEquals(cooldownEnd, (long) recovered.getCooldownsOrThrow(definition().getId()));
    }

    private enum SavedVitality { LIVING, DEAD }

    @Test
    public void rejectedRecoveryRetainsResidualAndCanRetry() {
        // 并发版本拒绝不能被当成取消成功，原状态保留以供同一恢复入口重试。
        MemoryHost host = activeHost();
        BodySnapshot before = host.current();
        TechniqueRecoveryHost rejected = new TechniqueRecoveryHost() {
            @Override
            public BodySnapshot snapshot() {
                // 复用真实权威快照，仅提交时注入并发拒绝。
                return host.current();
            }

            @Override
            public long submitRecovery(String requestId, BodyCommand command) {
                // 模拟宿主在事务中检测到版本已经变化。
                throw new TechniqueErrors.HostRejected(FailureCode.REVISION_CONFLICT);
            }

            @Override
            public TechniqueHost.EffectCommit submitEffect(TechniqueHost.EffectSubmission submission) {
                // 恢复清理复用真实测试宿主的幂等效果入口。
                return host.submitEffect(submission);
            }
        };
        TechniqueService service = service();
        var failure = assertThrows(TechniqueErrors.HostRejected.class,
                () -> service.recoverOnLoad(ENTITY_UUID, rejected));
        assertEquals(FailureCode.REVISION_CONFLICT, failure.failure());
        assertEquals(before, host.current());
        service.recoverOnLoad(ENTITY_UUID, host);
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
    }

    private static MemoryHost activeHost() {
        // 测试旧档由真实起手写入器生成，而非手工拼出 active_cast。
        var definition = definition();
        var initial = withProgress(body(Shape.TENDRIL), progress(definition));
        var host = new MemoryHost(initial);
        assertEquals(CastPhase.WARMUP, new TechniqueCasting(registry(definition))
                .cast(host, request(initial, "saved_cast")).getPhase());
        return host;
    }

    private static TechniqueService service() {
        // 恢复服务不依赖实体已经被公开目录认领。
        return new TechniqueService(registry(definition()), new TechniqueHostDirectory(List.of()));
    }
}
