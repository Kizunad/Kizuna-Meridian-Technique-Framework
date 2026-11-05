package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.fabric.world.TechniqueTissueEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/** 两次独立 JVM 验证正常停服保存与状态相位恢复。 */
public final class TechniqueStatusRestartClientTest implements ClientModInitializer {
    private static final String STATUS = "meridian_basic_techniques:charge";
    private static final int TOTAL_TICKS = 800;
    private static final int WEAK_REMAINING = 240;
    private static final int TIMEOUT_TICKS = 2400;
    private static final double WEAK_FACTOR = 0.85;
    private static final double EPSILON = 0.02;
    private static final Path CHECKPOINT = Path.of("../status-restart-checkpoint.properties");
    private final String mode = System.getProperty("meridian.statusRestart", "");
    private volatile boolean finished;
    private int ticks;
    private int clientTicks;
    private int playerTicks;
    private boolean granted;

    @Override
    public void onInitializeClient() {
        // 仅专用入口注册；停服和读档均使用真实整合服务端事件。
        if (mode.isEmpty()) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++clientTicks > TIMEOUT_TICKS && !finished) {
                finish("success=false\nfailure=客户端登录或加载超时\n");
            }
            if (finished) {
                client.stop();
            }
        });
    }

    private void tick(MinecraftServer server) {
        // 服务端主线程读写能力，客户端只在结果落盘后请求正常关闭。
        if (finished) {
            return;
        }
        try {
            if (++ticks > TIMEOUT_TICKS) {
                throw new IllegalStateException("重启验收超时");
            }
            if (server.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (++playerTicks <= 1) {
                return;
            }
            if (mode.equals("save")) {
                save(player);
            } else {
                restore(player);
            }
        } catch (Throwable failure) {
            finish("success=false\nfailure=" + failure + "\n");
        }
    }

    private void save(ServerPlayer player) throws Exception {
        // 真实等待自定义时长进入乏力段，再让原版关闭流程保存效果和身体。
        var registered = TechniqueStatusBenefits.get(STATUS);
        if (!granted) {
            player.removeAllEffects();
            var instance = new MobEffectInstance(registered.effect(), TOTAL_TICKS, 0,
                    false /* ambient */, false /* showParticles */, true /* showIcon */);
            if (!player.addEffect(instance)) {
                throw new IllegalStateException("重启夹具状态未接受");
            }
            granted = true;
        }
        int remaining = player.getEffect(registered.effect()).getDuration();
        if (remaining > WEAK_REMAINING) {
            return;
        }
        verifyWeak(player);
        var checkpoint = new Properties();
        checkpoint.setProperty("uuid", player.getStringUUID());
        checkpoint.setProperty("remaining", Integer.toString(remaining));
        checkpoint.setProperty("totalQi", Double.toString(body(player).totalQi()));
        try (var writer = Files.newBufferedWriter(CHECKPOINT)) {
            checkpoint.store(writer, "状态重启前的真实实例");
        }
        finish("success=true\nremaining=" + remaining + "\nfactor=" + WEAK_FACTOR + "\n");
    }

    private void restore(ServerPlayer player) throws Exception {
        // 新 JVM 不给予任何效果，只检查原版保存结果与恢复后的真实双臂能力。
        var checkpoint = new Properties();
        try (var reader = Files.newBufferedReader(CHECKPOINT)) {
            checkpoint.load(reader);
        }
        var instance = player.getEffect(TechniqueStatusBenefits.get(STATUS).effect());
        int previous = Integer.parseInt(checkpoint.getProperty("remaining"));
        if (!player.getStringUUID().equals(checkpoint.getProperty("uuid")) || instance == null
                || instance.getDuration() <= 0 || instance.getDuration() > previous) {
            throw new IllegalStateException("重启刷新时长、丢失状态或改变玩家身份：" + instance);
        }
        var leases = TechniqueTissueEffects.of(player).leases();
        if (leases.size() != 1 || leases.getFirst().getTotalTicks() != TOTAL_TICKS) {
            throw new IllegalStateException("重启丢失曲线总时长或重复登记归属");
        }
        verifyWeak(player);
        int remaining = instance.getDuration();
        player.removeAllEffects();
        if (!body(player).modifiers().isEmpty()) {
            throw new IllegalStateException("重启后清除遗留修正");
        }
        finish("success=true\npreviousRemaining=" + previous + "\nrestoredRemaining=" + remaining
                + "\ntotalTicks=" + TOTAL_TICKS + "\nfactor=" + WEAK_FACTOR + "\n");
    }

    private static void verifyWeak(ServerPlayer player) {
        // 双臂有效力量和功率必须仍为乏力相位，同时修正数量与归属一致。
        var body = body(player);
        int owned = TechniqueTissueEffects.of(player).leases().getFirst().getModifierIdsCount();
        if (body.modifiers().size() != owned) {
            throw new IllegalStateException("重启产生重复或孤儿修正");
        }
        for (var tissue : body.structure().tissues()) {
            if (tissue.tags().stream().noneMatch(tag -> tag.value().equals("human:arm_muscle"))) {
                continue;
            }
            var capacity = TissueCapabilities.available(body, tissue.id());
            double strength = capacity.strengthNewtons() / tissue.capacity().strengthNewtons();
            double burst = capacity.burstWatts() / tissue.capacity().burstWatts();
            if (Math.abs(strength - WEAK_FACTOR) > EPSILON || Math.abs(burst - WEAK_FACTOR) > EPSILON) {
                throw new IllegalStateException("重启改变曲线相位：" + strength + ", " + burst);
            }
        }
    }

    private static dev.kizuna.meridian.core.BodySnapshot body(ServerPlayer player) {
        // 每次通过正式宿主获得结清的身体状态。
        return TechniqueFabric.service().statusHost(player.getUUID()).orElseThrow().inspect().body();
    }

    private void finish(String result) {
        // 可机器读取的结果在请求关闭前落盘，不用人工停止伪装正常停服。
        try {
            Files.writeString(Path.of("../status-restart-" + mode + "-results.txt"), result);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        finished = true;
    }
}
