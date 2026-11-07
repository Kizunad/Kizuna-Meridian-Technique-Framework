package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.DurabilityRule;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Hand;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSelectionCriterion;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.InjuryKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ShieldInteraction;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** 书级持物规则只读取宿主事实，不读取物品注册表或执行耐久变化。 */
public final class TechniqueHeldItems {
    private static final int MAX_RULES = 32;
    private static final int MAX_ITEM_TAGS = 256;
    private static final int SELECTION_CRITERIA_COUNT = 3;

    private TechniqueHeldItems() {
        // 持物判定没有实体、世界或可变施放缓存。
    }

    enum AmmunitionRequirement {
        NOT_REQUIRED,
        REQUIRED
    }

    static void validateRules(HeldItemRules rules) {
        // 有序类别和耐久表必须以空标签兜底，避免运行时出现未定义的物品类别。
        if (rules.getCategoriesCount() == 0 || rules.getCategoriesCount() > MAX_RULES
                || rules.getDurabilityCount() == 0 || rules.getDurabilityCount() > MAX_RULES) {
            throw TechniqueErrors.invalid("held_item_rules", "类别和耐久表须非空且不超过上限");
        }
        validateCategories(rules.getCategoriesList());
        validateDurability(rules.getDurabilityList());
        validateSelectionOrder(rules.getSelectionOrderList());
        validateAmmunition(rules);
    }

    private static void validateCategories(List<HeldItemCategory> categories) {
        // 类别身份在书内唯一，只有末项匹配所有剩余物品。
        var ids = new HashSet<String>();
        int index = 0;
        for (HeldItemCategory category : categories) {
            TechniqueValues.localId(category.getId(), "category.id");
            if (!ids.add(category.getId())) {
                throw TechniqueErrors.invalid("category.id", "类别 ID 重复");
            }
            validateMatcher(category.getItemTagsList(), index++, categories.size());
            validateCategory(category);
        }
    }

    private static void validateDurability(List<DurabilityRule> durability) {
        // 耐久表按声明次序首次命中，倍率不读取或改变物品本身的耐久。
        int index = 0;
        for (DurabilityRule rule : durability) {
            validateMatcher(rule.getItemTagsList(), index++, durability.size());
            TechniqueValues.positive(rule.getMultiplier(), Unit.RATIO, TechniqueValues.MAX_PEAK,
                    "durability.multiplier");
        }
    }

    private static void validateSelectionOrder(List<HandSelectionCriterion> order) {
        // 主手仅作为完全相同候选的最后平局判定。
        if (order.size() != SELECTION_CRITERIA_COUNT
                || new HashSet<>(order).size() != order.size()
                || !order.containsAll(List.of(
                        HandSelectionCriterion.CATEGORY_PRIORITY,
                        HandSelectionCriterion.DURABILITY_MULTIPLIER,
                        HandSelectionCriterion.PREFER_MAIN_HAND))
                || order.get(order.size() - 1) != HandSelectionCriterion.PREFER_MAIN_HAND) {
            throw TechniqueErrors.invalid(
                    "selection_order",
                    "类别、耐久、主手须各出现一次，主手放最后");
        }
    }

