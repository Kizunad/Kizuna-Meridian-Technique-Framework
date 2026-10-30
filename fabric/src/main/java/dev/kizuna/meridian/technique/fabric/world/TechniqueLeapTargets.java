package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.server.TechniqueLeap;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/** 世界采样适配；玩家准星和 AI 目标均使用相同的站立判定。 */
public final class TechniqueLeapTargets {
    private static final double CENTER = 0.5;
    private static final double HALF_WIDTH = 0.5;
    private static final double MINIMUM_CLEARANCE = 2;
    private static final double SURFACE_EPSILON = 0.001;

    private TechniqueLeapTargets() {
        // 不在客户端和服务端保存跨 tick 世界几何缓存。
    }

    public static TechniqueLeap.Landing aimed(LivingEntity actor, LeapProfile profile) {
        // 射线从眼睛出发，长度包括上下坡的高度差，选择器再把命中点投影进直线预算。
        Vec3d eye = actor.getEyePos();
        Vec3d direction = Vec3d.fromPolar(actor.getPitch(), actor.getYaw());
        double reach = profile.getMaximumPath() + profile.getMaximumRise() + profile.getMaximumDrop();
        var hit = actor.getWorld().raycast(new RaycastContext(eye, eye.add(direction.multiply(reach)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, actor));
        Optional<TechniqueLeap.Point> pointed = Optional.empty();
        if (hit.getType() != HitResult.Type.MISS) {
            pointed = Optional.of(point(hit.getPos()));
        }
        return select(actor, profile, new TechniqueLeap.Aim(point(actor.getPos()), point(direction), pointed));
    }

    public static TechniqueLeap.Landing toward(LivingEntity actor, LeapProfile profile, Vec3d target) {
        // AI 使用当前目标脚下位置，不复用过期导航节点。
        Vec3d origin = actor.getPos();
        var aim = new TechniqueLeap.Aim(point(origin), point(target.subtract(origin)),
                Optional.of(point(target)));
        return select(actor, profile, aim);
    }

    private static TechniqueLeap.Landing select(LivingEntity actor, LeapProfile profile, TechniqueLeap.Aim aim) {
        // 两端只有世界来源不同，扫描顺序、优先级与轨迹一致。
        return TechniqueLeap.select(profile, aim, (x, y, z) -> standing(actor, x, y, z));
    }

    private static Optional<TechniqueLeap.Point> standing(LivingEntity actor, int x, int y, int z) {
        // 支撑必须有实体碰撞顶面，落点的完整宽度和至少两格高度均不可含流体。
        var world = actor.getWorld();
        var support = new BlockPos(x, y - 1, z);
        if (!world.isChunkLoaded(support) || !world.getFluidState(support).isEmpty()) {
            return Optional.empty();
        }
        var shape = world.getBlockState(support).getCollisionShape(world, support);
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        double top = support.getY() + shape.getMax(Direction.Axis.Y);
        double halfWidth = actor.getWidth() * HALF_WIDTH;
        double height = Math.max(MINIMUM_CLEARANCE, actor.getHeight());
        var box = new Box(x + CENTER - halfWidth, top + SURFACE_EPSILON, z + CENTER - halfWidth,
                x + CENTER + halfWidth, top + height, z + CENTER + halfWidth);
        if (!world.isSpaceEmpty(actor, box) || world.containsFluid(box)) {
            return Optional.empty();
        }
        return Optional.of(new TechniqueLeap.Point(x + CENTER, top, z + CENTER));
    }

    public static TechniqueLeap.Point point(Vec3d vector) {
        // 纯求解器不依赖 Minecraft 的向量类。
        return new TechniqueLeap.Point(vector.x, vector.y, vector.z);
    }

    public static Vec3d vector(TechniqueLeap.Point point) {
        // 实体宿主只在求解完成后转换速度和位置。
        return new Vec3d(point.x(), point.y(), point.z());
    }
}
