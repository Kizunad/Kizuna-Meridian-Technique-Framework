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
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** 本地预测与无粒子落点描边；服务端回执只在偏差超过容差时纠正预测结果。 */
public final class TechniqueLeapClient {
    private static final int MARKER_INTERVAL_TICKS = 3;
    private static final double MARKER_RADIUS = 0.3;
    private static final double MARKER_HEIGHT = 0.02;
    private static final float MARKER_COLOR = 0.75F;
    private static final float MARKER_ALPHA = 0.35F;
    private static final int MARKER_RINGS = 2;
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
        // 26.2 的收包回调已在客户端主线程上，解码后直接处理；消息非法时抛出，连接照旧断开。
        // 标记绘制不依赖 F3，在 26.2 的收集阶段提交。
        ClientPlayNetworking.registerGlobalReceiver(TechniqueLeapNetworking.CHANNEL.type(), (payload, context) -> {
            LeapMotion message;
            try {
                message = LeapMotion.parseFrom(payload.bytes());
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalArgumentException("跃步同步消息非法", invalid);
            }
            receive(context.client(), message);
        });
        ClientTickEvents.END_CLIENT_TICK.register(TechniqueLeapClient::updateMarker);
        LevelRenderEvents.COLLECT_SUBMITS.register(TechniqueLeapClient::render);
    }

    public static boolean jump(LocalPlayer player) {
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

    private static void request(LocalPlayer player) {
        // 视线包先于请求到达，服务端按同一起点和准星求解；下一次间隔等服务端 FINISH 给出。
        // 26.2 的位置包多带水平碰撞，照原版移动包取玩家当前值。
        var access = (TechniqueLeapAccess) player;
        prediction = access.meridian$getLeap();
        access.meridian$setLeapReadyTick(AWAITING_SERVER);
        var origin = prediction.origin;
        player.connection.send(new ServerboundMovePlayerPacket.PosRot(origin.x, origin.y, origin.z,
                player.getYRot(), player.getXRot(), true /* onGround */, player.horizontalCollision));
        var request = LeapMotion.newBuilder().setAction(LeapMotion.Action.REQUEST).build();
        ClientPlayNetworking.send(TechniqueLeapNetworking.CHANNEL.payload(request.toByteArray()));
    }

    private static void receive(Minecraft client, LeapMotion message) {
        // 只预测本人的移动；其他实体由原版跟踪器插值，避免双方同时移动。
        if (client.level == null || !(client.level.getEntity(message.getEntityId()) instanceof LivingEntity actor)
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
        access.meridian$setLeapReadyTick(actor.level().getGameTime());
        prediction = null;
        actor.setPos(message.getX(), message.getY(), message.getZ());
        actor.setDeltaMovement(Vec3.ZERO);
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
        if (!registered.benefit().hasLeap()) {
            throw new IllegalArgumentException("跃步回执引用了非跃步状态");
        }
        var flight = new TechniqueLeapFlight(registered, message.getLandingY(), position(message),
                velocity(message), "prediction");
        var access = (TechniqueLeapAccess) actor;
        access.meridian$setLeap(flight);
        access.meridian$setLeapReadyTick(AWAITING_SERVER);
        prediction = flight;
        actor.setPos(flight.origin);
        actor.setDeltaMovement(flight.launch);
        actor.setOnGround(false /* onGround */);
        for (int tick = 0; tick < elapsed && access.meridian$getLeap() == flight; tick++) {
            TechniqueLeaps.travel(actor);
        }
    }

    private static void finished(LivingEntity actor, LeapMotion message) {
        // 采用服务端可起跳时刻；落点相差不超过原版移动容差时保留本地位置，由下一次移动包让服务端跟随。
        var access = (TechniqueLeapAccess) actor;
        access.meridian$setLeapReadyTick(message.getReadyTick());
        Vec3 offset = position(message).subtract(localLanding(actor));
        access.meridian$setLeap(null);
        prediction = null;
        if (offset.length() > FINISH_TOLERANCE) {
            actor.setPos(actor.position().add(offset));
            actor.setDeltaMovement(velocity(message));
        }
    }

    private static Vec3 localLanding(LivingEntity actor) {
        // 本地已先落地时比较当时的落点，落地后继续行走的位移不算偏差。
        if (((TechniqueLeapAccess) actor).meridian$getLeap() == null && prediction != null
                && prediction.end != null) {
            return prediction.end;
        }
        return actor.position();
    }

    private static Vec3 position(LeapMotion message) {
        // 回执中的服务端脚底位置。
        return new Vec3(message.getX(), message.getY(), message.getZ());
    }

    private static Vec3 velocity(LeapMotion message) {
        // 回执中的服务端速度，单位为格每 tick。
        return new Vec3(message.getVelocityX(), message.getVelocityY(), message.getVelocityZ());
    }

    private static void updateMarker(Minecraft client) {
        // 没有状态或已在空中时不显示落点；有限间隔避免每帧扫描世界。
        LocalPlayer player = client.player;
        if (player == null || !player.onGround() || player.isShiftKeyDown()) {
            marker = null;
            return;
        }
        var registered = TechniqueStatusBenefits.activeLeap(player).orElse(null);
        if (registered == null) {
            marker = null;
        } else if (player.tickCount % MARKER_INTERVAL_TICKS == 0) {
            marker = TechniqueLeapTargets.aimed(player, registered.benefit().getLeap());
        }
    }

    static TechniqueLeap.Landing marker() {
        // 客户端验收读取实际渲染缓存，不另算一份测试落点。
        return marker;
    }

    private static void render(LevelRenderContext context) {
        // 使用原版线条管线描一块薄地面框，无粒子、无文字、无调试层。
        // 26.2 先收集再统一绘制：这里只提交几何，坐标相对相机；线宽取原版描边同一个值。
        if (marker == null || !marker.supported()) {
            return;
        }
        var point = marker.point();
        var camera = context.levelState().cameraRenderState.pos;
        float width = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
        var matrices = context.poseStack();
        matrices.pushPose();
        matrices.translate(point.x() - camera.x, point.y() + MARKER_HEIGHT - camera.y, point.z() - camera.z);
        context.submitNodeCollector().submitCustomGeometry(matrices, RenderTypes.lines(),
                (pose, buffer) -> outline(pose, buffer, width));
        matrices.popPose();
    }

    private static void outline(PoseStack.Pose pose, VertexConsumer buffer, float width) {
        // 1.20.1 的 renderLineBox 在 26.2 已删除。它画零高盒子时上下两圈重合、四条竖棱长度为零，
        // 这里照样画两圈方框、不画竖棱，叠加后的不透明度与原来相同。
        float radius = (float) MARKER_RADIUS;
        float[][] corners = {{-radius, -radius}, {radius, -radius}, {radius, radius}, {-radius, radius}};
        for (int ring = 0; ring < MARKER_RINGS; ring++) {
            for (int side = 0; side < corners.length; side++) {
                float[] from = corners[side];
                float[] to = corners[(side + 1) % corners.length];
                float normalX = Math.signum(to[0] - from[0]);
                float normalZ = Math.signum(to[1] - from[1]);
                vertex(pose, buffer, from, normalX, normalZ, width);
                vertex(pose, buffer, to, normalX, normalZ, width);
            }
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float[] corner, float normalX,
                               float normalZ, float width) {
        // 线条顶点的法线是线段方向，着色器据此在屏幕上加宽。
        buffer.addVertex(pose, corner[0], 0, corner[1])
                .setColor(MARKER_COLOR, MARKER_COLOR, MARKER_COLOR, MARKER_ALPHA)
                .setNormal(pose, normalX, 0, normalZ)
                .setLineWidth(width);
    }
}
