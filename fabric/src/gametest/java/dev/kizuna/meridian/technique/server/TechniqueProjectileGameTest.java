package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

import java.util.List;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static dev.kizuna.meridian.technique.server.ProjectileTechniqueFixtures.*;
import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/** 使用真实 Fabric tick 和字节宿主验证锁定与分次射击的提交顺序。 */
public final class TechniqueProjectileGameTest {
    private static final String ID = "sample:ranged_game";
    private static final int EVENT_WAIT_TICKS = 2;
    private static final long CAST_END = WARMUP + ACTIVE + RECOVERY;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void locksAtStartThenDispatchesBothShotsOnServerTicks(GameTestHelper context) {
        // 实体只承载身份，身体与费用仍经 Framework，箭实体执行由具体宿主验收。
        Entity entity = context.spawn(EntityTypes.ARMOR_STAND, 1, 1, 1);
        MemoryHost host = new MemoryHost(body(Shape.TENDRIL));
        host.heldItems = ready(ProjectileWeaponCategory.BOW, Hand.OFF_HAND);
        TechniqueGameTestHost.bind(entity.getUUID(), host);
        TechniqueService service = TechniqueFabric.service();
        TechniqueReference reference = TechniqueRegistry.reference(definition());
        service.learn(entity.getUUID(), reference);
        CastRequest request = request(host.current(), "ranged_game").toBuilder().setTechnique(reference).build();
        CastOutcome outcome = service.cast(entity.getUUID(), request);
        context.assertTrue(outcome.getPhase() == CastPhase.WARMUP, "远程起手失败");
        context.assertTrue(host.effects.size() == 1 && host.effects.getFirst().hasTargetLock(), "起手未立即锁定");
        host.nowMillis = WARMUP + 1;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> firstShot(context, entity, host));
    }

    private static void firstShot(GameTestHelper context, Entity entity, MemoryHost host) {
        // 迟到一毫秒仍携带原效果时刻和副手，不因宿主帧率重排射击。
        List<EffectIntent> shots = host.effects.stream().filter(EffectIntent::hasLaunchProjectile).toList();
        context.assertTrue(shots.size() == 1, "第一发未在服务端 tick 分发");
        EffectIntent first = shots.getFirst();
        context.assertTrue(first.getScheduledMillis() == WARMUP, "第一发时刻被迟到 tick 改写");
        context.assertTrue(first.getSelectedHand() == Hand.OFF_HAND && first.getLockTarget(), "选手或锁定丢失");
        host.nowMillis = REFERENCE_SHOT + 1;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> secondShot(context, entity, host));
    }

    private static void secondShot(GameTestHelper context, Entity entity, MemoryHost host) {
        // 同一施放只锁定一次，后续平射继续使用同一 cast 的目标快照。
        long shots = host.effects.stream().filter(EffectIntent::hasLaunchProjectile).count();
        long locks = host.effects.stream().filter(EffectIntent::hasTargetLock).count();
        context.assertTrue(shots == 2 && locks == 1, "射击或目标锁定重复、缺失");
        host.nowMillis = CAST_END;
        context.runAfterDelay(EVENT_WAIT_TICKS, () -> {
            context.assertTrue(!TechniqueState.read(host.current()).hasActiveCast(), "收势未清理活动施放");
            entity.discard();
            TechniqueGameTestHost.unbind(entity.getUUID());
            context.succeed();
        });
    }

    static TechniqueDefinition definition() {
        // 测试目录独立命名，正式产物不包含任何具体射法。
        LaunchProjectile base = projectile(ProjectileWeaponCategory.BOW);
        LaunchProjectile high = base.toBuilder().setConverge(converge()).build();
        return ranged(ProjectileWeaponCategory.BOW).toBuilder()
                .setId(ID)
                .clearEffects()
                .addEffects(shot(high, WARMUP))
                .addEffects(shot(base, REFERENCE_SHOT))
                .setLockTarget(true /* lockTarget */)
                .setTargetLockDistance(quantity(RANGE, Unit.METER))
                .build();
    }
}
