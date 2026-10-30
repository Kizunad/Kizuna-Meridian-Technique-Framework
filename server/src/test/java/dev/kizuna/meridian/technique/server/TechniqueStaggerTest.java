package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.kizuna.meridian.technique.server.Fixtures.ACTIVE;
import static dev.kizuna.meridian.technique.server.Fixtures.HIT_STAGGER;
import static dev.kizuna.meridian.technique.server.Fixtures.RECOVERY;
import static dev.kizuna.meridian.technique.server.Fixtures.STAGGER_MOVEMENT;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TechniqueStaggerTest {
    private static final long LONG_STAGGER = 500;
    private static final long SHORT_STAGGER = 200;
    private static final long LATER_STAGGER = 600;
    private static final long SECOND_HIT_MILLIS = 100;
    private static final long FINISHED_MILLIS = WARMUP + ACTIVE + RECOVERY;
    private static final long OVER_LIMIT_MILLIS = 60_001;
    private static final double SPEEDUP = 1.5;

    @Test
    public void staggerInterruptsInterruptibleWarmup() {
        // 起手中的可打断式受僵直即打断，费用不退、施放清除。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "warmup")).getPhase());
        CastOutcome interrupted = service.stagger(ENTITY, HIT_STAGGER).orElseThrow();
        assertEquals(CastPhase.INTERRUPTED, interrupted.getPhase());
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
    }

    @Test
    public void hitWithoutStaggerDoesNotInterrupt() {
        // 剂量不足门槛时伤害附属给出零时长，起手照常推进到出手。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        service.cast(ENTITY, request(host.current(), "unshaken"));
        var none = new TechniqueService.Stagger(0, STAGGER_MOVEMENT);
        assertEquals(Optional.empty(), service.stagger(ENTITY, none));
        assertEquals(0, service.staggerRemainingMillis(ENTITY));
        host.nowMillis = WARMUP;
        service.tick();
        assertTrue(TechniqueState.read(host.current()).hasActiveCast());
    }

    @Test
    public void staggerOutsideWarmupLetsCastFinish() {
        // 出手与收势中的僵直不打断，施放按原边界正常收势。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        service.cast(ENTITY, request(host.current(), "committed"));
        host.nowMillis = WARMUP;
        service.tick();
        assertEquals(CastPhase.EFFECTIVE, service.stagger(ENTITY, HIT_STAGGER).orElseThrow().getPhase());
        host.nowMillis = WARMUP + ACTIVE;
        assertEquals(CastPhase.RECOVERING, service.stagger(ENTITY, HIT_STAGGER).orElseThrow().getPhase());
        host.nowMillis = FINISHED_MILLIS;
        service.tick();
        TechniqueProgress progress = TechniqueState.read(host.current());
        assertFalse(progress.hasActiveCast());
        assertEquals(CastPhase.FINISHED, progress.getLastCast().getEnding());
    }

    @Test
    public void staggerRejectsNewCastUntilExpiry() {
        // 僵直期间起式回执「僵直中」且不扣费；到期后同一请求可以起手。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        service.stagger(ENTITY, new TechniqueService.Stagger(LONG_STAGGER, STAGGER_MOVEMENT));
        var before = host.current();
        CastOutcome rejected = service.cast(ENTITY, request(host.current(), "staggered"));
        assertEquals(TechniqueFailure.STAGGERED, rejected.getFailure());
        assertEquals("僵直中", rejected.getReason());
        assertEquals(before.revision(), host.current().revision());
        host.nowMillis = LONG_STAGGER;
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "recovered")).getPhase());
    }

    @Test
    public void laterExpiryWinsWithoutAddingDurations() {
        // 较早到期的新命中不缩短也不叠加；较晚到期的命中替换到期时刻并重新通知。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        List<TechniqueService.StaggerChanged> events = new ArrayList<>();
        service.addStaggerListener(events::add);
        service.stagger(ENTITY, new TechniqueService.Stagger(LONG_STAGGER, STAGGER_MOVEMENT));
        host.nowMillis = SECOND_HIT_MILLIS;
        service.stagger(ENTITY, new TechniqueService.Stagger(SHORT_STAGGER, STAGGER_MOVEMENT));
        assertEquals(LONG_STAGGER - SECOND_HIT_MILLIS, service.staggerRemainingMillis(ENTITY));
        service.stagger(ENTITY, new TechniqueService.Stagger(LATER_STAGGER, STAGGER_MOVEMENT));
        assertEquals(LATER_STAGGER, service.staggerRemainingMillis(ENTITY));
        assertEquals(List.of(LONG_STAGGER, LATER_STAGGER),
                events.stream().map(TechniqueService.StaggerChanged::remainingMillis).toList());
    }

    @Test
    public void tickEndsExpiredStaggerAndRestoresMovement() {
        // 身体时钟越过到期时刻后由 tick 发布结束事件，表现层据此恢复原速。
        MemoryHost host = learnedHost();
        TechniqueService service = service(host, registry(definition()));
        List<TechniqueService.StaggerChanged> events = new ArrayList<>();
        service.addStaggerListener(events::add);
        service.stagger(ENTITY, HIT_STAGGER);
        assertEquals(STAGGER_MOVEMENT, events.get(0).movementMultiplier(), 0);
        host.nowMillis = HIT_STAGGER.durationMillis();
        service.tick();
        assertEquals(0, events.get(1).remainingMillis());
        assertEquals(1, events.get(1).movementMultiplier(), 0);
        assertEquals(0, service.staggerRemainingMillis(ENTITY));
    }

    @Test
    public void staggerRejectsDurationAndSpeedupOutsideContract() {
        // 伤害附属只能给出 [0, 60000] 毫秒和只减速的倍率。
        assertThrows(IllegalArgumentException.class, () -> new TechniqueService.Stagger(-1, STAGGER_MOVEMENT));
        assertThrows(IllegalArgumentException.class,
                () -> new TechniqueService.Stagger(OVER_LIMIT_MILLIS, STAGGER_MOVEMENT));
        assertThrows(IllegalArgumentException.class, () -> new TechniqueService.Stagger(LONG_STAGGER, 0));
        assertThrows(IllegalArgumentException.class, () -> new TechniqueService.Stagger(LONG_STAGGER, SPEEDUP));
    }

    private static MemoryHost learnedHost() {
        // 每项测试独立一具已学会测试功法的身体。
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition())));
    }
}
