package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BreakBlocks;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InjuryKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegKnockbackKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegSelection;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegStrike;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeFacing;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;

import java.util.HashSet;
import java.util.List;

import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_DISTANCE;
import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_MODULES;
import static dev.kizuna.meridian.technique.server.TechniqueValues.MAX_PEAK;
import static dev.kizuna.meridian.technique.server.TechniqueValues.id;
import static dev.kizuna.meridian.technique.server.TechniqueValues.localId;
import static dev.kizuna.meridian.technique.server.TechniqueValues.millis;
import static dev.kizuna.meridian.technique.server.TechniqueValues.positive;
import static dev.kizuna.meridian.technique.server.TechniqueValues.quantity;

/** 持物效果的参数和整次施放约束，不读取世界实体。 */
final class TechniqueCombatEffects {
    private static final int MAX_TARGETS = 128;
    private static final int MAX_DURABILITY = 1024;
    private static final double FULL_CIRCLE = 360;
    private static final double HALF_CIRCLE = 180;
    private static final double SINGLE_TARGET_ANGLE = 60;

    private TechniqueCombatEffects() {
        // 伤害和方块权限由宿主执行，本类只拒绝无法解释的目录数据。
    }

    static void validateStrike(WeaponStrike strike) {
        // 显式填写目标预算与最后一击，不能靠缺省值漏扣耐久或绕过合计上限。
        localId(strike.getDedupGroup(), "weapon_strike.dedup_group");
        positive(strike.getDistance(), Unit.METER, MAX_DISTANCE, "weapon_strike.distance");
        double angle = positive(strike.getAngle(), Unit.DEGREE, FULL_CIRCLE, "weapon_strike.angle");
        quantity(strike.getFacingOffset(), Unit.DEGREE, -HALF_CIRCLE, HALF_CIRCLE, "facing_offset");
        positive(strike.getMultiplier(), Unit.RATIO, MAX_PEAK, "weapon_strike.multiplier");
        quantity(strike.getKnockback(), Unit.RATIO, 0, MAX_PEAK, "weapon_strike.knockback");
        double twoHand = positive(strike.getTwoHandMultiplier(), Unit.RATIO, MAX_PEAK, "two_hand_multiplier");
        validateBudget(strike, twoHand);
        validateShape(strike, angle);
        validateShield(strike);
        if (strike.hasInjuryOverride() && strike.getInjuryOverride() != InjuryKind.CUTTING
                && strike.getInjuryOverride() != InjuryKind.BLUNT) {
            throw TechniqueErrors.invalid("injury_override", "未知致伤覆盖");
        }
    }

    static void validateLegStrike(LegStrike strike) {
        // 腿法不读取持物，目录必须把目标、出力腿、伤害身份和鞋磨损规则写全。
        localId(strike.getDedupGroup(), "leg_strike.dedup_group");
        id(strike.getDamageTypeId(), "leg_strike.damage_type_id");
        positive(strike.getDistance(), Unit.METER, MAX_DISTANCE, "leg_strike.distance");
        double angle = positive(strike.getAngle(), Unit.DEGREE, FULL_CIRCLE, "leg_strike.angle");
        positive(strike.getMultiplier(), Unit.RATIO, MAX_PEAK, "leg_strike.multiplier");
        quantity(strike.getKnockback(), Unit.RATIO, 0, MAX_PEAK, "leg_strike.knockback");
        validateLegBudget(strike);
        validateLegShape(strike, angle);
        validateLegKnockback(strike);
    }

    private static void validateLegBudget(LegStrike strike) {
        // 每次范围腿击共享整式不同目标上限，显式末击才负责磨鞋。
        int maximumTargets = strike.getMaximumTargets();
        int castTargetLimit = strike.getCastTargetLimit();
        if (strike.getFacingValue() <= 0 || strike.getFacing() == StrikeFacing.UNRECOGNIZED
                || strike.getLegValue() <= 0 || strike.getLeg() == LegSelection.UNRECOGNIZED
                || !strike.hasMaximumTargets() || !strike.hasCastTargetLimit()
                || !strike.hasKnockbackWhenBlocked() || !strike.hasLastStrike()
                || maximumTargets < 1 || castTargetLimit < maximumTargets || castTargetLimit > MAX_TARGETS) {
            throw TechniqueErrors.invalid("leg_strike", "缺少显式朝向、出力腿、盾规则或目标预算");
        }
    }

    private static void validateLegShape(LegStrike strike, double angle) {
        // 单体目标沿用持物打击的六十度后备锥，扇形允许身前或身后朝向。
        if (strike.getShape() != StrikeShape.SINGLE_TARGET && strike.getShape() != StrikeShape.SECTOR) {
            throw TechniqueErrors.invalid("leg_strike.shape", "未知打击形状");
        }
        if (strike.getShape() == StrikeShape.SINGLE_TARGET
                && (strike.getMaximumTargets() != 1 || angle != SINGLE_TARGET_ANGLE)) {
            throw TechniqueErrors.invalid("leg_strike", "单体必须最多一个目标且后备锥为 60 度");
        }
    }

    private static void validateLegKnockback(LegStrike strike) {
        // 无击退必须填零，其余四种击退必须有正大小，避免宿主猜测单位含义。
        LegKnockbackKind kind = strike.getKnockbackKind();
        if (kind.getNumber() <= 0 || kind == LegKnockbackKind.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("leg_strike.knockback_kind", "未知腿法击退方式");
        }
        double amount = strike.getKnockback().getValue();
        if ((kind == LegKnockbackKind.NONE && amount != 0)
                || (kind != LegKnockbackKind.NONE && amount == 0)) {
            throw TechniqueErrors.invalid("leg_strike.knockback", "击退大小必须与击退方式匹配");
        }
    }

