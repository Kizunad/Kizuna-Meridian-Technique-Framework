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
        assertEquals(progress.toBuilder().clearActiveCast().build(), TechniqueState.read(recovered));
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
        assertEquals(progress.toBuilder().clearActiveCast().build(), TechniqueState.read(restored.current()));
        assertEquals(before.vitality(), restored.current().vitality());
        assertEquals(before.structure(), restored.current().structure());
        assertEquals(released, restored.store.environmentQi());
        assertFalse(TechniqueState.read(restored.restore().current()).hasActiveCast());
        assertTrue(restored.effects.isEmpty());
    }

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
