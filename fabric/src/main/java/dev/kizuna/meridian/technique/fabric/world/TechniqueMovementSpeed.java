package dev.kizuna.meridian.technique.fabric.world;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/** 把移动倍率换成移速属性修饰：怪物按 √倍率、玩家照倍率，两边的实际地面移速都是倍率倍。 */
public final class TechniqueMovementSpeed {
    private TechniqueMovementSpeed() {
        // 只有静态换算入口，不保存实体状态。
    }

    public static double attributeMultiplier(LivingEntity entity, double multiplier) {
        // 原版 Mob.setSpeed 同时把前进输入设成同一个值，怪物的地面加速度跟移速属性的平方成正比；
        // 玩家的前进输入来自按键，加速度只跟属性成正比。怪物按开平方后的倍率改属性，两边实际都走倍率倍。
        if (!Double.isFinite(multiplier) || multiplier < 0) {
            throw new IllegalArgumentException("移动倍率必须是非负有限数：" + multiplier);
        }
        if (entity instanceof Mob) {
            return Math.sqrt(multiplier);
        }
        return multiplier;
    }

    public static AttributeModifier modifier(LivingEntity entity, Identifier id, double multiplier) {
        // 按总值相乘，和同一实体上的其他乘法修饰相乘叠加；数值是换算后的属性倍率减一，倍率 1 时为 0。
        double amount = attributeMultiplier(entity, multiplier) - 1;
        return new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }
}
