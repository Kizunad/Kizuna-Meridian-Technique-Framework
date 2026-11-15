package dev.kizuna.meridian.technique.fabric.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.technique.fabric.world.TechniqueCastStatusSync;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastStatus;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 自己这一次施放的显示副本：施放条、承接提示、预备提示和预播起手读它，不参与任何判定。 */
public final class TechniqueCastStatusClient {
    /** 正在进行的施放和此刻走到了相对起手的第几毫秒。 */
    public record Cast(CastStatus status, double elapsedMillis) {
        /** 这一式此刻能不能接在后面。 */
        public boolean followUpOpen(String technique) {
            return status.getFollowUpsList().stream().anyMatch(hint -> hint.getTechniqueId().equals(technique)
                    && elapsedMillis >= hint.getOpenMillis() && elapsedMillis <= hint.getCloseMillis());
        }
    }

    /** 按键没放出来或施放开始时的通知。 */
    public interface Listener {
        /** 一次施放开始了（服务端确认）。 */
        void started(CastStatus status);

        /** 这一式的按键没放出来。 */
        void rejected(String technique, TechniqueFailure failure);
    }

    private static final double MILLIS_PER_TICK = 50;
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static CastStatus status = CastStatus.getDefaultInstance();
    /** 起手那一刻对应的客户端世界时间（tick，可带小数）。 */
    private static double startedTick;

    private TechniqueCastStatusClient() {
        // 记录只在客户端主线程读写。
    }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(TechniqueCastStatusSync.CHANNEL.type(), (payload, context) -> {
            try {
                receive(CastStatus.parseFrom(payload.bytes()));
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalArgumentException("施放状态同步消息非法", invalid);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(
                () -> status = CastStatus.getDefaultInstance()));
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    private static void receive(CastStatus next) {
        // 同一次施放重发（预备变化、按键被拒）不重新对表，免得施放条来回跳。
        boolean started = !next.getTechniqueId().isEmpty() && !next.getCastId().equals(status.getCastId());
        if (started) {
            startedTick = now(0) - next.getElapsedMillis() / MILLIS_PER_TICK;
        }
        status = next;
        for (Listener listener : List.copyOf(LISTENERS)) {
            if (started) {
                listener.started(next);
            }
            if (!next.getRejectedTechniqueId().isEmpty()) {
                listener.rejected(next.getRejectedTechniqueId(), next.getRejectedFailure());
            }
        }
    }

    /** 正在进行的施放；收势结束、最晚的承接窗口也关了以后就当它结束，不等服务端的结束通知。 */
    public static Optional<Cast> current(float partialTick) {
        if (status.getTechniqueId().isEmpty()) {
            return Optional.empty();
        }
        double elapsed = (now(partialTick) - startedTick) * MILLIS_PER_TICK;
        long end = status.getFollowUpsList().stream().mapToLong(hint -> hint.getCloseMillis())
                .max().orElse(0);
        if (elapsed > Math.max(end, status.getRecoveryEndMillis())) {
            return Optional.empty();
        }
        return Optional.of(new Cast(status, elapsed));
    }

    /** 服务端替自己记着的那一式，没有时为空串。 */
    public static String buffered() {
        return status.getBufferedTechniqueId();
    }

    private static double now(float partialTick) {
        // 用世界时间：游戏暂停、冻结时施放也停着，施放条跟着停。
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime() + partialTick;
    }
}
