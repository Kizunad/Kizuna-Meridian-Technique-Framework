package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.AddonMessages.ContentSymbolKind;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.TechniqueValues.*;

/** 目录边界的声明式规则；不执行效果。 */
final class TechniqueDefinitions {
    private static final int MIN_CURVE_POINTS = 2;
    private static final int MAX_CURVE_POINTS = 16;
    private static final int MAX_SUPPORT_ORDER = 100;
    private static final Map<AiRole, Category> SUPPORT_CATEGORIES = Map.of(
            AiRole.AI_ROLE_PREPARE, Category.BUFF,
            AiRole.AI_ROLE_RECOVER, Category.RECOVERY);

    private TechniqueDefinitions() {
        // 校验器不对宿主或 Framework 施加修正。
    }

    static void validate(AddonRegistry addons, Map<ModuleKind, Set<String>> tags,
                         List<BodyMessages.TemplateReference> templates, TechniqueDefinition definition) {
        // 身份、时间、槽位、费用、效果依次检查，错误直接定位原始字段。
        id(definition.getId(), "id");
        if (definition.getVersion() <= 0 || definition.getCategoryValue() <= 0
                || definition.getCategory() == Category.UNRECOGNIZED
                || definition.getMinimumRealmValue() <= 0
                || definition.getMinimumRealm() == BodyMessages.Realm.UNRECOGNIZED) {
            throw TechniqueErrors.invalid(definition.getId(), "版本、类别或最低境界非法");
        }
        validateTiming(definition);
        TechniqueMovement.validateMultiplier(TechniqueMovement.multiplier(definition));
        Map<String, TechniqueSlot> slots = validateSlots(addons, tags, definition);
        validateCosts(addons, templates, slots, definition);
        validateEffects(addons, templates, slots, definition);
        TechniqueCombatEffects.validateSequence(definition);
        TechniqueCombatEffects.validateLegSequence(definition);
        TechniqueSoundCues.validateTargets(definition);
        if (definition.hasAiHints()) {
            validateAiHints(definition.getCategory(), definition.getAiHints());
        }
        if (definition.hasPresentation()) {
            validatePresentation(definition.getPresentation());
        }
    }

    static long durationMillis(CastPhases phases) {
        // 每段已经有界，总时长再限制为一天，不允许三段分别用满上限。
        return phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
    }

    private static void validateTiming(TechniqueDefinition definition) {
        // 显式零时长合法，缺字段不合法；这能区分瞬时生效与未填写。
        CastPhases phases = definition.getPhases();
        if (!phases.hasWarmupMillis() || !phases.hasActiveMillis() || !phases.hasRecoveryMillis()
                || !phases.hasInterruptibleByDamage() || !definition.hasCooldownMillis()) {
            throw TechniqueErrors.invalid("phases", "三段毫秒、打断规则和冷却必须显式提供");
        }
        millis(phases.getWarmupMillis(), "warmup_millis");
        millis(phases.getActiveMillis(), "active_millis");
        millis(phases.getRecoveryMillis(), "recovery_millis");
        millis(definition.getCooldownMillis(), "cooldown_millis");
        long durationMillis = durationMillis(phases);
        if (durationMillis == 0 || durationMillis > MAX_MILLIS) {
            throw TechniqueErrors.invalid("phases", "总施放毫秒必须大于 0 且不超过一天");
        }
    }

    private static Map<String, TechniqueSlot> validateSlots(AddonRegistry addons,
            Map<ModuleKind, Set<String>> tags, TechniqueDefinition definition) {
        // 槽位定义按身份建立索引，后续费用与效果不能引用不存在的槽位。
        if (definition.getSlotsCount() > MAX_MODULES) {
            throw TechniqueErrors.invalid("slots", "槽位数量超限");
        }
        var slots = new HashMap<String, TechniqueSlot>();
        for (TechniqueSlot slot : definition.getSlotsList()) {
            localId(slot.getId(), "slot.id");
            if (slots.putIfAbsent(slot.getId(), slot) != null) {
                throw TechniqueErrors.invalid(slot.getId(), "槽位 ID 重复");
            }
            validateSlot(addons, tags, slot);
        }
        return slots;
    }