    private static void validateCategory(HeldItemCategory category) {
        // 伤害只定义计算来源及参数，致伤和破盾语义交给未来的宿主打击执行器。
        if (!category.hasSelectionPriority() || category.getSelectionPriority() < 0
                || !category.hasCanBreakDoors() || category.getInjuryKindValue() <= 0
                || category.getInjuryKind() == InjuryKind.UNRECOGNIZED
                || category.getShieldInteractionValue() <= 0
                || category.getShieldInteraction() == ShieldInteraction.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("category", "选手优先级、致伤、破盾或劈门声明非法");
        }
        if (category.hasProjectileWeapon()
                && (category.getProjectileWeapon() == ProjectileWeaponCategory.UNRECOGNIZED
                        || category.getProjectileWeapon()
                        == ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED
                        || category.getItemTagsCount() == 0)) {
            throw TechniqueErrors.invalid("category.projectile_weapon", "未知远程武器类别");
        }
        validateTags(category.getAmmunitionTagsList());
        if (category.getAmmunitionTagsCount() > 0 && !category.hasProjectileWeapon()) {
            throw TechniqueErrors.invalid("category.ammunition_tags", "弹药覆盖须声明远程武器类别");
        }
        switch (category.getBaseDamageSource()) {
            case ITEM_ATTACK_DAMAGE -> TechniqueValues.positive(category.getBaseDamage(), Unit.RATIO,
                    TechniqueValues.MAX_PEAK, "category.base_damage");
            case FIXED_DAMAGE -> TechniqueValues.quantity(category.getBaseDamage(), Unit.DAMAGE_POINT,
                    0, TechniqueValues.MAX_QUANTITY, "category.base_damage");
            default -> throw TechniqueErrors.invalid("category.base_damage_source", "未知基础伤害取法");
        }
    }

    private static void validateMatcher(List<String> tags, int index, int count) {
        // 只有末项使用空标签；其他项至少匹配一个标签，同项标签按任一命中解释。
        validateTags(tags);
        if (tags.isEmpty() != (index == count - 1)) {
            throw TechniqueErrors.invalid("item_tags", "仅表的最后一项必须为空标签兜底");
        }
    }

    public static void validate(HeldItems items) {
        // 主手侧和两手消息都必须由宿主显式提供，不能把未接入误当成空手。
        TechniqueValues.knownFields(items);
        if (!items.hasMainHand() || !items.hasOffHand() || items.getMainHandSideValue() <= 0
                || items.getMainHandSide() == HandSide.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("held_items", "缺少两手物品或主手侧");
        }
        validateItem(items.getMainHand());
        validateItem(items.getOffHand());
        validateProjectileWeapon(items.getMainHand(), items.getMainHandProjectileWeapon(),
                "main_hand_projectile_weapon");
        validateProjectileWeapon(items.getOffHand(), items.getOffHandProjectileWeapon(),
                "off_hand_projectile_weapon");
    }

    private static void validateAmmunition(HeldItemRules rules) {
        // 需要弹药或限制另一只手时必须提供可匹配的标签，避免运行时把所有物品
        // 当成箭。
        validateTags(rules.getAmmunitionTagsList());
        if ((rules.getRequireAmmunition() || rules.getOtherHandEmptyOrAmmunition())
                && rules.getAmmunitionTagsCount() == 0) {
            throw TechniqueErrors.invalid("ammunition_tags", "弹药规则必须声明至少一个标签");
        }
    }

    private static void validateProjectileWeapon(HeldItem item, ProjectileWeaponCategory weapon,
                                                 String path) {
        // 空手不携带弓弩类别，非空物品的类别由宿主按同次 inspect 明确提供。
        if (weapon == ProjectileWeaponCategory.UNRECOGNIZED) {
            throw TechniqueErrors.invalid(path, "远程武器类别非法");
        }
        if (item.getItemId().isEmpty()
                && weapon != ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED) {
            throw TechniqueErrors.invalid(path, "空手不能声明远程武器类别");
        }
    }

    private static void validateItem(HeldItem item) {
        // 空手不携带物品事实；有物品时必须显式提供耐久和容器状态。
        if (item.getItemId().isEmpty()) {
            if (!item.equals(HeldItem.getDefaultInstance())) {
                throw TechniqueErrors.invalid("held_item", "空手不得携带物品信息");
            }
            return;
        }
        TechniqueValues.id(item.getItemId(), "held_item.item_id");
        validateTags(item.getTagsList());
        if (!item.hasDamageable() || !item.hasContainsItems()) {
            throw TechniqueErrors.invalid("held_item", "有物品时须声明耐久和是否装着东西");
        }
    }

