package dev.kizuna.meridian.technique.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 学习成功后发送只读展示通知；没有客户端请求或学习确认回包。 */
public final class TechniqueScrollNetworking {
    public static final CustomPacketPayload.Type<Learned> LEARNED = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("meridian_technique", "scroll_learned"));
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll");

    private TechniqueScrollNetworking() {
        // 网络通知不参与身体提交和物品消耗事务。
    }

    static void initialize() {
        // 只有服务端发往客户端一个方向；在公共初始化里登记，两端才都能编解码。
        PayloadTypeRegistry.clientboundPlay().register(LEARNED, Learned.CODEC);
    }

    static void learned(ServerPlayer player, String volumeId) {
        // 展示通知失败不会把已经成功的学习报告成失败，也不会重复消耗卷轴。
        try {
            if (ServerPlayNetworking.canSend(player, LEARNED)) {
                ServerPlayNetworking.send(player, new Learned(Identifier.parse(volumeId)));
            }
        } catch (RuntimeException exception) {
            LOG.warn("卷轴阅读通知未发送：{}", volumeId, exception);
        }
    }

    /** 刚学会的卷；客户端只用它打开阅读界面。 */
    public record Learned(Identifier volume) implements CustomPacketPayload {
        static final StreamCodec<ByteBuf, Learned> CODEC = Identifier.STREAM_CODEC.map(Learned::new, Learned::volume);

        @Override
        public Type<Learned> type() {
            return LEARNED;
        }
    }
}
