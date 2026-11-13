package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

/** 肢击算子、拳掌书级规则、腿击换算和对方状态情境的回归。 */
public final class TechniqueLimbStrikeTest {
    private static final String ARM_ID = "sample:fist";
    private static final String ARM_BOOK_ID = "sample:fist_book";
    private static final String ARM_VOLUME_ID = "sample:fist_volume";
    private static final String DAMAGE_TYPE_ID = "sample:fist_torso";
    private static final String DEDUP_GROUP = "fist";
    private static final String HIT_SOUND_ID = "sample:fist_hit";
    private static final String PEDDLER_RESOURCE = "/peddler_leg_strikes.txtpb";
    private static final int PEDDLER_STRIKES = 13;
    private static final double STRIKE_DISTANCE = 2;
    private static final double SINGLE_TARGET_ANGLE = 60;
    private static final double STRIKE_MULTIPLIER = 1.4;
    private static final double PUSH = 0.6;
    private static final double LIFT_HORIZONTAL = 0.3;
    private static final double LIFT_SPEED = 8;
    private static final double BASE_DAMAGE = 1.5;
    private static final double ARMOR_DAMAGE = 0.5;
    private static final int DURABILITY_COST = 1;
    private static final int TWO_TARGETS = 2;
    private static final double CASTING_WEIGHT = 2;
    private static final double DOWNED_WEIGHT = 3;
    private static final double ARMORED_WEIGHT = 5;
    private static final double NEGATIVE_WEIGHT = -1;
    private static final double TOLERANCE = 1e-9;

    @Test
    public void limbStrikeKeepsLegFieldNumbersNamesAndTypes() {
        // 换算靠线格式重读，腿击的每个字段都必须在肢击里同号、同名、同类型。
        assertWireCompatible(LegStrike.getDefaultInstance(), LimbStrike.getDefaultInstance());
        assertWireCompatible(LegStrikeIntent.getDefaultInstance(), LimbStrikeIntent.getDefaultInstance());
    }

    @Test
    public void peddlerLegIntentsConvertFieldByField() throws IOException {
        // 千里腿目录的每一下腿击照调度器的拼法组成意图，换算后逐字段对得上、仍能通过肢击校验。
        TechniqueCatalog catalog = peddlerCatalog();
        LegRules rules = catalog.getBooks(0).getLegRules();
        int converted = 0;
        for (TechniqueDefinition definition : catalog.getDefinitionsList()) {
            for (int index = 0; index < definition.getEffectsCount(); index++) {
                LegStrike strike = definition.getEffects(index).getLegStrike();
                LegStrikeIntent intent = LegStrikeIntent.newBuilder()
                        .setStrike(strike)
                        .setLegRules(rules)
                        .addAllHitSounds(TechniqueSoundCues.hitSounds(definition, index))
                        .build();
                assertConverted(intent);
                converted++;
            }
        }
        assertEquals(PEDDLER_STRIKES, converted);
    }

    @Test
    public void fullyDeclaredLegIntentKeepsHitSoundsAndRules() {
        // 合成样例把腿击的可选字段和命中声音都填上，换算后一样不少。
        LegStrike strike = LegStrike.newBuilder()
                .setShape(StrikeShape.SECTOR)
                .setFacing(StrikeFacing.STRIKE_BACKWARD)
                .setDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setAngle(quantity(SINGLE_TARGET_ANGLE, Unit.DEGREE))
                .setMultiplier(quantity(STRIKE_MULTIPLIER, Unit.RATIO))
                .setMaximumTargets(TWO_TARGETS)
                .setCastTargetLimit(TWO_TARGETS)
                .setLeg(LegSelection.BOTH_LEGS)
                .setDamageTypeId(DAMAGE_TYPE_ID)
                .setKnockbackKind(LegKnockbackKind.TRIP)
                .setKnockback(quantity(PUSH, Unit.RATIO))
                .setKnockbackWhenBlocked(false /* knockbackWhenBlocked */)
                .setLastStrike(false /* lastStrike */)
                .setDedupGroup(DEDUP_GROUP)
                .build();
        LegStrikeIntent intent = LegStrikeIntent.newBuilder()
                .setStrike(strike)
                .setLegRules(legRules())
                .addHitSounds(hitSound())
                .build();
        LimbStrikeIntent limb = assertConverted(intent);
        assertEquals(List.of(hitSound()), limb.getHitSoundsList());
    }