    private static void validateSlot(AddonRegistry addons, Map<ModuleKind, Set<String>> tags, TechniqueSlot slot) {
        // 按模块种类校验标签，组织标签不能误用在经脉上。
        Set<String> knownTags = tags.get(slot.getModule());
        if (knownTags == null || (slot.getTagsCount() == 0 && slot.getKind().isEmpty())
                || slot.getTagsCount() > MAX_MODULES
                || new HashSet<>(slot.getTagsList()).size() != slot.getTagsCount()) {
            throw TechniqueErrors.invalid(slot.getId(), "模块种类或标签列表非法");
        }
        for (String tag : slot.getTagsList()) {
            id(tag, slot.getId() + ".tags");
            if (!knownTags.contains(tag)) {
                throw TechniqueErrors.invalid(slot.getId(), "未知标签：" + tag);
            }
        }
        if (!slot.hasMinimumCount() || !slot.hasMaximumCount() || slot.getMinimumCount() < 0
                || slot.getMaximumCount() < 1 || slot.getMaximumCount() > MAX_MODULES
                || slot.getMinimumCount() > slot.getMaximumCount()) {
            throw TechniqueErrors.invalid(slot.getId(), "绑定数量必须显式提供且位于允许范围");
        }
        validateSlotConstraints(addons, slot);
    }

    private static void validateSlotConstraints(AddonRegistry addons, TechniqueSlot slot) {
        // 开拓只适用于经脉，力量只适用于组织，防止无效约束被静默忽略。
        if (slot.getModule() == ModuleKind.MERIDIAN) {
            if (!slot.hasRequireOpened() || !slot.getKind().isEmpty() || slot.hasMinimumStrength()) {
                throw TechniqueErrors.invalid(slot.getId(), "经脉须显式声明开拓条件，不能声明类型或力量");
            }
            return;
        }
        if (slot.hasRequireOpened()) {
            throw TechniqueErrors.invalid(slot.getId(), "非经脉槽位不能声明开拓条件");
        }
        if (!slot.getKind().isEmpty()) {
            ContentSymbolKind expected = ContentSymbolKind.ORGAN_KIND;
            if (slot.getModule() == ModuleKind.TISSUE) {
                expected = ContentSymbolKind.TISSUE_KIND;
            }
            validateSymbol(addons, slot.getKind(), expected);
        }
        if (slot.hasMinimumStrength()) {
            if (slot.getModule() != ModuleKind.TISSUE) {
                throw TechniqueErrors.invalid(slot.getId(), "只有组织槽位可以声明力量");
            }
            quantity(slot.getMinimumStrength(), Unit.NEWTON, 0, MAX_QUANTITY, "minimum_strength");
        }
    }

    private static void validateCosts(AddonRegistry addons, List<BodyMessages.TemplateReference> templates,
                                      Map<String, TechniqueSlot> slots,
                                      TechniqueDefinition definition) {
        // 声明空费用必须显式保留 cost 消息，未知费用字段由递归检查拒绝。
        TechniqueCost cost = definition.getCost();
        if (!definition.hasCost() || cost.getQiCount() > MAX_COSTS || cost.getWorkCount() > MAX_COSTS) {
            throw TechniqueErrors.invalid("cost", "缺少费用声明或费用条数超限");
        }
        for (QiCost qi : cost.getQiList()) {
            TechniqueSlot slot = requireSlot(slots, qi.getSlotId(), ModuleKind.MERIDIAN);
            if (!slot.getRequireOpened() || slot.getMinimumCount() == 0) {
                throw TechniqueErrors.invalid(qi.getSlotId(), "扣费经脉必须已开拓且不可缺省");
            }
            validateQiMatching(addons, qi);
            if (qi.getPhaseValue() <= 0 || qi.getPhase() == BodyMessages.QiPhase.UNRECOGNIZED) {
                throw TechniqueErrors.invalid("cost.qi.phase", "未知相态");
            }
            double amount = positive(qi.getAmount(), Unit.QI, MAX_QUANTITY, "cost.qi.amount");
            if (amount < QiAccounting.MIN_TRANSFER_QI) {
                throw TechniqueErrors.invalid("cost.qi.amount", "费用低于 Framework 的最小可流转单位");
            }
            QiDistribution distribution = qi.getDistribution();
            if (qi.getDistributionValue() <= 0 || distribution == QiDistribution.UNRECOGNIZED) {
                throw TechniqueErrors.invalid("cost.qi.distribution", "费用分配方式必须显式声明");
            }
            if (distribution == QiDistribution.EVEN) {
                TechniqueQiCosts.validateEvenShares(amount, slot.getMinimumCount(), slot.getMaximumCount());
            }
        }
        for (WorkCost work : cost.getWorkList()) {
            validateWork(addons, templates, slots, work);
        }
    }

