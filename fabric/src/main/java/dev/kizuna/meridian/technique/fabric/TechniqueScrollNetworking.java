package dev.kizuna.meridian.technique.fabric;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 学习成功后发送只读展示通知；没有客户端请求或学习确认回包。 */
public final class TechniqueScrollNetworking {
    public static final ResourceLocation LEARNED = new ResourceLocation("meridian_technique", "scroll_learned");
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll");

    private TechniqueScrollNetworking() {
        // 网络通知不参与身体提交和物品消耗事务。
    }

    static void learned(ServerPlayer player, String volumeId) {
        // 展示通知失败不会把已经成功的学习报告成失败，也不会重复消耗卷轴。
        try {
            if (ServerPlayNetworking.canSend(player, LEARNED)) {
                var buffer = PacketByteBufs.create().writeResourceLocation(new ResourceLocation(volumeId));
                ServerPlayNetworking.send(player, LEARNED, buffer);
            }
        } catch (RuntimeException exception) {
            LOG.warn("卷轴阅读通知未发送：{}", volumeId, exception);
        }
    }
}
