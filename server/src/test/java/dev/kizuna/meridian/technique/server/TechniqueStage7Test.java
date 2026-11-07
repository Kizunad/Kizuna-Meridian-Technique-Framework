package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.ProjectileTechniqueFixtures.*;
import static org.junit.Assert.*;

/** 射击目录、持物检查、锁定快照与真实字节宿主流程的回归。 */
public final class TechniqueStage7Test {
    private static final double MOVING_SPEED = 4;
    private static final double DISTANCE = 20;
    private static final double HIDDEN_WEIGHT = 2;
    private static final double MOVING_WEIGHT = 3;
    private static final double APPROACHED_WEIGHT = 4;
    private static final double AMMUNITION_WEIGHT = 5;
    private static final double RESERVE_WEIGHT = 6;
    private static final double LEG_FORCE = 100;
    private static final long WINDOW_START = WARMUP + ACTIVE + RECOVERY / 2;
    private static final long CAST_END = WARMUP + ACTIVE + RECOVERY;

    @Test
    public void rangedSelectionChecksWeaponOtherHandAndAmmunition() {
        // 四类业务失败须在费用之前返回对应原因，两手候选都按同一规则检查。
        TechniqueDefinition bow = ranged(ProjectileWeaponCategory.BOW);
        HeldItemRules rules = rules();
        HeldItems ready = ready(ProjectileWeaponCategory.BOW, Hand.MAIN_HAND);
        HeldItems crossbow = ready(ProjectileWeaponCategory.CROSSBOW, Hand.MAIN_HAND);
        assertEquals(Hand.MAIN_HAND, TechniqueHeldItems.select(rules, ready, bow));
        assertFailure(crossbow, bow, TechniqueFailure.BOW_REQUIRED);
        assertFailure(ready, ranged(ProjectileWeaponCategory.CROSSBOW), TechniqueFailure.CROSSBOW_REQUIRED);
        HeldItems shield = ready.toBuilder().setOffHand(item("sample:shield")).build();
        assertFailure(shield, bow, TechniqueFailure.OTHER_HAND_AMMUNITION_REQUIRED);
        HeldItems noAmmo = ready.toBuilder().setCanFindAmmunition(false /* canFindAmmunition */).build();
        assertFailure(noAmmo, bow, TechniqueFailure.AMMUNITION_REQUIRED);
        assertTrue(TechniqueHeldItems.find(rules, noAmmo, bow).isEmpty());
    }

    @Test
    public void offHandArrowsAndWeaponSpecificAmmunitionAreChecked() {
        // 副手可施放；烟花仅能配弩，弓的类别覆盖书级弹药标签。
        TechniqueDefinition bow = ranged(ProjectileWeaponCategory.BOW);
        HeldItemRules rules = rules();
        HeldItems off = ready(ProjectileWeaponCategory.BOW, Hand.OFF_HAND);
        assertEquals(Optional.of(Hand.OFF_HAND), TechniqueHeldItems.find(rules, off, bow));
        HeldItem arrow = item("sample:arrow", AMMO_TAG, ARROW_TAG);
        HeldItems arrows = off.toBuilder().setMainHand(arrow).build();
        assertEquals(Hand.OFF_HAND, TechniqueHeldItems.select(rules, arrows, bow));
        HeldItem firework = item("sample:firework", AMMO_TAG);
        HeldItems wrongAmmo = off.toBuilder().setMainHand(firework).build();
        assertFailure(wrongAmmo, bow, TechniqueFailure.OTHER_HAND_AMMUNITION_REQUIRED);
        HeldItems crossbow = ready(ProjectileWeaponCategory.CROSSBOW, Hand.OFF_HAND).toBuilder()
                .setMainHand(firework)
                .build();
        assertEquals(Hand.OFF_HAND, TechniqueHeldItems.select(rules, crossbow,
                ranged(ProjectileWeaponCategory.CROSSBOW)));
    }