    private static void validateQiMatching(AddonRegistry addons, QiCost cost) {
        // 旧目录保持精确匹配，任意属性模式拒绝同时填写属性以免产生两种解释。
        switch (cost.getMatching()) {
            case EXACT_VARIANT -> validateSymbol(addons, cost.getAttribute(), ContentSymbolKind.QI_ATTRIBUTE);
            case ANY_ATTRIBUTE_AT_LEAST_PHASE -> {
                if (!cost.getAttribute().isEmpty()) {
                    throw TechniqueErrors.invalid("cost.qi.attribute", "任意属性匹配时属性必须为空");
                }
            }
            default -> throw TechniqueErrors.invalid("cost.qi.matching", "未知费用匹配方式");
        }
    }

    private static void validateWork(AddonRegistry addons, List<BodyMessages.TemplateReference> templates,
                                     Map<String, TechniqueSlot> slots, WorkCost work) {
        // 器官疲劳同样按 W × s 表达，与 Framework 的单一能量预算一致。
        TechniqueSlot slot = slots.get(work.getSlotId());
        if (slot == null || slot.getModule() == ModuleKind.MERIDIAN || slot.getMinimumCount() == 0) {
            throw TechniqueErrors.invalid(work.getSlotId(), "做功槽位必须是不可缺省的组织或器官");
        }
        if (slot.getModule() == ModuleKind.TISSUE) {
            quantity(work.getForce(), Unit.NEWTON, 0, MAX_QUANTITY, "cost.work.force");
            if (work.getTraining()) {
                validateTrainingWork(addons, templates, slot);
            }
        } else {
            if (work.getTraining()) {
                throw TechniqueErrors.invalid(work.getSlotId(), "训练做功只能用于组织槽位");
            }
            if (work.hasForce()) {
                throw TechniqueErrors.invalid(work.getSlotId(), "器官做功不能声明组织力量");
            }
        }
        double powerWatts = positive(work.getPower(), Unit.WATT, MAX_QUANTITY, "cost.work.power");
        double durationSeconds = positive(work.getDuration(), Unit.SECOND, MAX_SECONDS, "cost.work.duration");
        if (powerWatts * durationSeconds == 0) {
            throw TechniqueErrors.invalid("cost.work", "做功量下溢为零，无法安全结算");
        }
    }

    private static void validateTrainingWork(AddonRegistry addons,
                                             List<BodyMessages.TemplateReference> templates,
                                             TechniqueSlot slot) {
        // 训练负荷必须能在目录引用的每个匹配组织上安全落账，不能延迟到运行时。
        boolean matched = false;
        for (BodyMessages.TemplateReference reference : templates) {
            BodyMessages.BodyTemplate template = addons.findTemplate(reference.getId(), reference.getVersion())
                    .orElseThrow(() -> TechniqueErrors.invalid(reference.getId(), "绑定模板不存在"));
            for (BodyMessages.TissueDefinition tissue : template.getStructure().getTissuesList()) {
                if (!matches(slot, tissue)) {
                    continue;
                }
                matched = true;
                if (!tissue.hasTraining()) {
                    throw TechniqueErrors.invalid(slot.getId(), "训练做功命中的组织未声明 TissueTraining");
                }
            }
        }
        if (!matched) {
            throw TechniqueErrors.invalid(slot.getId(), "训练做功没有可验证的组织匹配项");
        }
    }

    private static boolean matches(TechniqueSlot slot, BodyMessages.TissueDefinition tissue) {
        // 与运行时绑定保持同一组标签和类型匹配规则，训练不能放宽槽位选择范围。
        return tissue.getTagsList().containsAll(slot.getTagsList())
                && (slot.getKind().isEmpty() || slot.getKind().equals(tissue.getKind()));
    }

