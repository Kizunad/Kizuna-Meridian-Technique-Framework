package dev.kizuna.meridian.technique.fabric.world;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HitFeel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HitTier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import java.util.List;
import java.util.Set;

import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/** 打中提示：看得见目标的玩家收到谁打了谁、打在哪一面、多重；没有出手者的伤害和爆炸不报。 */
public final class TechniqueHitFeelGameTest {
    // 实体跟踪每 tick 更新一次，多等几 tick 让旁观的玩家先看见目标。
    private static final int TRACKING_TICKS = 5;
    private static final double ATTACKER_DISTANCE = 2;
    // 尸壳 20 血、2 点护甲：这三下掉的血分别不到 5%、5% 到 15%、15% 以上。
    private static final float GRAZE_DAMAGE = 0.5f;
    private static final float LIGHT_DAMAGE = 2;
    private static final float HEAVY_DAMAGE = 4;
    private static final float LETHAL_DAMAGE = 100;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void watchersLearnWhoStruckWhomAndHowHard(GameTestHelper context) {
        // 铁傀儡在尸壳 +Z 一侧打它，旁边站一个只看不动手的玩家：按掉血比例报擦到、轻、重，打死报最重一档；
        // 摔伤没有出手者、爆炸即便有主也不算打中，都不报。
        var level = context.getLevel();
        var target = context.spawn(EntityTypes.HUSK, 1, 1, 1);
        var attacker = context.spawn(EntityTypes.IRON_GOLEM, 1, 1, 1);
        target.setNoAi(true /* aiDisabled */);
        attacker.setNoAi(true /* aiDisabled */);
        attacker.setPos(target.position().add(0, 0, ATTACKER_DISTANCE));
        ServerPlayer watcher = TestPlayers.detached(level, "hit_feel_watch");
        var connection = new RecordingConnection(List.of(TechniqueHitFeel.CHANNEL.type().id()));
        TestPlayers.logIn(watcher, connection);
        watcher.setGameMode(GameType.SPECTATOR);
        watcher.teleportTo(level, target.getX(), target.getY(), target.getZ() - ATTACKER_DISTANCE, Set.of(), 0, 0,
                false /* resetCamera */);
        context.runAfterDelay(TRACKING_TICKS, () -> {
            try {
                var sources = level.damageSources();
                strike(target, sources.fall(), LIGHT_DAMAGE);
                strike(target, sources.explosion(attacker, attacker), LIGHT_DAMAGE);
                context.assertTrue(hits(connection).isEmpty(), "摔伤或爆炸被报成了打中：" + hits(connection));
                strike(target, sources.mobAttack(attacker), GRAZE_DAMAGE);
                strike(target, sources.mobAttack(attacker), LIGHT_DAMAGE);
                strike(target, sources.mobAttack(attacker), HEAVY_DAMAGE);
                strike(target, sources.mobAttack(attacker), LETHAL_DAMAGE);
                List<HitFeel> hits = hits(connection);
                context.assertTrue(hits.stream().map(HitFeel::getTier).toList().equals(
                        List.of(HitTier.GRAZE, HitTier.LIGHT, HitTier.HEAVY, HitTier.BREAK)),
                        "档位不对：" + hits.stream().map(HitFeel::getTier).toList());
                for (HitFeel hit : hits) {
                    context.assertTrue(hit.getTargetEntityId() == target.getId()
                            && hit.getAttackerEntityId() == attacker.getId(), "打中提示里的人不对：" + hit);
                    // 出手者在 +Z 一侧：位置落在目标朝它那一面，方向从它指向目标。
                    context.assertTrue(hit.getZ() > target.getZ() && hit.getDirectionZ() < 0
                            && hit.getY() > target.getY() && hit.getY() < target.getY() + target.getBbHeight(),
                            "打中的位置不在目标朝出手者的那一面：" + hit);
                }
            } finally {
                connection.disconnectFromClient();
            }
            context.succeed();
        });
    }

    private static void strike(LivingEntity target, DamageSource source, float amount) {
        // 连着打几下：每下之前清掉受击后的无敌时间。
        target.invulnerableTime = 0;
        target.hurtServer((ServerLevel) target.level(), source, amount);
    }

    private static List<HitFeel> hits(RecordingConnection connection) {
        // 真正发给这名玩家的打中提示，按发出的先后排。
        return connection.packets().stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload())
                .filter(payload -> payload.type().equals(TechniqueHitFeel.CHANNEL.type()))
                .map(payload -> parse(((ProtobufChannel.Payload) payload).bytes()))
                .toList();
    }

    private static HitFeel parse(byte[] bytes) {
        try {
            return HitFeel.parseFrom(bytes);
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalStateException("打中提示解不开", failure);
        }
    }
}
