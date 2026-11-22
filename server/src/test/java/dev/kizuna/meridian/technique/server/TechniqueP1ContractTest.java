package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttack;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttackSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ConsumeHeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUp;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUpRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemConsumptionStrategy;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LastCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.ACTIVE;
import static dev.kizuna.meridian.technique.server.Fixtures.RECOVERY;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.catalog;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.AXES;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.SECOND;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.TOOLS;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.durableItem;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.groupedCatalog;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.hands;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.rules;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.FOLLOW_UP_UNAVAILABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** P1 通用承接、持物覆盖和整叠消耗契约的纯逻辑回归。 */
public class TechniqueP1ContractTest {
    private static final long CAST_END = WARMUP + ACTIVE + RECOVERY;
    private static final long BLOCKED_WINDOW_MILLIS = 600;
    private static final long FOLLOW_UP_AFTER_RECOVERY = 1000;
    private static final String BLOCKED_EVENT = "shield_event";
    private static final UUID UNKNOWN_ENTITY = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String PREVIOUS_ID = "sample:previous";
    private static final String NEXT_ID = "sample:next";

    @Test
    public void recordsBlockedAttackOnceAndRejectsUnknownOrFutureSources() {
        // 阻挡事实只能写入攻击者身体，未知攻击者、未来时刻和同事件重试都不产生新提交。
        TechniqueDefinition definition = definition();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        TechniqueService.BlockedAttackObservation future = blocked(UNKNOWN_ENTITY, "future_event", 1);
        assertFalse(service.recordBlockedAttack(future));
        assertFalse(service.recordBlockedAttack(blocked(ENTITY, "future_event", 1)));

        TechniqueService.BlockedAttackObservation observation = blocked(ENTITY, BLOCKED_EVENT, 0);
        assertTrue(service.recordBlockedAttack(observation));
        long revision = host.current().revision();
        assertFalse(service.recordBlockedAttack(blocked(UNKNOWN_ENTITY, "other_attacker", 0)));
        assertFalse(service.recordBlockedAttack(observation));
        assertEquals(revision, host.current().revision());
        assertEquals(BLOCKED_EVENT, TechniqueState.read(host.current()).getBlockedAttack().getEventId());
        assertThrows(IllegalArgumentException.class,
                () -> service.recordBlockedAttack(blocked(ENTITY, BLOCKED_EVENT, 0, BlockedAttackSource.GUARD)));
    }