    private static void validateEffects(AddonRegistry addons, List<BodyMessages.TemplateReference> templates,
                                        Map<String, TechniqueSlot> slots, TechniqueDefinition definition) {
        // 所有效果保留声明顺序，同一时刻不重新排序；这里只检查数据，不生成意图。
        TechniqueSoundCues.validateCounts(definition);
        long previousMillis = 0;
        long durationMillis = durationMillis(definition.getPhases());
        for (TechniqueEffect effect : definition.getEffectsList()) {
            long atMillis = effect.getAtMillis();
            if (!effect.hasAtMillis() || atMillis < previousMillis || atMillis > durationMillis) {
                throw TechniqueErrors.invalid("effects.at_millis", "缺少时刻、时刻无序或超出施放时长");
            }
            previousMillis = atMillis;
            switch (effect.getOperationCase()) {
                case TISSUE_MODIFIER -> validateModifier(slots, definition, effect);
                case IMPULSE -> validateImpulse(effect.getImpulse());
                case STATUS_BENEFIT -> {
                    TechniqueBenefits.validate(effect.getStatusBenefit());
                    validateWork(addons, templates, slots, effect.getStatusBenefit().getLeap().getWork());
                    requireSlot(slots, effect.getStatusBenefit().getLeap().getWork().getSlotId(), ModuleKind.TISSUE);
                }
                case WEAPON_STRIKE -> TechniqueCombatEffects.validateStrike(effect.getWeaponStrike());
                case LEG_STRIKE -> TechniqueCombatEffects.validateLegStrike(effect.getLegStrike());
                case BREAK_BLOCKS -> TechniqueCombatEffects.validateBlocks(effect.getBreakBlocks());
                case SOUND -> TechniqueSoundCues.validate(effect.getSound(), "effect.sound");
                case PRESENTATION -> {
                    PresentationEffect presentation = effect.getPresentation();
                    id(presentation.getId(), "effect.presentation.id");
                    millis(presentation.getStartMillis(), "effect.presentation.start_millis");
                    if (presentation.getDurationValue() <= 0
                            || presentation.getDuration() == PresentationDuration.UNRECOGNIZED) {
                        throw TechniqueErrors.invalid("effect.presentation.duration", "未知表现持续规则");
                    }
                }
                default -> throw TechniqueErrors.invalid("effects", "未知或缺少算子");
            }
        }
    }

    private static void validateModifier(Map<String, TechniqueSlot> slots, TechniqueDefinition definition,
                                         TechniqueEffect effect) {
        // 延迟生效计入冷却期限，防止只比较曲线长度而漏掉起手偏移。
        TissueModifier modifier = effect.getTissueModifier();
        requireSlot(slots, modifier.getSlotId(), ModuleKind.TISSUE);
        int pointCount = modifier.getCurveCount();
        if (pointCount < MIN_CURVE_POINTS || pointCount > MAX_CURVE_POINTS) {
            throw TechniqueErrors.invalid("tissue_modifier.curve", "曲线必须有 2–16 个点");
        }
        BodyMessages.CurvePoint first = modifier.getCurve(0);
        BodyMessages.CurvePoint last = modifier.getCurve(pointCount - 1);
        if (first.getOffsetMillis() != 0 || first.getValue() != 0 || last.getValue() != 0) {
            throw TechniqueErrors.invalid("tissue_modifier.curve", "首点偏移必须为 0，首尾值必须为 0");
        }
        long endOffsetMillis = last.getOffsetMillis();
        if (endOffsetMillis > MAX_CURVE_MILLIS
                || endOffsetMillis > durationMillis(definition.getPhases())
                        + definition.getCooldownMillis() - effect.getAtMillis()) {
            throw TechniqueErrors.invalid("tissue_modifier.curve", "修正曲线结束晚于冷却结束或超过曲线上限");
        }
        double[] peaks = {
                positive(modifier.getStrengthPeak(), Unit.RATIO, MAX_PEAK, "strength_peak"),
                positive(modifier.getBurstPeak(), Unit.RATIO, MAX_PEAK, "burst_peak"),
                positive(modifier.getEndurancePeak(), Unit.RATIO, MAX_PEAK, "endurance_peak"),
                positive(modifier.getRecoveryPeak(), Unit.RATIO, MAX_PEAK, "recovery_peak")
        };
        validateCurve(modifier, peaks);
    }

