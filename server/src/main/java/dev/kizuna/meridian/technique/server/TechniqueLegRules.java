package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LegRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

/** 腿法书级条件与靴子参数的目录边界。 */
final class TechniqueLegRules {
    private static final int MAX_DURABILITY_COST = 1024;

    private TechniqueLegRules() {
        // 书级规则只描述数据，不读取宿主物品或身体状态。
    }

    static void validate(LegRules rules) {
        // 腿法必须明确忽略手持物品并要求着地，鞋的三项参数不能依赖默认值。
        if (!rules.hasIgnoreHeldItems() || !rules.getIgnoreHeldItems()
                || !rules.hasRequireGrounded() || !rules.getRequireGrounded()
                || !rules.hasDurabilityCost() || rules.getDurabilityCost() < 1
                || rules.getDurabilityCost() > MAX_DURABILITY_COST) {
            throw TechniqueErrors.invalid(
                    "leg_rules", "腿法须显式忽略手持物品、要求着地并声明靴子耐久消耗");
        }
        TechniqueValues.positive(rules.getBaseDamage(), Unit.DAMAGE_POINT,
                TechniqueValues.MAX_QUANTITY, "leg_rules.base_damage");
        TechniqueValues.quantity(rules.getArmorDamage(), Unit.DAMAGE_POINT,
                0, TechniqueValues.MAX_QUANTITY, "leg_rules.armor_damage");
    }
}