    static boolean hasLegStrike(TechniqueDefinition definition) {
        // 书级腿法规则只对真正包含腿击算子的定义生效。
        return definition.getEffectsList().stream().anyMatch(TechniqueEffect::hasLegStrike);
    }

    static void validateLegSequence(TechniqueDefinition definition) {
        // 多段腿法的目标预算一致，只有最后一段能磨鞋；同组去重由宿主命中记录保证。
        List<LegStrike> strikes = definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasLegStrike).map(TechniqueEffect::getLegStrike).toList();
        if (strikes.isEmpty()) {
            return;
        }
        LegStrike first = strikes.get(0);
        for (int index = 0; index < strikes.size(); index++) {
            LegStrike strike = strikes.get(index);
            boolean last = index == strikes.size() - 1;
            if (strike.getCastTargetLimit() != first.getCastTargetLimit()
                    || strike.getLastStrike() != last) {
                throw TechniqueErrors.invalid(
                        definition.getId(), "腿法整式目标上限或最后一击声明不一致");
            }
        }
    }

    private static void validateBudget(WeaponStrike strike, double twoHand) {
        // 每击预算不能超过整次预算，耐久只在显式末击扣除。
        int maximumTargets = strike.getMaximumTargets();
        int castTargetLimit = strike.getCastTargetLimit();
        int durabilityBase = strike.getDurabilityBase();
        if (!strike.hasMaximumTargets() || !strike.hasCastTargetLimit() || !strike.hasCanTwoHand()
                || !strike.hasBreakShield() || !strike.hasLastStrike() || !strike.hasDurabilityBase()
                || !strike.hasShieldCooldownMillis() || !strike.hasShieldBreakMillis() || maximumTargets < 1
                || castTargetLimit < maximumTargets || castTargetLimit > MAX_TARGETS || durabilityBase < 1
                || durabilityBase > MAX_DURABILITY || (!strike.getCanTwoHand() && twoHand != 1)) {
            throw TechniqueErrors.invalid("weapon_strike", "缺少显式规则或目标、双手、耐久参数非法");
        }
    }

    private static void validateShape(WeaponStrike strike, double angle) {
        // 单体的后备锥固定为六十度，扇形可覆盖整圈。
        if (strike.getShape() != StrikeShape.SINGLE_TARGET && strike.getShape() != StrikeShape.SECTOR) {
            throw TechniqueErrors.invalid("weapon_strike.shape", "未知打击形状");
        }
        if (strike.getShape() == StrikeShape.SINGLE_TARGET
                && (strike.getMaximumTargets() != 1 || angle != SINGLE_TARGET_ANGLE)) {
            throw TechniqueErrors.invalid("weapon_strike", "单体必须最多一个目标且后备锥为 60 度");
        }
    }

    private static void validateShield(WeaponStrike strike) {
        // 盾冷却分为先破盾和格挡后两种；不破盾的打击不能附带冷却。
        long cooldownMillis = strike.getShieldCooldownMillis();
        long breakMillis = strike.getShieldBreakMillis();
        millis(cooldownMillis, "shield_cooldown_millis");
        millis(breakMillis, "shield_break_millis");
        if (!strike.getBreakShield() && (cooldownMillis != 0 || breakMillis != 0)) {
            throw TechniqueErrors.invalid("shield_cooldown_millis", "不破盾时不能声明盾冷却");
        }
    }

    static void validateBlocks(BreakBlocks blocks) {
        // 方块标签按任一匹配，门上下半部归并与权限检查均由宿主完成。
        positive(blocks.getForwardDistance(), Unit.METER, MAX_DISTANCE, "break_blocks.forward_distance");
        positive(blocks.getWidth(), Unit.METER, MAX_DISTANCE, "break_blocks.width");
        List<String> tags = blocks.getBlockTagsList();
        int maximumDoors = blocks.getMaximumDoors();
        if (tags.isEmpty() || tags.size() > MAX_MODULES || new HashSet<>(tags).size() != tags.size()
                || !blocks.hasMaximumDoors() || maximumDoors < 1 || maximumDoors > MAX_TARGETS) {
            throw TechniqueErrors.invalid("break_blocks", "方块标签或最多扇数非法");
        }
        for (String tag : tags) {
            id(tag, "break_blocks.block_tags");
        }
    }

    static void validateSequence(TechniqueDefinition definition) {
        // 全部去重组共用不同目标的合计预算和耐久基数，只有最后一次打击负责扣耐久。
        List<WeaponStrike> strikes = definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasWeaponStrike).map(TechniqueEffect::getWeaponStrike).toList();
        if (strikes.isEmpty()) {
            return;
        }
        WeaponStrike first = strikes.get(0);
        for (int index = 0; index < strikes.size(); index++) {
            WeaponStrike strike = strikes.get(index);
            boolean last = index == strikes.size() - 1;
            if (strike.getCastTargetLimit() != first.getCastTargetLimit()
                    || strike.getDurabilityBase() != first.getDurabilityBase() || strike.getLastStrike() != last) {
                throw TechniqueErrors.invalid(
                        definition.getId(),
                        "整次目标上限、耐久基数或最后一击声明不一致");
            }
        }
    }
}
