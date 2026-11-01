package dev.kizuna.meridian.technique.fabric.world;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/** 功法盾挡朝向逐例对照 26.2 原版格挡结果：头部朝向来源挡住，背对不挡，正侧方 90° 按原版算正面。 */
public final class TechniqueWorldBlockingGameTest {
    // 原版盾举起 0.25 秒（5 tick）后才算格挡，多等 1 tick。
    private static final int SHIELD_READY_TICKS = 6;
    private static final double ATTACKER_DISTANCE = 2;
    // 26.2 偏航 0 朝 +Z，攻击者放在目标 +Z 方向；偏航 90 朝 -X，来源正好在正侧方。
    private static final float TOWARD_ATTACKER_YAW = 0;
    private static final float AWAY_FROM_ATTACKER_YAW = 180;
    private static final float SIDE_YAW = 90;
    private static final float PROBE_DAMAGE = 1;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shieldWithHeadTowardSourceBlocks(GameTestHelper context) {
        // 头朝攻击者、身体背对：原版只看头部朝向，功法同样算挡住。
        assertFacing(context, TOWARD_ATTACKER_YAW, AWAY_FROM_ATTACKER_YAW, true, "正面持盾没有算作格挡");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shieldWithHeadAwayFromSourceDoesNotBlock(GameTestHelper context) {
        // 头背对攻击者、身体朝向：原版不挡，功法也不能因此破盾或给盾冷却。
        assertFacing(context, AWAY_FROM_ATTACKER_YAW, TOWARD_ATTACKER_YAW, false, "背后受击被算作格挡");
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void shieldAtExactSideAngleMatchesVanilla(GameTestHelper context) {
        // 来源正好在头部正侧方：夹角等于 90°，原版按「不超过格挡角」算挡住。
        assertFacing(context, SIDE_YAW, SIDE_YAW, true, "正侧方边界与原版不一致");
    }

    private static void assertFacing(GameTestHelper context, float headYaw, float bodyYaw, boolean expected,
                                     String message) {
        // 禁用 AI 保持朝向；举盾等过格挡延迟后摆好几何，先问功法判定，再让原版对同一来源结算。
        var target = context.spawn(EntityTypes.VINDICATOR, 1, 1, 1);
        var attacker = context.spawn(EntityTypes.IRON_GOLEM, 1, 1, 1);
        target.setNoAi(true /* aiDisabled */);
        attacker.setNoAi(true /* aiDisabled */);
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        target.startUsingItem(InteractionHand.OFF_HAND);
        context.runAfterDelay(SHIELD_READY_TICKS, () -> {
            context.assertTrue(target.isBlocking(), "原版盾没有进入格挡");
            attacker.setPos(target.position().add(0, 0, ATTACKER_DISTANCE));
            target.setYHeadRot(headYaw);
            target.setYBodyRot(bodyYaw);
            target.setYRot(bodyYaw);
            target.setXRot(0);
            var source = context.getLevel().damageSources().mobAttack(attacker);
            boolean faces = TechniqueWorldBlocking.facesSource(target, source);
            boolean vanillaBlocked = target.applyItemBlocking(context.getLevel(), source, PROBE_DAMAGE) > 0;
            context.assertTrue(faces == expected, message);
            context.assertTrue(vanillaBlocked == expected, "原版格挡结果与用例预期不符：" + vanillaBlocked);
            context.succeed();
        });
    }
}
