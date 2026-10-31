package dev.kizuna.meridian.technique.fabric.testing;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.fabricmc.fabric.impl.networking.ChannelInfoHolder;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;

/** 下游 GameTest 共用的记录连接：不建立套接字，记下发往客户端的包，并预先声明客户端能收的通道。 */
public final class RecordingConnection extends Connection {
    private final EmbeddedChannel channel;
    private final List<Packet<?>> packets = new ArrayList<>();

    public RecordingConnection(Collection<Identifier> clientChannels) {
        // 接收端仍为服务端；挂内存通道让连接处于已连接状态，关闭后与真实连接一样失效。
        super(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(this);
        // 真实客户端在配置阶段登记可收通道；Fabric 建立游戏阶段网络处理器时读取这份待定列表。
        ((ChannelInfoHolder) (Connection) this).fabric_getPendingChannelsNames(ConnectionProtocol.PLAY)
                .addAll(clientChannels);
    }

    /** 已发往客户端的包，按发送顺序排列；用例可以清空后只看下一段输出。 */
    public List<Packet<?>> packets() {
        // 返回记录本身而不是副本，用例清空后只断言之后的输出。
        return packets;
    }

    /** 模拟客户端退出：关闭传输层后，像服务端网络循环那样处理一次断线。 */
    public void disconnectFromClient() {
        // 服务端踢人时已在主线程处理过断线，连接也已关闭；这里只补客户端主动退出的路径。
        if (isConnected()) {
            disconnect(Component.literal("测试客户端退出"));
            handleDisconnection();
        }
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        // 26.2 的各个发送入口都汇到这里；保留原始包供断言，并像传输成功那样执行回调。
        packets.add(packet);
        if (listener != null) {
            completeSend(listener);
        }
    }

    private void completeSend(ChannelFutureListener listener) {
        // 踢人时原版在断线包发完的回调里关闭连接，回调必须照常执行。
        try {
            listener.operationComplete(channel.newSucceededFuture());
        } catch (Exception exception) {
            throw new IllegalStateException("测试连接的发送回调失败", exception);
        }
    }
}
