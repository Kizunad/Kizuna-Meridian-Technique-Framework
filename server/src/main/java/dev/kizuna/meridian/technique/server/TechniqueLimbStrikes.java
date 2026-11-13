package dev.kizuna.meridian.technique.server;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ArmSelection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegKnockbackKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegSelection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LimbStrikeIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeFacing;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.List;
import java.util.stream.Stream;

import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_DISTANCE;
import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_PEAK;
import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_SPEED;
import static dev.kizuna.meridian.technique.server.TechniqueValues.id;
import static dev.kizuna.meridian.technique.server.TechniqueValues.localId;
import static dev.kizuna.meridian.technique.server.TechniqueValues.positive;
import static dev.kizuna.meridian.technique.server.TechniqueValues.quantity;

/** 拳掌与腿的打击参数、腿击换算和整式约束，不读取世界实体。 */
public final class TechniqueLimbStrikes {
    private static final int MAX_TARGETS = 128;
    private static final double FULL_CIRCLE = 360;
    private static final double SINGLE_TARGET_ANGLE = 60;

    private TechniqueLimbStrikes() {
        // 伤害、击退和目标筛选由宿主执行，本类只拒绝无法解释的目录数据。
    }

    public static LimbStrike fromLeg(LegStrike strike) {
        // 两者 1–14 号字段同号同义；按线格式重读，可选字段有没有填写也原样保留。
        try {
            return LimbStrike.parseFrom(strike.toByteString());
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalStateException("腿击与肢击的字段号不再兼容", exception);
        }
    }

    public static LimbStrikeIntent fromLeg(LegStrikeIntent intent) {
        // 打击、鞋规则和命中声音同号重读，宿主据此把腿击交给肢击执行器。
        try {
            return LimbStrikeIntent.parseFrom(intent.toByteString());
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalStateException("腿击意图与肢击意图的字段号不再兼容", exception);
        }
    }

    public static void validate(LimbStrike strike) {
        // 肢击不读取持物，目录必须把目标、出力肢、伤害身份和击退写全。
        localId(strike.getDedupGroup(), "limb_strike.dedup_group");
        id(strike.getDamageTypeId(), "limb_strike.damage_type_id");
        positive(strike.getDistance(), Unit.METER, MAX_DISTANCE, "limb_strike.distance");
        double angle = positive(strike.getAngle(), Unit.DEGREE, FULL_CIRCLE, "limb_strike.angle");
        positive(strike.getMultiplier(), Unit.RATIO, MAX_PEAK, "limb_strike.multiplier");
        quantity(strike.getKnockback(), Unit.RATIO, 0, MAX_PEAK, "limb_strike.knockback");
        validateLimb(strike);
        validateBudget(strike);
        validateShape(strike, angle);
        validateKnockback(strike);
    }

    public static void validateIntent(LimbStrikeIntent intent) {
        // 意图只带出力肢对应的那份书级规则，命中声音都在目标处播放。
        LimbStrike strike = intent.getStrike();
        validate(strike);
        if (intent.hasArmRules() && intent.hasLegRules()) {
            throw TechniqueErrors.invalid("limb_strike", "肢击只能携带出力肢对应的书级规则");
        }
        if (strike.getLimbCase() == LimbStrike.LimbCase.ARM) {
            TechniqueArmRules.validate(intent.getArmRules());
        } else {
            TechniqueLegRules.validate(intent.getLegRules());
        }
        for (SoundCue cue : intent.getHitSoundsList()) {
            TechniqueSoundCues.validateAnchor(cue, SoundAnchor.TARGET, "limb_strike.hit_sounds");
        }
    }

    private static void validateLimb(LimbStrike strike) {
        // 出力肢在拳掌和腿之间二选一，取值必须是已知的前、后或双侧。
        boolean known = switch (strike.getLimbCase()) {
            case LEG -> strike.getLegValue() > 0 && strike.getLeg() != LegSelection.UNRECOGNIZED;
            case ARM -> strike.getArmValue() > 0 && strike.getArm() != ArmSelection.UNRECOGNIZED;
            case LIMB_NOT_SET -> false;
        };
        if (!known) {
            throw TechniqueErrors.invalid("limb_strike.limb", "缺少出力肢或出力肢未知");
        }
    }

