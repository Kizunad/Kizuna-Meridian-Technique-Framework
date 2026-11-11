package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TissueModifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 背包效果栏的组织状态说明：按目录曲线和原版剩余时间写出当前倍率与曲线走向，不读身体。 */
public final class TechniqueStatusText {
    private static final double MILLIS_PER_SECOND = 1000;
    private static final double IDENTITY = 1;

    private TechniqueStatusText() {
        // 纯文本投影，客户端提示与测试共用。
    }

    public static List<String> lines(StatusBenefit benefit, int remainingTicks, Function<String, String> slotLabel) {
        // 每条修正的每项非单位倍率一行，例如「两臂力量 ×1.25（最高 ×1.25 到第 20 s，第 24 s 归零）」，末行写已过与剩余。
        int catalogTicks = Math.toIntExact((benefit.getDurationMillis() + TechniqueStatusCurves.MILLIS_PER_TICK - 1)
                / TechniqueStatusCurves.MILLIS_PER_TICK);
        int totalTicks = Math.max(catalogTicks, remainingTicks);
        double totalMillis = totalTicks * (double) TechniqueStatusCurves.MILLIS_PER_TICK;
        double elapsedMillis = (totalTicks - remainingTicks) * (double) TechniqueStatusCurves.MILLIS_PER_TICK;
        var lines = new ArrayList<String>();
        for (TissueModifier modifier : benefit.getTissue().getModifiersList()) {
            List<CurvePoint> curve = modifier.getCurveList();
            double scale = totalMillis / curve.getLast().getOffsetMillis();
            double value = TechniqueStatusCurves.valueAt(curve, elapsedMillis / scale);
            String label = slotLabel.apply(modifier.getSlotId());
            for (Peak peak : peaks(modifier)) {
                lines.add(label + peak.name() + " ×" + ratio(multiplier(peak.value(), value))
                        + "（" + schedule(curve, peak.value(), scale) + "）");
            }
        }
        lines.add("第 " + seconds(elapsedMillis) + " s，剩 " + seconds(totalMillis - elapsedMillis) + " s");
        return List.copyOf(lines);
    }

    private static List<Peak> peaks(TissueModifier modifier) {
        // 只列真正改动的属性；缺省或单位倍率不写。
        var peaks = new ArrayList<Peak>();
        addPeak(peaks, "力量", modifier.hasStrengthPeak(), modifier.getStrengthPeak());
        addPeak(peaks, "功率", modifier.hasBurstPeak(), modifier.getBurstPeak());
        addPeak(peaks, "耐力", modifier.hasEndurancePeak(), modifier.getEndurancePeak());
        addPeak(peaks, "恢复", modifier.hasRecoveryPeak(), modifier.getRecoveryPeak());
        addPeak(peaks, "动作速度", modifier.hasTempoPeak(), modifier.getTempoPeak());
        return peaks;
    }

    private static void addPeak(List<Peak> peaks, String name, boolean present, Quantity quantity) {
        // 声明了且不等于 1 的峰值才进说明。
        if (present && quantity.getValue() != IDENTITY) {
            peaks.add(new Peak(name, quantity.getValue()));
        }
    }

    private static String schedule(List<CurvePoint> curve, double peak, double scale) {
        // 曲线走向按实际时长换算：最高值保持到何时、低于 1 的一段在何时、何时归零。
        double highest = curve.stream().mapToDouble(CurvePoint::getValue).max().orElse(0);
        double lowest = curve.stream().mapToDouble(CurvePoint::getValue).min().orElse(0);
        var parts = new ArrayList<String>();
        if (highest > 0) {
            long held = curve.stream().filter(point -> point.getValue() == highest)
                    .mapToLong(CurvePoint::getOffsetMillis).max().orElseThrow();
            parts.add("最高 ×" + ratio(multiplier(peak, highest)) + " 到第 " + seconds(held * scale) + " s");
        }
        if (lowest < 0) {
            long from = curve.stream().filter(point -> point.getValue() == lowest)
                    .mapToLong(CurvePoint::getOffsetMillis).min().orElseThrow();
            long to = curve.stream().filter(point -> point.getValue() == lowest)
                    .mapToLong(CurvePoint::getOffsetMillis).max().orElseThrow();
            String span = from == to ? seconds(from * scale) : seconds(from * scale) + "–" + seconds(to * scale);
            parts.add("第 " + span + " s 落到 ×" + ratio(multiplier(peak, lowest)));
        }
        parts.add("第 " + seconds(curve.getLast().getOffsetMillis() * scale) + " s 归零");
        return String.join("，", parts);
    }

    private static double multiplier(double peak, double value) {
        // 与 Framework 能力修正同一公式：1 + (峰值 − 1) × 曲线值。
        return IDENTITY + (peak - IDENTITY) * value;
    }

    private static String ratio(double value) {
        // 倍率保留两位，去掉末尾零。
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String seconds(double millis) {
        // 秒数保留一位，整秒不带小数。
        return BigDecimal.valueOf(millis / MILLIS_PER_SECOND).setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private record Peak(String name, double value) { }
}
