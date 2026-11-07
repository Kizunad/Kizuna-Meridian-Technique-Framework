package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModifierActivation;
import java.util.HashSet;

/** 状态增益的有界数据契约，不包含特定功法或身体模板。 */
public final class TechniqueBenefits {
    private static final int MAX_COLOR = 0xFFFFFF;
    private static final int MAX_FLIGHT_TICKS = 60;
    private static final double MAX_LEAP_DISTANCE = 16;
    private static final double MAX_SNAP_RADIUS = 4;
    private static final double MAX_LAUNCH_SPEED = 40;

    private TechniqueBenefits() {
        // 内容声明由目录和宿主两处复核。
    }

    public static void validate(StatusBenefit benefit) {
        // 搜索范围和飞行时间有界，防止内容数据制造无限扫描或持续免伤。
        TechniqueValues.id(benefit.getId(), "status_benefit.id");
        TechniqueValues.millis(benefit.getDurationMillis(), "status_benefit.duration_millis");
        if (benefit.getDurationMillis() == 0 || benefit.getColorRgb() < 0
                || benefit.getColorRgb() > MAX_COLOR || benefit.hasLeap() == benefit.hasTissue()) {
            throw TechniqueErrors.invalid("status_benefit", "必须恰有一种能力，且持续时间、颜色合法");
        }
        if (benefit.hasLeap()) {
            validateLeap(benefit.getLeap());
        } else {
            validateTissue(benefit);
        }
    }

    private static void validateTissue(StatusBenefit benefit) {
        // 多个槽共用一个状态时长，禁止重复槽和有歧义的起手登记配置。
        var modifiers = benefit.getTissue().getModifiersList();
        if (modifiers.isEmpty() || modifiers.size() > TechniqueValues.MAX_MODULES) {
            throw TechniqueErrors.invalid("status_benefit.tissue", "组织描述为空或数量超限");
        }
        var slots = new HashSet<String>();
        for (var modifier : modifiers) {
            TechniqueValues.localId(modifier.getSlotId(), "status_benefit.tissue.slot_id");
            TechniqueDefinitions.validateModifierCurve(modifier, benefit.getDurationMillis());
            long end = modifier.getCurve(modifier.getCurveCount() - 1).getOffsetMillis();
            if (!slots.add(modifier.getSlotId()) || end != benefit.getDurationMillis()
                    || modifier.getActivation() != ModifierActivation.AT_CAST_START) {
                throw TechniqueErrors.invalid("status_benefit.tissue", "重复槽、曲线时长或登记时机非法");
            }
        }
    }

    private static void validateLeap(LeapProfile leap) {
        // 停用的距离与固定飞行字段必须留空，直线预算、起跳速度和飞行时刻各自有界。
        if (leap.getMinimumDistance() != 0 || leap.getMaximumDistance() != 0
                || leap.getFallbackDistance() != 0 || leap.getFlightTicks() != 0) {
            throw TechniqueErrors.invalid("status_benefit.leap", "已停用的距离与 flight_ticks 必须为 0：" + leap);
        }
        positive(leap.getMaximumPath(), MAX_LEAP_DISTANCE);
        positive(leap.getLaunchSpeed(), MAX_LAUNCH_SPEED);
        positive(leap.getMaximumRise(), MAX_LEAP_DISTANCE);
        positive(leap.getMaximumDrop(), MAX_LEAP_DISTANCE);
        positive(leap.getSnapRadius(), MAX_SNAP_RADIUS);
        positive(leap.getAiMinimumDistance(), MAX_LEAP_DISTANCE);
        if (leap.getMinimumFlightTicks() < 1 || leap.getMinimumFlightTicks() > leap.getMaximumFlightTicks()
                || leap.getMaximumFlightTicks() > MAX_FLIGHT_TICKS
                || leap.getLandingDelayTicks() < 1 || leap.getLandingDelayTicks() > MAX_FLIGHT_TICKS
                || leap.getAiDecisionTicks() < 1 || leap.getAiDecisionTicks() > MAX_FLIGHT_TICKS
                || leap.getFailureTranslationKey().isBlank()) {
            throw TechniqueErrors.invalid("status_benefit.leap", "飞行时刻或提示缺失或越界：" + leap);
        }
        TechniqueValues.id(leap.getPresentationId(), "status_benefit.leap.presentation_id");
        if (leap.hasTakeoffSound()) {
            TechniqueSoundCues.validateAnchor(leap.getTakeoffSound(), SoundAnchor.CASTER,
                    "status_benefit.leap.takeoff_sound");
        }
    }

    private static void positive(double value, double maximum) {
        // 所有几何字段必须有限且为正。
        if (!Double.isFinite(value) || value <= 0 || value > maximum) {
            throw TechniqueErrors.invalid("status_benefit.leap", "几何范围非法");
        }
    }
}