    private static void validateTags(List<String> tags) {
        // 物品标签由宿主数据包解释，本库只校验格式、规模和重复项。
        if (tags.size() > MAX_ITEM_TAGS || new HashSet<>(tags).size() != tags.size()) {
            throw TechniqueErrors.invalid("item_tags", "物品标签重复或数量超限");
        }
        for (String tag : tags) {
            TechniqueValues.id(tag, "item_tags");
        }
    }

    public static Hand select(HeldItemRules rules, HeldItems items) {
        // 无式级覆盖时使用书级要求，近战书不要求宿主查询弹药。
        return select(rules, items, ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED,
                ammunitionRequirement(TechniqueDefinition.getDefaultInstance(), rules));
    }

    public static Hand select(HeldItemRules rules, HeldItems items, TechniqueDefinition definition) {
        // 宿主预判和正式起手共用式级武器与弹药覆盖，避免 AI 误选另一类武器。
        return select(rules, items, definition.getProjectileWeapon(), ammunitionRequirement(definition, rules));
    }

    private static AmmunitionRequirement ammunitionRequirement(TechniqueDefinition definition, HeldItemRules rules) {
        // 单式显式设置覆盖书级要求，训练式可关闭弹药检查。
        boolean required;
        if (definition.hasRequireAmmunition()) {
            required = definition.getRequireAmmunition();
        } else {
            required = rules.getRequireAmmunition();
        }
        if (required) {
            return AmmunitionRequirement.REQUIRED;
        }
        return AmmunitionRequirement.NOT_REQUIRED;
    }

    static void validateDefinition(HeldItemRules rules, TechniqueDefinition definition) {
        // 远程式不能引用书中不存在的武器类别，式级弹药覆盖也必须有可解释的标签。
        HeldItemCategory category = rules.getCategoriesList().stream()
                .filter(entry -> entry.getProjectileWeapon() == definition.getProjectileWeapon())
                .findFirst().orElseThrow(() -> TechniqueErrors.invalid(
                        definition.getId(), "书级规则没有声明本式武器类别"));
        if (ammunitionRequirement(definition, rules) == AmmunitionRequirement.REQUIRED
                && rules.getAmmunitionTagsCount() == 0 && category.getAmmunitionTagsCount() == 0) {
            throw TechniqueErrors.invalid("require_ammunition", "弹药要求没有对应标签");
        }
    }

    static Hand select(HeldItemRules rules, HeldItems items, ProjectileWeaponCategory requiredWeapon,
                       AmmunitionRequirement ammunition) {
        // 装有物品的容器不作为候选；远程式先选武器，再检查另一只手和弹药。
        validateRules(rules);
        validate(items);
        validateWeapon(requiredWeapon);
        validateRangedInspection(items, requiredWeapon);
        HeldItem main = items.getMainHand();
        HeldItem off = items.getOffHand();
        if (main.getItemId().isEmpty() && off.getItemId().isEmpty()) {
            throw TechniqueErrors.rejected(requiredFailure(requiredWeapon, TechniqueFailure.HELD_ITEM_REQUIRED));
        }
        Candidate mainCandidate = candidate(rules, items, main, Hand.MAIN_HAND, requiredWeapon);
        Candidate offCandidate = candidate(rules, items, off, Hand.OFF_HAND, requiredWeapon);
        if (mainCandidate == null && offCandidate == null) {
            throw TechniqueErrors.rejected(requiredFailure(requiredWeapon, TechniqueFailure.HELD_ITEM_UNUSABLE));
        }
        Hand selected;
        if (requiredWeapon != ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED
                && mainCandidate != null) {
            selected = Hand.MAIN_HAND;
        } else {
            selected = selectCandidate(rules, mainCandidate, offCandidate);
        }
        validateOtherHand(rules, items, selected);
        if (ammunition == AmmunitionRequirement.REQUIRED) {
            validateAmmunitionAvailable(items);
        }
        return selected;
    }

