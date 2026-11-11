package dev.kizuna.meridian.technique.server;

import com.google.protobuf.TextFormat;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class TechniqueStatusTextTest {
    private static final Map<String, String> LABELS = Map.of("muscles", "两臂");

    @Test
    public void heldCurveShowsCurrentMultiplierScheduleAndRemainingTime() {
        // 金臂样例：1 s 升满、保持到 20 s、24 s 归零；第 4 s 时力量已是峰值。
        StatusBenefit benefit = benefit("""
                id: "sample:metal_arms" duration_millis: 24000
                tissue { modifiers { slot_id: "muscles"
                  curve { offset_millis: 0 value: 0 } curve { offset_millis: 1000 value: 1 }
                  curve { offset_millis: 20000 value: 1 } curve { offset_millis: 24000 value: 0 }
                  strength_peak { value: 1.25 unit: RATIO } burst_peak { value: 1 unit: RATIO } } }
                """);
        assertEquals(List.of("两臂力量 ×1.25（最高 ×1.25 到第 20 s，第 24 s 归零）", "第 4 s，剩 20 s"),
                TechniqueStatusText.lines(benefit, 400, LABELS::get));
    }

    @Test
    public void negativeSegmentIsNamedAndMissingLabelLeavesNoKey() {
        // 火心样例：8 s 后跌到 −0.5，功率落到 1 + 0.4 × (−0.5) = 0.8；没有部位名时不加前缀。
        StatusBenefit benefit = benefit("""
                id: "sample:fire_heart" duration_millis: 18000
                tissue { modifiers { slot_id: "muscles"
                  curve { offset_millis: 0 value: 0 } curve { offset_millis: 500 value: 1 }
                  curve { offset_millis: 8000 value: 1 } curve { offset_millis: 10000 value: -0.5 }
                  curve { offset_millis: 16000 value: -0.5 } curve { offset_millis: 18000 value: 0 }
                  burst_peak { value: 1.4 unit: RATIO } } }
                """);
        String schedule = "（最高 ×1.4 到第 8 s，第 10–16 s 落到 ×0.8，第 18 s 归零）";
        assertEquals(List.of("功率 ×1" + schedule, "第 0 s，剩 18 s"),
                TechniqueStatusText.lines(benefit, 360, slot -> ""));
        assertEquals(List.of("功率 ×0.8" + schedule, "第 11 s，剩 7 s"),
                TechniqueStatusText.lines(benefit, 140, slot -> ""));
    }

    @Test
    public void longerVanillaDurationStretchesTheCurveLikeTheServer() {
        // 命令给的时长长于目录时，曲线按同一比例拉伸，和服务端修正的相位一致。
        StatusBenefit benefit = benefit("""
                id: "sample:metal_arms" duration_millis: 24000
                tissue { modifiers { slot_id: "muscles"
                  curve { offset_millis: 0 value: 0 } curve { offset_millis: 1000 value: 1 }
                  curve { offset_millis: 20000 value: 1 } curve { offset_millis: 24000 value: 0 }
                  strength_peak { value: 1.25 unit: RATIO } } }
                """);
        assertEquals(List.of("两臂力量 ×1（最高 ×1.25 到第 40 s，第 48 s 归零）", "第 0 s，剩 48 s"),
                TechniqueStatusText.lines(benefit, 960, LABELS::get));
    }

    private static StatusBenefit benefit(String text) {
        // 测试只关心曲线与峰值，用文本格式写最短的状态。
        try {
            return TextFormat.parse(text, StatusBenefit.class);
        } catch (TextFormat.ParseException exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
