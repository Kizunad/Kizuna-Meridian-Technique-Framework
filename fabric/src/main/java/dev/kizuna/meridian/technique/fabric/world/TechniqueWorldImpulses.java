package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ImpulseDirection;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 水平推进与竖直跳跃各自更新正交分量，交付去重由宿主负责。 */
public final class TechniqueWorldImpulses {
    private TechniqueWorldImpulses() {
        // 无状态组合器不保留实体速度副本。
    }

    public static void apply(LivingEntity entity, ImpulseDirection direction, Vec3 velocity) {
        // 两个同刻冲量不互相覆盖；也保留重力与其他来源的正交速度。
        Vec3 current = entity.getDeltaMovement();
        Vec3 combined;
        if (direction == ImpulseDirection.UP) {
            combined = new Vec3(current.x, velocity.y, current.z);
        } else {
            combined = new Vec3(velocity.x, current.y, velocity.z);
        }
        entity.setDeltaMovement(combined);
        entity.hurtMarked = true;
    }
}
