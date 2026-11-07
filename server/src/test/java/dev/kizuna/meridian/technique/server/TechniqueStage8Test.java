package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

/** 阶段 8 腿法契约、宿主事实、训练做功和 AI 情境的回归。 */
public final class TechniqueStage8Test {
    private static final String LEG_ID = "sample:leg";
    private static final String LEG_BOOK_ID = "sample:leg_book";
    private static final String LEG_VOLUME_ID = "sample:leg_volume";
    private static final String DAMAGE_TYPE_ID = "sample:kick_lead_torso";
    private static final String DEDUP_GROUP = "leg_sweep";
    private static final double LEG_DISTANCE = 3;
    private static final double LEG_ANGLE = 90;
    private static final double LEG_MULTIPLIER = 1.5;
    private static final double LEG_KNOCKBACK = 0.8;
    private static final int MAXIMUM_TARGETS = 2;
    private static final int CAST_TARGET_LIMIT = 3;
    private static final double BASE_DAMAGE = 2;
    private static final double ARMOR_DAMAGE = 0.5;
    private static final int DURABILITY_COST = 1;
    private static final double MINIMUM_LEG_FORCE = 100;
    private static final double BEHIND_WEIGHT = 2;
    private static final double AIRBORNE_WEIGHT = 3;
    private static final double FLEEING_WEIGHT = 4;
    private static final String CAST_ID = "leg_cast";

    @Test
    public void legStrikeUsesRearFacingAndSharedTargetBudget() {
        // 腿击沿用持物打击的同组去重和整式不同目标预算，但朝向、腿和击退独立声明。
        LegStrike strike = legStrike(false);
        TechniqueCombatEffects.validateLegStrike(strike);
        assertEquals(StrikeFacing.STRIKE_BACKWARD, strike.getFacing());
        assertEquals(LegSelection.REAR_LEG, strike.getLeg());
        assertEquals(LegKnockbackKind.PUSH, strike.getKnockbackKind());

        Map<String, Set<String>> hitIds = Map.of(DEDUP_GROUP, Set.of("first"));
        List<String> selected = TechniqueStrikeTargets.select(strike, hitIds,
                List.of("first", "second", "third", "fourth"));
        assertEquals(List.of("second", "third"), selected);
        assertEquals(Set.of("first"), hitIds.get(DEDUP_GROUP));
    }

    @Test
    public void legStrikeSchedulesSavedRulesAndRejectsWrongFinalStrike() {
        // 调度意图保留起手书规则；多段腿法只能把最后一击标为磨鞋末击。
        TechniqueDefinition definition = legDefinition();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(),
                List.of(legCatalog(definition, legRules())));
        BodySnapshot prepared = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(prepared);
        CastOutcome started = new TechniqueCasting(registry).cast(host,
                request(definition, prepared, "scheduled_leg"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        List<EffectIntent> effects = TechniqueScheduler.effectsAt(definition, active, 0);
        assertEquals(CastPhase.WARMUP, started.getPhase());
        assertEquals(1, effects.size());
        EffectIntent intent = effects.get(0);
        assertEquals(Hand.HAND_UNSPECIFIED, intent.getSelectedHand());
        assertEquals(legRules(), intent.getLegStrike().getLegRules());
        assertEquals(StrikeFacing.STRIKE_BACKWARD, intent.getLegStrike().getStrike().getFacing());
        TechniqueProtocol.validateEffect(intent);

        TechniqueEffect second = TechniqueEffect.newBuilder()
                .setAtMillis(1)
                .setLegStrike(legStrike(true))
                .build();
        TechniqueDefinition wrongFinal = definition.toBuilder().addEffects(second).build();
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueRegistry.load(addons(), List.of(legCatalog(wrongFinal, legRules()))));
    }

