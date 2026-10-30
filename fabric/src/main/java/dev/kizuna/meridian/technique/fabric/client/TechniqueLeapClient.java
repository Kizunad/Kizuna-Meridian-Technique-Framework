package dev.kizuna.meridian.technique.fabric.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.technique.fabric.mixin.TechniqueJumpInvoker;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapFlight;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapNetworking;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapTargets;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapMotion;
import dev.kizuna.meridian.technique.server.TechniqueLeap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** 本地预测与无粒子落点描边；服务端回执只在偏差超过容差时纠正预测结果。 */
public final class TechniqueLeapClient {
    private static final int MARKER_INTERVAL_TICKS = 3;
    private static final double MARKER_RADIUS = 0.3;
    private static final double MARKER_HEIGHT = 0.02;
    private static final float MARKER_COLOR = 0.75F;
    private static final float MARKER_ALPHA = 0.35F;
    private static final double START_POSITION_TOLERANCE = 0.05;
    private static final double START_VELOCITY_TOLERANCE = 0.02;
    private static final double FINISH_TOLERANCE = 0.25;
    private static final long AWAITING_SERVER = Long.MAX_VALUE;
    private static TechniqueLeap.Landing marker;
    private static TechniqueLeapFlight prediction;
    private static boolean ordinaryJump;

    private TechniqueLeapClient() {
        // 客户端不读取身体、不扣费、不提供权威落点。
    }

    public static void initialize() {
        // 接收有界 Protobuf 后回到渲染主线程，标记绘制不依赖 F3。
        ClientPlayNetworking.registerGlobalReceiver(TechniqueLeapNetworking.CHANNEL,
                (client, handler, buffer, sender) -> {
                    byte[] payload = buffer.readByteArray(TechniqueLeapNetworking.MAX_PACKET_BYTES);
                    try {
                        LeapMotion message = LeapMotion.parseFrom(payload);
                        client.execute(() -> receive(client, message));
                    } catch (InvalidProtocolBufferException invalid) {
                        throw new IllegalArgumentException("跃步同步消息非法", invalid);
                    }
                });
        ClientTickEvents.END_CLIENT_TICK.register(TechniqueLeapClient::updateMarker);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(TechniqueLeapClient::render);
    }

    public static boolean jump(ClientPlayerEntity player) {
        // 先预测以免按键等待网络；潜行普通跳不发跃步请求。
        if (ordinaryJump) {
            return false;
        }
        TechniqueLeaps.Jump result = TechniqueLeaps.jump(player);
        if (result == TechniqueLeaps.Jump.LEAP) {
            request(player);
        }
        return result != TechniqueLeaps.Jump.ORDINARY;
    }