    private static Hand selectCandidate(HeldItemRules rules, Candidate mainCandidate,
                                        Candidate offCandidate) {
        // 只有候选存在时才按书级顺序比较，避免远程失败被主手偏好掩盖。
        if (mainCandidate == null) {
            return Hand.OFF_HAND;
        }
        if (offCandidate == null) {
            return Hand.MAIN_HAND;
        }
        Comparator<Candidate> comparator = comparator(rules);
        if (comparator.compare(mainCandidate, offCandidate) <= 0) {
            return Hand.MAIN_HAND;
        }
        return Hand.OFF_HAND;
    }

    public static Optional<Hand> find(HeldItemRules rules, HeldItems items) {
        // 宿主 AI 预判用：与 select 同一规则，没有可用手时为空而不抛出拒绝。
        return find(rules, items, ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED,
                ammunitionRequirement(TechniqueDefinition.getDefaultInstance(), rules));
    }

    public static Optional<Hand> find(HeldItemRules rules, HeldItems items, TechniqueDefinition definition) {
        // AI 使用同一式级条件，拒绝不会产生费用或世界副作用。
        return find(rules, items, definition.getProjectileWeapon(), ammunitionRequirement(definition, rules));
    }

    static Optional<Hand> find(HeldItemRules rules, HeldItems items,
                               ProjectileWeaponCategory requiredWeapon,
                               AmmunitionRequirement ammunition) {
        // AI 预判复用完整远程持物规则，没有可用手时为空而不抛出拒绝。
        try {
            return Optional.of(select(rules, items, requiredWeapon, ammunition));
        } catch (TechniqueErrors.Rejected rejected) {
            return Optional.empty();
        }
    }

    private static Candidate candidate(HeldItemRules rules, HeldItems items, HeldItem item, Hand hand,
                                      ProjectileWeaponCategory requiredWeapon) {
        // 类别和耐久表各取首次命中，无耐久物品用无穷排序值表示一次性消耗。
        if (item.getItemId().isEmpty() || item.getContainsItems()) {
            return null;
        }
        Optional<HeldItemCategory> categoryResult = rules.getCategoriesList().stream()
                .filter(entry -> matches(entry.getItemTagsList(), item))
                .findFirst();
        if (categoryResult.isEmpty() || !matchesWeapon(categoryResult.orElseThrow(), items, hand, requiredWeapon)) {
            return null;
        }
        HeldItemCategory category = categoryResult.get();
        double durability = Double.POSITIVE_INFINITY;
        if (item.getDamageable()) {
            durability = rules.getDurabilityList().stream()
                    .filter(entry -> matches(entry.getItemTagsList(), item))
                    .findFirst().orElseThrow().getMultiplier().getValue();
        }
        return new Candidate(hand, category.getSelectionPriority(), durability);
    }

    private static boolean matchesWeapon(HeldItemCategory category, HeldItems items, Hand hand,
                                         ProjectileWeaponCategory requiredWeapon) {
        // 目录标签与宿主武器类别必须同时命中，兜底物品不能冒充射击武器。
        if (requiredWeapon == ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED) {
            return true;
        }
        ProjectileWeaponCategory inspected = inspectedWeapon(items, hand);
        return inspected == requiredWeapon && category.getProjectileWeapon() == requiredWeapon;
    }

    private static ProjectileWeaponCategory inspectedWeapon(HeldItems items, Hand hand) {
        // 使用起手候选所在手的显式观察，不从物品 ID 猜测武器类型。
        if (hand == Hand.MAIN_HAND && items.hasMainHandProjectileWeapon()) {
            return items.getMainHandProjectileWeapon();
        }
        if (hand == Hand.OFF_HAND && items.hasOffHandProjectileWeapon()) {
            return items.getOffHandProjectileWeapon();
        }
        return ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED;
    }