    private static void validateBudget(LimbStrike strike) {
        // 每次范围肢击共享整式不同目标上限，盾规则和最后一击都要显式声明。
        int maximumTargets = strike.getMaximumTargets();
        int castTargetLimit = strike.getCastTargetLimit();
        if (strike.getFacingValue() <= 0 || strike.getFacing() == StrikeFacing.UNRECOGNIZED
                || !strike.hasMaximumTargets() || !strike.hasCastTargetLimit()
                || !strike.hasKnockbackWhenBlocked() || !strike.hasLastStrike()
                || maximumTargets < 1 || castTargetLimit < maximumTargets || castTargetLimit > MAX_TARGETS) {
            throw TechniqueErrors.invalid("limb_strike", "缺少显式朝向、盾规则或目标预算");
        }
    }

    private static void validateShape(LimbStrike strike, double angle) {
        // 单体目标沿用持物打击的六十度后备锥，扇形允许身前或身后朝向。
        if (strike.getShape() != StrikeShape.SINGLE_TARGET && strike.getShape() != StrikeShape.SECTOR) {
            throw TechniqueErrors.invalid("limb_strike.shape", "未知打击形状");
        }
        if (strike.getShape() == StrikeShape.SINGLE_TARGET
                && (strike.getMaximumTargets() != 1 || angle != SINGLE_TARGET_ANGLE)) {
            throw TechniqueErrors.invalid("limb_strike", "单体必须最多一个目标且后备锥为 60 度");
        }
    }

    private static void validateKnockback(LimbStrike strike) {
        // 无击退必须填零；掀起另给竖直速度、水平分量可为零；其余击退须有正大小。
        LegKnockbackKind kind = strike.getKnockbackKind();
        if (kind.getNumber() <= 0 || kind == LegKnockbackKind.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("limb_strike.knockback_kind", "未知击退方式");
        }
        if (kind == LegKnockbackKind.LIFT) {
            positive(strike.getLiftSpeed(), Unit.METERS_PER_SECOND, MAX_SPEED, "limb_strike.lift_speed");
            return;
        }
        if (strike.hasLiftSpeed()) {
            throw TechniqueErrors.invalid("limb_strike.lift_speed", "只有掀起才填写竖直速度");
        }
        boolean noKnockback = kind == LegKnockbackKind.NONE;
        boolean zeroAmount = strike.getKnockback().getValue() == 0;
        if (noKnockback != zeroAmount) {
            throw TechniqueErrors.invalid("limb_strike.knockback", "击退大小必须与击退方式匹配");
        }
    }

    static void validateSequence(TechniqueDefinition definition) {
        // 肢击不和腿击混用；多段肢击目标预算一致，只有最后一段声明最后一击。
        List<LimbStrike> strikes = strikes(definition).toList();
        if (strikes.isEmpty()) {
            return;
        }
        if (TechniqueCombatEffects.hasLegStrike(definition)) {
            throw TechniqueErrors.invalid(definition.getId(), "同一式不能混用腿击与肢击");
        }
        LimbStrike first = strikes.get(0);
        int lastIndex = strikes.size() - 1;
        for (int index = 0; index < strikes.size(); index++) {
            LimbStrike strike = strikes.get(index);
            boolean last = index == lastIndex;
            if (strike.getCastTargetLimit() != first.getCastTargetLimit() || strike.getLastStrike() != last) {
                throw TechniqueErrors.invalid(definition.getId(), "肢击整式目标上限或最后一击声明不一致");
            }
        }
    }

    static boolean usesArms(TechniqueDefinition definition) {
        // 出拳掌的肢击和擒、摔都按书级拳掌规则结算。
        return strikes(definition).anyMatch(strike -> strike.getLimbCase() == LimbStrike.LimbCase.ARM)
                || TechniqueGrapples.usesGrapple(definition);
    }

    static boolean usesLegs(TechniqueDefinition definition) {
        // 腿击和出腿的肢击都要求书级腿法规则。
        return TechniqueCombatEffects.hasLegStrike(definition)
                || strikes(definition).anyMatch(strike -> strike.getLimbCase() == LimbStrike.LimbCase.LEG);
    }

    private static Stream<LimbStrike> strikes(TechniqueDefinition definition) {
        // 按目录顺序取出全部肢击，供整式约束和书级规则判断共用。
        return definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasLimbStrike)
                .map(TechniqueEffect::getLimbStrike);
    }
}
