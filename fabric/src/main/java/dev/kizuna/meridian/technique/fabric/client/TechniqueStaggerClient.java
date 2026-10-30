package dev.kizuna.meridian.technique.fabric.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStaggers;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StaggerStatus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/** 服务端僵直的显示副本，只供界面和动画读取，不参与任何判定。 */
public final class TechniqueStaggerClient {
    private static final long NANOS_PER_MILLI = 1_000_000;
    private static final Map<Integer, Long> endNanos = new HashMap<>();

    private TechniqueStaggerClient() {
        // 记录只在客户端主线程读写。
    }

    public static void initialize() {
        // 收包线程只解码，记录回到客户端主线程更新；断线清空旧世界的实体编号。
        ClientPlayNetworking.registerGlobalReceiver(TechniqueStaggers.CHANNEL,
                (client, handler, buffer, sender) -> {
                    byte[] payload = buffer.readByteArray(TechniqueStaggers.MAX_PACKET_BYTES);
                    try {
                        StaggerStatus message = StaggerStatus.parseFrom(payload);
                        client.execute(() -> receive(message));
                    } catch (InvalidProtocolBufferException invalid) {
                        throw new IllegalArgumentException("僵直同步消息非法", invalid);
                    }
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(endNanos::clear));
    }

    private static void receive(StaggerStatus message) {
        // 剩余 0 是服务端结束通知；否则按本地单调时钟记录到期。
        if (message.getRemainingMillis() <= 0) {
            endNanos.remove(message.getEntityId());
            return;
        }
        endNanos.put(message.getEntityId(), System.nanoTime() + message.getRemainingMillis() * NANOS_PER_MILLI);
    }

    public static long remainingMillis(Entity entity) {
        // 本地倒数到零即视为结束，不等服务端结束包。
        Long end = endNanos.get(entity.getId());
        if (end == null) {
            return 0;
        }
        long remaining = (end - System.nanoTime()) / NANOS_PER_MILLI;
        if (remaining <= 0) {
            endNanos.remove(entity.getId());
            return 0;
        }
        return remaining;
    }
}
