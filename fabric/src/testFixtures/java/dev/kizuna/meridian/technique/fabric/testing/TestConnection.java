package dev.kizuna.meridian.technique.fabric.testing;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** 下游 GameTest 共用的测试连接：不建立套接字，发往客户端的包直接丢弃。 */
public final class TestConnection extends Connection {
    public TestConnection() {
        // 接收端仍为服务端；与原版 makeMockServerPlayerInLevel 相同，挂内存通道让连接处于已连接状态。
        super(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(this);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        // 26.2 的各个发送入口都汇到这里；只验证服务端入站处理的用例不需要出站包。
    }
}
