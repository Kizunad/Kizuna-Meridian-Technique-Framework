package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockWork;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewUseMethod;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.UseBrew;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewItemRequirement;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewTechniqueParameters;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewPartGroup;

import java.util.HashSet;

/** 巫药与方块处理器的声明边界；这里只校验身份和时间，不执行世界行为。 */
final class TechniqueBrewEffects {
    private static final int MAX_BREW_KINDS = 16;
    private static final int MAX_BLOCK_TAGS = 64;
    private static final long MIN_INTERVAL_MILLIS = 1;
    private static final int MAX_BREW_QUALITY = 3;
    private static final double MAX_MULTIPLIER = 10;
    private static final double MAX_FAN_ANGLE = 180;
    private static final int MAX_TARGETS = 64;

    private TechniqueBrewEffects() {
        // 处理器声明由 Framework 校验，实际执行交给附属模组。
    }

    static void validateBlockWork(BlockWork work) {
        // 目标与周期必须有界，否则目录可生成无限量世界事件。
        TechniqueValues.id(work.getProcessorId(), "block_work.processor_id");
        if (work.getBlockTagsCount() == 0 || work.getBlockTagsCount() > MAX_BLOCK_TAGS
                || new HashSet<>(work.getBlockTagsList()).size() != work.getBlockTagsCount()) {
            throw TechniqueErrors.invalid("block_work.block_tags", "目标方块标签必须非空且不重复");
        }
        for (String tag : work.getBlockTagsList()) {
            TechniqueValues.id(tag, "block_work.block_tags");
        }
        TechniqueValues.positive(work.getMaximumDistance(), Unit.METER,
                TechniqueValues.MAX_DISTANCE, "block_work.maximum_distance");
        TechniqueValues.millis(work.getIntervalMillis(), "block_work.interval_millis");
        TechniqueValues.millis(work.getDurationMillis(), "block_work.duration_millis");
        if (work.getIntervalMillis() < MIN_INTERVAL_MILLIS
                || work.getDurationMillis() < work.getIntervalMillis()) {
            throw TechniqueErrors.invalid("block_work", "间隔必须大于零且不超过持续时间");
        }
    }

    static void validateUseBrew(UseBrew useBrew) {
        // 种类和用法必须显式声明，未知枚举不能扩展附属的消费权限。
        TechniqueValues.id(useBrew.getProcessorId(), "use_brew.processor_id");
        if (useBrew.getBrewKindIdsCount() == 0
                || useBrew.getBrewKindIdsCount() > MAX_BREW_KINDS
                || new HashSet<>(useBrew.getBrewKindIdsList()).size() != useBrew.getBrewKindIdsCount()) {
            throw TechniqueErrors.invalid("use_brew.brew_kind_ids", "药水种类必须非空且不重复");
        }
        for (String kindId : useBrew.getBrewKindIdsList()) {
            TechniqueValues.id(kindId, "use_brew.brew_kind_ids");
        }
        if (useBrew.getUseMethodsCount() == 0
                || new HashSet<>(useBrew.getUseMethodsList()).size() != useBrew.getUseMethodsCount()) {
            throw TechniqueErrors.invalid("use_brew.use_methods", "药水用法必须非空且不重复");
        }
        for (BrewUseMethod method : useBrew.getUseMethodsList()) {
            if (method == BrewUseMethod.BREW_USE_METHOD_UNSPECIFIED
                    || method == BrewUseMethod.UNRECOGNIZED) {
                throw TechniqueErrors.invalid("use_brew.use_methods", "药水用法不能为 unspecified");
            }
        }
        if (useBrew.hasTechnique()) {
            validateParameters(useBrew.getTechnique());
        }
        if (useBrew.hasPreparationSound()) {
            TechniqueSoundCues.validate(useBrew.getPreparationSound(), "use_brew.preparation_sound");
            if (!useBrew.hasTechnique() || useBrew.getTechnique().getMethod() != BrewUseMethod.ANOINT
                    || useBrew.getSoundGroup() != BrewPartGroup.ARMS && useBrew.getSoundGroup() != BrewPartGroup.LEGS) {
                throw TechniqueErrors.invalid("use_brew.preparation_sound", "部位动作声音只用于敷药");
            }
        }
    }

    private static void validateParameters(BrewTechniqueParameters parameters) {
        // 加成由目录提供，所有数值有界，不能靠缺省零值悄悄清空药效。
        BrewUseMethod method = parameters.getMethod();
        if (method != BrewUseMethod.DRINK && method != BrewUseMethod.ANOINT && method != BrewUseMethod.THROW
                && method != BrewUseMethod.FAN && method != BrewUseMethod.MIST) {
            throw TechniqueErrors.invalid("use_brew.technique.method", "必须声明功法用法");
        }
        multiplier(parameters.getStrengthMultiplier(), "strength_multiplier");
        multiplier(parameters.getDurationMultiplier(), "duration_multiplier");
        multiplier(parameters.getMaximumQualityMultiplier(), "maximum_quality_multiplier");
        if (parameters.getStrengthQualitySteps() < 0 || parameters.getStrengthQualitySteps() > MAX_BREW_QUALITY
                || !Double.isFinite(parameters.getNauseaMultiplier()) || parameters.getNauseaMultiplier() < 0
                || parameters.getNauseaMultiplier() > MAX_MULTIPLIER
                || !Double.isFinite(parameters.getResidueDose()) || parameters.getResidueDose() < 0
                || parameters.getResidueDose() > 1) {
            throw TechniqueErrors.invalid("use_brew.technique", "品质、反胃或残药参数越界");
        }
        if (method == BrewUseMethod.FAN || method == BrewUseMethod.MIST) {
            TechniqueValues.positive(parameters.getRange(), Unit.METER, TechniqueValues.MAX_DISTANCE,
                    "use_brew.range");
        }
        if (method == BrewUseMethod.FAN) {
            TechniqueValues.positive(parameters.getFanAngle(), Unit.DEGREE, MAX_FAN_ANGLE, "use_brew.fan_angle");
            if (parameters.getMaximumTargets() < 1 || parameters.getMaximumTargets() > MAX_TARGETS) {
                throw TechniqueErrors.invalid("use_brew.maximum_targets", "扇形目标数量越界");
            }
        }
    }

