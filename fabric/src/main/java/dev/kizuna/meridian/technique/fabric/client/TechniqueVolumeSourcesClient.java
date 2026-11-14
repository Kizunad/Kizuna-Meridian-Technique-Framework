package dev.kizuna.meridian.technique.fabric.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.technique.fabric.TechniqueVolumeSources;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSources;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 客户端手里的那份功法谱来源：进服收到整份，断线清空；只给功法谱拼来源暗示用。 */
public final class TechniqueVolumeSourcesClient {
    private static VolumeSources current = VolumeSources.getDefaultInstance();

    private TechniqueVolumeSourcesClient() {
        // 只有静态缓存。
    }

    static void initialize() {
        // 26.2 的收包回调已在客户端主线程上；消息非法时抛出，连接照旧断开。
        ClientPlayNetworking.registerGlobalReceiver(TechniqueVolumeSources.CHANNEL.type(), (payload, context) -> {
            try {
                current = VolumeSources.parseFrom(payload.bytes());
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalArgumentException("功法谱来源消息非法", invalid);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(
                () -> current = VolumeSources.getDefaultInstance()));
    }

    public static VolumeSources current() {
        // 只在客户端主线程读。
        return current;
    }
}
