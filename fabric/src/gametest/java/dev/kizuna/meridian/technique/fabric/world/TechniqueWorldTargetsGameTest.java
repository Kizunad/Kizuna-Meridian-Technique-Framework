package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Quantity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.List;

import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/** 用实体碰撞盒验证单体射线和后备锥各自的距离边界。 */
public final class TechniqueWorldTargetsGameTest {
    private static final double REACH = 3;
    private static final double TARGET_CENTER_DISTANCE = 3.2;
    private static final double FALLBACK_ANGLE = 60;
    private static final double SECTOR_ANGLE = 180;
    private static final float FORWARD_YAW = 0;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void singleTargetUsesCollisionIntersectionWithinReach(GameTestHelper context) {
        // 大实体中心可在距离外，但进入射线距离的碰撞盒仍应命中。
        Pair pair = entities(context);
        var strike = strike(StrikeShape.SINGLE_TARGET, FALLBACK_ANGLE);
        List<LivingEntity> selected = TechniqueWorldTargets.candidates(pair.actor(), strike,
                entity -> entity == pair.target());
        context.assertTrue(selected.equals(List.of(pair.target())), "距离内的大实体碰撞盒被原点距离提前排除");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void sectorKeepsEntityPositionDistanceLimit(GameTestHelper context) {
        // 扇区没有单体射线，实体位置超出半径时仍应排除。
        Pair pair = entities(context);
        var strike = strike(StrikeShape.SECTOR, SECTOR_ANGLE);
        List<LivingEntity> selected = TechniqueWorldTargets.candidates(pair.actor(), strike,
                entity -> entity == pair.target());
        context.assertTrue(selected.isEmpty(), "单体碰撞盒修正扩大了扇区半径");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void alliedTargetIsLeftToHostPredicate(GameTestHelper context) {
        // 框架不再按同队预筛：宿主谓词接受同队目标时照常入选，敌我只由宿主决定。
        Pair pair = entities(context);
        var scoreboard = context.getLevel().getScoreboard();
        var team = scoreboard.addPlayerTeam("allies_" + pair.actor().getStringUUID());
        try {
            scoreboard.addPlayerToTeam(pair.actor().getScoreboardName(), team);
            scoreboard.addPlayerToTeam(pair.target().getScoreboardName(), team);
            context.assertTrue(pair.actor().isAlliedTo(pair.target()), "两只实体没有进同一队伍");
            var strike = strike(StrikeShape.SINGLE_TARGET, FALLBACK_ANGLE);
            List<LivingEntity> selected = TechniqueWorldTargets.candidates(pair.actor(), strike,
                    entity -> entity == pair.target());
            context.assertTrue(selected.equals(List.of(pair.target())), "同队目标被框架预筛排除，宿主谓词没有生效");
        } finally {
            scoreboard.removePlayerTeam(team);
        }
        context.succeed();
    }

    private static Pair entities(GameTestHelper context) {
        // 两个实体共用测试结构坐标，禁用 AI 保持几何不受寻路改变。
        var actor = context.spawn(EntityTypes.VINDICATOR, 1, 1, 1);
        var target = context.spawn(EntityTypes.IRON_GOLEM, 1, 1, 1);
        actor.setNoAi(true /* aiDisabled */);
        target.setNoAi(true /* aiDisabled */);
        actor.setYRot(FORWARD_YAW);
        actor.setXRot(0);
        Vec3 targetPosition = actor.position().add(0, 0, TARGET_CENTER_DISTANCE);
        target.setPos(targetPosition);
        return new Pair(actor, target);
    }

    private static WeaponStrike strike(StrikeShape shape, double angle) {
        // 几何 helper 仅读范围字段，协议完整性由独立服务契约测试覆盖。
        Quantity distance = quantity(REACH, Unit.METER);
        Quantity sectorAngle = quantity(angle, Unit.DEGREE);
        Quantity offset = quantity(0, Unit.DEGREE);
        return WeaponStrike.newBuilder()
                .setShape(shape)
                .setDistance(distance)
                .setAngle(sectorAngle)
                .setFacingOffset(offset)
                .build();
    }

    private static Quantity quantity(double value, Unit unit) {
        // 数值携带协议单位，避免测试依赖字段缺省。
        return Quantity.newBuilder()
                .setValue(value)
                .setUnit(unit)
                .build();
    }

    private record Pair(LivingEntity actor, LivingEntity target) { }
}