    private static void validateOtherHand(HeldItemRules rules, HeldItems items, Hand selected) {
        // 开启限制时，弓弩的另一只手只能空着或拿对应类别的弹药。
        if (!rules.getOtherHandEmptyOrAmmunition()) {
            return;
        }
        HeldItem other;
        HeldItem weapon;
        if (selected == Hand.MAIN_HAND) {
            other = items.getOffHand();
            weapon = items.getMainHand();
        } else {
            other = items.getMainHand();
            weapon = items.getOffHand();
        }
        HeldItemCategory category = rules.getCategoriesList().stream()
                .filter(entry -> matches(entry.getItemTagsList(), weapon))
                .findFirst().orElseThrow();
        List<String> ammunitionTags = rules.getAmmunitionTagsList();
        if (category.getAmmunitionTagsCount() > 0) {
            ammunitionTags = category.getAmmunitionTagsList();
        }
        if (!other.getItemId().isEmpty()
                && (other.getContainsItems() || !matches(ammunitionTags, other))) {
            throw TechniqueErrors.rejected(TechniqueFailure.OTHER_HAND_AMMUNITION_REQUIRED);
        }
    }

    private static void validateAmmunitionAvailable(HeldItems items) {
        // 背包事实只有宿主知道，缺少显式回执不能按有弹药继续施放。
        if (!items.hasCanFindAmmunition()) {
            throw TechniqueErrors.invalid("can_find_ammunition", "缺少宿主弹药观察");
        }
        if (!items.getCanFindAmmunition()) {
            throw TechniqueErrors.rejected(TechniqueFailure.AMMUNITION_REQUIRED);
        }
    }

    private static void validateRangedInspection(HeldItems items, ProjectileWeaponCategory weapon) {
        // 远程式必须接入双手武器观察；漏报是宿主契约错误，不伪装成玩家没拿弓。
        if (weapon != ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED
                && (!items.hasMainHandProjectileWeapon() || !items.hasOffHandProjectileWeapon())) {
            throw TechniqueErrors.invalid("held_items", "远程施放缺少双手武器类别观察");
        }
    }

    private static void validateWeapon(ProjectileWeaponCategory weapon) {
        // 未指定表示旧式持物招式；远程式只接受弓或弩。
        if (weapon == ProjectileWeaponCategory.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("projectile_weapon", "未知远程武器类别");
        }
    }

    private static TechniqueFailure requiredFailure(ProjectileWeaponCategory weapon,
                                                     TechniqueFailure fallback) {
        // 武器类别失败需要给动作栏一个可理解的方向，旧式持物仍沿用原失败码。
        return switch (weapon) {
            case BOW -> TechniqueFailure.BOW_REQUIRED;
            case CROSSBOW -> TechniqueFailure.CROSSBOW_REQUIRED;
            case PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED -> fallback;
            case UNRECOGNIZED -> TechniqueFailure.INVALID_STATE;
        };
    }

    private static boolean matches(List<String> tags, HeldItem item) {
        // 末项空标签覆盖所有剩余物品，标签顺序不影响同一项的匹配结果。
        return tags.isEmpty() || tags.stream().anyMatch(item.getTagsList()::contains);
    }

    private static Comparator<Candidate> comparator(HeldItemRules rules) {
        // 比较器完全由书声明的顺序组成，最后以主手固定打破平局。
        Comparator<Candidate> result = (first, second) -> 0;
        for (HandSelectionCriterion criterion : rules.getSelectionOrderList()) {
            Comparator<Candidate> next = switch (criterion) {
                case CATEGORY_PRIORITY -> Comparator.comparingInt(Candidate::priority);
                case DURABILITY_MULTIPLIER -> Comparator.comparingDouble(Candidate::durability);
                case PREFER_MAIN_HAND -> Comparator.comparingInt(candidate -> candidate.hand().getNumber());
                default -> throw TechniqueErrors.invalid("selection_order", "未知选手规则");
            };
            result = result.thenComparing(next);
        }
        return result;
    }

    private record Candidate(Hand hand, int priority, double durability) { }
}
