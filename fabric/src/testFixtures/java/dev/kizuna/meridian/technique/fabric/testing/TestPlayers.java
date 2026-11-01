package dev.kizuna.meridian.technique.fabric.testing;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** 下游 GameTest 共用的 26.2 假玩家：真实 ServerPlayer 与正式网络处理器，只把传输层换成测试连接。 */
public final class TestPlayers {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TestPlayers() {
        // 只放静态夹具。
    }

    /** 不进玩家列表的玩家：能走正式网络入口，但不触发登录、进服等事件。 */
    public static ServerPlayer detached(ServerLevel level, String name) {
        // 与原版登录同一套 cookie 与构造；网络处理器构造时会把自己挂到 player.connection。
        var profile = new GameProfile(UUID.randomUUID(), name);
        var cookie = CommonListenerCookie.createInitial(profile, false /* transferred */);
        var player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
        new ServerGamePacketListenerImpl(level.getServer(), new TestConnection(), player, cookie);
        return player;
    }

    /**
     * 走原版进服入口：读同一 UUID 的玩家存档，进玩家列表并触发进服与 Fabric 网络事件，
     * 最后按客户端已加载完世界处理。断线用 {@link RecordingConnection#disconnectFromClient}。
     */
    public static void logIn(ServerPlayer player, Connection connection) {
        // 26.2 的玩家列表不再自己读存档；与原版生成任务一样先读档，没有存档就放到世界出生点，再交给玩家列表进服。
        var server = player.level().getServer();
        try (var reporter = new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
            var saved = server.getPlayerList().loadPlayerData(player.nameAndId())
                    .map(tag -> TagValueInput.create(reporter, server.registryAccess(), tag));
            if (saved.isPresent()) {
                player.load(saved.get());
            } else {
                moveToWorldSpawn(player);
            }
        }
        var cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false /* transferred */);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // 真实客户端加载完世界后会上报；上报前原版视玩家无敌，并忽略移动、攻击与交互。
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    /** 测试连接每发出一个包都交给这里：区块批次结束包按真实客户端回执，其他包不处理。 */
    public static void acknowledgeChunkBatch(Connection connection, Packet<?> packet) {
        // 26.2 分批发区块，客户端回执前不发下一批，未发到的区块里的实体也不向玩家同步；
        // 这里像真实客户端那样收到批次结束就回执，按最大速率要下一批。
        // 区块发到后，其中的实体要等玩家跨区段或发来移动包才重算配对；测试玩家不发移动包，
        // 回执后按移动包的服务端处理重算一次。1.20.1 没有批次回执，发区块时就同步其中的实体。
        if (!(packet instanceof ClientboundChunkBatchFinishedPacket)) {
            return;
        }
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
            listener.handleChunkBatchReceived(
                    new ServerboundChunkBatchReceivedPacket(PlayerChunkSender.MAX_CHUNKS_PER_TICK));
            listener.player.level().getChunkSource().move(listener.player);
        }
    }

    private static void moveToWorldSpawn(ServerPlayer player) {
        // 1.20.1 的玩家构造器自己找出生点；26.2 挪到登录配置阶段的生成任务，构造出的玩家停在原点的未加载区块。
        // 这里按原版传送回出生点的同一写法找安全落脚点，出生点区块随之加载。
        var level = player.level();
        var respawn = level.getRespawnData();
        BlockPos spawn = player.adjustSpawnLocation(level, respawn.pos());
        player.snapTo(Vec3.atBottomCenterOf(spawn), respawn.yaw(), respawn.pitch());
    }
}
