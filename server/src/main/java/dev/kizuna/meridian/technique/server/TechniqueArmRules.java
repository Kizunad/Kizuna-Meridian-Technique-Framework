package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ArmRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

/** 拳掌书级条件的目录边界。 */
final class TechniqueArmRules {
    private TechniqueArmRules() {
        // 书级规则只描述数据，不读取宿主物品或身体状态。
    }

    static void validate(ArmRules rules) {
        // 拳掌必须明确忽略手里的东西，并写明要不要着地（不能靠默认值）；空手基础伤害也不能依赖默认值。
        boolean ignoresHeldItems = rules.hasIgnoreHeldItems() && rules.getIgnoreHeldItems();
        if (!ignoresHeldItems || !rules.hasRequireGrounded()) {
            throw TechniqueErrors.invalid("arm_rules", "拳掌须显式忽略手持物品并写明要不要着地");
        }
        TechniqueValues.positive(rules.getBaseDamage(), Unit.DAMAGE_POINT,
                TechniqueValues.MAX_QUANTITY, "arm_rules.base_damage");
    }
}
