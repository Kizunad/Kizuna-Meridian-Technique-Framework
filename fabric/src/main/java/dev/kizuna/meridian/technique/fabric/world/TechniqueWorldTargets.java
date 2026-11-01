package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

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
        List<LivingEntity> nearby = actor.level().getEntitiesOfClass(LivingEntity.class,
                actor.getBoundingBox().inflate(distance), target -> eligible(actor, target, hostile))
                .stream()
                .sorted(Comparator.<LivingEntity>comparingDouble(actor::distanceToSqr)
                        .thenComparing(LivingEntity::getStringUUID))
                .toList();
        if (strike.getShape() == StrikeShape.SINGLE_TARGET) {
            LivingEntity aimed = aimed(actor, nearby, distance);
            if (aimed != null) {
                return List.of(aimed);
            }
        }
        return nearby.stream()
                .filter(target -> actor.distanceToSqr(target) <= distance * distance)
                .filter(target -> inSector(actor, target, strike))
                .toList();
    }

    private static boolean eligible(LivingEntity actor, LivingEntity target, Predicate<LivingEntity> hostile) {
        // 框架只排除自己、死亡、旁观者和视线外的目标；敌我（含同队、同伴）全部交宿主谓词，
        // 宿主才知道「同伴但正是我的敌人」这类例外。
        if (target == actor || !target.isAlive() || target.isSpectator()
                || !actor.hasLineOfSight(target) || !hostile.test(target)) {
            return false;
        }
        return !(target instanceof ArmorStand stand) || !stand.isMarker();
    }

    private static LivingEntity aimed(LivingEntity actor, List<LivingEntity> nearby, double distance) {
        // 方块射线截断视线，避免击中墙后目标；实体碰撞取射线遇到的第一个。
        Vec3 start = actor.getEyePosition();
        Vec3 end = start.add(actor.getViewVector(1).scale(distance));
        var block = actor.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, actor));
        Vec3 limit = block.getLocation();
        return nearby.stream()
                .filter(target -> target.getBoundingBox().inflate(TARGET_MARGIN).clip(start, limit).isPresent())
                .min(Comparator.comparingDouble(target -> target.getBoundingBox().inflate(TARGET_MARGIN)
                        .clip(start, limit).orElseThrow().distanceToSqr(start)))
                .orElse(null);
    }

    private static boolean inSector(LivingEntity actor, LivingEntity target, WeaponStrike strike) {
        // 扇区只比较水平方向，纵向仍由最大距离约束。
        Vec3 facing = Vec3.directionFromRotation(0, actor.getYRot() + (float) strike.getFacingOffset().getValue());
        Vec3 delta = target.position().subtract(actor.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() == 0) {
            return true;
        }
        double threshold = Math.cos(Math.toRadians(strike.getAngle().getValue() * HALF));
        return facing.dot(delta.normalize()) >= threshold;
    }
}
