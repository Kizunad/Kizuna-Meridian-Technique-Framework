package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhases;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HorizontalDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Impulse;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.MovementAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationAction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationDuration;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.PresentationEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.ACTIVE;
import static dev.kizuna.meridian.technique.server.Fixtures.COOLDOWN;
import static dev.kizuna.meridian.technique.server.Fixtures.COST_QI;
import static dev.kizuna.meridian.technique.server.Fixtures.CURVE_END;
import static dev.kizuna.meridian.technique.server.Fixtures.HIT_STAGGER;
import static dev.kizuna.meridian.technique.server.Fixtures.RECOVERY;
import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.TECHNIQUE_ID;
import static dev.kizuna.meridian.technique.server.Fixtures.WARMUP;
import static dev.kizuna.meridian.technique.server.Fixtures.addons;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.definition;
import static dev.kizuna.meridian.technique.server.Fixtures.modifier;
import static dev.kizuna.meridian.technique.server.Fixtures.progress;
import static dev.kizuna.meridian.technique.server.Fixtures.quantity;
import static dev.kizuna.meridian.technique.server.Fixtures.registry;
import static dev.kizuna.meridian.technique.server.Fixtures.request;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.AXES;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.BLOCKS;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.BOOK;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.CLIP_START;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.ENTITY;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.IRON_AXES;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.LOWER;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.SECOND;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.THIRD;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.TOOLS;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.UPPER;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.durableItem;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.groupedCatalog;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.hands;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.movementDefinition;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.presentationEffect;
import static dev.kizuna.meridian.technique.server.HeldTechniqueFixtures.service;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TechniqueStage6aTest {
    private static final long CAST_END = WARMUP + ACTIVE + RECOVERY;
    private static final long INTERRUPT_TIME = WARMUP / 2;
    private static final long LONG_WARMUP = 4050;
    private static final long LONG_ACTIVE = 800;
    private static final long LONG_RECOVERY = 4150;
    private static final long LONG_END = 9000;
    private static final long FIRST_IMPULSE = 1550;
    private static final long SECOND_IMPULSE = 3050;
    private static final long THIRD_IMPULSE = 4850;
    private static final int ALL_TECHNIQUES = 3;
    private static final int UPPER_TECHNIQUES = 2;
    private static final int IMPULSE_COUNT = 3;
    private static final double INPUT_X = 3;
    private static final double QI_TOLERANCE = 1e-9;

    @Test
    public void volumeLearningIsAtomicChecksPrerequisiteAndKeepsSingleTechniqueRecords() {
        // 下卷不能绕过前置；整卷一次提交并发一次通知，知识只保存式的精确版本。
        TechniqueRegistry registry = groupedRegistry(definition());
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), TechniqueState.empty()));
        TechniqueService service = service(host, registry);
        var events = new ArrayList<TechniqueService.KnownChanged>();
        service.addKnownChangedListener(events::add);
        BodySnapshot before = host.current();
        assertThrows(TechniqueService.PrerequisiteVolumeException.class, () -> service.learn(ENTITY, LOWER));
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
        host.rejectNextActivity();
        assertThrows(TechniqueErrors.HostRejected.class, () -> service.learn(ENTITY, UPPER));
        assertEquals(before, host.current());
        assertTrue(events.isEmpty());
        assertEquals(UPPER_TECHNIQUES, service.learn(ENTITY, UPPER).size());
        assertEquals(1, events.size());
        assertEquals(before.revision() + 1, host.current().revision());
        assertEquals(ALL_TECHNIQUES, service.learn(ENTITY, LOWER).size());
        int submissions = host.activitySubmissions;
        assertEquals(service.known(ENTITY), service.learn(ENTITY, LOWER));
        assertEquals(submissions, host.activitySubmissions);
        assertEquals(List.of(TECHNIQUE_ID, SECOND, THIRD), service.known(ENTITY).stream()
                .map(entry -> entry.technique().getId()).toList());
    }

    @Test
    public void singleTechniqueLearningRemainsAvailableAndPartialVolumeDoesNotMeetPrerequisite() {
        // 单式命令仍有效，只有前置卷全部精确版本已学会才视为满足。
        TechniqueRegistry registry = groupedRegistry(definition());
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), TechniqueState.empty()));
        TechniqueService service = service(host, registry);
        assertEquals(1, service.learn(ENTITY, TECHNIQUE_ID).size());
        assertThrows(TechniqueService.PrerequisiteVolumeException.class, () -> service.learn(ENTITY, LOWER));
        assertEquals(UPPER_TECHNIQUES, service.learn(ENTITY, SECOND).size());
        assertEquals(ALL_TECHNIQUES, service.learn(ENTITY, LOWER).size());
    }

    @Test
    public void bookLearningCommitsAllVolumesOnceAndRejectsAtomically() {
        // 主机拒绝时整本书不留部分知识，成功重试只增加一次 revision 和通知。
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), TechniqueState.empty()));
        TechniqueService service = service(host, groupedRegistry(definition()));
        var events = new ArrayList<TechniqueService.KnownChanged>();
        service.addKnownChangedListener(events::add);
        BodySnapshot before = host.current();
        host.rejectNextActivity();
        assertThrows(TechniqueErrors.HostRejected.class, () -> service.learn(ENTITY, BOOK));
        assertEquals(before, host.current());
        assertTrue(events.isEmpty());
        assertEquals(ALL_TECHNIQUES, service.learn(ENTITY, BOOK).size());
        assertEquals(before.revision() + 1, host.current().revision());
        assertEquals(1, events.size());
        BodySnapshot learned = host.current();
        service.learn(ENTITY, BOOK);
        assertEquals(learned, host.current());
        assertEquals(1, events.size());
    }

    @Test
    public void emptyHandsAndFilledContainersRejectBeforePaymentWithPlayerReasons() {
        // 持物规则在起手活动生成前检查，失败不扣费、不写冷却。
        TechniqueDefinition definition = definition();
        var casting = new TechniqueCasting(groupedRegistry(definition));
        MemoryHost host = prepared(definition);
        BodySnapshot before = host.current();
        CastOutcome empty = casting.cast(host, request(before, "empty"));
        assertEquals(TechniqueFailure.HELD_ITEM_REQUIRED, empty.getFailure());
        assertEquals("需要手持物品", empty.getReason());
        HeldItem filled = durableItem("sample:container").toBuilder()
                .setContainsItems(true /* containsItems */)
                .build();
        host.heldItems = hands(filled, HeldItem.getDefaultInstance());
        CastOutcome container = casting.cast(host, request(before, "container"));
        assertEquals(TechniqueFailure.HELD_ITEM_UNUSABLE, container.getFailure());
        assertEquals("这件物品不能拿来劈", container.getReason());
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
    }

    @Test
    public void handSelectionUsesCategoryThenWearThenMainHandAndSupportsOffHandOnly() {
        // 通过真实起手和字节宿主固定选手；左手为主手不会颠倒主副手身份。
        HeldItem empty = HeldItem.getDefaultInstance();
        HeldItem woodSword = durableItem("minecraft:wooden_sword", TOOLS);
        HeldItem ironAxe = durableItem("minecraft:iron_axe", AXES, IRON_AXES);
        HeldItem diamondAxe = durableItem("minecraft:diamond_axe", AXES);
        HeldItem dirt = durableItem("minecraft:dirt", BLOCKS).toBuilder()
                .setDamageable(false /* damageable */)
                .build();
        HeldItem disposableAxe = diamondAxe.toBuilder().setDamageable(false /* damageable */).build();
        HeldItem filled = ironAxe.toBuilder().setContainsItems(true /* containsItems */).build();
        List<HeldItems> inputs = List.of(hands(empty, woodSword), hands(dirt, woodSword),
                hands(diamondAxe, ironAxe), hands(ironAxe, ironAxe), hands(disposableAxe, diamondAxe),
                hands(filled, woodSword));
        List<Hand> expected = List.of(Hand.OFF_HAND, Hand.OFF_HAND, Hand.OFF_HAND, Hand.MAIN_HAND,
                Hand.OFF_HAND, Hand.OFF_HAND);
        assertSelectedHands(inputs, expected);
    }

    private static void assertSelectedHands(List<HeldItems> inputs, List<Hand> expected) {
        // 每组事实独立起手，核对存档及字节效果中固定的选手，不修改宿主物品。
        TechniqueDefinition definition = movementDefinition();
        for (int index = 0; index < inputs.size(); index++) {
            MemoryHost host = prepared(definition);
            host.heldItems = inputs.get(index);
            TechniqueService service = service(host, groupedRegistry(definition));
            assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "hand")).getPhase());
            Hand hand = expected.get(index);
            assertEquals(hand, TechniqueState.read(host.current()).getActiveCast().getSelectedHand());
            assertEquals(hand, host.effects.get(0).getSelectedHand());
            assertEquals(inputs.get(index), host.heldItems);
        }
    }

    @Test
    public void movementLocksImmediatelyAndUnlocksAtRecoveryBoundary() {
        // 起手提交后立即下发锁，收势边界恰好解锁，重复 tick 不重复发送。
        TechniqueDefinition definition = movementDefinition();
        MemoryHost host = prepared(definition);
        TechniqueService service = service(host, registry(definition));
        assertEquals(CastPhase.WARMUP, service.cast(ENTITY, request(host.current(), "lock")).getPhase());
        assertEquals(List.of(MovementAction.LOCK), movementActions(host));
        host.nowMillis = CAST_END - 1;
        service.tick();
        assertEquals(List.of(MovementAction.LOCK), movementActions(host));
        host.nowMillis = CAST_END;
        service.tick();
        service.tick();
        assertEquals(List.of(MovementAction.LOCK, MovementAction.UNLOCK), movementActions(host));
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
    }

    @Test
    public void damageUnloadAndDisconnectUnlockAndStopWarmupPresentation() {
        // 各种提前结束都停止已开始的剪辑并解锁，保留费用且按实际终止时刻计冷却。
        for (Termination termination : Termination.values()) {
            TechniqueDefinition definition = movementDefinition();
            MemoryHost host = prepared(definition);
            TechniqueService service = service(host, registry(definition));
            service.cast(ENTITY, request(host.current(), "terminate"));
            assertEquals(CLIP_START, host.effects.get(1).getPresentation().getStartMillis());
            host.nowMillis = INTERRUPT_TIME;
            CastOutcome outcome = terminate(service, termination);
            assertTrue(outcome.getPhase() == CastPhase.INTERRUPTED || outcome.getPhase() == CastPhase.CANCELLED);
            assertEquals(List.of(MovementAction.LOCK, MovementAction.UNLOCK), movementActions(host));
            assertEquals(List.of(PresentationAction.START, PresentationAction.STOP), presentationActions(host));
            assertEquals(INTERRUPT_TIME + COOLDOWN,
                    TechniqueState.read(host.current()).getCooldownsOrThrow(TECHNIQUE_ID));
            assertEquals(COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
            assertTrue(host.current().actions().isEmpty());
        }
    }

    @Test
    public void catalogRemovalAndSavedBodyRecoveryStillUnlockAndStopPresentation() {
        // 清理使用起手保存的约束和表现，不依赖已卸载目录，也不会补发冲量。
        TechniqueDefinition definition = movementDefinition();
        MemoryHost host = prepared(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "removed"));
        host.nowMillis = INTERRUPT_TIME;
        var emptyRegistry = TechniqueRegistry.load(addons(), List.of());
        var casting = new TechniqueCasting(emptyRegistry);
        var cancelled = casting.cancelOnUnload(host, 0);
        assertEquals(CastPhase.CANCELLED, cancelled.outcome().getPhase());
        assertEquals(MovementAction.UNLOCK, cancelled.effects().get(1).getMovement().getAction());
        assertEquals(PresentationAction.STOP, cancelled.effects().get(0).getPresentation().getAction());

        MemoryHost saved = prepared(definition);
        TechniqueService original = service(saved, registry(definition));
        original.cast(ENTITY, request(saved.current(), "saved_lock"));
        MemoryHost restored = saved.restore();
        restored.die();
        TechniqueService recovery = service(restored, emptyRegistry);
        recovery.recoverOnLoad(ENTITY, restored);
        assertEquals(List.of(MovementAction.UNLOCK), movementActions(restored));
        assertEquals(List.of(PresentationAction.STOP), presentationActions(restored));
        int effects = restored.effects.size();
        recovery.recoverOnLoad(ENTITY, restored);
        assertEquals(effects, restored.effects.size());
        assertFalse(TechniqueState.read(restored.current()).hasActiveCast());
    }

    @Test
    public void inputFallbackSupportsBackwardKeepsForwardDefaultAndPreservesInput() {
        // 输入缺省和零向量遵循效果默认方向，非零输入不被默认值覆盖。
        Impulse backward = impulse().toBuilder()
                .setInputDefaultDirection(ImpulseDirection.BACKWARD)
                .build();
        TechniqueDefinition definition = definition().toBuilder()
                .addEffects(impulseEffect(0, backward))
                .addEffects(impulseEffect(0, impulse()))
                .build();
        MemoryHost host = prepared(definition);
        var casting = new TechniqueCasting(registry(definition));
        casting.cast(host, request(host.current(), "default"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        List<EffectIntent> defaults = TechniqueScheduler.effectsAt(definition, active, 0);
        assertEquals(ImpulseDirection.BACKWARD, defaults.get(0).getImpulse().getDirection());
        assertEquals(ImpulseDirection.FORWARD, defaults.get(1).getImpulse().getDirection());
        CastRequest zeroInput = active.getRequest().toBuilder()
                .setDirection(HorizontalDirection.getDefaultInstance())
                .build();
        ActiveCast still = active.toBuilder().setRequest(zeroInput).build();
        assertEquals(defaults, TechniqueScheduler.effectsAt(definition, still, 0));
        HorizontalDirection direction = HorizontalDirection.newBuilder().setX(INPUT_X).build();
        CastRequest input = active.getRequest().toBuilder().setDirection(direction).build();
        ActiveCast moving = active.toBuilder().setRequest(input).build();
        EffectIntent effect = TechniqueScheduler.effectsAt(definition, moving, 0).get(0);
        assertEquals(ImpulseDirection.INPUT, effect.getImpulse().getDirection());
        assertEquals(direction, effect.getDirection());
    }

    @Test
    public void nineSecondCastDispatchesThreeImpulsesWithStableIndependentIds() {
        // 跨多个 tick 批次推进也保持每个效果独立身份，重复查询由宿主回执去重。
        CastPhases phases = CastPhases.newBuilder()
                .setWarmupMillis(LONG_WARMUP)
                .setActiveMillis(LONG_ACTIVE)
                .setRecoveryMillis(LONG_RECOVERY)
                .setInterruptibleByDamage(true /* interruptibleByDamage */)
                .build();
        TechniqueDefinition definition = definition().toBuilder()
                .setPhases(phases)
                .addEffects(impulseEffect(FIRST_IMPULSE, impulse()))
                .addEffects(impulseEffect(SECOND_IMPULSE, impulse()))
                .addEffects(impulseEffect(THIRD_IMPULSE, impulse()))
                .build();
        MemoryHost host = prepared(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "three"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        assertEquals(LONG_END, active.getRecoveryEndMillis());
        host.nowMillis = FIRST_IMPULSE;
        service.tick();
        host.nowMillis = THIRD_IMPULSE;
        service.tick();
        List<EffectIntent> all = TechniqueScheduler.due(definition, active, -1, THIRD_IMPULSE).effects();
        assertEquals(all, host.effects);
        assertEquals(IMPULSE_COUNT, new HashSet<>(all.stream().map(EffectIntent::getEffectId).toList()).size());
        service.tick();
        assertEquals(IMPULSE_COUNT, host.effects.size());
        host.nowMillis = LONG_END;
        service.tick();
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertEquals(LONG_END + COOLDOWN, TechniqueState.read(host.current()).getCooldownsOrThrow(TECHNIQUE_ID));
    }

    @Test
    public void identicalPresentationsHaveIndependentStopsAndStartTimeDefaultsToZero() {
        // 同时同内容的表现也不能合并幂等身份，省略起播时间必须仍为零。
        TechniqueEffect presentation = presentationEffect(0);
        TechniqueDefinition definition = definition().toBuilder()
                .addEffects(presentation)
                .addEffects(presentation)
                .build();
        MemoryHost host = prepared(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "identical"));
        service.tick();
        assertEquals(0, host.effects.get(0).getPresentation().getStartMillis());
        host.nowMillis = CAST_END;
        service.tick();
        assertEquals(UPPER_TECHNIQUES + UPPER_TECHNIQUES, host.effects.size());
        assertEquals(host.effects.size(), new HashSet<>(host.effects.stream().map(EffectIntent::getEffectId)
                .toList()).size());
    }

    @Test
    public void oncePresentationStopsWhenWarmupIsInterrupted() {
        // 一次性剪辑在起手已经播放时也必须停止，不能等自然播完。
        PresentationEffect presentation = presentationEffect(CLIP_START).getPresentation().toBuilder()
                .setDuration(PresentationDuration.ONCE)
                .build();
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setPresentation(presentation)
                .build();
        TechniqueDefinition definition = definition().toBuilder().addEffects(effect).build();
        MemoryHost host = prepared(definition);
        TechniqueService service = service(host, registry(definition));
        service.cast(ENTITY, request(host.current(), "once"));
        service.tick();
        host.nowMillis = INTERRUPT_TIME;
        service.stagger(ENTITY, HIT_STAGGER);
        assertEquals(List.of(PresentationAction.PLAY_ONCE, PresentationAction.STOP), presentationActions(host));
        assertEquals(0, host.effects.get(1).getPresentation().getStartMillis());
    }

    @Test
    public void everyCastPhaseAcceptsNineSeconds() {
        // 三段各自可以达到九秒，阶段边界由保存的身体毫秒决定。
        CastPhases phases = CastPhases.newBuilder()
                .setWarmupMillis(LONG_END)
                .setActiveMillis(LONG_END)
                .setRecoveryMillis(LONG_END)
                .setInterruptibleByDamage(true /* interruptibleByDamage */)
                .build();
        TechniqueDefinition definition = definition().toBuilder().setPhases(phases).build();
        MemoryHost host = prepared(definition);
        var casting = new TechniqueCasting(registry(definition));
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(host.current(), "long_phases")).getPhase());
        host.nowMillis += LONG_END;
        assertEquals(CastPhase.EFFECTIVE, casting.advance(host).getPhase());
        host.nowMillis += LONG_END;
        assertEquals(CastPhase.RECOVERING, casting.advance(host).getPhase());
        host.nowMillis += LONG_END;
        assertEquals(CastPhase.FINISHED, casting.advance(host).getPhase());
    }

    @Test
    public void interruptedCurveStillBlocksRecastUntilFullCurveEnd() {
        // 提前结束虽提前开始冷却，已提交的修正仍完整运行且不能同式叠加。
        TechniqueEffect modifier = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setTissueModifier(modifier())
                .build();
        CastPhases phases = definition().getPhases().toBuilder().setRecoveryMillis(CURVE_END).build();
        TechniqueDefinition definition = definition().toBuilder()
                .setCooldownMillis(0)
                .setPhases(phases)
                .addEffects(modifier)
                .build();
        MemoryHost host = prepared(definition);
        var casting = new TechniqueCasting(registry(definition));
        casting.cast(host, request(host.current(), "curve"));
        host.nowMillis = INTERRUPT_TIME;
        casting.interruptByStagger(host, -1);
        host.nowMillis = CURVE_END - 1;
        assertEquals(TechniqueFailure.COOLING_DOWN,
                casting.cast(host, request(host.current(), "curve_early")).getFailure());
        host.nowMillis = CURVE_END;
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(host.current(), "curve_ready")).getPhase());
    }

    private static Impulse impulse() {
        // 默认未声明缺省方向，用于验证兼容的向前行为。
        return Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
    }

    private static TechniqueEffect impulseEffect(long atMillis, Impulse impulse) {
        // 三次冲量独立声明，不合并为一个持续速度效果。
        return TechniqueEffect.newBuilder()
                .setAtMillis(atMillis)
                .setImpulse(impulse)
                .build();
    }

    private static TechniqueRegistry groupedRegistry(TechniqueDefinition definition) {
        // 正式加载器校验书卷、持物规则和精确单式引用。
        return TechniqueRegistry.load(addons(), List.of(groupedCatalog(definition)));
    }

    private static MemoryHost prepared(TechniqueDefinition definition) {
        // 每项测试独立保存知识和身体，费用仍由真实 Framework 处理。
        return new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
    }

    private static List<MovementAction> movementActions(MemoryHost host) {
        // 只取移动意图，表现和冲量的相对顺序由独立断言检查。
        return host.effects.stream().filter(EffectIntent::hasMovement)
                .map(effect -> effect.getMovement().getAction()).toList();
    }

    private static List<PresentationAction> presentationActions(MemoryHost host) {
        // 表现开始和停止通过同一宿主效果通道提交。
        return host.effects.stream().filter(EffectIntent::hasPresentation)
                .map(effect -> effect.getPresentation().getAction()).toList();
    }

    private static CastOutcome terminate(TechniqueService service, Termination termination) {
        // 各入口拥有不同生命周期含义，但都必须解除本次约束。
        return switch (termination) {
            case STAGGER -> service.stagger(ENTITY, HIT_STAGGER).orElseThrow();
            case UNLOAD -> service.cancelOnUnload(ENTITY).orElseThrow();
            case DISCONNECT -> service.cancelOnDisconnect(ENTITY).orElseThrow();
        };
    }

    private enum Termination { STAGGER, UNLOAD, DISCONNECT }
}
