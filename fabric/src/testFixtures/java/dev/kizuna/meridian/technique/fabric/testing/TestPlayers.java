package dev.kizuna.meridian.technique.fabric.testing;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** 下游 GameTest 共用的 26.2 假玩家：真实 ServerPlayer 与正式网络处理器，只把传输层换成测试连接。 */
public final class TestPlayers {
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
}
