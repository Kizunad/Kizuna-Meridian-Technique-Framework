package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.AiHints;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BindingOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CombatAiHints;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUp;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUpRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LastCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrikeIntent;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.AFTER_WINDOW;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.CAST_END;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.CHAIN_HIT_COUNT;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.CHAIN_HIT_INTERVAL;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.CLIP_OFFSET;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.CONTEXT_WEIGHT;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.DURABILITY;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.FAR_DISTANCE;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.FOLLOW_MODIFIER;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.Hit;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.REAR_FACING;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.SECOND_STRIKE;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.SINGLE_TARGET_ANGLE;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.STRIKE_DISTANCE;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.SWEEP_GROUP;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.TARGET_LIMIT;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.WINDOW_START;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.blocks;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.chain;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.combatCatalog;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.combatHost;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.combatRegistry;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.first;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.next;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.nextRequest;
import static dev.kizuna.meridian.technique.server.CombatTechniqueFixtures.strike;
import static dev.kizuna.meridian.technique.server.Fixtures.COOLDOWN;
import static dev.kizuna.meridian.technique.server.Fixtures.COST_QI;
import static dev.kizuna.meridian.technique.server.Fixtures.CURVE_END;
import static dev.kizuna.meridian.technique.server.Fixtures.HIT_STAGGER;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.TECHNIQUE_ID;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.catalog;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.SECOND;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.groupedCatalog;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.rules;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TechniqueStage6bTest {
    private static final int FRONT_TARGETS = 3;
    private static final int TOTAL_CURVES = 2;
    private static final int DISTINCT_TARGET_LIMIT = 2;
    private static final double QI_TOLERANCE = 1e-9;
    private static final long LAST_WINDOW = CAST_END + AFTER_WINDOW;
    private static final long NEXT_HIT = WINDOW_START + WARMUP - CLIP_OFFSET;
    private static final long NEXT_END = WINDOW_START + CAST_END - CLIP_OFFSET;

    @Test
    public void strikesAndDoorsKeepParametersScheduledTimeAndStableReceiptsAcrossLateTicks() {
        // 迟到 tick 不改变原效果时刻；字节宿主收到完整规则并按效果身份去重。
        MemoryHost host = combatHost();
        TechniqueService service = service(host, combatRegistry());
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "timing")).getPhase());
        host.nowMillis = WARMUP - 1;
        service.tick();
        assertTrue(strikes(host).isEmpty());
        host.nowMillis = WARMUP;
        service.tick();
        EffectIntent front = strikes(host).get(0);
        assertEquals(WARMUP, front.getScheduledMillis());
        assertEquals(strike(Hit.INTERMEDIATE), front.getWeaponStrike().getStrike());
        assertEquals(rules(), front.getWeaponStrike().getHeldItemRules());
        assertEquals(Hand.MAIN_HAND, front.getSelectedHand());
        host.nowMillis = SECOND_STRIKE + 1;
        service.tick();
        EffectIntent back = strikes(host).get(1);
        assertEquals(SECOND_STRIKE, back.getScheduledMillis());
        assertEquals(REAR_FACING, back.getWeaponStrike().getStrike().getFacingOffset().getValue(), 0);
        assertTrue(back.getWeaponStrike().getStrike().getLastStrike());
        EffectIntent doors = host.effects.stream().filter(EffectIntent::hasBreakBlocks).findFirst().orElseThrow();
        assertEquals(SECOND_STRIKE, doors.getScheduledMillis());
        assertEquals(blocks(), doors.getBreakBlocks().getBlocks());
        assertEquals(rules(), doors.getBreakBlocks().getHeldItemRules());
        int delivered = host.effects.size();
        service.tick();
        assertTrue(service.cast(ENTITY, request(host.current(), "timing")).getReplayed());
        assertEquals(delivered, host.effects.size());
        assertEquals(delivered, host.effectReceipts.size());
    }

    @Test
    public void sweepDeduplicatesWithinGroupAndSharesCastTargetBudgetAcrossSectors() {
        // 几何由宿主筛选，两半圈同组去重且共享五个不同目标的预算。
        var hit = new HashSet<String>();
        Map<String, Set<String>> hitsByGroup = Map.of(SWEEP_GROUP, hit);
        WeaponStrike frontStrike = strike(Hit.INTERMEDIATE);
        WeaponStrike rearStrike = strike(Hit.LAST);
        List<String> front = TechniqueStrikeTargets.select(frontStrike, hitsByGroup,
                List.of("a", "a", "b", "c"));
        assertEquals(FRONT_TARGETS, front.size());
        hit.addAll(front);
        List<String> rear = TechniqueStrikeTargets.select(rearStrike, hitsByGroup,
                List.of("a", "d", "d", "e", "f"));
        assertEquals(List.of("d", "e"), rear);
        hit.addAll(rear);
        assertEquals(TARGET_LIMIT, hit.size());
        assertTrue(TechniqueStrikeTargets.select(rearStrike, hitsByGroup, List.of("g")).isEmpty());
        assertEquals(Set.of("a", "b", "c", "d", "e"), hit);
        WeaponStrike single = rearStrike.toBuilder()
                .setShape(StrikeShape.SINGLE_TARGET)
                .setAngle(quantity(SINGLE_TARGET_ANGLE, Unit.DEGREE))
                .setMaximumTargets(1)
                .build();
        assertEquals(List.of("near"), TechniqueStrikeTargets.select(single, Map.of(), List.of("near", "far")));
    }

    @Test
    public void chainStrikesInDifferentGroupsEachHitTheSameTargetAfterTimedDispatch() {
        // 服务按时交付三个去重组，同一目标每组各中一刀，组内重试仍不重复。
        TechniqueDefinition definition = chain();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(groupedCatalog(definition)));
        MemoryHost host = combatHost();
        TechniqueService service = service(host, registry);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "chain")).getPhase());
        var hitsByGroup = new HashMap<String, Set<String>>();
        for (int index = 0; index < CHAIN_HIT_COUNT; index++) {
            long scheduledMillis = WARMUP + index * CHAIN_HIT_INTERVAL;
            host.nowMillis = scheduledMillis;
            service.tick();
            List<EffectIntent> delivered = strikes(host);
            assertEquals(index + 1, delivered.size());
            EffectIntent intent = delivered.get(index);
            assertEquals(scheduledMillis, intent.getScheduledMillis());
            WeaponStrike strike = intent.getWeaponStrike().getStrike();
            String group = strike.getDedupGroup();
            assertFalse(hitsByGroup.containsKey(group));
            List<String> selected = TechniqueStrikeTargets.select(strike, hitsByGroup, List.of("same", "other"));
            assertEquals(List.of("same"), selected);
            hitsByGroup.put(group, new HashSet<>(selected));
            assertTrue(TechniqueStrikeTargets.select(strike, hitsByGroup, List.of("same")).isEmpty());
        }
        assertEquals(CHAIN_HIT_COUNT, hitsByGroup.size());
        assertTrue(hitsByGroup.values().stream().allMatch(hits -> hits.equals(Set.of("same"))));
    }

    @Test
    public void differentGroupsShareDistinctTargetBudgetWithoutCountingRepeatHitsTwice() {
        // 不同组仍共用整式不同目标上限，满额后允许跨组重击已有目标并跳过新目标。
        Map<String, Set<String>> hitsByGroup = Map.of("first", Set.of("a"), "second", Set.of("a", "b"));
        WeaponStrike strike = strike(Hit.LAST).toBuilder()
                .setDedupGroup("third")
                .setMaximumTargets(DISTINCT_TARGET_LIMIT)
                .setCastTargetLimit(DISTINCT_TARGET_LIMIT)
                .build();
        assertEquals(List.of("a", "b"),
                TechniqueStrikeTargets.select(strike, hitsByGroup, List.of("new", "a", "a", "b")));
        assertTrue(TechniqueStrikeTargets.select(strike, hitsByGroup, List.of("new")).isEmpty());
        assertEquals(List.of("new"), TechniqueStrikeTargets.select(strike, Map.of(), List.of("new")));
    }

    @Test
    public void missingOrMalformedDedupGroupsAreRejectedAtCatalogAndEffectBoundaries() {
        // 去重组不可缺失，也不能靠空白、非法或超长标识绕过去重。
        TechniqueDefinition definition = chain();
        TechniqueEffect original = definition.getEffects(0);
        WeaponStrike originalStrike = original.getWeaponStrike();
        List<String> invalid = List.of("", " ", "bad group", "sample:group", "UPPER",
                "g".repeat(TechniqueValues.MAX_TEXT + 1));
        for (String group : invalid) {
            WeaponStrike strike = originalStrike.toBuilder().setDedupGroup(group).build();
            TechniqueEffect effect = original.toBuilder().setWeaponStrike(strike).build();
            TechniqueDefinition broken = definition.toBuilder().setEffects(0, effect).build();
            TechniqueCatalog catalog = groupedCatalog(broken);
            assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(catalog)));
            WeaponStrikeIntent weaponStrike = WeaponStrikeIntent.newBuilder()
                    .setStrike(strike)
                    .setHeldItemRules(rules())
                    .build();
            EffectIntent intent = EffectIntent.newBuilder()
                    .setCastId("invalid_group")
                    .setSelectedHand(Hand.MAIN_HAND)
                    .setWeaponStrike(weaponStrike)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(intent));
        }
    }

    @Test
    public void followUpAtomicallyReplacesActionKeepsCurvesAndNormalCooldownAndSkipsPreparation() {
        // 承接只缩短后式起手，不提前前式冷却、不取消曲线、不发送预备冲量。
        MemoryHost host = combatHost();
        TechniqueService service = learnedService(host);
        service.cast(ENTITY, request(host.current(), "before"));
        String oldAction = TechniqueState.read(host.current()).getActiveCast().getActionInstanceId();
        host.nowMillis = WINDOW_START;
        CastOutcome outcome = service.cast(ENTITY, nextRequest(host, "after"));
        assertEquals(CastPhase.WARMUP, outcome.getPhase());
        TechniqueProgress progress = TechniqueState.read(host.current());
        ActiveCast active = progress.getActiveCast();
        assertEquals(CLIP_OFFSET, active.getClipStartMillis());
        assertEquals(NEXT_HIT, active.getWarmupEndMillis());
        assertEquals(NEXT_END, active.getRecoveryEndMillis());
        assertEquals(CAST_END + COOLDOWN, progress.getCooldownsOrThrow(TECHNIQUE_ID));
        assertEquals(NEXT_END + COOLDOWN, progress.getCooldownsOrThrow(SECOND));
        assertEquals("before", progress.getLastCast().getCastId());
        assertEquals(1, host.current().actions().size());
        assertFalse(host.current().actions().stream().anyMatch(action -> action.instanceId().equals(oldAction)));
        assertEquals(TOTAL_CURVES, host.current().modifiers().size());
        assertEquals(CURVE_END, progress.getCurveEndsOrThrow(TECHNIQUE_ID));
        assertEquals(WINDOW_START + FOLLOW_MODIFIER - CLIP_OFFSET + CURVE_END,
                progress.getCurveEndsOrThrow(SECOND));
        assertEquals(COST_QI + COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
        assertFollowUpEffects(host, service);
    }

    private static void assertFollowUpEffects(MemoryHost host, TechniqueService service) {
        // 先清理前式再锁后式，后式剪辑从偏移起播，原声明索引继续标识各次打击。
        List<EffectIntent> cleanup = host.effects.stream()
                .filter(effect -> effect.getCastId().equals("before"))
                .filter(effect -> effect.hasMovement() || effect.hasPresentation()).toList();
        assertEquals(MovementAction.UNLOCK, cleanup.get(cleanup.size() - 1).getMovement().getAction());
        List<EffectIntent> after = host.effects.stream()
                .filter(effect -> effect.getCastId().equals("after")).toList();
        assertEquals(MovementAction.LOCK, after.get(0).getMovement().getAction());
        assertEquals(CLIP_OFFSET, after.get(1).getPresentation().getStartMillis());
        host.nowMillis = NEXT_HIT - 1;
        service.tick();
        assertTrue(strikes(host).stream().noneMatch(effect -> effect.getCastId().equals("after")));
        host.nowMillis = NEXT_HIT;
        service.tick();
        EffectIntent hit = strikes(host).stream()
                .filter(effect -> effect.getCastId().equals("after")).findFirst().orElseThrow();
        assertEquals(NEXT_HIT, hit.getScheduledMillis());
        assertEquals("effect_3", hit.getEffectId());
        assertTrue(host.effects.stream()
                .noneMatch(effect -> effect.getCastId().equals("after") && effect.hasImpulse()));
    }

    @Test
    public void followUpWindowIncludesBothEdgesAndRejectsOutsideWithoutPayment() {
        // 收势后半段首毫秒到结束后五百毫秒均可承接，窗口之外必须拒绝。
        for (long now : new long[] {WINDOW_START - 1, WINDOW_START, CAST_END, LAST_WINDOW, LAST_WINDOW + 1}) {
            MemoryHost host = combatHost();
            TechniqueService service = learnedService(host);
            service.cast(ENTITY, request(host.current(), "edge_first"));
            host.nowMillis = now;
            CastOutcome outcome = service.cast(ENTITY, nextRequest(host, "edge_next"));
            if (now < WINDOW_START || now > LAST_WINDOW) {
                assertEquals(TechniqueFailure.FOLLOW_UP_UNAVAILABLE, outcome.getFailure());
                assertEquals(COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
            } else {
                assertEquals(CastPhase.WARMUP, outcome.getPhase());
                assertEquals(CLIP_OFFSET, TechniqueState.read(host.current()).getActiveCast().getClipStartMillis());
            }
        }
    }

    @Test
    public void onlyFollowUpRejectsStandaloneAndOrdinaryTechniqueCanStartOutsideWindow() {
        // 玩家可理解的原因区分只能承接与错过时机；普通式仍允许完整起手。
        MemoryHost host = combatHost();
        TechniqueService service = learnedService(host);
        CastRequest standalone = nextRequest(host, "standalone").toBuilder().setEntry(CastEntry.AUTO).build();
        CastOutcome denied = service.cast(ENTITY, standalone);
        assertEquals(TechniqueFailure.FOLLOW_UP_REQUIRED, denied.getFailure());
        assertEquals("这一式须借前一式的余势施展", denied.getReason());
        TechniqueDefinition followUpOnly = next();
        FollowUpRules ordinaryRules = followUpOnly.getFollowUp().toBuilder()
                .setOnlyFollowUp(false /* onlyFollowUp */)
                .build();
        TechniqueDefinition ordinary = followUpOnly.toBuilder()
                .setFollowUp(ordinaryRules)
                .build();
        TechniqueCatalog catalog = combatCatalog().toBuilder().setDefinitions(1, ordinary).build();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(catalog));
        TechniqueService ordinaryService = service(host, registry);
        assertEquals(CastPhase.WARMUP, ordinaryService.cast(ENTITY, standalone).getPhase());
        assertEquals(0, TechniqueState.read(host.current()).getActiveCast().getClipStartMillis());
    }

    @Test
    public void rejectedFollowUpPreservesPreviousActionCurvesAndPaymentAndCanRetry() {
        // Framework 拒绝时前式动作和资源保持完整，不先做独立取消。
        MemoryHost host = combatHost();
        TechniqueService service = learnedService(host);
        service.cast(ENTITY, request(host.current(), "atomic_first"));
        host.nowMillis = WINDOW_START;
        host.inspect();
        BodySnapshot before = host.current();
        host.rejectNextActivity();
        CastRequest next = nextRequest(host, "atomic_next");
        assertEquals(TechniqueFailure.FRAMEWORK_REJECTED, service.cast(ENTITY, next).getFailure());
        assertEquals(before, host.current());
        assertEquals(COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, next).getPhase());
    }

    @Test
    public void unconfirmedPredecessorEffectsBlockFollowUpUntilAcknowledged() {
        // 旧效果暂时拒绝时不扣后式费用，原批次确认后才能替换动作。
        MemoryHost host = combatHost();
        TechniqueService service = learnedService(host);
        service.cast(ENTITY, request(host.current(), "pending_first"));
        host.nowMillis = WINDOW_START;
        host.rejectNextEffect();
        CastRequest next = nextRequest(host, "pending_next");
        assertEquals(TechniqueFailure.FRAMEWORK_REJECTED, service.cast(ENTITY, next).getFailure());
        assertEquals("pending_first", TechniqueState.read(host.current()).getActiveCast().getRequest().getCastId());
        assertEquals(COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, next).getPhase());
        assertEquals(COST_QI + COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
        assertEquals(TOTAL_CURVES, host.current().modifiers().size());
    }

    @Test
    public void followUpInterruptUsesShortenedWarmupBoundaryAndDoesNotEmitStrikesEarly() {
        // 缩短后的起手仍可被打断，恰好到首击时刻则进入生效段。
        for (long now : new long[] {NEXT_HIT - 1, NEXT_HIT}) {
            MemoryHost host = combatHost();
            TechniqueService service = learnedService(host);
            service.cast(ENTITY, request(host.current(), "interrupt_first"));
            host.nowMillis = WINDOW_START;
            service.cast(ENTITY, nextRequest(host, "interrupt_next"));
            host.nowMillis = now;
            CastOutcome outcome = service.stagger(ENTITY, HIT_STAGGER).orElseThrow();
            service.tick();
            if (now < NEXT_HIT) {
                assertEquals(CastPhase.INTERRUPTED, outcome.getPhase());
                assertTrue(strikes(host).stream().noneMatch(effect -> effect.getCastId().equals("interrupt_next")));
            } else {
                assertEquals(CastPhase.EFFECTIVE, outcome.getPhase());
                assertEquals(1, strikes(host).stream()
                        .filter(effect -> effect.getCastId().equals("interrupt_next")).count());
            }
            assertEquals(COST_QI + COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
            assertEquals(TOTAL_CURVES, host.current().modifiers().size());
        }
    }

    @Test
    public void finishedHistorySurvivesSnapshotAndCancelledHistoryCannotOpenWindow() {
        // 正常收势记录可跨存档承接，取消和打断记录不能借历史制造新机会。
        MemoryHost host = combatHost();
        TechniqueService service = learnedService(host);
        service.cast(ENTITY, request(host.current(), "history"));
        host.nowMillis = CAST_END;
        service.tick();
        assertEquals(CastPhase.FINISHED, TechniqueState.read(host.current()).getLastCast().getEnding());
        MemoryHost restored = host.restore();
        TechniqueService afterLoad = service(restored, combatRegistry());
        assertEquals(CastPhase.WARMUP, afterLoad.cast(ENTITY, nextRequest(restored, "history_next")).getPhase());
        MemoryHost cancelled = combatHost();
        TechniqueService cancelledService = learnedService(cancelled);
        cancelledService.cast(ENTITY, request(cancelled.current(), "cancelled"));
        cancelled.nowMillis = WINDOW_START;
        cancelledService.cancelOnUnload(ENTITY);
        assertEquals(CastPhase.CANCELLED, TechniqueState.read(cancelled.current()).getLastCast().getEnding());
        assertEquals(TechniqueFailure.FOLLOW_UP_UNAVAILABLE,
                cancelledService.cast(ENTITY, nextRequest(cancelled, "cancelled_next")).getFailure());
    }

    @Test
    public void replayOfEitherCastDoesNotRestoreOldSessionOrDuplicateEffectsAndFees() {
        // 旧起手回执可重放，但服务游标必须继续属于承接后的新施放。
        MemoryHost host = combatHost();
        TechniqueService service = learnedService(host);
        CastRequest previous = request(host.current(), "replay_first");
        service.cast(ENTITY, previous);
        host.nowMillis = WINDOW_START;
        CastRequest next = nextRequest(host, "replay_next");
        service.cast(ENTITY, next);
        assertTrue(service.cast(ENTITY, next).getReplayed());
        assertTrue(service.cast(ENTITY, previous).getReplayed());
        host.nowMillis = NEXT_HIT;
        service.tick();
        service.tick();
        assertEquals(1, strikes(host).stream().filter(effect -> effect.getCastId().equals("replay_next")).count());
        assertEquals(COST_QI + COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
    }

    @Test
    public void recoveryCleansBothSidesOfCommittedFollowUpWithoutReplayingStrikes() {
        // 起手提交后崩溃仍能停止前后两个剪辑并解除约束，不补发攻击或劈门。
        MemoryHost host = combatHost();
        TechniqueService service = learnedService(host);
        service.cast(ENTITY, request(host.current(), "recover_first"));
        host.nowMillis = WINDOW_START;
        var casting = new TechniqueCasting(combatRegistry());
        assertEquals(CastPhase.WARMUP, casting.cast(host, nextRequest(host, "recover_next")).getPhase());
        MemoryHost restored = host.restore();
        TechniqueService recovery = service(restored, combatRegistry());
        recovery.recoverOnLoad(ENTITY, restored);
        assertTrue(restored.effects.stream().noneMatch(effect -> effect.hasWeaponStrike() || effect.hasBreakBlocks()));
        Set<String> cleaned = new HashSet<>();
        for (EffectIntent effect : restored.effects) {
            cleaned.add(effect.getCastId());
        }
        assertEquals(Set.of("recover_first", "recover_next"), cleaned);
        assertFalse(TechniqueState.read(restored.current()).hasActiveCast());
        assertEquals(CastPhase.CANCELLED, TechniqueState.read(restored.current()).getLastCast().getEnding());
        int effects = restored.effects.size();
        recovery.recoverOnLoad(ENTITY, restored);
        assertEquals(effects, restored.effects.size());
    }

    @Test
    public void aiFactsMultiplyScoresAndLongWarmupIsExcludedWhenFarOrUnderAttack() {
        // 四种事实都是独立权重钩子；距离和挨打门槛在加权抽样前直接过滤。
        MemoryHost host = combatHost();
        TechniqueRegistry registry = combatRegistry();
        TechniqueProgress progress = TechniqueState.read(host.current());
        KnownTechnique known = progress.getKnown(0);
        double base = TechniqueSelector.score(aiContext(registry, progress, Set.of(), STRIKE_DISTANCE), known);
        assertTrue(base > 0);
        for (TechniqueSelector.CombatFact fact : List.of(TechniqueSelector.CombatFact.TARGET_SHIELDING,
                TechniqueSelector.CombatFact.SURROUNDED, TechniqueSelector.CombatFact.PURSUED)) {
            assertEquals(base * CONTEXT_WEIGHT,
                    TechniqueSelector.score(aiContext(registry, progress, Set.of(fact), STRIKE_DISTANCE), known), 0);
        }
        assertEquals(0, TechniqueSelector.score(aiContext(registry, progress,
                Set.of(TechniqueSelector.CombatFact.UNDER_ATTACK), STRIKE_DISTANCE), known), 0);
        assertEquals(0, TechniqueSelector.score(aiContext(registry, progress, Set.of(), FAR_DISTANCE), known), 0);
    }

    @Test
    public void aiOnlyConsidersFollowUpWithinWindowAndUsesShortenedWarmup() {
        // 只能承接的长式在窗口外不进候选池，承接缩短后可满足挨打时的起手门槛。
        MemoryHost host = combatHost();
        TechniqueRegistry registry = combatRegistry();
        TechniqueService service = learnedService(host);
        TechniqueProgress initial = TechniqueState.read(host.current());
        KnownTechnique next = initial.getKnownList().stream()
                .filter(known -> known.getTechnique().getId().equals(SECOND)).findFirst().orElseThrow();
        assertEquals(0, TechniqueSelector.score(aiContext(registry, initial, Set.of(), STRIKE_DISTANCE), next), 0);
        service.cast(ENTITY, request(host.current(), "ai"));
        host.nowMillis = WINDOW_START;
        host.inspect();
        TechniqueProgress progress = TechniqueState.read(host.current());
        var context = aiContext(registry, progress, Set.of(TechniqueSelector.CombatFact.UNDER_ATTACK), STRIKE_DISTANCE);
        assertTrue(TechniqueSelector.score(context, next) > 0);
        assertEquals(next.getTechnique(), TechniqueSelector.choose(context, progress.getKnownList(), new Random(1))
                .orElseThrow());
    }

    private static TechniqueSelector.Context aiContext(TechniqueRegistry registry, TechniqueProgress progress,
                                                       Set<TechniqueSelector.CombatFact> facts, double distance) {
        // 绑定和费用仍由权威身体查询提供，样例只关注情境、曲线与承接筛选。
        long now = 0;
        if (progress.hasActiveCast()) {
            now = WINDOW_START;
        }
        var situation = new TechniqueSelector.Situation(now, 1, 1, distance, TechniqueSelector.TargetState.ENGAGED);
        BindingOutcome binding = TechniqueBinder.bind(body(Shape.TENDRIL), first());
        TechniqueReference first = TechniqueRegistry.reference(first());
        TechniqueReference next = TechniqueRegistry.reference(next());
        var availability = new TechniqueSelector.Availability(Map.of(first, binding, next, binding),
                Set.of(first, next), Map.of());
        var combat = new TechniqueSelector.CombatContext(facts, progress);
        return new TechniqueSelector.Context(registry, situation, availability, combat);
    }

    @Test
    public void malformedCombatParametersSequencesReferencesAndProgressAreRejected() {
        // 单位、数量、末击、书归属及承接引用都在目录边界拒绝。
        List<WeaponStrike> invalid = List.of(strike(Hit.LAST).toBuilder().clearCanTwoHand().build(),
                strike(Hit.LAST).toBuilder().setCastTargetLimit(0).build(),
                strike(Hit.LAST).toBuilder().setFacingOffset(quantity(Double.NaN, Unit.DEGREE)).build(),
                strike(Hit.LAST).toBuilder().setShape(StrikeShape.SINGLE_TARGET).build(),
                strike(Hit.LAST).toBuilder().setInjuryOverrideValue(-1).build());
        for (WeaponStrike strike : invalid) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueCombatEffects.validateStrike(strike));
        }
        assertThrows(IllegalArgumentException.class, () -> TechniqueCombatEffects.validateBlocks(
                blocks().toBuilder().clearBlockTags().build()));
        TechniqueCatalog catalog = combatCatalog();
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueRegistry.load(addons(),
                        List.of(catalog.toBuilder().clearBooks().clearVolumes().build())));
        FollowUp broken = next().getFollowUp().getTransitions(0).toBuilder()
                .setStartMillis(WARMUP)
                .build();
        FollowUpRules brokenRules = next().getFollowUp().toBuilder().setTransitions(0, broken).build();
        TechniqueDefinition brokenDefinition = next().toBuilder().setFollowUp(brokenRules).build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(),
                List.of(catalog.toBuilder().setDefinitions(1, brokenDefinition).build())));
        LastCast invalidHistory = LastCast.newBuilder()
                .setTechnique(TechniqueRegistry.reference(first()))
                .setCastId("invalid")
                .setEnding(CastPhase.WARMUP)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueState.pack(TechniqueState.empty().toBuilder().setLastCast(invalidHistory).build()));
    }

    @Test
    public void inconsistentStrikeBudgetsDurabilityAndLastStrikeAreRejected() {
        // 错误末击、合计上限和耐久声明都不能进入可执行目录。
        TechniqueDefinition definition = first();
        int lastStrikeIndex = definition.getEffectsCount() - 2;
        TechniqueEffect original = definition.getEffects(lastStrikeIndex);
        WeaponStrike lastStrike = original.getWeaponStrike();
        List<WeaponStrike> invalid = List.of(
                lastStrike.toBuilder().setLastStrike(false /* lastStrike */).build(),
                lastStrike.toBuilder().setCastTargetLimit(TARGET_LIMIT + 1).build(),
                lastStrike.toBuilder().setDurabilityBase(DURABILITY + 1).build());
        for (WeaponStrike strike : invalid) {
            TechniqueEffect effect = original.toBuilder().setWeaponStrike(strike).build();
            TechniqueDefinition broken = definition.toBuilder().setEffects(lastStrikeIndex, effect).build();
            TechniqueCatalog catalog = combatCatalog().toBuilder().setDefinitions(0, broken).build();
            assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(catalog)));
        }
    }

    @Test
    public void unknownPredecessorsAndExplicitEmptyCombatWeightsAreRejected() {
        // 跨式引用必须存在，显式空权重不能冒充未声明而绕过单位校验。
        TechniqueDefinition firstDefinition = first();
        TechniqueDefinition nextDefinition = next();
        TechniqueReference previous = TechniqueRegistry.reference(firstDefinition);
        TechniqueReference unknown = previous.toBuilder().setVersion(previous.getVersion() + 1).build();
        FollowUpRules originalRules = nextDefinition.getFollowUp();
        FollowUp transition = originalRules.getTransitions(0).toBuilder().setPrevious(unknown).build();
        FollowUpRules rules = originalRules.toBuilder().setTransitions(0, transition).build();
        TechniqueDefinition brokenReference = nextDefinition.toBuilder().setFollowUp(rules).build();
        TechniqueCatalog references = combatCatalog().toBuilder().setDefinitions(1, brokenReference).build();
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueRegistry.load(addons(), List.of(references)));
        AiHints originalHints = firstDefinition.getAiHints();
        CombatAiHints combat = originalHints.getCombat().toBuilder()
                .setTargetShieldingWeight(Quantity.getDefaultInstance())
                .build();
        AiHints hints = originalHints.toBuilder().setCombat(combat).build();
        TechniqueDefinition brokenWeight = firstDefinition.toBuilder().setAiHints(hints).build();
        TechniqueCatalog weights = combatCatalog().toBuilder().setDefinitions(0, brokenWeight).build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(weights)));
    }

    private static List<EffectIntent> strikes(MemoryHost host) {
        // 返回宿主真实收到的意图，不能用直接调用调度器代替服务旅程。
        return host.effects.stream().filter(EffectIntent::hasWeaponStrike).toList();
    }

    private static TechniqueService learnedService(MemoryHost host) {
        // 后式知识经真实可信活动写入，避免只在测试中伪造承接资格。
        TechniqueService service = service(host, combatRegistry());
        service.learn(ENTITY, SECOND);
        return service;
    }
}
