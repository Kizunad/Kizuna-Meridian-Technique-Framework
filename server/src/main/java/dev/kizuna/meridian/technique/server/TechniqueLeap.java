package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;

import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** 纯几何选点和离散抛物线，与两端实体碰撞共用同一运动参数。 */
public final class TechniqueLeap {
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    private static final double EPSILON = 0.000001;
    private static final double TICKS_PER_SECOND = 20;
    private static final double APEX_CLEARANCE = 0.5;
    private static final int APEX_TICK_STEP = 2;

    public record Point(double x, double y, double z) {
        public double horizontalSquared(Point other) {
            // 高度不参与水平距离约束。
            double dx = x - other.x;
            double dz = z - other.z;
            return dx * dx + dz * dz;
        }

        public double distanceSquared(Point other) {
            // 准星附近候选按真实三维距离排序。
            double dy = y - other.y;
            return horizontalSquared(other) + dy * dy;
        }
    }

    public interface Terrain {
        /** 返回指定方块列和整数高度附近的可站立顶面，宿主负责身体净空和流体检查。 */
        Optional<Point> standing(int x, int y, int z);
    }

    public record Aim(Point origin, Point direction, Optional<Point> hit) { }
    public enum Surface { SUPPORTED, FALLBACK }

    public record Landing(Point point, Surface surface) {
        public boolean supported() {
            // 客户端只为已通过站立校验的顶面显示标记。
            return surface == Surface.SUPPORTED;
        }
    }

    private TechniqueLeap() {
        // 不保留世界、玩家或上一帧查询结果。
    }

    public static Landing select(LeapProfile profile, Aim aim, Terrain terrain) {
        // 优先准星邻域的可站立点，随后搜索朝向带；没有安全点才落向投影后的准星。
        Point origin = aim.origin();
        Point forward = horizontal(aim.direction());
        Point pointed = aim.hit().orElse(offset(origin, forward, profile.getMaximumPath()));
        Point target = withinPath(profile, origin, pointed);
        Comparator<Point> order = Comparator.comparingDouble((Point point) -> point.distanceSquared(target))
                .thenComparingDouble(Point::y).thenComparingDouble(Point::x).thenComparingDouble(Point::z);
        var candidates = candidates(profile, origin, terrain).toList();
        double snapSquared = profile.getSnapRadius() * profile.getSnapRadius();
        Optional<Point> nearby = candidates.stream()
                .filter(point -> point.horizontalSquared(target) <= snapSquared).min(order);
        Optional<Point> along = candidates.stream()
                .filter(point -> ahead(origin, forward, point) && !sameColumn(origin, point)).min(order);
        return nearby.or(() -> along).map(point -> new Landing(point, Surface.SUPPORTED))
                .orElse(new Landing(target, Surface.FALLBACK));
    }

    public static Point withinPath(LeapProfile profile, Point origin, Point pointed) {
        // 高差先夹到升降上限，直线长度超出 maximum_path 时沿起点到准星的方向等比缩短。
        double rise = Math.max(-profile.getMaximumDrop(), Math.min(profile.getMaximumRise(), pointed.y() - origin.y()));
        Point clamped = new Point(pointed.x(), origin.y() + rise, pointed.z());
        double length = path(origin, clamped);
        if (length <= profile.getMaximumPath()) {
            return clamped;
        }
        double scale = profile.getMaximumPath() / length;
        return new Point(origin.x() + (clamped.x() - origin.x()) * scale, origin.y() + rise * scale,
                origin.z() + (clamped.z() - origin.z()) * scale);
    }

    public static double path(Point origin, Point point) {
        // 上升与水平共用直线预算，下降只由 maximum_drop 封顶。
        double rise = Math.max(0, point.y() - origin.y());
        return Math.sqrt(point.horizontalSquared(origin) + rise * rise);
    }

    private static boolean reachable(LeapProfile profile, Point origin, Point point) {
        // 候选同时满足升降上限和直线预算。
        double rise = point.y() - origin.y();
        return rise >= -profile.getMaximumDrop() - EPSILON && rise <= profile.getMaximumRise() + EPSILON
                && path(origin, point) <= profile.getMaximumPath() + EPSILON;
    }

