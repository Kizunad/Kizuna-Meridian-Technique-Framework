package dev.kizuna.meridian.technique.fabric.testing;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
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
        // 26.2 的玩家列表不再自己读存档；与原版生成任务一样先读档，再交给玩家列表进服。
        var server = player.level().getServer();
        try (var reporter = new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
            server.getPlayerList().loadPlayerData(player.nameAndId())
                    .map(tag -> TagValueInput.create(reporter, server.registryAccess(), tag))
                    .ifPresent(player::load);
        }
        var cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false /* transferred */);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        // 真实客户端加载完世界后会上报；上报前原版视玩家无敌，并忽略移动、攻击与交互。
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }
}