    private static void validateCurve(TissueModifier modifier, double[] peaks) {
        // 分段线性的最低倍率只会出现在端点；负曲线值不能把任何能力压到约定下限以下。
        long previousMillis = -1;
        for (BodyMessages.CurvePoint point : modifier.getCurveList()) {
            double value = point.getValue();
            long offsetMillis = point.getOffsetMillis();
            if (offsetMillis <= previousMillis || !Double.isFinite(value) || value < -1 || value > 1) {
                throw TechniqueErrors.invalid("tissue_modifier.curve", "偏移必须严格递增且曲线值位于 [-1, 1]");
            }
            for (double peak : peaks) {
                double multiplier = 1 + (peak - 1) * value;
                if (multiplier < MIN_MULTIPLIER) {
                    throw TechniqueErrors.invalid("tissue_modifier.curve", "曲线最低倍率小于 " + MIN_MULTIPLIER);
                }
            }
            previousMillis = offsetMillis;
        }
    }

    static void validateImpulse(Impulse impulse) {
        // INPUT 只声明方向来源；当前层不读取实体朝向或改变速度。
        if (impulse.getDirectionValue() <= 0 || impulse.getDirection() == ImpulseDirection.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("impulse.direction", "未知冲量方向");
        }
        if (impulse.hasInputDefaultDirection()
                && (impulse.getDirection() != ImpulseDirection.INPUT
                        || (impulse.getInputDefaultDirection() != ImpulseDirection.FORWARD
                                && impulse.getInputDefaultDirection() != ImpulseDirection.BACKWARD))) {
                throw TechniqueErrors.invalid(
                        "impulse.input_default_direction",
                        "仅 INPUT 可声明向前或向后缺省方向");
        }
        positive(impulse.getSpeed(), Unit.METERS_PER_SECOND, MAX_SPEED, "impulse.speed");
    }

    private static void validateAiHints(Category category, AiHints hints) {
        // 提示仅作为数据保存，熟练度与提示均不参与费用、倍率或阶段时长计算。
        double minimum = quantity(hints.getMinimumDistance(), Unit.METER, 0, MAX_DISTANCE, "minimum_distance");
        double maximum = quantity(hints.getMaximumDistance(), Unit.METER, 0, MAX_DISTANCE, "maximum_distance");
        if (minimum > maximum) {
            throw TechniqueErrors.invalid("ai_hints", "最小距离不得大于最大距离");
        }
        quantity(hints.getMaximumHpRatio(), Unit.RATIO, 0, 1, "maximum_hp_ratio");
        quantity(hints.getMinimumQiRatio(), Unit.RATIO, 0, 1, "minimum_qi_ratio");
        quantity(hints.getCategoryWeight(), Unit.RATIO, 0, MAX_WEIGHT, "category_weight");
        validateCombatHints(hints.getCombat());
        if (hints.hasSupport()) {
            validateSupportHints(category, hints.getSupport());
        }
    }

    private static void validateSupportHints(Category category, SupportAiHints hints) {
        // 角色必须显式；准备式须为增益类、恢复式须为恢复类，打击式不能被误标成辅助。
        if (hints.getRoleValue() <= 0 || hints.getRole() == AiRole.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("support.role", "辅助角色必须显式且已定义");
        }
        Category required = SUPPORT_CATEGORIES.get(hints.getRole());
        if (required != null && category != required) {
            throw TechniqueErrors.invalid("support.role", hints.getRole() + " 要求类别为 " + required);
        }
        if (hints.hasOrder() && (hints.getOrder() < 0 || hints.getOrder() > MAX_SUPPORT_ORDER)) {
            throw TechniqueErrors.invalid("support.order", "顺序必须位于 [0, " + MAX_SUPPORT_ORDER + "]");
        }
        if (hints.hasMinimumFatigueRatio()) {
            quantity(hints.getMinimumFatigueRatio(), Unit.RATIO, 0, 1, "minimum_fatigue_ratio");
        }
        if (hints.hasRefreshBeforeExpiryMillis()) {
            millis(hints.getRefreshBeforeExpiryMillis(), "refresh_before_expiry_millis");
        }
    }

