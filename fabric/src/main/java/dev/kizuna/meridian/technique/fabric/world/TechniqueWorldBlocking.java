package dev.kizuna.meridian.technique.fabric.world;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.phys.Vec3;

/** 功法命中持盾目标时的正面判定，与 26.2 原版 {@code LivingEntity.applyItemBlocking} 用同一套朝向与阈值。 */
final class TechniqueWorldBlocking {
    private TechniqueWorldBlocking() {
    }

    /** 调用方先确认 {@code target.isBlocking()}；只判朝向，伤害类型过滤与穿透箭仍由原版受伤链处理。 */
    static boolean facesSource(LivingEntity target, DamageSource source) {
        // 原版取头部水平朝向（俯仰按 0）与来源水平方向的夹角，来源没有位置时按正背后处理，夹角同样先转 float。
        // 夹角不超过格挡物品任一减伤项的水平格挡角才算正面；原版盾只有一项，格挡角 90°。
        BlocksAttacks blocksAttacks = target.getItemBlockingWith().get(DataComponents.BLOCKS_ATTACKS);
        double angle = (float) Math.PI;
        Vec3 sourcePosition = source.getSourcePosition();
        if (sourcePosition != null) {
            Vec3 view = target.calculateViewVector(0.0F, target.getYHeadRot());
            Vec3 toward = sourcePosition.subtract(target.position());
            toward = new Vec3(toward.x, 0.0, toward.z).normalize();
            angle = Math.acos(toward.dot(view));
        }
        for (BlocksAttacks.DamageReduction reduction : blocksAttacks.damageReductions()) {
            if (angle <= (float) (Math.PI / 180.0) * reduction.horizontalBlockingAngle()) {
                return true;
            }
        }
        return false;
    }
}
