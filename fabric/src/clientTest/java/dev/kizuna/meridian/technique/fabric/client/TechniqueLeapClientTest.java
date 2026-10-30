package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** 隔离整合客户端中按住真实跳跃键连跳，逐 tick 记录本地位置、传送包与落点。 */
public final class TechniqueLeapClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../leap-client-results.txt");
    private static final Path TRACE = Path.of("../leap-client-trace.txt");
    private static final int TIMEOUT = 2400;
    private static final int READY_TICKS = 80;
    private static final int SETTLE_TICKS = 30;
    private static final int AIM_TICKS = 6;
    private static final int SEQUENCE_TIMEOUT = 240;
    private static final int LEAPS = 3;
    private static final int ARENA_Y = 90;
    private static final int HALF_WIDTH = 4;
    private static final int RUNWAY_BACK = 4;
    private static final int RUNWAY_LENGTH = 32;
    private static final double AIM_DISTANCE = 6;
    private static final double MAXIMUM_ERROR = 0.5;
    private static final double MAXIMUM_SNAP_BACK = 0.05;
    private static final double BLOCK_CENTER = 0.5;
    private enum Stage { PREPARE, PREPARING, SETTLE, AIM, LEAP, REST, DONE }
    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stageTick;
    private CompletableFuture<Void> prepared;
    private Vec3d target;
    private Vec3d previous;
    private boolean flying;
    private int teleportsBefore;
    private double snapBack;
    private int snapTick = -1;
    private final List<Vec3d> takeoffs = new ArrayList<>();
    private final List<Vec3d> landings = new ArrayList<>();
    private final List<String> trace = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        // 专用入口只随验收任务加载，正常发行包不包含本类。
        if (!Boolean.getBoolean("meridian.leapClientTest")) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        // 客户端和服务端各自线程准备场地，不操作用户世界。
        if (stage == Stage.DONE) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("客户端跃步验收超时");
            }
            if (ticks < READY_TICKS || client.player == null || client.getServer() == null
                    || client.getOverlay() != null) {
                return;
            }
            advance(client);
        } catch (Throwable failure) {
            finish(client, failure.toString());
        }
    }

    private void advance(MinecraftClient client) {
        // 场地、瞄准、连跳、静置四步依次推进，每步只等待自己的条件。
        if (stage == Stage.PREPARE) {
            prepared = prepare(client);
            stage = Stage.PREPARING;
        } else if (stage == Stage.PREPARING && prepared.isDone()) {
            prepared.join();
            client.setScreen(null);
            stageTick = ticks;
            stage = Stage.SETTLE;
        } else if (stage == Stage.SETTLE && ticks - stageTick >= SETTLE_TICKS) {
            aim(client.player);
        } else if (stage == Stage.AIM && ticks - stageTick >= AIM_TICKS) {
            start(client);
        } else if (stage == Stage.LEAP) {
            record(client.player);
        } else if (stage == Stage.REST && ticks - stageTick >= SETTLE_TICKS) {
            verify(client);
        }
    }

    private CompletableFuture<Void> prepare(MinecraftClient client) {
        // 仅在复制出来的测试世界铺一条直跑道，通过正式状态入口给予轻身。
        var server = client.getServer();
        return CompletableFuture.runAsync(() -> {
            var player = server.getPlayerManager().getPlayer(client.player.getUuid());
            var world = player.getServerWorld();
            for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
                for (int z = -RUNWAY_BACK; z <= RUNWAY_LENGTH; z++) {
                    world.setBlockState(new BlockPos(x, ARENA_Y - 1, z), Blocks.STONE.getDefaultState());
                }
            }
            player.changeGameMode(GameMode.SURVIVAL);
            player.networkHandler.requestTeleport(BLOCK_CENTER, ARENA_Y, BLOCK_CENTER, 0, 0);
            var benefit = TechniqueStatusBenefits.get("meridian_basic_techniques:light_body");
            TechniqueStatusBenefits.apply(player, benefit.benefit());
        }, server);
    }

    private void aim(ClientPlayerEntity player) {
        // 视线与地面交于前方六格，落点标记按自身间隔刷新后再读取。
        double pitch = Math.toDegrees(Math.atan2(player.getStandingEyeHeight(), AIM_DISTANCE));
        player.setYaw(0);
        player.setPitch((float) pitch);
        stageTick = ticks;
        stage = Stage.AIM;
    }

    private void start(MinecraftClient client) {
        // 原版跳跃键保持按下，标记必须来自实际渲染缓存。
        var player = client.player;
        var effect = TechniqueStatusBenefits.active(player).orElseThrow();
        var status = player.getStatusEffect(effect.effect());
        if (status.shouldShowParticles() || !status.shouldShowIcon()) {
            throw new IllegalStateException("客户端轻身显示属性错误");
        }
        var marker = TechniqueLeapClient.marker();
        if (marker == null || !marker.supported()) {
            throw new IllegalStateException("客户端缺少合法落点标记");
        }
        target = new Vec3d(marker.point().x(), marker.point().y(), marker.point().z());
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "light-body-marker.png", client.getFramebuffer(),
                message -> { /* 截图文件由验收任务归档。 */ });
        previous = player.getPos();
        teleportsBefore = TechniqueTeleportCounter.count();
        client.options.jumpKey.setPressed(true /* pressed */);
        stageTick = ticks;
        stage = Stage.LEAP;
    }

    private void record(ClientPlayerEntity player) {
        // 逐 tick 比较本地位置，沿跑道方向倒退即为一次回拉。
        if (ticks - stageTick > SEQUENCE_TIMEOUT) {
            throw new IllegalStateException("客户端没有完成连跳：" + landings.size() + " " + player.getPos());
        }
        Vec3d position = player.getPos();
        double backward = previous.z - position.z;
        if (backward > snapBack) {
            snapBack = backward;
            snapTick = ticks - stageTick;
        }
        boolean leaping = ((TechniqueLeapAccess) player).meridian$getLeap() != null;
        trace.add(String.format(Locale.ROOT, "%d %.4f %.4f %.4f leap=%s ground=%s teleports=%d",
                ticks - stageTick, position.x, position.y, position.z, leaping, player.isOnGround(),
                TechniqueTeleportCounter.count() - teleportsBefore));
        if (leaping && !flying) {
            takeoffs.add(previous);
        } else if (!leaping && flying) {
            landings.add(position);
        }
        flying = leaping;
        previous = position;
        if (landings.size() == LEAPS) {
            MinecraftClient.getInstance().options.jumpKey.setPressed(false /* pressed */);
            stageTick = ticks;
            stage = Stage.REST;
        }
    }

    private void verify(MinecraftClient client) {
        // 静置后没有迟到的传送或回拉，每跳都落在前方六格的方块中心附近。
        int teleports = TechniqueTeleportCounter.count() - teleportsBefore;
        if (teleports != 0) {
            throw new IllegalStateException("连跳期间收到传送包：" + teleports);
        }
        if (snapBack > MAXIMUM_SNAP_BACK) {
            throw new IllegalStateException("位置回拉 " + snapBack + " 格，第 " + snapTick + " tick");
        }
        if (landings.get(0).distanceTo(target) > MAXIMUM_ERROR) {
            throw new IllegalStateException("客户端落点偏离：" + landings.get(0) + " target=" + target);
        }
        for (int index = 0; index < LEAPS; index++) {
            double distance = landings.get(index).subtract(takeoffs.get(index)).horizontalLength();
            if (Math.abs(distance - AIM_DISTANCE) > MAXIMUM_ERROR) {
                throw new IllegalStateException("第 " + (index + 1) + " 跳距离：" + distance);
            }
        }
        finish(client, null);
    }

    private void finish(MinecraftClient client, String failure) {
        // 无论成功失败都写出轨迹并结束本验收客户端，不留下后台游戏进程。
        stage = Stage.DONE;
        client.options.jumpKey.setPressed(false /* pressed */);
        String summary = String.format(Locale.ROOT, "maximumSnapBack=%.4f snapTick=%d teleports=%d landings=%s",
                snapBack, snapTick, TechniqueTeleportCounter.count() - teleportsBefore, landings);
        try {
            Files.write(TRACE, trace);
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure + "\n" + summary + "\n");
        } catch (Exception writeFailure) {
            throw new IllegalStateException(writeFailure);
        }
        client.scheduleStop();
    }
}