    private static void validateCombatHints(CombatAiHints hints) {
        // 情境权重允许零值排除候选，时长和距离门槛必须成对声明。
        if (hints.hasTargetShieldingWeight()) {
            quantity(hints.getTargetShieldingWeight(), Unit.RATIO, 0, MAX_WEIGHT, "target_shielding_weight");
        }
        if (hints.hasSurroundedWeight()) {
            quantity(hints.getSurroundedWeight(), Unit.RATIO, 0, MAX_WEIGHT, "surrounded_weight");
        }
        if (hints.hasUnderAttackWeight()) {
            quantity(hints.getUnderAttackWeight(), Unit.RATIO, 0, MAX_WEIGHT, "under_attack_weight");
        }
        if (hints.hasPursuedWeight()) {
            quantity(hints.getPursuedWeight(), Unit.RATIO, 0, MAX_WEIGHT, "pursued_weight");
        }
        if (hints.hasTargetBehindWeight()) {
            quantity(hints.getTargetBehindWeight(), Unit.RATIO, 0, MAX_WEIGHT, "target_behind_weight");
        }
        if (hints.hasTargetAirborneWeight()) {
            quantity(hints.getTargetAirborneWeight(), Unit.RATIO, 0, MAX_WEIGHT, "target_airborne_weight");
        }
        if (hints.hasSelfFleeingWeight()) {
            quantity(hints.getSelfFleeingWeight(), Unit.RATIO, 0, MAX_WEIGHT, "self_fleeing_weight");
        }
        if (hints.hasMinimumLegForce()) {
            quantity(hints.getMinimumLegForce(), Unit.NEWTON, 0, MAX_QUANTITY, "minimum_leg_force");
        }
        if (hints.hasMaximumWarmupUnderAttackMillis()) {
            millis(hints.getMaximumWarmupUnderAttackMillis(), "maximum_warmup_under_attack_millis");
        }
        if (hints.hasMaximumLongWarmupDistance() != hints.hasLongWarmupMillis()) {
            throw TechniqueErrors.invalid("combat", "长起手的距离和时长门槛须同时提供");
        }
        if (hints.hasLongWarmupMillis()) {
            millis(hints.getLongWarmupMillis(), "long_warmup_millis");
            quantity(hints.getMaximumLongWarmupDistance(), Unit.METER, 0, MAX_DISTANCE,
                    "maximum_long_warmup_distance");
        }
    }

    private static void validatePresentation(Presentation presentation) {
        // 首版内容可以完全省略表现；提供资源身份时则必须格式合法。声音已改由效果时间线声明。
        if (!presentation.getSoundId().isEmpty()) {
            throw TechniqueErrors.invalid("presentation.sound_id", "已废弃，声音改用 effects.sound");
        }
        for (String resource : new String[] {presentation.getIconId(), presentation.getParticleId()}) {
            if (!resource.isEmpty()) {
                id(resource, "presentation");
            }
        }
        if (presentation.getDescription().length() > MAX_DESCRIPTION) {
            throw TechniqueErrors.invalid("presentation.description", "说明长度超限");
        }
    }

    private static TechniqueSlot requireSlot(Map<String, TechniqueSlot> slots, String id, ModuleKind module) {
        // 不同算子的槽位要求显式固定，错误种类不能等到运行时才被忽略。
        TechniqueSlot slot = slots.get(id);
        if (slot == null || slot.getModule() != module) {
            throw TechniqueErrors.invalid(id, "槽位不存在或模块种类不符，应为 " + module);
        }
        return slot;
    }

    private static void validateSymbol(AddonRegistry addons, String symbolId, ContentSymbolKind expected) {
        // 属性与组织/器官类型复用 Framework 注册身份，不建立另一套符号目录。
        id(symbolId, "kind/attribute");
        var symbol = addons.findSymbol(symbolId)
                .orElseThrow(() -> TechniqueErrors.invalid(symbolId, "内容身份未登记"));
        if (symbol.getKind() != expected) {
            throw TechniqueErrors.invalid(symbolId, "内容身份种类不符，应为 " + expected);
        }
    }
}
