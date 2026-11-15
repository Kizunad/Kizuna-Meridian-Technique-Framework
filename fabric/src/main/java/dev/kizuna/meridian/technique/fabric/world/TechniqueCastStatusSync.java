package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastStatus;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.server.TechniqueCastTimelines;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/**
 * 把玩家自己的施放状态发给本人：正在放哪一式、三段各到什么时候、哪几下打击、能接哪几式、有没有预备着的按键。
 * 只在起手、结束、预备变化和按键被拒时各发一条，客户端自己往下走时间；只是显示副本，判定都在服务端。
 */
public final class TechniqueCastStatusSync {
    private static final int MAX_PACKET_BYTES = 2048;
    public static final ProtobufChannel CHANNEL = new ProtobufChannel(
            Identifier.fromNamespaceAndPath("meridian_technique", "cast_status"), MAX_PACKET_BYTES);

    private TechniqueCastStatusSync() {
        // 没有自己的状态：每次都从身体现读。
    }

    /** 每个服务器实例登记一次：玩家的起手和结束各发一条。生物没有本人可发。 */
    public static void listen(MinecraftServer server, TechniqueService service) {
        service.addCastListener(observed -> {
            if (observed.outcome().getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
                send(server, observed.entityUuid());
            }
        });
        service.addCastEndListener(ended -> send(server, ended.entityUuid()));
    }

    static void send(MinecraftServer server, UUID entityUuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(entityUuid);
        if (player != null) {
            send(player, "", TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED);
        }
    }

    /** 发一条当前状态；rejected 不空时同时通报这一式的按键没放出来和原因。 */
    static void send(ServerPlayer player, String rejected, TechniqueFailure failure) {
        if (!ServerPlayNetworking.canSend(player, CHANNEL.type())) {
            return;
        }
        CastStatus.Builder status = TechniqueFabric.service().observe(player.getUUID())
                .map(observation -> TechniqueCastTimelines.status(TechniqueFabric.service().registry(),
                        TechniqueState.read(observation.body()), observation.body().settledAt().elapsedMillis()))
                .orElseGet(CastStatus::newBuilder);
        status.setBufferedTechniqueId(TechniqueInputBuffer.buffered(player.getUUID()))
                .setRejectedTechniqueId(rejected)
                .setRejectedFailure(failure);
        ServerPlayNetworking.send(player, CHANNEL.payload(status.build().toByteArray()));
    }
}