    private static Stream<Point> candidates(LeapProfile profile, Point origin, Terrain terrain) {
        // 逐格检查以起点为心的有界球，净空由宿主按实体实际宽高验证。
        var points = Stream.<Point>builder();
        int radius = (int) Math.ceil(profile.getMaximumPath());
        int floorX = (int) Math.floor(origin.x());
        int floorZ = (int) Math.floor(origin.z());
        int minimumY = (int) Math.floor(origin.y() - profile.getMaximumDrop());
        int maximumY = (int) Math.ceil(origin.y() + Math.min(profile.getMaximumRise(), profile.getMaximumPath()));
        for (int x = floorX - radius; x <= floorX + radius; x++) {
            for (int z = floorZ - radius; z <= floorZ + radius; z++) {
                for (int y = minimumY; y <= maximumY; y++) {
                    terrain.standing(x, y, z).filter(point -> reachable(profile, origin, point))
                            .ifPresent(points::add);
                }
            }
        }
        return points.build();
    }

    private static boolean ahead(Point origin, Point direction, Point point) {
        // 后备沿视线搜索保留一格宽通道，不跳向身后的孤立平台。
        double dx = point.x() - origin.x();
        double dz = point.z() - origin.z();
        double dot = dx * direction.x() + dz * direction.z();
        double side = Math.abs(dx * direction.z() - dz * direction.x());
        return dot >= 0 && side <= 1;
    }

    private static boolean sameColumn(Point origin, Point point) {
        // 沿视线带不含脚下这一格，没有别的顶面时按回退起跳而不是原地跳。
        return Math.floor(origin.x()) == Math.floor(point.x()) && Math.floor(origin.z()) == Math.floor(point.z());
    }

    private static Point horizontal(Point direction) {
        // 垂直准星仍有确定前向，避免除零产生非有限速度。
        double length = Math.hypot(direction.x(), direction.z());
        if (length < EPSILON) {
            return new Point(0, 0, 1);
        }
        return new Point(direction.x() / length, 0, direction.z() / length);
    }

    private static Point offset(Point origin, Point direction, double distance) {
        // 无准星命中时沿水平前向取起点高度，不凭空提供更低的安全落地高度。
        return new Point(origin.x() + direction.x() * distance, origin.y(), origin.z() + direction.z() * distance);
    }

    public static int flightTicks(LeapProfile profile, Point origin, Point target) {
        // 沿直线按起跳速度估算 tick；弧顶不足以越过两端较高者的边沿时逐步延长。
        double blocksPerTick = profile.getLaunchSpeed() / TICKS_PER_SECOND;
        long estimated = Math.round(Math.sqrt(origin.distanceSquared(target)) / blocksPerTick);
        int maximum = profile.getMaximumFlightTicks();
        int ticks = (int) Math.max(profile.getMinimumFlightTicks(), Math.min(maximum, estimated));
        double clearance = Math.max(origin.y(), target.y()) + APEX_CLEARANCE;
        while (ticks < maximum && apex(origin, target, ticks) < clearance) {
            ticks = Math.min(maximum, ticks + APEX_TICK_STEP);
        }
        return ticks;
    }

    public static double apex(Point origin, Point target, int ticks) {
        // 按实体宿主相同的先位移后衰减顺序逐 tick 求脚底最高点。
        double vertical = velocity(origin, target, ticks).y();
        double height = origin.y();
        double highest = height;
        for (int tick = 0; tick < ticks; tick++) {
            height += vertical;
            vertical = nextVertical(vertical);
            highest = Math.max(highest, height);
        }
        return highest;
    }

    public static Point velocity(Point origin, Point target, int ticks) {
        // 解析离散递推 v[n+1]=(v[n]-g)*drag，避免连续公式在 Minecraft tick 上积累误差。
        double sum = (1 - Math.pow(VERTICAL_DRAG, ticks)) / (1 - VERTICAL_DRAG);
        double gravityOffset = GRAVITY * VERTICAL_DRAG / (1 - VERTICAL_DRAG);
        double vertical = (target.y() - origin.y() + gravityOffset * (ticks - sum)) / sum;
        return new Point((target.x() - origin.x()) / ticks, vertical, (target.z() - origin.z()) / ticks);
    }

    public static double nextVertical(double velocity) {
        // 两端和测试使用相同的重力与空气阻力顺序。
        return (velocity - GRAVITY) * VERTICAL_DRAG;
    }
}