    private static void multiplier(double value, String path) {
        // 原始数值没有单位包装，但仍遵守与其余目录倍率相同的有限上限。
        if (!Double.isFinite(value) || value <= 0 || value > MAX_MULTIPLIER) {
            throw TechniqueErrors.invalid("use_brew." + path, "倍率必须大于零且不超过十");
        }
    }

    static void validateRequirement(BrewItemRequirement requirement) {
        // 起手只接受物品能实际提供的饮用或喷溅形态。
        if (requirement.getKindIdsCount() == 0 || requirement.getKindIdsCount() > MAX_BREW_KINDS
                || requirement.getPhysicalUsesCount() == 0
                || new HashSet<>(requirement.getKindIdsList()).size() != requirement.getKindIdsCount()
                || new HashSet<>(requirement.getPhysicalUsesList()).size() != requirement.getPhysicalUsesCount()) {
            throw TechniqueErrors.invalid("brew_item", "药种和物理形态必须非空");
        }
        for (String kind : requirement.getKindIdsList()) {
            TechniqueValues.id(kind, "brew_item.kind_ids");
        }
        for (BrewUseMethod method : requirement.getPhysicalUsesList()) {
            if (method != BrewUseMethod.DRINK && method != BrewUseMethod.SPLASH) {
                throw TechniqueErrors.invalid("brew_item.physical_uses", "物理形态只能是饮用或喷溅");
            }
        }
    }

    static void validateDefinition(TechniqueDefinition definition) {
        // 药瓶规则只能放行药瓶效果，不能借此绕过弓弩、劈砍等书级持物约束。
        if (!definition.hasBrewItem()) {
            return;
        }
        validateRequirement(definition.getBrewItem());
        if (definition.hasProjectileWeapon()
                && definition.getProjectileWeapon() != ProjectileWeaponCategory.HELD_SPLASH_BREW) {
            throw TechniqueErrors.invalid("brew_item", "药瓶规则不能代替武器规则");
        }
        for (var effect : definition.getEffectsList()) {
            if (effect.hasWeaponStrike() || effect.hasBreakBlocks()) {
                throw TechniqueErrors.invalid("brew_item", "药瓶规则不能用于持械打击");
            }
            if (effect.hasLaunchProjectile()
                    && effect.getLaunchProjectile().getProjectileWeapon()
                    != ProjectileWeaponCategory.HELD_SPLASH_BREW) {
                throw TechniqueErrors.invalid("brew_item", "药瓶规则只允许射出喷溅巫药");
            }
        }
    }

    static void requireHeld(BrewItemRequirement requirement, HeldItems items) {
        // 主手和副手限制在扣费前检查，副手药瓶不能代替主手。
        HeldItem main = items.getMainHand();
        if (main.getBrewKindId().isEmpty() || !requirement.getPhysicalUsesList().contains(main.getBrewUse())) {
            TechniqueFailure failure = TechniqueFailure.BREW_REQUIRED;
            if (requirement.getPhysicalUsesCount() == 1) {
                if (requirement.getPhysicalUses(0) == BrewUseMethod.DRINK) {
                    failure = TechniqueFailure.DRINK_BREW_REQUIRED;
                } else {
                    failure = TechniqueFailure.SPLASH_BREW_REQUIRED;
                }
            }
            throw TechniqueErrors.rejected(failure);
        }
        if (!requirement.getKindIdsList().contains(main.getBrewKindId())) {
            throw TechniqueErrors.rejected(TechniqueFailure.BREW_KIND_REQUIRED);
        }
        if (requirement.getEmptyOffHand() && !items.getOffHand().getItemId().isEmpty()) {
            throw TechniqueErrors.rejected(TechniqueFailure.ANOINT_OFF_HAND_REQUIRED);
        }
    }

    static void validateHeldBrewFacts(HeldItem item) {
        // 巫药事实要么全无，要么完整提供；空手不能伪装为药水。
        if (item.getItemId().isEmpty()) {
            if (!item.getBrewKindId().isEmpty() || item.hasBrewQuality() || item.hasBrewUse()) {
                throw TechniqueErrors.invalid("held_item.brew", "空手不能携带巫药事实");
            }
            return;
        }
        boolean hasAnyFact = !item.getBrewKindId().isEmpty()
                || item.hasBrewQuality() || item.hasBrewUse();
        if (!hasAnyFact) {
            return;
        }
        TechniqueValues.id(item.getBrewKindId(), "held_item.brew_kind_id");
        if (!item.hasBrewQuality() || item.getBrewQuality() < 0 || item.getBrewQuality() > MAX_BREW_QUALITY) {
            throw TechniqueErrors.invalid("held_item.brew_quality", "品质必须位于 [0, 3]");
        }
        if (!item.hasBrewUse() || item.getBrewUse() == BrewUseMethod.BREW_USE_METHOD_UNSPECIFIED
                || item.getBrewUse() == BrewUseMethod.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("held_item.brew_use", "巫药用法必须显式提供");
        }
    }
}
