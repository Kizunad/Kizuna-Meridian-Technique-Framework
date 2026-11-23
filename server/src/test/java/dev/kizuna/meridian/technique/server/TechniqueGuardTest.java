package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.GuardTechniqueFixtures.*;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static org.junit.Assert.*;

public final class TechniqueGuardTest {
    @Test
    public void innateIsReadOnlyAndDoesNotUnlockOrdinaryTechniques() {
        // 空进度只投影天生式，普通式仍须学习。
        MemoryHost host = host();
        TechniqueRegistry registry = load(catalog(guard()).toBuilder().addDefinitions(definition()).build());
        TechniqueService service = HeldTechniqueFixtures.service(host, registry);
        var before = host.current();
        assertEquals(List.of(TechniqueRegistry.reference(guard())), service.known(ENTITY).stream()
                .map(TechniqueService.KnownEntry::technique).toList());
        service.learn(ENTITY, TechniqueRegistry.reference(guard()));
        assertEquals(before, host.current());
        assertEquals(TechniqueFailure.NOT_KNOWN,
                service.cast(ENTITY, request(host.current(), "ordinary")).getFailure());
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, press(host, "innate")).getPhase());
        assertEquals(0, TechniqueState.read(host.current()).getKnownCount());
    }

    @Test
    public void holdsBeyondOneDayThenKeepsTheWholeRecovery() {
        // 持有不设假结束；收势最后一毫秒仍不能被下一式顶掉。
        MemoryHost host = host();
        TechniqueService service = service(host);
        CastRequest press = press(host, "long_hold");
        service.cast(ENTITY, press);
        assertTrue(service.activeGuard(ENTITY).isEmpty());
        host.nowMillis = WARMUP;
        service.tick();
        assertTrue(service.activeGuard(ENTITY).isPresent());
        host.nowMillis = LONG_HOLD;
        service.tick();
        assertTrue(service.activeGuard(ENTITY).isPresent());
        assertFalse(progress(host).getCooldownsMap().containsKey(ID));
        assertTrue(TechniqueCastTimelines.status(service.registry(), host.current(), LONG_HOLD).getEndTimesUnknown());
        assertEquals(CastPhase.RECOVERING, service.endActive(ENTITY, release(press)).getPhase());
        assertTrue(service.activeGuard(ENTITY).isEmpty());
        host.nowMillis = LONG_HOLD + RECOVERY - 1;
        service.tick();
        CastRequest other = request(host.current(), "other");
        assertEquals(TechniqueFailure.ALREADY_CASTING, service.cast(ENTITY, other).getFailure());
        host.nowMillis++;
        service.tick();
        assertFalse(progress(host).hasActiveCast());
        assertTrue(host.current().actions().isEmpty());
        assertEquals(LONG_HOLD + RECOVERY + COOLDOWN, progress(host).getCooldownsOrThrow(ID));
        assertEquals(1, unlocks(host));
    }

    @Test
    public void shortPressHasNoGuardWindowAndDuplicateDoesNotMoveTheBoundary() {
        // 时刻零的松开也有 presence，短按只通知起手与收势。
        MemoryHost host = host();
        TechniqueService service = service(host);
        var states = new ArrayList<TechniqueService.CastStateChanged>();
        service.addCastStateListener(states::add);
        CastRequest press = press(host, "short_press");
        service.cast(ENTITY, press);
        assertFalse(service.endActive(ENTITY, release(press)).getReplayed());
        ActiveCast released = progress(host).getActiveCast();
        assertTrue(released.getSustained().hasEndRequestedMillis());
        assertEquals(0, released.getSustained().getEndRequestedMillis());
        host.nowMillis = WARMUP;
        service.tick();
        assertTrue(service.endActive(ENTITY, release(press)).getReplayed());
        assertEquals(released, progress(host).getActiveCast());
        assertTrue(service.activeGuard(ENTITY).isEmpty());
        assertEquals(List.of(CastPhase.WARMUP, CastPhase.WARMUP, CastPhase.RECOVERING),
                states.stream().map(TechniqueService.CastStateChanged::phase).toList());
        host.nowMillis = WARMUP + RECOVERY;
        service.tick();
        assertEquals(CastPhase.IDLE, service.endActive(ENTITY, release(press)).getPhase());
    }

    @Test
    public void releaseChecksIdentityAndDoesNotApplyToFiniteCasts() {
        // 旧身体或旧按压不能结束当前式，有限式也不接受持续收势。
        MemoryHost host = host();
        TechniqueService service = service(host);
        CastRequest press = press(host, "identity");
        service.cast(ENTITY, press);
        EndActiveRequest end = release(press);
        assertEquals(TechniqueFailure.BODY_UNAVAILABLE,
                service.endActive(ENTITY, end.toBuilder().setBodyId("other-body").build()).getFailure());
        assertEquals(TechniqueFailure.CAST_ID_CONFLICT,
                service.endActive(ENTITY, end.toBuilder().setCastId("old_press").build()).getFailure());
        assertFalse(progress(host).getActiveCast().getSustained().hasEndRequestedMillis());
        service.cancelOnUnload(ENTITY);
        CastRequest ordinary = request(host.current(), "finite");
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, ordinary).getPhase());
        assertEquals(TechniqueFailure.INVALID_REQUEST, service.endActive(ENTITY, release(ordinary)).getFailure());
    }

    @Test
    public void callbackReleaseIsAcceptedOnceAndNeverSubmitsReentrantly() {
        // 回调只调用一次；会话在宿主回执完成后自动提交收势。
        MemoryHost host = host();
        TechniqueService service = service(host);
        CastRequest press = press(host, "callback");
        service.cast(ENTITY, press);
        AtomicBoolean called = new AtomicBoolean();
        host.effectObserver = effect -> {
            if (effect.effect().hasPresentation() && called.compareAndSet(false /* expected */, true /* newValue */)) {
                int before = host.activitySubmissions;
                assertEquals(TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                        service.endActive(ENTITY, release(press)).getFailure());
                assertEquals(before, host.activitySubmissions);
                assertTrue(service.activeGuard(ENTITY).isEmpty());
            }
        };
        host.nowMillis = WARMUP;
        service.tick();
        assertTrue(called.get());
        assertEquals(WARMUP, progress(host).getActiveCast().getSustained().getEndRequestedMillis());
        assertEquals(CastPhase.RECOVERING, TechniqueScheduler.phase(progress(host).getActiveCast(), WARMUP));
    }

    @Test
    public void bodyClockConsumesAlreadyAcceptedReleaseBeforeChargingAgain() {
        // 首次提交冲突后，身体推进可提交同一回执；库下次推进只确认原边界。
        MemoryHost host = host();
        var configured = guard().toBuilder();
        var rules = GuardStamina.newBuilder()
                .setJoulesPerSecond(1)
                .setJoulesPerDamagePrevented(1)
                .setBreakMillis(RECOVERY)
                .setRestartRatio(1)
                .build();
        configured.getEffectsBuilder(0).getGuardBuilder().setStamina(rules);
        var service = HeldTechniqueFixtures.service(host, load(catalog(configured.build())));
        var cast = press(host, "paid_conflict");
        service.cast(ENTITY, cast);
        host.nowMillis = RELEASE_AT;
        service.tick();
        var body = host.current();
        var hold = TechniqueGuardStamina.hold(body, TechniqueState.read(body), List.of());
        host.submit(new TechniqueHost.Submission("guard_paid", body.revision(), hold));
        host.conflictNextActivity();
        service.endActive(ENTITY, release(cast));
        var release = service.pendingGuardRelease(ENTITY, host.current()).orElseThrow();
        host.submit(release);
        assertTrue(service.pendingGuardRelease(ENTITY, host.current()).isEmpty());
        host.nowMillis++;
        service.tick();
        var ended = progress(host).getActiveCast().getSustained();
        assertEquals(RELEASE_AT, ended.getEndRequestedMillis());
        assertEquals(RELEASE_AT, ended.getStaminaPaidMillis());
        assertEquals(1, host.current().actions().size());
    }

    @Test
    public void conflictingReleaseRetainsFirstTimeAndRetriesWithoutTheCaller() {
        // 瞬时冲突返回接收回执，下一 tick 自动重试且不延长收势。
        MemoryHost host = host();
        TechniqueService service = service(host);
        CastRequest press = press(host, "conflict");
        service.cast(ENTITY, press);
        host.nowMillis = RELEASE_AT;
        service.tick();
        host.conflictNextActivity();
        assertEquals(TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                service.endActive(ENTITY, release(press)).getFailure());
        assertTrue(service.activeGuard(ENTITY).isEmpty());
        host.nowMillis++;
        service.tick();
        assertEquals(RELEASE_AT, progress(host).getActiveCast().getSustained().getEndRequestedMillis());
        assertEquals(RELEASE_AT + RECOVERY, progress(host).getActiveCast().getRecoveryEndMillis());
    }

    @Test
    public void hitKeepsGuardAndHardControlsCleanItImmediately() {
        // 普通受击守不断；三种硬控在起手、生效、收势各阶段都撤防清理。
        for (TechniqueService.StaggerKind kind : List.of(TechniqueService.StaggerKind.CHARM,
                TechniqueService.StaggerKind.HOLD, TechniqueService.StaggerKind.DOWN)) {
            for (long at : List.of(0L, WARMUP, RELEASE_AT)) {
                MemoryHost host = host();
                TechniqueService service = service(host);
                CastRequest press = press(host, "control");
                service.cast(ENTITY, press);
                host.nowMillis = at;
                service.tick();
                if (at == WARMUP) {
                    service.stagger(ENTITY, HIT_STAGGER);
                    assertTrue(service.activeGuard(ENTITY).isPresent());
                }
                if (at == RELEASE_AT) {
                    service.endActive(ENTITY, release(press));
                }
                service.stagger(ENTITY, hardStagger(kind));
                assertTrue(service.activeGuard(ENTITY).isEmpty());
                assertFalse(progress(host).hasActiveCast());
                assertTrue(host.current().actions().isEmpty());
                assertEquals(1, unlocks(host));
            }
        }
    }

    @Test
    public void deathAndReloadNeverResumeHeldGuard() {
        // 死亡立即解锁；重载只取消保存的持续式，即使目录不存在。
        MemoryHost host = host();
        TechniqueService service = service(host);
        service.cast(ENTITY, press(host, "death"));
        host.nowMillis = WARMUP;
        service.tick();
        host.die();
        service.cancelOnDeath(ENTITY);
        assertTrue(service.activeGuard(ENTITY).isEmpty());
        assertEquals(1, unlocks(host));
        MemoryHost restored = host.restore();
        TechniqueService recovery = HeldTechniqueFixtures.service(restored, registry(definition()));
        recovery.recoverOnLoad(ENTITY, restored);
        recovery.recoverOnLoad(ENTITY, restored);
        assertFalse(progress(restored).hasActiveCast());
        assertTrue(restored.current().actions().isEmpty());
    }

    @Test
    public void rejectsUnsupportedGuardDefinitions() {
        // 内容错误在目录加载时拒绝，不留到世界效果派发时才失败。
        TechniqueDefinition guard = guard();
        CastPhases invalidPhases = guard.getPhases().toBuilder().setActiveMillis(0).build();
        TechniqueDefinition invalid = guard.toBuilder().setPhases(invalidPhases).build();
        assertThrows(IllegalArgumentException.class, () -> load(catalog(invalid)));
        TechniqueEffect unsupported = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setWork(WorkCost.getDefaultInstance())
                .build();
        assertThrows(IllegalArgumentException.class, () -> load(catalog(guard.toBuilder()
                .addEffects(unsupported).build())));
        assertFalse(TechniqueSelector.isOrdinarySupport(guard));
    }

    @Test
    public void registryLoadsVolumeWithInnateMembers() {
        // 核对正式注册表保留卷成员的精确版本及其天生属性。
        var catalog = HeldTechniqueFixtures.groupedCatalog(guard());
        var registry = load(catalog);
        var volume = registry.findVolume(HeldTechniqueFixtures.UPPER).orElseThrow();
        assertEquals(catalog.getVolumes(0), volume);
        for (var reference : volume.getTechniquesList()) {
            assertTrue(registry.find(reference).orElseThrow().getInnate());
        }
    }

    private static TechniqueService service(MemoryHost host) {
        // 普通式已学只用于验证占用，守式仍由 innate 合并提供。
        TechniqueRegistry registry = load(catalog(guard()).toBuilder().addDefinitions(definition()).build());
        TechniqueService service = HeldTechniqueFixtures.service(host, registry);
        service.learn(ENTITY, TechniqueRegistry.reference(definition()));
        return service;
    }

    @Test
    public void guardNeverEntersStrikeOrOrdinarySupportSelection() {
        // 给出完整可施放条件和正权重，仍不得被普通打击池选中。
        TechniqueDefinition guard = guard();
        TechniqueRegistry registry = registry(guard);
        TechniqueReference reference = TechniqueRegistry.reference(guard);
        BindingOutcome binding = TechniqueBinder.bind(host().current(), guard);
        var availability = new TechniqueSelector.Availability(Map.of(reference, binding), Set.of(reference), Map.of());
        var situation = new TechniqueSelector.Situation(0, 1, 1, 1, TechniqueSelector.TargetState.ENGAGED);
        var context = new TechniqueSelector.Context(registry, situation, availability);
        assertTrue(TechniqueSelector.castable(context, Fixtures.progress(guard).getKnown(0), guard));
        assertTrue(TechniqueSelector.choose(context, Fixtures.progress(guard).getKnownList(), new Random(0)).isEmpty());
        assertFalse(TechniqueSelector.isOrdinarySupport(guard));
    }

    @Test
    public void zeroRecoveryReleasesTheLockEvenAfterTheCurrentTickWasDelivered() {
        // 收势为零且同毫秒松开不能漏发解锁，也不伪造收势阶段。
        MemoryHost host = host();
        TechniqueDefinition guard = guard();
        CastPhases phases = guard.getPhases().toBuilder().setRecoveryMillis(0).build();
        TechniqueDefinition definition = guard.toBuilder().setPhases(phases).build();
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        var states = new ArrayList<CastPhase>();
        service.addCastStateListener(event -> states.add(event.phase()));
        CastRequest press = press(host, "instant_end");
        service.cast(ENTITY, press);
        host.nowMillis = WARMUP;
        service.tick();
        assertEquals(CastPhase.FINISHED, service.endActive(ENTITY, release(press)).getPhase());
        assertFalse(states.contains(CastPhase.RECOVERING));
        assertFalse(progress(host).hasActiveCast());
        assertEquals(1, unlocks(host));
    }

    @Test
    public void zeroWarmupDoesNotInventWarmupAndReleaseIgnoresChangedAdmission() {
        // 零起手直接守；宿主后来拒绝起式，也不能阻止已经开始的守式收势。
        MemoryHost host = host();
        TechniqueDefinition guard = guard();
        CastPhases phases = guard.getPhases().toBuilder().setWarmupMillis(0).build();
        List<TechniqueEffect> effects = guard.getEffectsList().stream()
                .map(effect -> effect.toBuilder().setAtMillis(0).build()).toList();
        TechniqueDefinition definition = guard.toBuilder()
                .setPhases(phases)
                .clearEffects()
                .addAllEffects(effects)
                .setMovementSpeedMultiplier(1)
                .build();
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        var phasesSeen = new ArrayList<CastPhase>();
        service.addCastStateListener(event -> phasesSeen.add(event.phase()));
        CastRequest press = press(host, "instant_guard");
        assertEquals(CastPhase.EFFECTIVE, service.cast(ENTITY, press).getPhase());
        assertTrue(service.activeGuard(ENTITY).isPresent());
        service.addCastGuard((entity, request) -> false);
        assertEquals(CastPhase.RECOVERING, service.endActive(ENTITY, release(press)).getPhase());
        assertEquals(List.of(CastPhase.EFFECTIVE, CastPhase.RECOVERING), phasesSeen);
        host.nowMillis = RECOVERY;
        service.tick();
        assertEquals(1, unlocks(host));
    }

    @Test
    public void hardControlWinsOverReleaseQueuedInTheSameCallback() {
        // 同一次世界回调先请求收势再硬控，安全点只能进入中断，不回到普通收势。
        MemoryHost host = host();
        TechniqueService service = service(host);
        CastRequest press = press(host, "queued_control");
        service.cast(ENTITY, press);
        AtomicBoolean called = new AtomicBoolean();
        host.effectObserver = effect -> {
            if (effect.effect().hasPresentation() && called.compareAndSet(false /* expected */, true /* newValue */)) {
                service.endActive(ENTITY, release(press));
                service.stagger(ENTITY, hardStagger(TechniqueService.StaggerKind.HOLD));
                assertTrue(service.activeGuard(ENTITY).isEmpty());
            }
        };
        host.nowMillis = WARMUP;
        service.tick();
        assertTrue(called.get());
        assertFalse(progress(host).hasActiveCast());
        assertEquals(CastPhase.INTERRUPTED, progress(host).getLastCast().getEnding());
        assertEquals(1, unlocks(host));
    }

    @Test
    public void refusedCleanupRetriesWithoutKeepingGuardOrDuplicatingEnd() {
        // 停止表现被拒时仍解锁、仍撤防，下一 tick 只补交未完成清理。
        MemoryHost host = host();
        TechniqueService service = service(host);
        var ended = new ArrayList<TechniqueCastEnded>();
        service.addCastEndListener(ended::add);
        service.cast(ENTITY, press(host, "cleanup_retry"));
        host.nowMillis = WARMUP;
        service.tick();
        host.rejectNextEffect();
        service.cancelOnUnload(ENTITY);
        assertTrue(service.activeGuard(ENTITY).isEmpty());
        assertEquals(1, ended.size());
        service.tick();
        service.cancelOnUnload(ENTITY);
        assertEquals(1, ended.size());
        assertEquals(1, unlocks(host));
        assertFalse(progress(host).hasActiveCast());
    }

    @Test
    public void unloadDisconnectShutdownAndLiveRestoreCleanHeldState() {
        // 活体重载、离线与停服都不恢复按住事实；重复清理也只有一份终态通知。
        for (String lifecycle : List.of("unload", "disconnect", "shutdown", "restore")) {
            MemoryHost host = host();
            TechniqueService service = service(host);
            var ended = new ArrayList<TechniqueCastEnded>();
            service.addCastEndListener(ended::add);
            service.cast(ENTITY, press(host, lifecycle));
            host.nowMillis = WARMUP;
            service.tick();
            switch (lifecycle) {
                case "unload" -> service.cancelOnUnload(ENTITY);
                case "disconnect" -> service.cancelOnDisconnect(ENTITY);
                case "shutdown" -> service.shutdown();
                case "restore" -> service.recoverOnLoad(ENTITY, host);
                default -> throw new AssertionError(lifecycle);
            }
            service.cancelOnUnload(ENTITY);
            assertFalse(progress(host).hasActiveCast());
            assertTrue(host.current().actions().isEmpty());
            assertEquals(1, unlocks(host));
            assertEquals(1, ended.size());
        }
    }

    private static TechniqueProgress progress(MemoryHost host) {
        // 所有断言读取同一份 Framework 权威进度。
        return TechniqueState.read(host.current());
    }

    private static TechniqueRegistry load(TechniqueCatalog catalog) {
        // 多式目录走与正式装配相同的合并校验入口。
        return TechniqueRegistry.load(addons(), List.of(catalog));
    }

    private static TechniqueService.Stagger hardStagger(TechniqueService.StaggerKind kind) {
        // 被擒和魅惑使用定身倍率，倒地仍遵守原有减速约束。
        double movement = 0;
        if (kind == TechniqueService.StaggerKind.DOWN) {
            movement = STAGGER_MOVEMENT;
        }
        return new TechniqueService.Stagger(STAGGER_MILLIS, movement, kind);
    }

    private static long unlocks(MemoryHost host) {
        // 宿主对稳定效果身份去重，最终只产生一次解锁事实。
        return host.effects.stream().filter(effect -> effect.hasMovement()
                && effect.getMovement().getAction() == MovementAction.UNLOCK).count();
    }
}