    @Test
    public void strikeReportDoesNotCreateBlockedAttackFact() {
        // StrikeReport 只供观察者统计，不能冒充 Damage 提交的可信阻挡事实。
        TechniqueDefinition previous = previousDefinition();
        TechniqueDefinition next = nextDefinition(previous, BlockedAttackSource.SHIELD);
        TechniqueProgress initial = progressWithHistory(previous, BlockedAttackSource.SHIELD, 0)
                .toBuilder()
                .clearBlockedAttack()
                .addKnown(known(previous))
                .addKnown(known(next))
                .build();
        TechniqueCatalog catalog = catalog(previous).toBuilder().addDefinitions(next).build();
        TechniqueRegistry registry = TechniqueRegistry.load(Fixtures.addons(), List.of(catalog));
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), initial));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry);
        TechniqueService.StrikeReport report = new TechniqueService.StrikeReport(
                "blocked_report", TechniqueRegistry.reference(previous), ENTITY,
                UNKNOWN_ENTITY, TechniqueService.StrikeOutcome.BLOCKED);

        service.reportStrike(report);

        assertFalse(TechniqueState.read(host.current()).hasBlockedAttack());

        CastRequest followUpRequest = request(next, "blocked_report_follow_up").toBuilder()
                .setEntry(CastEntry.FOLLOW_UP)
                .build();
        host.nowMillis = 200;
        CastOutcome rejected = service.cast(ENTITY, followUpRequest);
        assertEquals(FOLLOW_UP_UNAVAILABLE, rejected.getFailure());
    }

    @Test
    public void registeredAttackersKeepBlockedFactsOnTheirOwnBodies() {
        // 两个已认领身体各自保存最近阻挡，攻击者身份不会串用另一具身体的事实。
        UUID otherEntity = UUID.fromString("00000000-0000-0000-0000-000000000007");
        TechniqueDefinition previous = previousDefinition();
        TechniqueDefinition next = nextDefinition(previous, BlockedAttackSource.SHIELD);
        TechniqueProgress initial = progressWithHistory(previous, BlockedAttackSource.SHIELD, 0)
                .toBuilder()
                .clearBlockedAttack()
                .addKnown(known(previous))
                .addKnown(known(next))
                .build();
        TechniqueCatalog catalog = catalog(previous).toBuilder().addDefinitions(next).build();
        TechniqueRegistry registry = TechniqueRegistry.load(Fixtures.addons(), List.of(catalog));
        MemoryHost first = new MemoryHost(withProgress(body(Shape.TENDRIL), initial));
        MemoryHost second = new MemoryHost(withProgress(body(Shape.TENDRIL), initial));
        TechniqueService service = HeldTechniqueFixtures.service(
                registry, ENTITY, first, otherEntity, second);

        assertTrue(service.recordBlockedAttack(blocked(ENTITY, "first_attacker_event", 0)));
        TechniqueProgress firstProgress = TechniqueState.read(first.current());
        TechniqueProgress secondProgress = TechniqueState.read(second.current());
        assertEquals("first_attacker_event", firstProgress.getBlockedAttack().getEventId());
        assertFalse(secondProgress.hasBlockedAttack());
        assertFalse(TechniqueFollowUps.available(next, secondProgress, 200).isPresent());

        assertTrue(service.recordBlockedAttack(blocked(otherEntity, "second_attacker_event", 0)));
        TechniqueProgress updatedFirstProgress = TechniqueState.read(first.current());
        TechniqueProgress updatedSecondProgress = TechniqueState.read(second.current());
        assertEquals("first_attacker_event", updatedFirstProgress.getBlockedAttack().getEventId());
        assertEquals("second_attacker_event", updatedSecondProgress.getBlockedAttack().getEventId());
        assertTrue(TechniqueFollowUps.available(next, updatedFirstProgress, 200).isPresent());
        assertTrue(TechniqueFollowUps.available(next, updatedSecondProgress, 200).isPresent());
    }

    @Test
    public void retriesBlockedAttackAfterRevisionConflictWithoutChangingEvent() {
        // 版本冲突只重试同一请求身份，最终进度只保留一份阻挡事实。
        TechniqueDefinition definition = definition();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        host.conflictNextActivity();
        assertTrue(service.recordBlockedAttack(blocked(ENTITY, "retry_event", 0)));
        assertEquals(2, host.activitySubmissions);
        assertEquals("retry_event", TechniqueState.read(host.current()).getBlockedAttack().getEventId());
    }

    @Test
    public void blockedAttackWindowIncludes600MillisecondsAndRejectsTheNextMillisecond() {
        // 有来源限制的承接把 600ms 作为包含边界，超过一毫秒即返回不可承接。
        assertEquals(BLOCKED_WINDOW_MILLIS,
                TechniqueFollowUps.BLOCKED_ATTACK_WINDOW_MILLIS);
        for (BlockedAttackSource source : List.of(BlockedAttackSource.SHIELD,
                BlockedAttackSource.GUARD, BlockedAttackSource.HARD_OBJECT)) {
            TechniqueDefinition previous = previousDefinition();
            TechniqueDefinition next = nextDefinition(previous, source);
            TechniqueProgress progress = progressWithHistory(previous, source, 0);
            CastRequest request = request(next, "edge_follow_up_" + source.name()).toBuilder()
                    .setEntry(CastEntry.FOLLOW_UP)
                    .build();
            TechniqueFollowUps.EntryPlan plan = TechniqueFollowUps.plan(
                    next, progress, request, BLOCKED_WINDOW_MILLIS,
                    OptionalLong.empty());
            assertEquals(0, plan.clipStartMillis());
            TechniqueErrors.Rejected rejected = assertThrows(TechniqueErrors.Rejected.class,
                    () -> TechniqueFollowUps.plan(next, progress, request,
                            BLOCKED_WINDOW_MILLIS + 1, OptionalLong.empty()));
            assertEquals(FOLLOW_UP_UNAVAILABLE, rejected.code);
        }
    }

    @Test
    public void newerBlockedEventReplacesOlderEventAndRetryUsesReceipt() {
        // 新事实覆盖旧事实后，旧事件仍由宿主回执去重，不能回写旧时刻或来源。
        TechniqueDefinition definition = definition();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));

        assertTrue(service.recordBlockedAttack(blocked(ENTITY, "first_event", 0)));
        assertTrue(service.recordBlockedAttack(blocked(ENTITY, "second_event", 0, BlockedAttackSource.GUARD)));
        long revision = host.current().revision();

        assertFalse(service.recordBlockedAttack(blocked(ENTITY, "first_event", 0)));
        assertEquals(revision, host.current().revision());
        assertEquals("second_event", TechniqueState.read(host.current()).getBlockedAttack().getEventId());
    }

    @Test
    public void ordinaryCastPreservesBlockedFactAndFollowUpConsumesIt() {
        // 普通起手不触碰阻挡事实，合法承接成功后才原子清除对应事件。
        TechniqueDefinition ordinary = previousDefinition();
        TechniqueDefinition followUp = nextDefinition(ordinary, BlockedAttackSource.SHIELD);
        TechniqueProgress initial = TechniqueState.empty().toBuilder()
                .addKnown(known(ordinary))
                .addKnown(known(followUp))
                .build();
        TechniqueCatalog catalog = catalog(ordinary).toBuilder().addDefinitions(followUp).build();
        TechniqueRegistry registry = TechniqueRegistry.load(Fixtures.addons(), List.of(catalog));
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), initial));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry);

        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(ordinary, "ordinary")).getPhase());
        assertTrue(service.recordBlockedAttack(blocked(ENTITY, "follow_event", 0)));
        assertTrue(TechniqueState.read(host.current()).hasBlockedAttack());
        host.nowMillis = CAST_END;
        service.tick();
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY,
                request(followUp, "follow_up").toBuilder().setEntry(CastEntry.FOLLOW_UP).build()).getPhase());
        assertFalse(TechniqueState.read(host.current()).hasBlockedAttack());
    }

    @Test
    public void lifecycleCleanupIsDeferredOnUnloadDisconnectDeathAndLoad() {
        // 离场入口只排队，下一 tick 清理存活身体；死亡与读档继续由各自可信入口处理。
        assertClearedAfterExit(Exit.UNLOAD);
        assertClearedAfterExit(Exit.DISCONNECT);
        assertClearedAfterExit(Exit.DEATH);

        TechniqueDefinition definition = definition();
        MemoryHost original = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(original, registry(definition));
        assertTrue(service.recordBlockedAttack(blocked(ENTITY, "load_event", 0)));
        original.die();
        MemoryHost restored = original.restore();
        service.recoverOnLoad(ENTITY, restored);
        assertFalse(TechniqueState.read(restored.current()).hasBlockedAttack());
    }

    @Test
    public void lifecycleCleanupWithoutFactDoesNotInspectDuringCallback() {
        // 没有阻挡事实时，生命周期回调不能为了确认事实而访问宿主。
        TechniqueDefinition definition = definition();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        int inspections = host.inspectCalls;
        int activities = host.activitySubmissions;

        service.cancelOnUnload(ENTITY);

        assertEquals(inspections, host.inspectCalls);
        assertEquals(activities, host.activitySubmissions);
        service.tick();
        assertTrue(host.inspectCalls > inspections);
    }

    @Test
    public void lifecycleCleanupIgnoresDeadHostInspectionRejection() {
        // 死体拒绝读取时清理视为不可用，不能把宿主拒绝传播到生命周期回调或 tick。
        TechniqueDefinition definition = definition();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        assertTrue(service.recordBlockedAttack(blocked(ENTITY, "dead_event", 0)));
        host.rejectNextInspection(FailureCode.BODY_DEAD);

        service.cancelOnDeath(ENTITY);
        service.tick();

        assertTrue(TechniqueState.read(host.current()).hasBlockedAttack());
    }

    @Test
    public void definitionHeldItemRulesOverrideBookRulesAndLimitSelectionTags() {
        // 单式声明完整持物规则后覆盖书级规则，未命中单式标签的物品不能成为候选。
        TechniqueDefinition first = definition();
        HeldItemRules override = rules().toBuilder().addRequiredItemTags(TOOLS).build();
        TechniqueDefinition second = first.toBuilder()
                .setId(SECOND)
                .setHeldItemRules(override)
                .build();
        TechniqueCatalog catalog = groupedCatalog(first).toBuilder()
                .setDefinitions(1, second)
                .build();
        TechniqueRegistry registry = TechniqueRegistry.load(Fixtures.addons(), List.of(catalog));
        assertEquals(override, TechniqueHeldItems.rulesFor(registry, second));
        assertEquals(rules(), TechniqueHeldItems.rulesFor(registry, first));

        HeldItems axeOnly = hands(durableItem("sample:axe", AXES),
                dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem.getDefaultInstance());
        assertThrows(TechniqueErrors.Rejected.class, () -> TechniqueHeldItems.select(override, axeOnly, second));
        HeldItems toolOnly = hands(durableItem("sample:tool", TOOLS),
                dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem.getDefaultInstance());
        assertEquals(Hand.MAIN_HAND, TechniqueHeldItems.select(override, toolOnly, second));
    }

    @Test
    public void consumeHeldItemRequiresStrategyAndEmitsOneFrozenStackIntent() {
        // 整叠消耗必须显式选择策略，效果只提交带策略的幂等意图，不在功法库内修改物品。
        HeldItemRules consumingRules = rules().toBuilder()
                .setConsumptionStrategy(HeldItemConsumptionStrategy.CONSUME_SELECTED_STACK)
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setConsumeHeldItem(ConsumeHeldItem.getDefaultInstance())
                .build();
        TechniqueDefinition definition = definition().toBuilder()
                .setHeldItemRules(consumingRules)
                .addEffects(effect)
                .build();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        host.heldItems = hands(durableItem("sample:axe", AXES),
                dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem.getDefaultInstance());
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(definition, "consume")).getPhase());
        host.rejectNextEffect();
        host.nowMillis = WARMUP;
        service.tick();
        service.tick();
        List<EffectIntent> consume = host.effects.stream()
                .filter(EffectIntent::hasConsumeHeldItem)
                .toList();
        assertEquals(1, consume.size());
        assertEquals(HeldItemConsumptionStrategy.CONSUME_SELECTED_STACK,
                consume.get(0).getConsumeHeldItem().getStrategy());
        assertEquals(1, host.effectReceipts.values().stream()
                .filter(EffectIntent::hasConsumeHeldItem)
                .count());

        TechniqueDefinition missingStrategy = definition().toBuilder()
                .setHeldItemRules(rules())
                .addEffects(effect)
                .build();
        assertThrows(IllegalArgumentException.class, () -> registry(missingStrategy));
    }

    private static void assertClearedAfterExit(Exit exit) {
        // 每个离场入口都从同一份已记录事实开始，避免清理测试互相依赖。
        TechniqueDefinition definition = definition();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        TechniqueService service = HeldTechniqueFixtures.service(host, registry(definition));
        assertTrue(service.recordBlockedAttack(blocked(ENTITY, exit.name().toLowerCase() + "_event", 0)));
        int inspections = host.inspectCalls;
        int activities = host.activitySubmissions;
        switch (exit) {
            case UNLOAD -> service.cancelOnUnload(ENTITY);
            case DISCONNECT -> service.cancelOnDisconnect(ENTITY);
            case DEATH -> {
                service.cancelOnDeath(ENTITY);
                host.die();
            }
        }
        assertEquals(inspections, host.inspectCalls);
        assertEquals(activities, host.activitySubmissions);
        assertTrue(TechniqueState.read(host.current()).hasBlockedAttack());
        service.tick();
        if (exit != Exit.DEATH) {
            assertFalse(TechniqueState.read(host.current()).hasBlockedAttack());
        }
    }

    private static TechniqueService.BlockedAttackObservation blocked(UUID attacker, String eventId, long millis) {
        // 阻挡观察值使用默认盾来源，其他测试只替换攻击者、事件身份或时刻。
        return blocked(attacker, eventId, millis, BlockedAttackSource.SHIELD);
    }

    private static TechniqueService.BlockedAttackObservation blocked(UUID attacker, String eventId, long millis,
                                                                       BlockedAttackSource source) {
        // 事件来源由测试明确声明，验证服务不接受同一 ID 的事实改写。
        return new TechniqueService.BlockedAttackObservation(attacker, eventId, source, millis);
    }

    private static TechniqueDefinition previousDefinition() {
        // 前式使用无具体持物规则的通用样例，专测承接事实而非内容判断。
        return definition().toBuilder().setId(PREVIOUS_ID).build();
    }

    private static TechniqueDefinition nextDefinition(TechniqueDefinition previous,
                                                       BlockedAttackSource source) {
        // 后式声明精确前式和阻挡来源，窗口长度故意大于阻挡事实窗口以隔离边界。
        FollowUp transition = FollowUp.newBuilder()
                .setPrevious(TechniqueRegistry.reference(previous))
                .setRecoveryStartRatio(quantity(0, Unit.RATIO))
                .setAfterRecoveryMillis(FOLLOW_UP_AFTER_RECOVERY)
                .setStartMillis(0)
                .addBlockedAttackSources(source)
                .build();
        FollowUpRules rules = FollowUpRules.newBuilder()
                .addTransitions(transition)
                .build();
        return definition().toBuilder()
                .setId(NEXT_ID)
                .setFollowUp(rules)
                .build();
    }

    private static TechniqueProgress progressWithHistory(TechniqueDefinition previous,
                                                          BlockedAttackSource source, long blockedMillis) {
        // 进度只构造可信的已结束前式和阻挡事实，承接判定不读取客户端时间。
        LastCast last = LastCast.newBuilder()
                .setTechnique(TechniqueRegistry.reference(previous))
                .setCastId("previous_cast")
                .setActiveEndMillis(100)
                .setRecoveryEndMillis(200)
                .setEnding(CastPhase.FINISHED)
                .build();
        BlockedAttack blocked = BlockedAttack.newBuilder()
                .setEventId(BLOCKED_EVENT)
                .setSource(source)
                .setBlockedMillis(blockedMillis)
                .build();
        return TechniqueState.empty().toBuilder()
                .setLastCast(last)
                .setBlockedAttack(blocked)
                .build();
    }

    private static KnownTechnique known(TechniqueDefinition definition) {
        // 端到端承接测试预置两式知识，和生产可信学习记录使用同一消息形状。
        Quantity proficiency = quantity(0, Unit.RATIO);
        return KnownTechnique.newBuilder()
                .setTechnique(TechniqueRegistry.reference(definition))
                .setProficiency(proficiency)
                .setSource("sample:trusted")
                .setLearnedMillis(0)
                .build();
    }

    private static CastRequest request(TechniqueDefinition definition, String castId) {
        // 请求只带精确式引用和施放身份，持物、阻挡和费用事实均由服务读取。
        return CastRequest.newBuilder()
                .setBodyId("body-TENDRIL")
                .setTechnique(TechniqueRegistry.reference(definition))
                .setCastId(castId)
                .build();
    }

    private enum Exit { UNLOAD, DISCONNECT, DEATH }
}