    @Test
    public void limbStrikeRejectsMissingLimbAndMismatchedLift() {
        // 出力肢必填；掀起要竖直速度、水平分量可为零；其余击退不能带竖直速度。
        TechniqueLimbStrikes.validate(armStrike());
        TechniqueLimbStrikes.validate(liftStrike(0));
        TechniqueLimbStrikes.validate(liftStrike(LIFT_HORIZONTAL));
        assertInvalid(armStrike().toBuilder().clearLimb().build());
        assertInvalid(armStrike().toBuilder().setArm(ArmSelection.ARM_SELECTION_UNSPECIFIED).build());
        assertInvalid(liftStrike(0).toBuilder().clearLiftSpeed().build());
        assertInvalid(armStrike().toBuilder().setLiftSpeed(quantity(LIFT_SPEED, Unit.METERS_PER_SECOND)).build());
        assertInvalid(armStrike().toBuilder().setKnockbackKind(LegKnockbackKind.NONE).build());
        LimbStrike singleWithTwoTargets = armStrike().toBuilder()
                .setMaximumTargets(TWO_TARGETS)
                .setCastTargetLimit(TWO_TARGETS)
                .build();
        assertInvalid(singleWithTwoTargets);
        assertInvalid(armStrike().toBuilder().clearKnockbackWhenBlocked().build());

        LegStrike liftingLeg = legStrikeTemplate().toBuilder()
                .setKnockbackKind(LegKnockbackKind.LIFT)
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueCombatEffects.validateLegStrike(liftingLeg));
    }

    @Test
    public void armRulesRequireBothFlagsAndPositiveBaseDamage() {
        // 两个开关必须显式为 true，空手基础伤害必须大于零。
        TechniqueArmRules.validate(armRules());
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueArmRules.validate(armRules().toBuilder()
                        .setIgnoreHeldItems(false /* ignoreHeldItems */)
                        .build()));
        assertThrows(IllegalArgumentException.class,
                () -> TechniqueArmRules.validate(armRules().toBuilder().clearRequireGrounded().build()));
        assertThrows(IllegalArgumentException.class, () -> TechniqueArmRules.validate(
                armRules().toBuilder().setBaseDamage(quantity(0, Unit.DAMAGE_POINT)).build()));
    }

    @Test
    public void armStrikeSchedulesBookArmRulesAndHitSounds() {
        // 出拳的意图带所属式、起手保存的拳掌规则和同刻命中声音，不带腿法规则和出手手位。
        TechniqueDefinition definition = armDefinition();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(armCatalog(definition, armBook())));
        BodySnapshot prepared = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(prepared);
        CastOutcome started = new TechniqueCasting(registry).cast(host, request(definition, prepared, "fist"));
        ActiveCast active = TechniqueState.read(host.current()).getActiveCast();
        List<EffectIntent> effects = TechniqueScheduler.effectsAt(definition, active, 0);
        assertEquals(CastPhase.WARMUP, started.getPhase());
        assertEquals(armRules(), active.getArmRules());
        assertFalse(active.hasLegRules());
        assertEquals(1, effects.size());
        EffectIntent intent = effects.get(0);
        assertEquals(Hand.HAND_UNSPECIFIED, intent.getSelectedHand());
        assertEquals(TechniqueRegistry.reference(definition), intent.getTechnique());
        assertEquals(armRules(), intent.getLimbStrike().getArmRules());
        assertFalse(intent.getLimbStrike().hasLegRules());
        assertEquals(List.of(hitSound()), intent.getLimbStrike().getHitSoundsList());
        TechniqueProtocol.validateEffect(intent);
        EffectIntent wrongRules = intent.toBuilder()
                .setLimbStrike(intent.getLimbStrike().toBuilder().setLegRules(legRules()))
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(wrongRules));
    }

    @Test
    public void armStrikeNeedsArmBookWithoutHeldRulesAndNoLegMix() {
        // 出拳要书声明拳掌规则；拳掌书不能声明持物规则；同一式不能混用腿击和肢击。
        TechniqueDefinition definition = armDefinition();
        TechniqueBook noRules = armBook().toBuilder().clearArmRules().build();
        TechniqueBook held = armBook().toBuilder().setHeldItemRules(HeldTechniqueFixtures.rules()).build();
        TechniqueEffect leg = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setLegStrike(legStrikeTemplate())
                .build();
        TechniqueDefinition mixed = definition.toBuilder().addEffects(leg).build();
        TechniqueBook bothRules = armBook().toBuilder().setLegRules(legRules()).build();
        assertLoadFails(armCatalog(definition, noRules));
        assertLoadFails(armCatalog(definition, held));
        assertLoadFails(armCatalog(mixed, bothRules));
    }

    @Test
    public void armCastOffGroundUsesFistReasonAndPaysNothing() {
        // 不着地时出拳返回 NOT_GROUNDED 和出拳的说法，不扣费也不写活动。
        TechniqueDefinition definition = armDefinition();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(armCatalog(definition, armBook())));
        BodySnapshot prepared = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(prepared);
        host.inspectFacts = TechniqueInspection.defaults().toBuilder()
                .setGrounded(false /* grounded */)
                .build();
        CastOutcome refused = new TechniqueCasting(registry).cast(host, request(definition, prepared, "airborne"));
        assertEquals(TechniqueFailure.NOT_GROUNDED, refused.getFailure());
        assertEquals("要站在地上才能出拳", refused.getReason());
        assertEquals(0, host.activitySubmissions);
    }

    @Test
    public void targetStateFactsMultiplyTheirWeights() {
        // 对方起手、倒地、局部护甲高三个情境的权重与已有情境一样相乘。
        CombatAiHints combat = CombatAiHints.newBuilder()
                .setTargetCastingWeight(quantity(CASTING_WEIGHT, Unit.RATIO))
                .setTargetDownedWeight(quantity(DOWNED_WEIGHT, Unit.RATIO))
                .setTargetArmoredWeight(quantity(ARMORED_WEIGHT, Unit.RATIO))
                .build();
        TechniqueDefinition definition = armDefinition().toBuilder()
                .setCategory(Category.ATTACK)
                .setAiHints(aiHints(combat))
                .build();
        TechniqueRegistry registry = TechniqueRegistry.load(addons(), List.of(armCatalog(definition, armBook())));
        TechniqueReference reference = TechniqueRegistry.reference(definition);
        var availability = new TechniqueSelector.Availability(
                Map.of(reference, TechniqueBinder.bind(body(Shape.TENDRIL), definition)), Set.of(reference), Map.of());
        KnownTechnique known = progress(definition).getKnown(0);
        var situation = new TechniqueSelector.Situation(0, 1, 1, 1, TechniqueSelector.TargetState.ENGAGED, 0);
        Set<TechniqueSelector.CombatFact> facts = Set.of(TechniqueSelector.CombatFact.TARGET_CASTING,
                TechniqueSelector.CombatFact.TARGET_DOWNED, TechniqueSelector.CombatFact.TARGET_ARMORED);
        var plain = new TechniqueSelector.Context(registry, situation, availability);
        var weighted = new TechniqueSelector.Context(registry, situation, availability,
                new TechniqueSelector.CombatContext(facts, TechniqueState.empty()));
        double base = TechniqueSelector.score(plain, known);
        assertTrue(base > 0);
        assertEquals(base * CASTING_WEIGHT * DOWNED_WEIGHT * ARMORED_WEIGHT,
                TechniqueSelector.score(weighted, known), TOLERANCE);
        CombatAiHints negative = combat.toBuilder()
                .setTargetDownedWeight(quantity(NEGATIVE_WEIGHT, Unit.RATIO))
                .build();
        TechniqueDefinition invalid = definition.toBuilder().setAiHints(aiHints(negative)).build();
        assertLoadFails(armCatalog(invalid, armBook()));
    }

    private static LimbStrikeIntent assertConverted(LegStrikeIntent intent) {
        // 打击逐字段比较出现性和取值，书级鞋规则和命中声音原样带过去，并通过肢击意图校验。
        LimbStrikeIntent limb = TechniqueLimbStrikes.fromLeg(intent);
        assertSameFields(intent.getStrike(), limb.getStrike());
        assertEquals(LimbStrike.LimbCase.LEG, limb.getStrike().getLimbCase());
        assertEquals(intent.getLegRules(), limb.getLegRules());
        assertEquals(intent.getHitSoundsList(), limb.getHitSoundsList());
        assertFalse(limb.hasArmRules());
        TechniqueLimbStrikes.validateIntent(limb);
        return limb;
    }

    private static void assertSameFields(Message leg, Message limb) {
        // 按腿击的字段表逐个比对；有出现性的字段还要比对填没填。
        for (FieldDescriptor legField : leg.getDescriptorForType().getFields()) {
            FieldDescriptor limbField = limb.getDescriptorForType().findFieldByNumber(legField.getNumber());
            if (legField.hasPresence()) {
                assertEquals(legField.getName(), leg.hasField(legField), limb.hasField(limbField));
            }
            assertEquals(legField.getName(), leg.getField(legField), limb.getField(limbField));
        }
    }

    private static void assertWireCompatible(Message leg, Message limb) {
        // 同号字段的名字、标量类型和枚举或消息类型都要一致，重复性也不能变。
        for (FieldDescriptor legField : leg.getDescriptorForType().getFields()) {
            FieldDescriptor limbField = limb.getDescriptorForType().findFieldByNumber(legField.getNumber());
            assertNotNull(legField.getName(), limbField);
            assertEquals(legField.getName(), limbField.getName());
            assertEquals(legField.getName(), legField.getType(), limbField.getType());
            assertEquals(legField.getName(), legField.isRepeated(), limbField.isRepeated());
            if (legField.getType() == FieldDescriptor.Type.ENUM) {
                assertEquals(legField.getName(), legField.getEnumType(), limbField.getEnumType());
            }
            if (legField.getType() == FieldDescriptor.Type.MESSAGE) {
                boolean sameStrike = legField.getMessageType() == LegStrike.getDescriptor()
                        && limbField.getMessageType() == LimbStrike.getDescriptor();
                assertTrue(legField.getName(), sameStrike || legField.getMessageType() == limbField.getMessageType());
            }
        }
    }

    private static void assertInvalid(LimbStrike strike) {
        // 非法肢击在目录校验阶段就被拒绝。
        assertThrows(IllegalArgumentException.class, () -> TechniqueLimbStrikes.validate(strike));
    }

    private static void assertLoadFails(TechniqueCatalog catalog) {
        // 书级约束只在完整目录加载时检查。
        assertThrows(IllegalArgumentException.class, () -> TechniqueRegistry.load(addons(), List.of(catalog)));
    }

    private static TechniqueCatalog peddlerCatalog() throws IOException {
        // 测试资源逐字照抄千里腿目录的腿击和书级鞋规则。
        try (InputStream input = TechniqueLimbStrikeTest.class.getResourceAsStream(PEDDLER_RESOURCE)) {
            assertNotNull(PEDDLER_RESOURCE, input);
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return TechniqueCatalogManifest.parseText(PEDDLER_RESOURCE, text);
        }
    }

    private static LimbStrike armStrike() {
        // 后手单体推开，完整填写预算、盾规则和最后一击。
        return LimbStrike.newBuilder()
                .setShape(StrikeShape.SINGLE_TARGET)
                .setFacing(StrikeFacing.STRIKE_FORWARD)
                .setDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setAngle(quantity(SINGLE_TARGET_ANGLE, Unit.DEGREE))
                .setMultiplier(quantity(STRIKE_MULTIPLIER, Unit.RATIO))
                .setMaximumTargets(1)
                .setCastTargetLimit(1)
                .setArm(ArmSelection.REAR_ARM)
                .setDamageTypeId(DAMAGE_TYPE_ID)
                .setKnockbackKind(LegKnockbackKind.PUSH)
                .setKnockback(quantity(PUSH, Unit.RATIO))
                .setKnockbackWhenBlocked(true /* knockbackWhenBlocked */)
                .setLastStrike(true /* lastStrike */)
                .setDedupGroup(DEDUP_GROUP)
                .build();
    }

    private static LimbStrike liftStrike(double horizontal) {
        // 双手掀起：竖直速度单独声明，水平分量写在 knockback。
        return armStrike().toBuilder()
                .setArm(ArmSelection.BOTH_ARMS)
                .setKnockbackKind(LegKnockbackKind.LIFT)
                .setKnockback(quantity(horizontal, Unit.RATIO))
                .setLiftSpeed(quantity(LIFT_SPEED, Unit.METERS_PER_SECOND))
                .build();
    }

    private static LegStrike legStrikeTemplate() {
        // 与出拳样例同形状的前腿推开，用于混用和掀起的拒绝样例。
        return LegStrike.newBuilder()
                .setShape(StrikeShape.SINGLE_TARGET)
                .setFacing(StrikeFacing.STRIKE_FORWARD)
                .setDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setAngle(quantity(SINGLE_TARGET_ANGLE, Unit.DEGREE))
                .setMultiplier(quantity(STRIKE_MULTIPLIER, Unit.RATIO))
                .setMaximumTargets(1)
                .setCastTargetLimit(1)
                .setLeg(LegSelection.FRONT_LEG)
                .setDamageTypeId(DAMAGE_TYPE_ID)
                .setKnockbackKind(LegKnockbackKind.PUSH)
                .setKnockback(quantity(PUSH, Unit.RATIO))
                .setKnockbackWhenBlocked(true /* knockbackWhenBlocked */)
                .setLastStrike(true /* lastStrike */)
                .setDedupGroup(DEDUP_GROUP)
                .build();
    }

    private static SoundCue hitSound() {
        // 目标锚点的命中声音，调度时并入同刻那一击。
        return SoundCue.newBuilder()
                .setSoundId(HIT_SOUND_ID)
                .setAnchor(SoundAnchor.TARGET)
                .build();
    }

    private static ArmRules armRules() {
        // 拳掌书固定忽略手里的东西、要求着地，空手基础伤害 1.5。
        return ArmRules.newBuilder()
                .setIgnoreHeldItems(true /* ignoreHeldItems */)
                .setRequireGrounded(true /* requireGrounded */)
                .setBaseDamage(quantity(BASE_DAMAGE, Unit.DAMAGE_POINT))
                .build();
    }

    private static LegRules legRules() {
        // 腿法书的完整鞋规则，用于换算和错配样例。
        return LegRules.newBuilder()
                .setIgnoreHeldItems(true /* ignoreHeldItems */)
                .setRequireGrounded(true /* requireGrounded */)
                .setBaseDamage(quantity(BASE_DAMAGE, Unit.DAMAGE_POINT))
                .setArmorDamage(quantity(ARMOR_DAMAGE, Unit.DAMAGE_POINT))
                .setDurabilityCost(DURABILITY_COST)
                .build();
    }

    private static TechniqueDefinition armDefinition() {
        // 一记出拳加同刻命中声音，费用和组织槽位沿用测试功法。
        TechniqueEffect strike = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setLimbStrike(armStrike())
                .build();
        TechniqueEffect sound = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setSound(hitSound())
                .build();
        return definition().toBuilder()
                .setId(ARM_ID)
                .addEffects(strike)
                .addEffects(sound)
                .build();
    }

    private static TechniqueBook armBook() {
        // 只声明拳掌规则的书。
        return TechniqueBook.newBuilder()
                .setId(ARM_BOOK_ID)
                .setArmRules(armRules())
                .build();
    }

    private static TechniqueCatalog armCatalog(TechniqueDefinition definition, TechniqueBook book) {
        // 一书一卷一式，书级规则经完整目录加载生效。
        TechniqueVolume volume = TechniqueVolume.newBuilder()
                .setId(ARM_VOLUME_ID)
                .setBookId(ARM_BOOK_ID)
                .addTechniques(TechniqueRegistry.reference(definition))
                .build();
        return catalog(definition).toBuilder()
                .addBooks(book)
                .addVolumes(volume)
                .build();
    }

    private static AiHints aiHints(CombatAiHints combat) {
        // 距离、血量和真元门槛全部放开，只看情境权重。
        return AiHints.newBuilder()
                .setMinimumDistance(quantity(0, Unit.METER))
                .setMaximumDistance(quantity(STRIKE_DISTANCE, Unit.METER))
                .setMaximumHpRatio(quantity(1, Unit.RATIO))
                .setMinimumQiRatio(quantity(0, Unit.RATIO))
                .setCategoryWeight(quantity(1, Unit.RATIO))
                .setCombat(combat)
                .build();
    }

    private static CastRequest request(TechniqueDefinition definition, BodySnapshot body, String castId) {
        // 请求只带精确功法引用和施放身份。
        return CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(TechniqueRegistry.reference(definition))
                .setCastId(castId)
                .build();
    }
}