    private static void request(ClientPlayerEntity player) {
        // 视线包先于请求到达，服务端按同一起点和准星求解；下一次间隔等服务端 FINISH 给出。
        var access = (TechniqueLeapAccess) player;
        prediction = access.meridian$getLeap();
        access.meridian$setLeapReadyTick(AWAITING_SERVER);
        var origin = prediction.origin;
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(origin.x, origin.y, origin.z,
                player.getYaw(), player.getPitch(), true /* onGround */));
        var request = LeapMotion.newBuilder().setAction(LeapMotion.Action.REQUEST).build();
        var buffer = PacketByteBufs.create().writeByteArray(request.toByteArray());
        ClientPlayNetworking.send(TechniqueLeapNetworking.CHANNEL, buffer);
    }

    private static void receive(MinecraftClient client, LeapMotion message) {
        // 只预测本人的移动；其他实体由原版跟踪器插值，避免双方同时移动。
        if (client.world == null || !(client.world.getEntityById(message.getEntityId()) instanceof LivingEntity actor)
                || actor != client.player) {
            return;
        }
        LeapMotion.Action action = message.getAction();
        if (action == LeapMotion.Action.ORDINARY_JUMP) {
            ordinary(actor, message);
        } else if (action == LeapMotion.Action.START) {
            started(actor, message);
        } else if (action == LeapMotion.Action.FINISH) {
            finished(actor, message);
        }
    }

    private static void ordinary(LivingEntity actor, LeapMotion message) {
        // 服务端拒绝跃步时撤销预测，从服务端位置补一次原版跳跃并允许下一次预测。
        var access = (TechniqueLeapAccess) actor;
        access.meridian$setLeap(null);
        access.meridian$setLeapReadyTick(actor.getWorld().getTime());
        prediction = null;
        actor.setPosition(message.getX(), message.getY(), message.getZ());
        actor.setVelocity(Vec3d.ZERO);
        ordinaryJump = true;
        try {
            ((TechniqueJumpInvoker) actor).meridian$ordinaryJump();
        } finally {
            ordinaryJump = false;
        }
    }

    private static void started(LivingEntity actor, LeapMotion message) {
        // 预测起点和初速度与服务端一致时只采用权威落点高度，避免回执迟到时把本地位置拉回起点。
        TechniqueLeapFlight local = prediction;
        if (local != null && local.origin.distanceTo(position(message)) <= START_POSITION_TOLERANCE
                && local.launch.distanceTo(velocity(message)) <= START_VELOCITY_TOLERANCE) {
            local.landingY = message.getLandingY();
            return;
        }
        replay(actor, message, local);
    }

    private static void replay(LivingEntity actor, LeapMotion message, TechniqueLeapFlight local) {
        // 轨迹不一致时从权威起点重来，并快进本地已飞过的 tick，纠正量只剩两条轨迹之差。
        int elapsed;
        if (local == null) {
            elapsed = 0;
        } else {
            elapsed = local.ticks;
        }
        var registered = TechniqueStatusBenefits.get(message.getEffectId());
        var flight = new TechniqueLeapFlight(registered, message.getLandingY(), position(message),
                velocity(message), "prediction");
        var access = (TechniqueLeapAccess) actor;
        access.meridian$setLeap(flight);
        access.meridian$setLeapReadyTick(AWAITING_SERVER);
        prediction = flight;
        actor.setPosition(flight.origin);
        actor.setVelocity(flight.launch);
        actor.setOnGround(false /* onGround */);
        for (int tick = 0; tick < elapsed && access.meridian$getLeap() == flight; tick++) {
            TechniqueLeaps.travel(actor);
        }
    }

    private static void finished(LivingEntity actor, LeapMotion message) {
        // 采用服务端可起跳时刻；落点相差不超过原版移动容差时保留本地位置，由下一次移动包让服务端跟随。
        var access = (TechniqueLeapAccess) actor;
        access.meridian$setLeapReadyTick(message.getReadyTick());
        Vec3d offset = position(message).subtract(localLanding(actor));
        access.meridian$setLeap(null);
        prediction = null;
        if (offset.length() > FINISH_TOLERANCE) {
            actor.setPosition(actor.getPos().add(offset));
            actor.setVelocity(velocity(message));
        }
    }

    private static Vec3d localLanding(LivingEntity actor) {
        // 本地已先落地时比较当时的落点，落地后继续行走的位移不算偏差。
        if (((TechniqueLeapAccess) actor).meridian$getLeap() == null && prediction != null
                && prediction.end != null) {
            return prediction.end;
        }
        return actor.getPos();
    }

    private static Vec3d position(LeapMotion message) {
        // 回执中的服务端脚底位置。
        return new Vec3d(message.getX(), message.getY(), message.getZ());
    }

    private static Vec3d velocity(LeapMotion message) {
        // 回执中的服务端速度，单位为格每 tick。
        return new Vec3d(message.getVelocityX(), message.getVelocityY(), message.getVelocityZ());
    }

    private static void updateMarker(MinecraftClient client) {
        // 没有状态或已在空中时不显示落点；有限间隔避免每帧扫描世界。
        ClientPlayerEntity player = client.player;
        if (player == null || !player.isOnGround() || player.isSneaking()) {
            marker = null;
            return;
        }
        var registered = TechniqueStatusBenefits.active(player).orElse(null);
        if (registered == null) {
            marker = null;
        } else if (player.age % MARKER_INTERVAL_TICKS == 0) {
            marker = TechniqueLeapTargets.aimed(player, registered.benefit().getLeap());
        }
    }

    static TechniqueLeap.Landing marker() {
        // 客户端验收读取实际渲染缓存，不另算一份测试落点。
        return marker;
    }

    private static void render(WorldRenderContext context) {
        // 使用原版线框渲染器描一块薄地面框，无粒子、无文字、无调试层。
        if (marker == null || !marker.supported() || context.consumers() == null) {
            return;
        }
        var point = marker.point();
        var box = new Box(point.x() - MARKER_RADIUS, point.y() + MARKER_HEIGHT, point.z() - MARKER_RADIUS,
                point.x() + MARKER_RADIUS, point.y() + MARKER_HEIGHT, point.z() + MARKER_RADIUS);
        var camera = context.camera().getPos();
        var matrices = context.matrixStack();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        WorldRenderer.drawBox(matrices, context.consumers().getBuffer(RenderLayer.getLines()), box,
                MARKER_COLOR, MARKER_COLOR, MARKER_COLOR, MARKER_ALPHA);
        matrices.pop();
    }
}
