package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.server.TechniqueLeap;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 世界采样适配；玩家准星和 AI 目标均使用相同的站立判定。 */
public final class TechniqueLeapTargets {
    private static final double CENTER = 0.5;
    private static final double HALF_WIDTH = 0.5;
    private static final double HALF_BLOCK = 0.5;
    private static final double MINIMUM_CLEARANCE = 2;
    private static final double SURFACE_EPSILON = 0.001;
    private static final double DIRECTION_EPSILON = 0.000001;

    private TechniqueLeapTargets() {
        // 不在客户端和服务端保存跨 tick 世界几何缓存。
    }

    public static TechniqueLeap.Landing aimed(LivingEntity actor, LeapProfile profile) {
        // 射线从眼睛出发，长度包括上下坡的高度差，选择器再把命中点投影进直线预算。
        Vec3 eye = actor.getEyePosition();
        Vec3 direction = Vec3.directionFromRotation(actor.getXRot(), actor.getYRot());
        double reach = profile.getMaximumPath() + profile.getMaximumRise() + profile.getMaximumDrop();
        var hit = actor.level().clip(new ClipContext(eye, eye.add(direction.scale(reach)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, actor));
        Optional<TechniqueLeap.Point> pointed = Optional.empty();
        if (hit.getType() != HitResult.Type.MISS) {
            pointed = Optional.of(point(hit.getLocation()));
        }
        return select(actor, profile, new TechniqueLeap.Aim(point(actor.position()), point(direction), pointed));
    }

    public static TechniqueLeap.Landing toward(LivingEntity actor, LeapProfile profile, Vec3 target) {
        // AI 使用当前目标脚下位置，不复用过期导航节点。
        Vec3 origin = actor.position();
        var aim = new TechniqueLeap.Aim(point(origin), point(target.subtract(origin)),
                Optional.of(point(target)));
        return select(actor, profile, aim);
    }

    public static TechniqueLeap.Landing directed(LivingEntity actor, LeapProfile profile, Vec3 direction,
                                                   double distance) {
        // 定向跃出只在射线经过的方块列内取安全落点，不使用准星吸附和横向扩宽。
        Vec3 horizontal = new Vec3(direction.x, 0, direction.z);
        double length = horizontal.length();
        if (!Double.isFinite(length) || length < DIRECTION_EPSILON || !Double.isFinite(distance) || distance <= 0) {
            throw new IllegalArgumentException("定向跃出方向或距离非法");
        }
        Vec3 unit = horizontal.scale(1 / length);
        TechniqueLeap.Point origin = point(actor.position());
        TechniqueLeap.Point forward = point(unit);
        double path = Math.min(distance, profile.getMaximumPath());
        var target = new TechniqueLeap.Point(origin.x() + forward.x() * path, origin.y(),
                origin.z() + forward.z() * path);
        var aim = new TechniqueLeap.Aim(origin, forward, Optional.of(target));
        return TechniqueLeap.select(profile, aim,
                (x, y, z) -> directedStanding(actor, origin, forward, x, y, z));
    }

    public static boolean clearTakeoff(LivingEntity actor) {
        // 起跳只检查当前身体上方的净空，避免在低顶下已经支付后卡进方块。
        double requiredHeight = Math.max(MINIMUM_CLEARANCE, actor.getBbHeight());
        var box = actor.getBoundingBox();
        var headroom = new AABB(box.minX, box.maxY, box.minZ, box.maxX,
                box.maxY + requiredHeight, box.maxZ);
        return actor.level().noCollision(actor, headroom) && !actor.level().containsAnyLiquid(headroom);
    }

    private static Optional<TechniqueLeap.Point> directedStanding(LivingEntity actor, TechniqueLeap.Point origin,
                                                                    TechniqueLeap.Point direction, int x, int y,
                                                                    int z) {
        // 方块中心允许半格离散误差，落点不因普通准星吸附偏离固定方向。
        double dx = x + CENTER - origin.x();
        double dz = z + CENTER - origin.z();
        double lateral = Math.abs(dx * direction.z() - dz * direction.x());
        if (lateral > HALF_BLOCK) {
            return Optional.empty();
        }
        return standing(actor, x, y, z);
    }

    private static TechniqueLeap.Landing select(LivingEntity actor, LeapProfile profile, TechniqueLeap.Aim aim) {
        // 两端只有世界来源不同，扫描顺序、优先级与轨迹一致。
        return TechniqueLeap.select(profile, aim, (x, y, z) -> standing(actor, x, y, z));
    }

    private static Optional<TechniqueLeap.Point> standing(LivingEntity actor, int x, int y, int z) {
        // 支撑必须有实体碰撞顶面，落点的完整宽度和至少两格高度均不可含流体。
        var world = actor.level();
        var support = new BlockPos(x, y - 1, z);
        if (!world.hasChunkAt(support) || !world.getFluidState(support).isEmpty()) {
            return Optional.empty();
        }
        var shape = world.getBlockState(support).getCollisionShape(world, support);
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        double top = support.getY() + shape.max(Direction.Axis.Y);
        double halfWidth = actor.getBbWidth() * HALF_WIDTH;
        double height = Math.max(MINIMUM_CLEARANCE, actor.getBbHeight());
        var box = new AABB(x + CENTER - halfWidth, top + SURFACE_EPSILON, z + CENTER - halfWidth,
                x + CENTER + halfWidth, top + height, z + CENTER + halfWidth);
        if (!world.noCollision(actor, box) || world.containsAnyLiquid(box)) {
            return Optional.empty();
        }
        return Optional.of(new TechniqueLeap.Point(x + CENTER, top, z + CENTER));
    }

    public static TechniqueLeap.Point point(Vec3 vector) {
        // 纯求解器不依赖 Minecraft 的向量类。
        return new TechniqueLeap.Point(vector.x, vector.y, vector.z);
    }

    public static Vec3 vector(TechniqueLeap.Point point) {
        // 实体宿主只在求解完成后转换速度和位置。
        return new Vec3(point.x(), point.y(), point.z());
    }
}