    @Test
    public void legBookRequiresGroundingRulesAndRejectsHeldRules() {
        // 腿击必须来自声明腿法鞋规则的书，腿法书不能同时启用持物规则。
        TechniqueDefinition definition = legDefinition();
        TechniqueCatalog catalog = legCatalog(definition, legRules());
        assertTrue(TechniqueRegistry.load(addons(), List.of(catalog))
                .bookOf(TechniqueRegistry.reference(definition)).isPresent());

        TechniqueCatalog missingRules = legCatalog(definition, LegRules.getDefaultInstance());
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(missingRules)));

        TechniqueBook mixed = TechniqueBook.newBuilder()
                .setId(LEG_BOOK_ID)
                .setLegRules(legRules())
                .setHeldItemRules(HeldTechniqueFixtures.rules())
                .build();
        TechniqueCatalog mixedCatalog = catalog(definition).toBuilder()
                .addBooks(mixed)
                .addVolumes(volume(definition))
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(mixedCatalog)));
    }

    @Test
    public void nonGroundedLegCastReturnsPlayerReasonAndSavesRules() {
        // 水中、攀爬、骑乘、滑翔和离地都返回同一个中文失败原因，着地后保存规则。
        TechniqueDefinition definition = legDefinition();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(),
                List.of(legCatalog(definition, legRules())));
        BodySnapshot prepared = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(prepared);
        TechniqueCasting casting = new TechniqueCasting(registry);

        for (TechniqueHostFacts facts : blockedFacts()) {
            host.inspectFacts = facts;
            CastOutcome outcome = casting.cast(host, request(definition, prepared, CAST_ID + facts.hashCode()));
            assertEquals(TechniqueFailure.NOT_GROUNDED, outcome.getFailure());
            assertEquals("要站在地上才能出腿", outcome.getReason());
        }

        host.inspectFacts = TechniqueInspection.defaults().toBuilder()
                .setGrounded(true /* grounded */)
                .build();
        CastOutcome started = casting.cast(host, request(definition, host.current(), CAST_ID));
        assertEquals(CastPhase.WARMUP, started.getPhase());
        assertEquals(legRules(), TechniqueState.read(host.current()).getActiveCast().getLegRules());
        assertEquals(Hand.HAND_UNSPECIFIED, TechniqueState.read(host.current()).getActiveCast().getSelectedHand());
    }

    @Test
    public void publicLegacyInspectionRejectsLegsAndKeepsOrdinaryCasting() throws NoSuchMethodException {
        // 外部宿主仍能调用两参数入口；缺少移动事实不能支付费用并起手腿法。
        var constructor = TechniqueHost.Inspection.class.getConstructor(BodySnapshot.class, HeldItems.class);
        assertNotNull(constructor);
        TechniqueDefinition leg = legDefinition();
        BodySnapshot prepared = withProgress(body(Shape.TENDRIL), progress(leg));
        MemoryHost host = new MemoryHost(prepared);
        var legacy = new TechniqueHost.Inspection(prepared, host.heldItems);
        host.inspectFacts = legacy.facts();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(legCatalog(leg, legRules())));
        CastOutcome refused = new TechniqueCasting(registry).cast(host, request(leg, prepared, "legacy_leg"));
        assertEquals(TechniqueFailure.NOT_GROUNDED, refused.getFailure());
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertEquals(0, host.activitySubmissions);

        TechniqueDefinition ordinary = definition();
        BodySnapshot ordinaryBody = withProgress(body(Shape.TENDRIL), progress(ordinary));
        MemoryHost ordinaryHost = new MemoryHost(ordinaryBody);
        ordinaryHost.inspectFacts = new TechniqueHost.Inspection(ordinaryBody, ordinaryHost.heldItems).facts();
        TechniqueRegistry ordinaryRegistry = TechniqueRegistry.load(addons(), List.of(catalog(ordinary)));
        CastOutcome started = new TechniqueCasting(ordinaryRegistry).cast(ordinaryHost,
                request(ordinary, ordinaryBody, "legacy_ordinary"));
        assertEquals(CastPhase.WARMUP, started.getPhase());
    }

    @Test
    public void trainingWorkIsMarkedOnCastAndLeapAndUntrainedCatalogIsRejected() {
        // 目录先验证所有模板的 TissueTraining，起手和跃步再把 training 原样交给 Framework。
        TechniqueDefinition definition = trainingDefinition();
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueRegistry.load(addons(), List.of(Fixtures.catalog(definition))));
        TechniqueRegistry registry = TechniqueRegistry.load(trainingAddons(), List.of(Fixtures.catalog(definition)));
        BodySnapshot prepared = trainingBody();
        BindingOutcome binding = TechniqueBinder.bind(prepared, definition);
        assertTrue(binding.hasBinding());
        TechniqueActivities.Start start = new TechniqueActivities.Start(prepared, definition,
                request(definition, prepared, "training_cast"), progress(definition),
                new TechniqueActivities.CastBinding(binding.getBinding(), Hand.HAND_UNSPECIFIED));
        BodyActivity activity = TechniqueActivities.start(start);
        assertTrue(activity.getEffortsList().stream().anyMatch(ActivityMessages.ActivityEffort::getTraining));
        assertTrue(activity.getEffortsList().stream().anyMatch(effort -> !effort.getTraining()));

        WorkCost trainingWork = definition.getCost().getWork(0);
        LeapProfile leap = LeapProfile.newBuilder().setWork(trainingWork).build();
        MemoryHost host = new MemoryHost(prepared);
        TechniqueService.LeapExertion exertion = TechniqueLeapWork.submit(host, definition, leap, "training_leap");
        assertTrue(exertion.accepted());
        assertTrue(exertion.efforts().get(0).getTraining());
        assertEquals(registry.definitions().size(), 1);
    }

    @Test
    public void organTrainingWithoutForceIsRejected() {
        // 器官没有力量字段时，训练标记仍须独立拒绝，不能落入普通器官做功路径。
        TechniqueDefinition base = definition();
        WorkCost organTraining = base.getCost().getWork(1).toBuilder()
                .setTraining(true)
                .build();
        TechniqueCost invalidCost = base.getCost().toBuilder()
                .setWork(1, organTraining)
                .build();
        TechniqueDefinition invalidDefinition = base.toBuilder()
                .setCost(invalidCost)
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> TechniqueRegistry.load(addons(), List.of(Fixtures.catalog(invalidDefinition))));
    }

    @Test
    public void aiFactsMultiplyLegWeightsAndEnforceEffectiveForce() {
        // 三个新增情境权重相乘；两腿有效力量低于门槛时，候选直接不可用。
        TechniqueDefinition definition = legDefinition().toBuilder()
                .setCategory(Category.ATTACK)
                .setAiHints(aiHints())
                .build();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(),
                List.of(legCatalog(definition, legRules())));
        TechniqueReference reference = TechniqueRegistry.reference(definition);
        BindingOutcome binding = TechniqueBinder.bind(body(Shape.TENDRIL), definition);
        TechniqueSelector.Availability availability = new TechniqueSelector.Availability(
                Map.of(reference, binding), Set.of(reference), Map.of());
        KnownTechnique known = progress(definition).getKnown(0);
        TechniqueSelector.Situation weak = new TechniqueSelector.Situation(0, 1, 1, 2,
                TechniqueSelector.TargetState.ENGAGED, MINIMUM_LEG_FORCE - 1);
        TechniqueSelector.Situation strong = new TechniqueSelector.Situation(0, 1, 1, 2,
                TechniqueSelector.TargetState.ENGAGED, MINIMUM_LEG_FORCE);
        Set<TechniqueSelector.CombatFact> facts = Set.of(TechniqueSelector.CombatFact.TARGET_BEHIND,
                TechniqueSelector.CombatFact.TARGET_AIRBORNE, TechniqueSelector.CombatFact.SELF_FLEEING);
        TechniqueSelector.Context weakContext = new TechniqueSelector.Context(registry, weak, availability,
                new TechniqueSelector.CombatContext(facts, TechniqueState.empty()));
        TechniqueSelector.Context strongContext = new TechniqueSelector.Context(registry, strong, availability,
                new TechniqueSelector.CombatContext(facts, TechniqueState.empty()));
        assertEquals(0, TechniqueSelector.score(weakContext, known), 0);
        double base = TechniqueSelector.score(new TechniqueSelector.Context(registry, strong, availability), known);
        assertEquals(base * BEHIND_WEIGHT * AIRBORNE_WEIGHT * FLEEING_WEIGHT,
                TechniqueSelector.score(strongContext, known), 1e-9);
    }

    private static TechniqueDefinition legDefinition() {
        // 一条腿击沿用普通费用和组织槽位，目标参数仅用于验证阶段 8 契约。
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setLegStrike(legStrike(true))
                .build();
        return definition().toBuilder()
                .setId(LEG_ID)
                .addEffects(effect)
                .build();
    }

    private static TechniqueDefinition trainingDefinition() {
        // 蹲马步样例只把组织做功标记为训练，器官做功仍是普通负荷。
        WorkCost training = definition().getCost().getWork(0).toBuilder()
                .setTraining(true)
                .build();
        TechniqueCost cost = definition().getCost().toBuilder().setWork(0, training).build();
        return definition().toBuilder().setId("sample:training").setCost(cost).build();
    }

    private static LegStrike legStrike(boolean lastStrike) {
        // 书目录的基础打击使用身前/身后、后腿和推开参数的完整显式值。
        return LegStrike.newBuilder()
                .setShape(StrikeShape.SECTOR)
                .setFacing(StrikeFacing.STRIKE_BACKWARD)
                .setDistance(quantity(LEG_DISTANCE, Unit.METER))
                .setAngle(quantity(LEG_ANGLE, Unit.DEGREE))
                .setMultiplier(quantity(LEG_MULTIPLIER, Unit.RATIO))
                .setMaximumTargets(MAXIMUM_TARGETS)
                .setCastTargetLimit(CAST_TARGET_LIMIT)
                .setLeg(LegSelection.REAR_LEG)
                .setDamageTypeId(DAMAGE_TYPE_ID)
                .setKnockbackKind(LegKnockbackKind.PUSH)
                .setKnockback(quantity(LEG_KNOCKBACK, Unit.RATIO))
                .setKnockbackWhenBlocked(false /* knockbackWhenBlocked */)
                .setLastStrike(lastStrike)
                .setDedupGroup(DEDUP_GROUP)
                .build();
    }

    private static LegRules legRules() {
        // 腿法书固定忽略双手物品、要求着地，并显式保存三项鞋参数。
        return LegRules.newBuilder()
                .setIgnoreHeldItems(true /* ignoreHeldItems */)
                .setRequireGrounded(true /* requireGrounded */)
                .setBaseDamage(quantity(BASE_DAMAGE, Unit.DAMAGE_POINT))
                .setArmorDamage(quantity(ARMOR_DAMAGE, Unit.DAMAGE_POINT))
                .setDurabilityCost(DURABILITY_COST)
                .build();
    }

    private static TechniqueCatalog legCatalog(TechniqueDefinition definition, LegRules rules) {
        // 书卷归属使用完整目录校验，避免单独调用参数 helper 漏掉书级约束。
        return catalog(definition).toBuilder()
                .addBooks(TechniqueBook.newBuilder()
                        .setId(LEG_BOOK_ID)
                        .setLegRules(rules)
                        .build())
                .addVolumes(volume(definition))
                .build();
    }

    private static TechniqueVolume volume(TechniqueDefinition definition) {
        // 单卷只收一式，足以建立 bookOf 的精确规则来源。
        return TechniqueVolume.newBuilder()
                .setId(LEG_VOLUME_ID)
                .setBookId(LEG_BOOK_ID)
                .addTechniques(TechniqueRegistry.reference(definition))
                .build();
    }

    private static AiHints aiHints() {
        // 新增三类事实只在战斗权重中起作用，最低腿力用牛顿表达。
        CombatAiHints combat = CombatAiHints.newBuilder()
                .setTargetBehindWeight(quantity(BEHIND_WEIGHT, Unit.RATIO))
                .setTargetAirborneWeight(quantity(AIRBORNE_WEIGHT, Unit.RATIO))
                .setSelfFleeingWeight(quantity(FLEEING_WEIGHT, Unit.RATIO))
                .setMinimumLegForce(quantity(MINIMUM_LEG_FORCE, Unit.NEWTON))
                .build();
        return AiHints.newBuilder()
                .setMinimumDistance(quantity(0, Unit.METER))
                .setMaximumDistance(quantity(10, Unit.METER))
                .setMaximumHpRatio(quantity(1, Unit.RATIO))
                .setMinimumQiRatio(quantity(0, Unit.RATIO))
                .setCategoryWeight(quantity(1, Unit.RATIO))
                .setCombat(combat)
                .build();
    }

    private static List<TechniqueHostFacts> blockedFacts() {
        // 每次只改变一个着地相关事实，验证错误原因不被水、攀爬或载具状态改写。
        return List.of(
                TechniqueHostFacts.newBuilder().setGrounded(false).setInWater(false).setClimbing(false)
                        .setRiding(false).setGliding(false).build(),
                TechniqueHostFacts.newBuilder().setGrounded(true).setInWater(true).setClimbing(false)
                        .setRiding(false).setGliding(false).build(),
                TechniqueHostFacts.newBuilder().setGrounded(true).setInWater(false).setClimbing(true)
                        .setRiding(false).setGliding(false).build(),
                TechniqueHostFacts.newBuilder().setGrounded(true).setInWater(false).setClimbing(false)
                        .setRiding(true).setGliding(false).build(),
                TechniqueHostFacts.newBuilder().setGrounded(true).setInWater(false).setClimbing(false)
                        .setRiding(false).setGliding(true).build());
    }

    private static BodySnapshot trainingBody() {
        // 测试身体的组织定义与训练目录一致，状态从零成长、零待巩固负荷开始。
        BodyMessages.BodySnapshot message = BodyProtobuf.toMessage(body(Shape.TENDRIL));
        BodyMessages.TissueDefinition tissue = message.getStructure().getTissues(0).toBuilder()
                .setTraining(BodyMessages.TissueTraining.newBuilder()
                        .setMaximumGrowth(TRAINING_MAXIMUM_GROWTH)
                        .setConsolidationHalfLifeSeconds(TRAINING_HALF_LIFE_SECONDS)
                        .setWorkJoulesPerGrowth(TRAINING_WORK_JOULES_PER_GROWTH)
                        .build())
                .build();
        BodyMessages.BodyStructure structure = message.getStructure().toBuilder()
                .setTissues(0, tissue)
                .build();
        return BodyProtobuf.fromMessage(message.toBuilder().setStructure(structure).build());
    }

    private static CastRequest request(TechniqueDefinition definition, BodySnapshot body, String castId) {
        // 请求引用与专测定义一致，避免复用普通样例的固定功法身份。
        return CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(TechniqueRegistry.reference(definition))
                .setCastId(castId)
                .build();
    }
}
