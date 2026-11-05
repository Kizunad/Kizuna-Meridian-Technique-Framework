package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** 真实整合客户端验收原版状态列表、HUD、语言与图标，产物不进入正式包。 */
public final class TechniqueStatusClientTest implements ClientModInitializer {
    private static final Path REPORT = Path.of("../status-client-results.txt");
    private static final List<String> IDS = List.of("meridian_basic_techniques:breath",
            "meridian_basic_techniques:charge", "meridian_basic_techniques:light_body");
    private static final int TIMEOUT = 2400;
    private static final int READY_TICKS = 80;
    private static final int STABLE_TICKS = 12;
    private static final int GUI_SMALL = 2;
    private static final int GUI_LARGE = 4;
    private static final int ARENA_Y = 90;
    private static final int ARENA_RADIUS = 6;
    private static final long DAY = 6000;
    private static final long NIGHT = 18000;
    private static final int FULL_SIZE = 1;
    private static final List<Scene> SCENES = List.of(
            new Scene("day-hud-small", GUI_SMALL, View.HUD, DAY),
            new Scene("day-list-small", GUI_SMALL, View.INVENTORY, DAY),
            new Scene("day-hud-large", GUI_LARGE, View.HUD, DAY),
            new Scene("day-list-large", GUI_LARGE, View.INVENTORY, DAY),
            new Scene("night-hud-small", GUI_SMALL, View.HUD, NIGHT),
            new Scene("night-list-small", GUI_SMALL, View.INVENTORY, NIGHT),
            new Scene("night-hud-large", GUI_LARGE, View.HUD, NIGHT),
            new Scene("night-list-large", GUI_LARGE, View.INVENTORY, NIGHT));
    private enum View { HUD, INVENTORY }
    private record Scene(String name, int scale, View view, long time) { }
    private enum Stage { PREPARE, RELOAD, SCENE, STABLE, CAPTURE, CLEAR, VERIFY_CLEAR, DONE }
    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stableUntil;
    private int sceneIndex;
    private CompletableFuture<Void> pending;
    private CompletableFuture<Void> screenshot;

    @Override
    public void onInitializeClient() {
        // 验收只能由专用 Gradle 入口启用。
        if (Boolean.getBoolean("meridian.statusClientTest")) {
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }
    }

    private void tick(Minecraft client) {
        // 等待世界和资源稳定，图像回调完成后才进入下一场景。
        if (stage == Stage.DONE) {
            return;
        }
        try {
            if (++ticks > TIMEOUT) {
                throw new IllegalStateException("状态客户端验收超时：" + stage);
            }
            if (ticks < READY_TICKS || client.player == null || client.getSingleplayerServer() == null
                    || client.gui.overlay() != null) {
                return;
            }
            advance(client);
        } catch (Throwable failure) {
            finish(client, failure.toString());
        }
    }

    private void advance(Minecraft client) {
        // 各阶段只推进一个完整动作，等待均由客户端帧驱动。
        switch (stage) {
            case PREPARE -> prepare(client);
            case RELOAD -> {
                if (pending.isDone()) {
                    pending.join();
                    stage = Stage.SCENE;
                }
            }
            case SCENE -> scene(client);
            case STABLE -> {
                if (pending.isDone() && ticks >= stableUntil) {
                    pending.join();
                    verifyEffects(client);
                    capture(client, SCENES.get(sceneIndex).name());
                    stage = Stage.CAPTURE;
                }
            }
            case CAPTURE -> captured();
            case CLEAR -> clear(client);
            case VERIFY_CLEAR -> verifyClear(client);
            default -> { }
        }
    }

    private void prepare(Minecraft client) {
        // 中文资源真实重载，测试场地只存在于复制的验收世界。
        client.options.pauseOnLostFocus = false;
        client.getTutorial().setStep(TutorialSteps.NONE);
        client.options.languageCode = "zh_cn";
        client.getLanguageManager().setSelected("zh_cn");
        pending = client.reloadResourcePacks();
        stage = Stage.RELOAD;
    }

