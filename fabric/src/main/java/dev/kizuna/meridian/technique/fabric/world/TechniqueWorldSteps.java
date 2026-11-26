package dev.kizuna.meridian.technique.fabric.world;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 定距短移：沿水平方向逐段试探，只走到不撞墙、脚下有支撑的最远处，再折算成一次初速度。 */
public final class TechniqueWorldSteps {
    private static final double PROBE = 0.1;
    private static final double SUPPORT_DEPTH = 0.6;
    /** 落脚处向内收一点再查支撑，免得只靠碰撞箱的浮点尾差搭在边上。 */
    private static final double SUPPORT_INSET = 0.01;
    /** 原版普通地面每刻保留的水平速度比例：方块摩擦 0.6 乘空气阻力 0.91。 */
    private static final double GROUND_KEEP = 0.546;

    private TechniqueWorldSteps() {
        // 只读世界碰撞，不保存实体或位置。
    }

    public static double reach(LivingEntity entity, Vec3 direction, double maximum) {
        // 每一段都要求身体放得下、脚下半格多之内有碰撞面；第一处不满足就停在上一段。
        Vec3 heading = direction.multiply(1, 0, 1).normalize();
        AABB body = entity.getBoundingBox();
        int probes = (int) Math.ceil(maximum / PROBE);
        double reached = 0;
        for (int index = 1; index <= probes; index++) {
            double offset = Math.min(index * PROBE, maximum);
            AABB moved = body.move(heading.scale(offset));
            AABB below = new AABB(moved.minX + SUPPORT_INSET, moved.minY - SUPPORT_DEPTH, moved.minZ + SUPPORT_INSET,
                    moved.maxX - SUPPORT_INSET, moved.minY, moved.maxZ - SUPPORT_INSET);
            if (!entity.level().noCollision(entity, moved) || entity.level().noCollision(entity, below)) {
                break;
            }
            reached = offset;
        }
        return reached;
    }

    public static Vec3 velocity(Vec3 direction, double distance, double capPerTick) {
        // 地面上初速度逐刻按固定比例衰减，总位移是初速度除以每刻损失的比例。
        double speed = Math.min(distance * (1 - GROUND_KEEP), capPerTick);
        return direction.multiply(1, 0, 1).normalize().scale(speed);
    }
}
