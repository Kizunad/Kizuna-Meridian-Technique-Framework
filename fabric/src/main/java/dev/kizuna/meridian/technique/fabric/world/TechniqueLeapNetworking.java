package dev.kizuna.meridian.technique.fabric.world;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapMotion;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import java.util.HashSet;

/** 有界 Protobuf 请求只携带按键意图，客户端不能指定落点或速度。 */
public final class TechniqueLeapNetworking {
    private static final int MAX_PACKET_BYTES = 1024;
    public static final ProtobufChannel CHANNEL =
            new ProtobufChannel(Identifier.fromNamespaceAndPath("meridian_technique", "leap"), MAX_PACKET_BYTES);

    private TechniqueLeapNetworking() {
        // 所有世界访问调度回服务端主线程。
    }

    public static void initialize() {
        // 原版玩家位置包不驱动权威飞行，每 tick 独立推进一次并保留原版碰撞；挂起的请求到时再决策。
        // 两个方向都在公共初始化里登记，服务端和客户端用同一份通道定义编解码。
        CHANNEL.registerServerbound();
        CHANNEL.registerClientbound();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                TechniqueLeaps.travel(player);
                resume(player);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL.type(), (payload, context) -> {
            LeapMotion message;
            try {
                message = LeapMotion.parseFrom(payload.bytes());
            } catch (InvalidProtocolBufferException invalid) {
                return;
            }
            if (!message.equals(LeapMotion.newBuilder().setAction(LeapMotion.Action.REQUEST).build())) {
                return;
            }
            // 26.2 的收包回调已经在服务端主线程上，直接决策。
            request(context.player());
        });
    }

    private static void request(ServerPlayer player) {
        // 只有地面新起跳能够收费；重复请求不会重新决策或重扣体力。
        TechniqueLeaps.Jump result = TechniqueLeaps.jump(player);
        if (result == TechniqueLeaps.Jump.ORDINARY) {
            var message = LeapMotion.newBuilder()
                    .setAction(LeapMotion.Action.ORDINARY_JUMP)
                    .setEntityId(player.getId())
                    .setX(player.getX())
                    .setY(player.getY())
                    .setZ(player.getZ())
                    .build();
            sendTo(player, message);
        } else if (result == TechniqueLeaps.Jump.WAIT) {
            var access = (TechniqueLeapAccess) player;
            TechniqueLeapFlight flight = access.meridian$getLeap();
            if (flight != null) {
                send(player, LeapMotion.Action.START, flight);
            } else if (early(player)) {
                access.meridian$markLeapPending();
            } else {
                correctGrounded(player);
            }
        }
    }

    private static boolean early(ServerPlayer player) {
        // 客户端世界时间在服务端变慢时会超前；站在地上只差落地间隔的请求挂起到时刻，不回拉预测。
        long readyTick = ((TechniqueLeapAccess) player).meridian$getLeapReadyTick();
        return player.onGround() && player.level().getGameTime() < readyTick;
    }

    private static void resume(ServerPlayer player) {
        // 挂起期间位置和视线包都不被采纳，到时按请求那一刻的状态起跳，与客户端预测一致。
        var access = (TechniqueLeapAccess) player;
        if (access.meridian$getLeapPending()
                && player.level().getGameTime() >= access.meridian$getLeapReadyTick()) {
            access.meridian$clearLeapPending();
            request(player);
        }
    }

    private static void correctGrounded(ServerPlayer player) {
        // 落地间隔内拒绝预测时，回传当前位置、速度和可起跳时刻，避免客户端残留飞行状态。
        var velocity = player.getDeltaMovement();
        var correction = LeapMotion.newBuilder()
                .setAction(LeapMotion.Action.FINISH)
                .setEntityId(player.getId())
                .setX(player.getX())
                .setY(player.getY())
                .setZ(player.getZ())
                .setVelocityX(velocity.x)
                .setVelocityY(velocity.y)
                .setVelocityZ(velocity.z)
                .setReadyTick(((TechniqueLeapAccess) player).meridian$getLeapReadyTick())
                .build();
        sendTo(player, correction);
    }

    public static void send(LivingEntity actor, LeapMotion.Action action, TechniqueLeapFlight flight) {
        // 起点、速度和可起跳时刻来自当前服务端实体，观察者和本人收到同一权威结果。
        var position = actor.position();
        var velocity = actor.getDeltaMovement();
        var message = LeapMotion.newBuilder()
                .setAction(action)
                .setEntityId(actor.getId())
                .setEffectId(flight.registered.benefit().getId())
                .setX(position.x)
                .setY(position.y)
                .setZ(position.z)
                .setVelocityX(velocity.x)
                .setVelocityY(velocity.y)
                .setVelocityZ(velocity.z)
                .setLandingY(flight.landingY)
                .setReadyTick(((TechniqueLeapAccess) actor).meridian$getLeapReadyTick())
                .build();
        var recipients = new HashSet<>(PlayerLookup.tracking(actor));
        if (actor instanceof ServerPlayer player) {
            recipients.add(player);
        }
        for (ServerPlayer player : recipients) {
            sendTo(player, message);
        }
    }

    private static void sendTo(ServerPlayer player, LeapMotion message) {
        // 消息大小由本地有限字段固定，每个接收者一份新载荷。
        ServerPlayNetworking.send(player, CHANNEL.payload(message.toByteArray()));
    }
}
