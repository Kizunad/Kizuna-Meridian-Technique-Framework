package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HitFeel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HitTier;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;

/**
 * 打中时给客户端的表现提示：谁打中了谁、打在哪、多重。客户端据此放溅血一类的粒子。
 *
 * <p>挂在原版「受伤以后」和「死亡以后」两个事件上，所以功法的兵器和拳脚、生物的招式、原版的普通攻击和箭，走的是同一条路。
 * 只认有出手者、而且是直接打中或射中的伤害；爆炸、火、摔落这些不算。被盾挡下、没掉血的不发。</p>
 */
public final class TechniqueHitFeel {
    private static final int MAX_PACKET_BYTES = 96;
    public static final ProtobufChannel CHANNEL =
            new ProtobufChannel(Identifier.fromNamespaceAndPath("meridian_technique", "hit_feel"), MAX_PACKET_BYTES);
    /** 掉的血占血量上限不到这么多算擦到，到下一条算重。 */
    private static final double LIGHT_RATIO = 0.05;
    private static final double HEAVY_RATIO = 0.15;
    /** 打中的高度取出手者眼睛的高度，但不出目标身高的这个范围。 */
    private static final double LOWEST_HIT = 0.25;
    private static final double HIGHEST_HIT = 0.85;
    private static final double HALF = 0.5;

    private TechniqueHitFeel() {
        // 只转述已经发生的伤害，不保存状态。
    }

    public static void initialize() {
        // 服务端发往客户端的通道在公共初始化里登记，客户端才能解码。
        CHANNEL.registerClientbound();
        ServerLivingEntityEvents.AFTER_DAMAGE.register((target, source, dealt, taken, blocked) -> {
            if (!blocked && taken > 0) {
                send(target, source, tier(taken / target.getMaxHealth()));
            }
        });
        // 打死的那一下不走「受伤以后」，单独报成最重一档。
        ServerLivingEntityEvents.AFTER_DEATH.register((target, source) -> send(target, source, HitTier.BREAK));
    }

    static HitTier tier(double ratio) {
        if (ratio < LIGHT_RATIO) {
            return HitTier.GRAZE;
        }
        return ratio < HEAVY_RATIO ? HitTier.LIGHT : HitTier.HEAVY;
    }

    private static void send(LivingEntity target, DamageSource source, HitTier tier) {
        if (!(source.getEntity() instanceof LivingEntity attacker) || attacker == target || !struck(source, attacker)) {
            return;
        }
        Vec3 toward = target.position().subtract(attacker.position()).multiply(1, 0, 1);
        Vec3 direction = Vec3.directionFromRotation(0, attacker.getYRot());
        if (toward.lengthSqr() > 0) {
            direction = toward.normalize();
        }
        double height = Math.clamp(attacker.getEyeY() - target.getY(),
                target.getBbHeight() * LOWEST_HIT, target.getBbHeight() * HIGHEST_HIT);
        // 位置取目标朝出手者那一面的表面。
        Vec3 surface = target.position().subtract(direction.scale(target.getBbWidth() * HALF));
        var message = HitFeel.newBuilder()
                .setTargetEntityId(target.getId())
                .setAttackerEntityId(attacker.getId())
                .setX(surface.x)
                .setY(target.getY() + height)
                .setZ(surface.z)
                .setDirectionX(direction.x)
                .setDirectionZ(direction.z)
                .setTier(tier)
                .build();
        var recipients = new HashSet<>(PlayerLookup.tracking(target));
        if (target instanceof ServerPlayer player) {
            recipients.add(player);
        }
        for (ServerPlayer player : recipients) {
            ServerPlayNetworking.send(player, CHANNEL.payload(message.toByteArray()));
        }
    }

    private static boolean struck(DamageSource source, LivingEntity attacker) {
        // 近身直接打中（直接来源就是出手者）或射中；爆炸即便有主也不算。
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return false;
        }
        return source.getDirectEntity() == attacker || source.is(DamageTypeTags.IS_PROJECTILE);
    }
}