    @Test
    public void missingInspectionAndMislabelledWeaponsNeverUseCatalogFallback() {
        // 标签不能替宿主生成武器事实，宿主误报也不能绕过书级标签。
        TechniqueDefinition bow = ranged(ProjectileWeaponCategory.BOW);
        HeldItemRules rules = rules();
        HeldItems ready = ready(ProjectileWeaponCategory.BOW, Hand.MAIN_HAND);
        HeldItems missing = ready.toBuilder().clearMainHandProjectileWeapon().build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueHeldItems.select(rules, missing, bow));
        HeldItems noAmmoFact = ready.toBuilder().clearCanFindAmmunition().build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueHeldItems.select(rules, noAmmoFact, bow));
        HeldItems wrongTag = ready.toBuilder().setMainHand(item("sample:stick")).build();
        assertFailure(wrongTag, bow, TechniqueFailure.BOW_REQUIRED);
    }

    @Test
    public void dryDrawOverridesOnlyAmmunitionAndKeepsOtherHandRule() {
        // 不射击的训练式免弹药但仍要弓和空闲另一手，零倍率使训练期间静止。
        TechniqueDefinition dry = ranged(ProjectileWeaponCategory.BOW).toBuilder()
                .clearEffects()
                .setRequireAmmunition(false /* requireAmmunition */)
                .setMovementSpeedMultiplier(0)
                .build();
        HeldItems missing = ready(ProjectileWeaponCategory.BOW, Hand.OFF_HAND).toBuilder()
                .setCanFindAmmunition(false /* canFindAmmunition */)
                .build();
        MemoryHost host = host(dry, missing);
        CastOutcome outcome = new TechniqueCasting(registryOf(dry)).cast(host, request(host.current(), "dry"));
        assertEquals(CastPhase.WARMUP, outcome.getPhase());
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        assertEquals(0, active.getMovementSpeedMultiplier(), 0);
        assertTrue(TechniqueScheduler.effectsAt(dry, active, 0).stream().noneMatch(EffectIntent::hasLaunchProjectile));
        HeldItems shield = missing.toBuilder().setMainHand(item("sample:shield")).build();
        assertFailure(shield, dry, TechniqueFailure.OTHER_HAND_AMMUNITION_REQUIRED);
    }

    @Test
    public void rejectedCastDoesNotSpendAndOffHandCastUsesFrozenHand() {
        // 真实起手先检查弹药再提交 Framework 活动，成功后手位进入快照。
        TechniqueDefinition bow = ranged(ProjectileWeaponCategory.BOW);
        HeldItems missing = ready(ProjectileWeaponCategory.BOW, Hand.OFF_HAND).toBuilder()
                .setCanFindAmmunition(false /* canFindAmmunition */)
                .build();
        MemoryHost host = host(bow, missing);
        BodySnapshot before = host.current();
        TechniqueCasting casting = new TechniqueCasting(registryOf(bow));
        assertEquals(TechniqueFailure.AMMUNITION_REQUIRED,
                casting.cast(host, request(before, "empty")).getFailure());
        assertEquals(before, host.current());
        assertEquals(0, host.activitySubmissions);
        host.heldItems = ready(ProjectileWeaponCategory.BOW, Hand.OFF_HAND);
        assertEquals(CastPhase.WARMUP, casting.cast(host, request(before, "ready")).getPhase());
        assertEquals(Hand.OFF_HAND, TechniqueState.read(host.current()).getActiveCast().getSelectedHand());
        assertEquals(1, host.activitySubmissions);
    }

    @Test
    public void projectileDefinitionAndEffectProtocolRejectMismatchedWeapon() {
        // 式级类别必须等于每发类别，宿主边界继续拒绝非法参数与缺少手位。
        TechniqueDefinition bow = ranged(ProjectileWeaponCategory.BOW);
        assertEquals(bow, registryOf(bow).find(TechniqueRegistry.reference(bow)).orElseThrow());
        TechniqueDefinition wrong = bow.toBuilder().setProjectileWeapon(ProjectileWeaponCategory.CROSSBOW).build();
        assertThrows(IllegalArgumentException.class, () -> registryOf(wrong));
        LaunchProjectile projectile = projectile(ProjectileWeaponCategory.BOW);
        EffectIntent intent = intent(projectile);
        TechniqueProtocol.validateEffect(intent);
        EffectIntent noHand = intent.toBuilder().clearSelectedHand().build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(noHand));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueProjectiles.validate(projectile.toBuilder()
                        .clearCritical()
                        .build()));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueProjectiles.validate(projectile.toBuilder()
                        .setPiercing(Byte.MAX_VALUE + 1)
                        .build()));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueProjectiles.validate(projectile.toBuilder()
                        .clearDrawStrengthArm()
                        .build()));
    }

    @Test
    public void lobOffsetAndConvergenceRequireCompleteConsistentParameters() {
        // 四种方向都过目录边界，汇聚必须有真实后续平射和锁定范围。
        LaunchProjectile base = projectile(ProjectileWeaponCategory.BOW);
        LaunchProjectile offset = base.toBuilder()
                .setLookOffset(quantity(OFFSET, Unit.DEGREE))
                .setMirrorByHand(true /* mirrorByHand */)
                .setTargetSnapAngle(quantity(SNAP, Unit.DEGREE))
                .build();
        TechniqueProjectiles.validate(offset);
        TechniqueProjectiles.validate(base.toBuilder().setLob(lob()).build());
        TechniqueDefinition convergence = convergence();
        TechniqueRegistry registry = registryOf(convergence);
        assertEquals(convergence, registry.find(TechniqueRegistry.reference(convergence)).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> registryOf(convergence.toBuilder().clearLockTarget().build()));
        assertThrows(IllegalArgumentException.class,
                () -> registryOf(convergence.toBuilder().removeEffects(1).build()));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueProjectiles.validate(base.toBuilder().setLook(false /* look */).build()));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueProjectiles.validate(base.toBuilder().setMirrorByHand(true /* mirrorByHand */).build()));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueProjectiles.validate(base.toBuilder()
                        .setLob(LobProjectile.getDefaultInstance())
                        .build()));
    }

    @Test
    public void lockTargetIsFrozenInActiveCastAndProjectileEffect() {
        // 起手锁定先于任何射击，迟到调度与重复 tick 保留原时刻和幂等身份。
        TechniqueDefinition definition = convergence();
        MemoryHost host = host(definition, ready(ProjectileWeaponCategory.BOW, Hand.OFF_HAND));
        TechniqueService service = HeldTechniqueFixtures.service(host, registryOf(definition));
        service.cast(HeldTechniqueFixtures.ENTITY, request(host.current(), "locked"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        assertTrue(active.getLockTarget());
        assertEquals(quantity(RANGE, Unit.METER), active.getTargetLockDistance());
        assertEquals(1, host.effects.size());
        assertTrue(host.effects.getFirst().hasTargetLock());
        host.nowMillis = REFERENCE_SHOT + 1;
        service.tick();
        List<EffectIntent> shots = host.effects.stream().filter(EffectIntent::hasLaunchProjectile).toList();
        assertEquals(2, shots.size());
        assertEquals(WARMUP, shots.getFirst().getScheduledMillis());
        assertTrue(shots.getFirst().getLockTarget());
        assertEquals(Hand.OFF_HAND, shots.getFirst().getSelectedHand());
        int delivered = host.effects.size();
        service.tick();
        assertEquals(delivered, host.effects.size());
    }

    @Test
    public void convergenceKeepsOriginalReferenceAcrossFollowUpProjection() {
        // 汇聚参考保持原剪辑坐标，意图携带承接偏移和新起手时刻供宿主计算。
        TechniqueDefinition definition = convergence();
        ActiveCast active = ActiveCast.newBuilder()
                .setRequest(request(body(Shape.TENDRIL), "projected"))
                .setStartedMillis(CAST_END)
                .setWarmupEndMillis(CAST_END + WARMUP - CLIP_SKIP)
                .setActiveEndMillis(CAST_END + REFERENCE_SHOT - CLIP_SKIP)
                .setRecoveryEndMillis(CAST_END + CAST_END - CLIP_SKIP)
                .setClipStartMillis(CLIP_SKIP)
                .setSelectedHand(Hand.MAIN_HAND)
                .setHeldItemRules(rules())
                .setLockTarget(true /* lockTarget */)
                .setTargetLockDistance(quantity(RANGE, Unit.METER))
                .build();
        long firstShot = CAST_END + WARMUP - CLIP_SKIP;
        EffectIntent shot = TechniqueScheduler.effectsAt(definition, active, firstShot).getFirst();
        TechniqueProtocol.validateEffect(shot);
        assertEquals(firstShot, shot.getScheduledMillis());
        assertEquals(CAST_END, shot.getLaunchProjectile().getCastStartedMillis());
        assertEquals(CLIP_SKIP, shot.getLaunchProjectile().getClipStartMillis());
        assertEquals(REFERENCE_SHOT, shot.getLaunchProjectile().getProjectile().getConverge().getReferenceShotMillis());
    }

    @Test
    public void rangedAiContextKeepsDistanceVisibilityVelocityAndReserveWeaponFacts() {
        // 同一次观察的远程权重只乘一次；没有观察时远程式不能混入候选。
        TechniqueDefinition definition = aiDefinition();
        TechniqueRegistry registry = registryOf(definition);
        KnownTechnique known = progress(definition).getKnown(0);
        BindingOutcome binding = TechniqueBinder.bind(body(Shape.TENDRIL), definition);
        var availability = new TechniqueSelector.Availability(
                Map.of(known.getTechnique(), binding), Set.of(known.getTechnique()), Map.of());
        var situation = new TechniqueSelector.Situation(0, 1, 1, DISTANCE,
                TechniqueSelector.TargetState.ENGAGED, LEG_FORCE);
        var observed = new TechniqueSelector.TargetContext(TargetDistanceBand.FAR,
                TechniqueSelector.Visibility.HIDDEN, MOVING_SPEED, TechniqueSelector.Approach.APPROACHING,
                TechniqueSelector.Ammunition.AVAILABLE, Set.of(ProjectileWeaponCategory.BOW));
        var neutral = new TechniqueSelector.TargetContext(TargetDistanceBand.FAR,
                TechniqueSelector.Visibility.VISIBLE, 0, TechniqueSelector.Approach.NOT_APPROACHING,
                TechniqueSelector.Ammunition.UNAVAILABLE, Set.of());
        var weighted = new TechniqueSelector.Context(registry, situation, availability, combat(observed));
        var baseline = new TechniqueSelector.Context(registry, situation, availability, combat(neutral));
        double expected = TechniqueSelector.score(baseline, known) * HIDDEN_WEIGHT * MOVING_WEIGHT
                * APPROACHED_WEIGHT * AMMUNITION_WEIGHT * RESERVE_WEIGHT;
        assertTrue(expected > 0);
        assertEquals(expected, TechniqueSelector.score(weighted, known), 0);
        var missing = new TechniqueSelector.Context(registry, situation, availability);
        assertEquals(0, TechniqueSelector.score(missing, known), 0);
        assertEquals(LEG_FORCE, situation.effectiveLegForceNewtons(), 0);
    }

    @Test
    public void crossWeaponFollowUpRequiresMatchingWeaponGroups() {
        // 类别在目录加载时验证，运行时使用精确前式版本与窗口边界。
        TechniqueDefinition bow = ranged(ProjectileWeaponCategory.BOW);
        TechniqueDefinition crossbow = followUp(bow);
        TechniqueRegistry registry = registryOf(bow, crossbow);
        assertEquals(crossbow, registry.find(TechniqueRegistry.reference(crossbow)).orElseThrow());
        FollowUp transition = crossbow.getFollowUp().getTransitions(0);
        FollowUp missing = transition.toBuilder().clearPreviousProjectileWeapon().clearNextProjectileWeapon().build();
        FollowUpRules brokenRules = FollowUpRules.newBuilder().addTransitions(missing).build();
        TechniqueDefinition broken = crossbow.toBuilder().setFollowUp(brokenRules).build();
        assertThrows(IllegalArgumentException.class, () -> registryOf(bow, broken));
        LastCast previous = LastCast.newBuilder()
                .setTechnique(TechniqueRegistry.reference(bow))
                .setCastId("previous")
                .setActiveEndMillis(WARMUP + ACTIVE)
                .setRecoveryEndMillis(CAST_END)
                .setEnding(CastPhase.FINISHED)
                .build();
        TechniqueProgress progress = TechniqueState.empty().toBuilder().setLastCast(previous).build();
        assertTrue(TechniqueFollowUps.available(crossbow, progress, CAST_END).isPresent());
        assertTrue(TechniqueFollowUps.available(crossbow, progress, CAST_END + WINDOW_AFTER + 1).isEmpty());
    }

    @Test
    public void switchingWeaponInWindowRechecksCurrentHandBeforePaying() {
        // 换兵沿用单次原子承接，未换武器时失败且不破坏前式，换后重新选择副手。
        TechniqueDefinition bow = ranged(ProjectileWeaponCategory.BOW);
        TechniqueDefinition crossbow = followUp(bow);
        TechniqueProgress known = progress(bow).toBuilder().addAllKnown(progress(crossbow).getKnownList()).build();
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), known));
        host.heldItems = ready(ProjectileWeaponCategory.BOW, Hand.MAIN_HAND);
        TechniqueService service = HeldTechniqueFixtures.service(host, registryOf(bow, crossbow));
        service.cast(HeldTechniqueFixtures.ENTITY, request(host.current(), "before"));
        host.nowMillis = WINDOW_START;
        service.tick();
        CastRequest next = request(host.current(), "after").toBuilder()
                .setTechnique(TechniqueRegistry.reference(crossbow))
                .setEntry(CastEntry.FOLLOW_UP)
                .build();
        int payments = host.activitySubmissions;
        assertEquals(TechniqueFailure.CROSSBOW_REQUIRED, service.cast(HeldTechniqueFixtures.ENTITY, next).getFailure());
        assertEquals(payments, host.activitySubmissions);
        host.heldItems = ready(ProjectileWeaponCategory.CROSSBOW, Hand.OFF_HAND);
        assertEquals(CastPhase.WARMUP, service.cast(HeldTechniqueFixtures.ENTITY, next).getPhase());
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        assertEquals(CLIP_SKIP, active.getClipStartMillis());
        assertEquals(Hand.OFF_HAND, active.getSelectedHand());
        assertEquals(payments + 1, host.activitySubmissions);
    }

    private static void assertFailure(HeldItems items, TechniqueDefinition definition, TechniqueFailure failure) {
        // 断言业务拒绝而非解析错误，保持玩家失败文本的契约。
        TechniqueErrors.Rejected rejected = assertThrows(TechniqueErrors.Rejected.class,
                () -> TechniqueHeldItems.select(rules(), items, definition));
        assertEquals(failure, rejected.code);
    }

    @Test
    public void explicitDamageTypesRequireBothHandsAndDisableTwoHandedStrikes() {
        // 弓梢的完整伤害身份成对传到宿主，缺副手或误开双手均在目录边界拒绝。
        WeaponStrike original = CombatTechniqueFixtures.first().getEffectsList().stream()
                .filter(TechniqueEffect::hasWeaponStrike)
                .map(TechniqueEffect::getWeaponStrike)
                .findFirst().orElseThrow();
        WeaponStrike explicit = original.toBuilder()
                .setMainHandDamageTypeId(MAIN_DAMAGE)
                .setOffHandDamageTypeId(OFF_DAMAGE)
                .setCanTwoHand(false /* canTwoHand */)
                .setTwoHandMultiplier(quantity(1, Unit.RATIO))
                .build();
        TechniqueCombatEffects.validateStrike(explicit);
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueCombatEffects.validateStrike(explicit.toBuilder().clearOffHandDamageTypeId().build()));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueCombatEffects.validateStrike(explicit.toBuilder()
                        .setCanTwoHand(true /* canTwoHand */)
                        .build()));
    }

    @Test
    public void invalidRangesAndUnknownEnumsFailCatalogAndWireValidation() {
        // 非有限数、错单位、负时刻与未知枚举不能穿过任一协议边界。
        LaunchProjectile base = projectile(ProjectileWeaponCategory.BOW);
        List<LaunchProjectile> invalid = List.of(
                base.toBuilder().setSpeedMultiplier(quantity(Double.NaN, Unit.RATIO)).build(),
                base.toBuilder().setDamageMultiplier(quantity(1, Unit.METER)).build(),
                base.toBuilder().setShieldCooldownMillis(-1).build(),
                base.toBuilder().setProjectileWeaponValue(Integer.MAX_VALUE).build(),
                base.toBuilder().setDrawStrengthArmValue(Integer.MAX_VALUE).build(),
                base.toBuilder().clearDirection().build());
        for (LaunchProjectile projectile : invalid) {
            TechniqueDefinition definition = ranged(ProjectileWeaponCategory.BOW).toBuilder()
                    .clearEffects()
                    .addEffects(shot(projectile, WARMUP))
                    .build();
            assertThrows(IllegalArgumentException.class, () -> registryOf(definition));
            assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(intent(projectile)));
        }
    }

    private static TechniqueRegistry registryOf(TechniqueDefinition... definitions) {
        // 正常与错误目录都走完整公开注册入口。
        return TechniqueRegistry.load(addons(), List.of(archery(definitions)));
    }

    @Test
    public void relativeArmBindingUsesSelectedWeaponHandAndActualDominantSide() {
        // 左右主手与持武器手的四种组合都选择另一条臂，公开预览与真实起手一致。
        RelativeArmSlot arm = RelativeArmSlot.newBuilder()
                .setArm(DrawStrengthArm.OTHER_ARM)
                .addLeftModuleIds("a")
                .addRightModuleIds("b")
                .build();
        TechniqueDefinition original = ranged(ProjectileWeaponCategory.BOW);
        TechniqueSlot slot = original.getSlots(1).toBuilder().setRelativeArm(arm).build();
        TechniqueDefinition definition = original.toBuilder().setSlots(1, slot).build();
        TechniqueRegistry registry = registryOf(definition);
        BodySnapshot prepared = withProgress(body(Shape.PAIRED), progress(definition));
        assertEquals(TechniqueFailure.INVALID_REQUEST, TechniqueBinder.bind(prepared, definition).getFailure());
        List<ArmCase> cases = List.of(new ArmCase(Hand.MAIN_HAND, HandSide.RIGHT, "a"),
                new ArmCase(Hand.OFF_HAND, HandSide.RIGHT, "b"),
                new ArmCase(Hand.MAIN_HAND, HandSide.LEFT, "b"),
                new ArmCase(Hand.OFF_HAND, HandSide.LEFT, "a"));
        for (ArmCase variant : cases) {
            BindingOutcome preview = TechniqueBinder.bind(prepared, definition, variant.hand(), variant.side());
            assertEquals(List.of(variant.moduleId()), preview.getBinding().getSlots(1).getModuleIdsList());
            MemoryHost host = new MemoryHost(prepared);
            host.heldItems = ready(ProjectileWeaponCategory.BOW, variant.hand()).toBuilder()
                    .setMainHandSide(variant.side())
                    .build();
            CastOutcome cast = new TechniqueCasting(registry).cast(host, request(prepared, "relative"));
            assertEquals(CastPhase.WARMUP, cast.getPhase());
            assertEquals(preview.getBinding(), cast.getBinding());
        }
    }

    @Test
    public void relativeArmCatalogRejectsUnknownAndOverlappingTissues() {
        // 左右表不能凭空引用组织，也不能把同一组织声明在两侧。
        TechniqueDefinition original = ranged(ProjectileWeaponCategory.BOW);
        for (String rightId : List.of("a", "missing")) {
            RelativeArmSlot arm = RelativeArmSlot.newBuilder()
                    .setArm(DrawStrengthArm.OTHER_ARM)
                    .addLeftModuleIds("a")
                    .addRightModuleIds(rightId)
                    .build();
            TechniqueSlot slot = original.getSlots(1).toBuilder().setRelativeArm(arm).build();
            TechniqueDefinition invalid = original.toBuilder().setSlots(1, slot).build();
            assertThrows(IllegalArgumentException.class, () -> registryOf(invalid));
        }
    }

    private record ArmCase(Hand hand, HandSide side, String moduleId) { }

    private static MemoryHost host(TechniqueDefinition definition, HeldItems items) {
        // 费用与进度使用真实 Framework，只有实体观察由测试注入。
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), progress(definition)));
        host.heldItems = items;
        return host;
    }

    private static EffectIntent intent(LaunchProjectile projectile) {
        // 消息先分层构造，再交给公开协议边界验证。
        LaunchProjectileIntent launch = LaunchProjectileIntent.newBuilder()
                .setProjectile(projectile)
                .setHeldItemRules(rules())
                .build();
        return EffectIntent.newBuilder()
                .setCastId("projectile")
                .setEffectId("shot")
                .setSelectedHand(Hand.MAIN_HAND)
                .setScheduledMillis(WARMUP)
                .setLaunchProjectile(launch)
                .build();
    }

    private static TechniqueDefinition convergence() {
        // 同一式先仰射后平射，参考倍率与出力臂必须完全一致。
        LaunchProjectile base = projectile(ProjectileWeaponCategory.BOW);
        LaunchProjectile high = base.toBuilder().setConverge(converge()).build();
        return ranged(ProjectileWeaponCategory.BOW).toBuilder()
                .clearEffects()
                .addEffects(shot(high, WARMUP))
                .addEffects(shot(base, REFERENCE_SHOT))
                .setLockTarget(true /* lockTarget */)
                .setTargetLockDistance(quantity(RANGE, Unit.METER))
                .build();
    }

    private static TechniqueSelector.CombatContext combat(TechniqueSelector.TargetContext target) {
        // 远程事实单独保存，避免在离散事实集合中再次乘相同权重。
        return new TechniqueSelector.CombatContext(Set.of(), TechniqueState.empty(), Optional.of(target));
    }

    private static TechniqueDefinition aiDefinition() {
        // 各项权重使用不同值，缺项或重复相乘都会改变最终分数。
        CombatAiHints combat = CombatAiHints.newBuilder()
                .setTargetDistanceBand(TargetDistanceBand.FAR)
                .setTargetHiddenWeight(quantity(HIDDEN_WEIGHT, Unit.RATIO))
                .setTargetMovingWeight(quantity(MOVING_WEIGHT, Unit.RATIO))
                .setSelfApproachedWeight(quantity(APPROACHED_WEIGHT, Unit.RATIO))
                .setAmmunitionWeight(quantity(AMMUNITION_WEIGHT, Unit.RATIO))
                .setReserveWeaponWeight(quantity(RESERVE_WEIGHT, Unit.RATIO))
                .build();
        AiHints hints = AiHints.newBuilder()
                .setMinimumDistance(quantity(0, Unit.METER))
                .setMaximumDistance(quantity(RANGE, Unit.METER))
                .setMaximumHpRatio(quantity(1, Unit.RATIO))
                .setMinimumQiRatio(quantity(0, Unit.RATIO))
                .setCategoryWeight(quantity(1, Unit.RATIO))
                .setCombat(combat)
                .build();
        return ranged(ProjectileWeaponCategory.BOW).toBuilder().setAiHints(hints).build();
    }

    private static TechniqueDefinition followUp(TechniqueDefinition bow) {
        // 换兵规则同时声明前后类别与精确前式，跳过的预备段保持小于起手。
        FollowUp transition = FollowUp.newBuilder()
                .setPrevious(TechniqueRegistry.reference(bow))
                .setRecoveryStartRatio(quantity(WINDOW_RATIO, Unit.RATIO))
                .setAfterRecoveryMillis(WINDOW_AFTER)
                .setStartMillis(CLIP_SKIP)
                .setPreviousProjectileWeapon(ProjectileWeaponCategory.BOW)
                .setNextProjectileWeapon(ProjectileWeaponCategory.CROSSBOW)
                .build();
        FollowUpRules rules = FollowUpRules.newBuilder().addTransitions(transition).build();
        return ranged(ProjectileWeaponCategory.CROSSBOW).toBuilder()
                .setId("sample:crossbow")
                .setFollowUp(rules)
                .build();
    }
}
