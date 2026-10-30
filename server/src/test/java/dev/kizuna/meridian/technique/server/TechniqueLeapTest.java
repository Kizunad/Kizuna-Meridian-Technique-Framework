package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundAnchor;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.server.TechniqueLeap.Point;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class TechniqueLeapTest {
    private static final double MAXIMUM_PATH = 7;
    private static final double LAUNCH_SPEED = 12;
    private static final int MINIMUM_TICKS = 6;
    private static final int MAXIMUM_TICKS = 30;
    private static final double MAXIMUM_RISE = 3;
    private static final double MAXIMUM_DROP = 4;
    private static final double SNAP_RADIUS = 2;
    private static final double SHORT = 3;
    private static final double AIM = 6;
    private static final double NEAR_RISE = 5;
    private static final double BEYOND_RISE = 6.5;
    private static final double FAR = 12;
    private static final int SHORT_TICKS = 8;
    private static final int AIM_TICKS = 10;
    private static final int PATH_TICKS = 12;
    private static final int RISE_ESTIMATE_TICKS = 11;
    private static final int RISE_TICKS = 13;
    private static final int DROP_TICKS = 16;
    private static final int APEX_TICK_STEP = 2;
    private static final int MAXIMUM_RISE_TICKS = 14;
    private static final double FLAT_AIM_APEX = 1.5;
    private static final double RISE_APEX = 1.2;
    private static final double APEX_CLEARANCE = 0.5;
    private static final double EPSILON = 0.000001;
    private static final String TAKEOFF_SOUND = "minecraft:entity.rabbit.jump";
    private static final Point ORIGIN = new Point(0.5, 64, 0.5);
    private static final Point FORWARD = new Point(0, 0, 1);
    private static final LeapProfile PROFILE = profile();

    @Test
    public void flatLeapsTakeTimeFromChordAndStayLow() {
        // 平地 6、7 格直接按 0.6 格/tick 求时，3 格短跳由弧顶保护补足越过边沿的高度。
        assertEquals(SHORT_TICKS, ticks(ahead(SHORT, 0)));
        Point aimed = ahead(AIM, 0);
        int aimedTicks = ticks(aimed);
        assertEquals(AIM_TICKS, aimedTicks);
        assertEquals(PATH_TICKS, ticks(ahead(MAXIMUM_PATH, 0)));
        assertTrue(TechniqueLeap.apex(ORIGIN, aimed, aimedTicks) - ORIGIN.y() <= FLAT_AIM_APEX);
    }

    @Test
    public void risingAndDroppingLeapsKeepApexNearHigherEnd() {
        // 上 3 格弧顶只略高于落点；下 4 格以起点为较高端加时，仍远少于固定 24 tick。
        Point rise = ahead(AIM, MAXIMUM_RISE);
        int riseTicks = ticks(rise);
        assertEquals(RISE_TICKS, riseTicks);
        assertTrue(TechniqueLeap.apex(ORIGIN, rise, riseTicks) <= rise.y() + RISE_APEX);
        Point nearRise = ahead(NEAR_RISE, MAXIMUM_RISE);
        int nearRiseTicks = ticks(nearRise);
        assertTrue(nearRiseTicks <= MAXIMUM_RISE_TICKS);
        assertTrue(TechniqueLeap.apex(ORIGIN, nearRise, nearRiseTicks) <= nearRise.y() + RISE_APEX);
        Point drop = ahead(AIM, -MAXIMUM_DROP);
        int dropTicks = ticks(drop);
        assertEquals(DROP_TICKS, dropTicks);
        assertTrue(TechniqueLeap.apex(ORIGIN, drop, dropTicks) >= ORIGIN.y() + APEX_CLEARANCE);
    }

    @Test
    public void apexGuardAddsTicksUntilHigherEdgeIsCleared() {
        // 直线估算的 11 tick 弧顶不足落点上方半格，保护按两 tick 一步加到满足为止。
        Point rise = ahead(AIM, MAXIMUM_RISE);
        double clearance = rise.y() + APEX_CLEARANCE;
        assertTrue(TechniqueLeap.apex(ORIGIN, rise, RISE_ESTIMATE_TICKS) < clearance);
        assertTrue(TechniqueLeap.apex(ORIGIN, rise, RISE_ESTIMATE_TICKS + APEX_TICK_STEP) >= clearance);
        assertEquals(RISE_ESTIMATE_TICKS + APEX_TICK_STEP, ticks(rise));
    }

    @Test
    public void tickEstimateIsClampedToProfileBounds() {
        // 零长度仍至少飞最短 tick；极慢起跳速度封顶，弧顶保护也不会越过上限。
        int inPlace = ticks(ORIGIN);
        assertTrue(inPlace >= MINIMUM_TICKS);
        assertTrue(TechniqueLeap.apex(ORIGIN, ORIGIN, inPlace) >= ORIGIN.y() + APEX_CLEARANCE);
        var slow = PROFILE.toBuilder()
                .setLaunchSpeed(1)
                .build();
        assertEquals(MAXIMUM_TICKS, TechniqueLeap.flightTicks(slow, ORIGIN, ahead(MAXIMUM_PATH, 0)));
        var capped = PROFILE.toBuilder()
                .setMinimumFlightTicks(1)
                .setMaximumFlightTicks(MINIMUM_TICKS)
                .build();
        assertEquals(MINIMUM_TICKS, TechniqueLeap.flightTicks(capped, ORIGIN, ahead(AIM, MAXIMUM_RISE)));
    }

    @Test
    public void pointedBeyondPathIsProjectedOntoBudget() {
        // 远处准星沿起点方向缩到 7 格；上升计入直线长度，下降只受 maximum_drop 封顶。
        Point flat = TechniqueLeap.withinPath(PROFILE, ORIGIN, ahead(FAR, 0));
        assertEquals(MAXIMUM_PATH, Math.sqrt(flat.horizontalSquared(ORIGIN)), EPSILON);
        Point high = TechniqueLeap.withinPath(PROFILE, ORIGIN, ahead(FAR, FAR));
        assertEquals(MAXIMUM_PATH, TechniqueLeap.path(ORIGIN, high), EPSILON);
        assertTrue(high.y() - ORIGIN.y() <= MAXIMUM_RISE + EPSILON);
        Point lowest = ahead(MAXIMUM_PATH, -MAXIMUM_DROP);
        assertEquals(lowest, TechniqueLeap.withinPath(PROFILE, ORIGIN, lowest));
        Point near = ahead(1, 0);
        assertEquals(near, TechniqueLeap.withinPath(PROFILE, ORIGIN, near));
    }

    @Test
    public void candidatesStayInsidePathSphere() {
        // 3 格高台在水平 6.5 格处超出直线预算，只能落到预算内的更近台面；无命中时落向前方 7 格。
        Point beyond = ahead(BEYOND_RISE, MAXIMUM_RISE);
        Point reachable = ahead(NEAR_RISE, MAXIMUM_RISE);
        var aim = new TechniqueLeap.Aim(ORIGIN, FORWARD, Optional.of(beyond));
        var landing = TechniqueLeap.select(PROFILE, aim, new Terrain(List.of(beyond, reachable)));
        assertTrue(landing.supported());
        assertEquals(reachable, landing.point());
        var blind = new TechniqueLeap.Aim(ORIGIN, FORWARD, Optional.empty());
        var fallback = TechniqueLeap.select(PROFILE, blind, new Terrain(List.of()));
        assertFalse(fallback.supported());
        assertEquals(ahead(MAXIMUM_PATH, 0), fallback.point());
    }

    @Test
    public void validationRejectsRetiredFieldsAndBadFlightBounds() {
        // 停用字段非零、起跳速度非正和最短飞行长于最长飞行都在注册时拒绝。
        TechniqueBenefits.validate(benefit(UnaryOperator.identity()));
        List<UnaryOperator<LeapProfile.Builder>> invalid = List.of(
                leap -> leap.setFlightTicks(MINIMUM_TICKS),
                leap -> leap.setMaximumDistance(MAXIMUM_PATH),
                leap -> leap.setLaunchSpeed(0),
                leap -> leap.setMaximumPath(Double.NaN),
                leap -> leap.setMinimumFlightTicks(MAXIMUM_TICKS + 1),
                leap -> leap.setMinimumFlightTicks(0));
        for (var change : invalid) {
            assertThrows(IllegalArgumentException.class, () -> TechniqueBenefits.validate(benefit(change)));
        }
    }

    @Test
    public void takeoffSoundMustAnchorTheCaster() {
        // 起跳声只在施放者处播放；锚点写成目标或音量为 0 都在注册时拒绝。
        SoundCue takeoff = SoundCue.newBuilder()
                .setSoundId(TAKEOFF_SOUND)
                .setAnchor(SoundAnchor.CASTER)
                .build();
        TechniqueBenefits.validate(benefit(leap -> leap.setTakeoffSound(takeoff)));
        Quantity silent = Quantity.newBuilder()
                .setValue(0)
                .setUnit(Unit.RATIO)
                .build();
        List<SoundCue> invalid = List.of(
                takeoff.toBuilder().setAnchor(SoundAnchor.TARGET).build(),
                takeoff.toBuilder().setVolume(silent).build());
        for (SoundCue cue : invalid) {
            StatusBenefit broken = benefit(leap -> leap.setTakeoffSound(cue));
            assertThrows(IllegalArgumentException.class, () -> TechniqueBenefits.validate(broken));
        }
    }

    private static int ticks(Point target) {
        // 测试统一使用轻身的默认直线预算与起跳速度。
        return TechniqueLeap.flightTicks(PROFILE, ORIGIN, target);
    }

    private static Point ahead(double distance, double rise) {
        // 沿正 z 方向的落点，高差相对起点。
        return new Point(ORIGIN.x(), ORIGIN.y() + rise, ORIGIN.z() + distance);
    }

    private static LeapProfile profile() {
        // 与 Basic 轻身目录相同的几何与时刻，做功和表现字段只为通过校验。
        return LeapProfile.newBuilder()
                .setMaximumPath(MAXIMUM_PATH)
                .setLaunchSpeed(LAUNCH_SPEED)
                .setMinimumFlightTicks(MINIMUM_TICKS)
                .setMaximumFlightTicks(MAXIMUM_TICKS)
                .setMaximumRise(MAXIMUM_RISE)
                .setMaximumDrop(MAXIMUM_DROP)
                .setSnapRadius(SNAP_RADIUS)
                .setLandingDelayTicks(MINIMUM_TICKS)
                .setAiMinimumDistance(MAXIMUM_DROP)
                .setAiDecisionTicks(MINIMUM_TICKS)
                .setPresentationId("test:technique_dash")
                .setFailureTranslationKey("message.test.weak_legs")
                .build();
    }

    private static StatusBenefit benefit(UnaryOperator<LeapProfile.Builder> change) {
        // 只改跃步配置，其余增益字段保持合法。
        return StatusBenefit.newBuilder()
                .setId("test:light_body")
                .setDurationMillis(1)
                .setLeap(change.apply(PROFILE.toBuilder()))
                .build();
    }

    private record Terrain(List<Point> tops) implements TechniqueLeap.Terrain {
        @Override
        public Optional<Point> standing(int x, int y, int z) {
            // 只有列表中的方块中心顶面可站立。
            return tops.stream().filter(top -> Math.floor(top.x()) == x && Math.floor(top.z()) == z
                    && Math.round(top.y()) == y).findFirst();
        }
    }
}