    private void scene(Minecraft client) {
        // 同一组三效果在两档 GUI scale 和昼夜场景中分别显示 HUD 与原版状态列表。
        Scene scene = SCENES.get(sceneIndex);
        client.options.guiScale().set(scene.scale());
        client.resizeGui();
        if (scene.view() == View.INVENTORY) {
            client.gui.setScreen(new InventoryScreen(client.player));
        } else {
            client.gui.setScreen(null);
        }
        var server = client.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            var player = server.getPlayerList().getPlayer(client.player.getUUID());
            var world = player.level();
            BlockPos position = player.blockPosition();
            var clock = world.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
            world.clockManager().setTotalTicks(clock, scene.time());
            buildPlatform(world, position);
            player.setGameMode(GameType.SURVIVAL);
            player.connection.teleport(player.getX(), ARENA_Y, player.getZ(), 0, 0);
            for (String id : IDS) {
                TechniqueStatusBenefits.apply(player, TechniqueStatusBenefits.get(id).benefit());
            }
        }, server);
        stableUntil = ticks + STABLE_TICKS;
        stage = Stage.STABLE;
    }

    private static void buildPlatform(ServerLevel world, BlockPos position) {
        // 固定高度的测试平台让昼夜截图使用同一背景。
        for (int x = -ARENA_RADIUS; x <= ARENA_RADIUS; x++) {
            for (int z = -ARENA_RADIUS; z <= ARENA_RADIUS; z++) {
                world.setBlockAndUpdate(new BlockPos(position.getX() + x, ARENA_Y - 1, position.getZ() + z),
                        Blocks.STONE.defaultBlockState());
            }
        }
    }

    private void verifyEffects(Minecraft client) {
        // 原版网络状态必须有名称、正剩余时间和图标，资源管理器必须能解析真实 PNG。
        for (String id : IDS) {
            var registered = TechniqueStatusBenefits.get(id);
            var effect = client.player.getEffect(registered.effect());
            if (effect == null || effect.getDuration() <= 0 || !effect.showIcon() || effect.isVisible()) {
                throw new IllegalStateException("客户端状态缺失或显示属性错误：" + id);
            }
            Identifier identity = Identifier.parse(id);
            Identifier texture = Identifier.fromNamespaceAndPath(identity.getNamespace(),
                    "textures/mob_effect/" + identity.getPath() + ".png");
            if (client.getResourceManager().getResource(texture).isEmpty()) {
                throw new IllegalStateException("客户端状态贴图缺失：" + texture);
            }
            if (registered.effect().value().getDisplayName().getString().startsWith("effect.")) {
                throw new IllegalStateException("状态没有翻译：" + id);
            }
        }
    }

    private void capture(Minecraft client, String name) {
        // 等待渲染器真正完成截图后再切屏，避免归档错误场景。
        screenshot = new CompletableFuture<>();
        Screenshot.grab(client.gameDirectory, "status-" + name + ".png", client.gameRenderer.mainRenderTarget(),
                FULL_SIZE, message -> screenshot.complete(null));
    }

    private void captured() {
        // 截图回调只改变阶段，不把磁盘尚未写完的文件当作完成。
        if (!screenshot.isDone()) {
            return;
        }
        sceneIndex++;
        if (sceneIndex < SCENES.size()) {
            stage = Stage.SCENE;
        } else {
            stage = Stage.CLEAR;
        }
    }

    private void clear(Minecraft client) {
        // 牛奶走真实消费入口，服务端同时检查身体修正已撤销。
        client.gui.setScreen(null);
        var server = client.getSingleplayerServer();
        pending = CompletableFuture.runAsync(() -> {
            var player = server.getPlayerList().getPlayer(client.player.getUUID());
            new ItemStack(Items.MILK_BUCKET).finishUsingItem(player.level(), player);
            var body = TechniqueFabric.service().statusHost(player.getUUID()).orElseThrow().inspect().body();
            if (!body.modifiers().isEmpty()) {
                throw new IllegalStateException("牛奶清除后遗留能力修正");
            }
        }, server);
        stableUntil = ticks + STABLE_TICKS;
        stage = Stage.VERIFY_CLEAR;
    }

    private void verifyClear(Minecraft client) {
        // 客户端效果消失后再截一张清除结果，不能只依赖服务端断言。
        if (!pending.isDone() || ticks < stableUntil) {
            return;
        }
        pending.join();
        if (!client.player.getActiveEffects().isEmpty()) {
            throw new IllegalStateException("牛奶清除没有同步到客户端");
        }
        if (sceneIndex == SCENES.size()) {
            capture(client, "cleared");
            sceneIndex++;
        } else if (screenshot.isDone()) {
            finish(client, null);
        }
    }

    private void finish(Minecraft client, String failure) {
        // 保存可机器读取的验收结果，正常或失败都结束本次独立客户端。
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure
                    + "\nscenes=" + sceneIndex + "\n");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        client.stop();
    }
}
