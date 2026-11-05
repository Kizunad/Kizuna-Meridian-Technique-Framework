package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TissueModifier;
import java.util.ArrayList;
import java.util.List;

/** 把目录曲线映射到原版实际时长，并按 Framework 的单条曲线上限分段登记。 */
public final class TechniqueStatusCurves {
    public static final long MILLIS_PER_TICK = 50;

    public record Phase(int totalTicks, int remainingTicks) {
        public Phase {
            // 剩余时长由原版提供；此值只参与本次计划，不成为独立倒计时。
            if (totalTicks <= 0 || remainingTicks <= 0 || remainingTicks > totalTicks) {
                throw new IllegalArgumentException("状态曲线时长非法");
            }
        }

        public int segmentTicks() {
            // 长命令效果按有界窗口续接，最终结束仍由原版效果决定。
            long maximumTicks = CapabilityModifier.MAX_CURVE_DURATION_MILLIS / MILLIS_PER_TICK;
            return Math.toIntExact(Math.min(remainingTicks, maximumTicks));
        }
    }

    private TechniqueStatusCurves() {
        // 这里只转换时间坐标，能力倍率与身体恢复仍由 Framework 计算。
    }

    public static List<CurvePoint> points(TissueModifier modifier, Phase phase) {
        // 当前插值作首点，窗口内保留原拐点，结束值由原曲线插值得到。
        List<CurvePoint> original = modifier.getCurveList();
        long originalDuration = original.getLast().getOffsetMillis();
        long totalMillis = phase.totalTicks() * MILLIS_PER_TICK;
        long elapsedMillis = (phase.totalTicks() - phase.remainingTicks()) * MILLIS_PER_TICK;
        long segmentMillis = phase.segmentTicks() * MILLIS_PER_TICK;
        double scale = (double) totalMillis / originalDuration;
        var points = new ArrayList<CurvePoint>();
        points.add(point(0, valueAt(original, elapsedMillis / scale)));
        long previous = 0;
        for (CurvePoint originalPoint : original) {
            long offset = Math.round(originalPoint.getOffsetMillis() * scale) - elapsedMillis;
            if (offset > previous && offset < segmentMillis) {
                points.add(point(offset, originalPoint.getValue()));
                previous = offset;
            }
        }
        points.add(point(segmentMillis, valueAt(original, (elapsedMillis + segmentMillis) / scale)));
        return List.copyOf(points);
    }

    private static double valueAt(List<CurvePoint> points, double millis) {
        // 仅在相邻拐点间作线性插值，不复制肌肉能力公式。
        for (int index = 1; index < points.size(); index++) {
            CurvePoint right = points.get(index);
            if (millis <= right.getOffsetMillis()) {
                CurvePoint left = points.get(index - 1);
                long leftMillis = left.getOffsetMillis();
                double fraction = (millis - leftMillis) / (right.getOffsetMillis() - leftMillis);
                return left.getValue() + (right.getValue() - left.getValue()) * fraction;
            }
        }
        return 0;
    }

    private static CurvePoint point(long offset, double value) {
        // 构造 Framework 通用曲线点。
        return CurvePoint.newBuilder().setOffsetMillis(offset).setValue(value).build();
    }
}
