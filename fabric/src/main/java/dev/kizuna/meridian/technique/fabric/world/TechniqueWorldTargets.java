package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** 目标几何随每次打击读取实际朝向，敌我策略由实体宿主提供。 */
final class TechniqueWorldTargets {
    private static final double HALF = 0.5;
    private static final double TARGET_MARGIN = 0.1;

    private TechniqueWorldTargets() {
        // 几何筛选没有施放游标，去重预算由执行器统一处理。
    }

    static List<LivingEntity> candidates(LivingEntity actor, WeaponStrike strike, Predicate<LivingEntity> hostile) {
        // 先统一过滤可伤害对象，再按距离及 UUID 稳定排序。
        double distance = strike.getDistance().getValue();
        List<LivingEntity> nearby = actor.getWorld().getEntitiesByClass(LivingEntity.class,
                actor.getBoundingBox().expand(distance), target -> eligible(actor, target, hostile))
                .stream()
                .sorted(Comparator.<LivingEntity>comparingDouble(actor::squaredDistanceTo)
                        .thenComparing(LivingEntity::getUuidAsString))
                .toList();
        if (strike.getShape() == StrikeShape.SINGLE_TARGET) {
            LivingEntity aimed = aimed(actor, nearby, distance);
            if (aimed != null) {
                return List.of(aimed);
            }
        }
        return nearby.stream()
                .filter(target -> actor.squaredDistanceTo(target) <= distance * distance)
                .filter(target -> inSector(actor, target, strike))
                .toList();
    }

    private static boolean eligible(LivingEntity actor, LivingEntity target, Predicate<LivingEntity> hostile) {
        // 世界障碍与原版队伍规则适用于玩家和生物。
        if (target == actor || !target.isAlive() || target.isSpectator() || actor.isTeammate(target)
                || !actor.canSee(target) || !hostile.test(target)) {
            return false;
        }
        return !(target instanceof ArmorStandEntity stand) || !stand.isMarker();
    }

    private static LivingEntity aimed(LivingEntity actor, List<LivingEntity> nearby, double distance) {
        // 方块射线截断视线，避免击中墙后目标；实体碰撞取射线遇到的第一个。
        Vec3d start = actor.getEyePos();
        Vec3d end = start.add(actor.getRotationVec(1).multiply(distance));
        var block = actor.getWorld().raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, actor));
        Vec3d limit = block.getPos();
        return nearby.stream()
                .filter(target -> target.getBoundingBox().expand(TARGET_MARGIN).raycast(start, limit).isPresent())
                .min(Comparator.comparingDouble(target -> target.getBoundingBox().expand(TARGET_MARGIN)
                        .raycast(start, limit).orElseThrow().squaredDistanceTo(start)))
                .orElse(null);
    }

    private static boolean inSector(LivingEntity actor, LivingEntity target, WeaponStrike strike) {
        // 扇区只比较水平方向，纵向仍由最大距离约束。
        Vec3d facing = Vec3d.fromPolar(0, actor.getYaw() + (float) strike.getFacingOffset().getValue());
        Vec3d delta = target.getPos().subtract(actor.getPos()).multiply(1, 0, 1);
        if (delta.lengthSquared() == 0) {
            return true;
        }
        double threshold = Math.cos(Math.toRadians(strike.getAngle().getValue() * HALF));
        return facing.dotProduct(delta.normalize()) >= threshold;
    }
}
