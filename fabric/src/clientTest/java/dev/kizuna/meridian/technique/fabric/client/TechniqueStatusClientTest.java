package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.inventoryui.client.status.StatusIcons;
import dev.kizuna.inventoryui.client.status.StatusLayout;
import dev.kizuna.inventoryui.status.StatusArrangement.Single;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.client.testmixin.StatusNotesAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
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
import net.minecraft.world.phys.Vec2;

/** 真实整合客户端验收统一状态图标、悬停说明、语言与牛奶清除，产物不进入正式包。 */
public final class TechniqueStatusClientTest implements FabricClientGameTest {
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
    private enum Stage { PREPARE, RELOAD, SCENE, STABLE, HOVER, CAPTURE, CLEAR, VERIFY_CLEAR, DONE }
    private Stage stage = Stage.PREPARE;
    private int ticks;
    private int stableUntil;
    private int sceneIndex;
    private CompletableFuture<Void> pending;
    private CompletableFuture<Void> screenshot;
    private Vec2 hoverCursor;
    private String failureMessage;

    @Override
    public void runTest(ClientGameTestContext context) {
        // 每次创建独立世界，不能依赖被入口清掉的旧存档；状态机仍在客户端线程推进。
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            while (context.computeOnClient(client -> stage != Stage.DONE)) {
                context.runOnClient(this::tick);
                Vec2 cursor = context.computeOnClient(client -> hoverCursor);
                if (cursor != null) {
                    context.getInput().setCursorPos(cursor.x, cursor.y);
                }
                context.waitTick();
            }
            String failure = context.computeOnClient(client -> failureMessage);
            if (failure != null) {
                throw new AssertionError(failure);
            }
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
            finish(failure.toString());
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
                    showScene(client);
                }
            }
            case HOVER -> {
                if (ticks >= stableUntil) {
                    verifyNotes(client);
                    capture(client, SCENES.get(sceneIndex).name() + "-effect-tooltip");
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
        // 中文资源真实重载，测试场地只存在于独立的验收世界。
        client.options.pauseOnLostFocus = false;
        client.getTutorial().setStep(TutorialSteps.NONE);
        client.options.languageCode = "zh_cn";
        client.getLanguageManager().setSelected("zh_cn");
        pending = client.reloadResourcePacks();
        stage = Stage.RELOAD;
    }

    private void scene(Minecraft client) {
        // 同一组三效果在两档 GUI scale 和昼夜场景中分别显示 HUD 与背包里的统一状态图标。
        Scene scene = SCENES.get(sceneIndex);
        hoverCursor = null;
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

    private void showScene(Minecraft client) {
        // 背包先定位当前格位并移鼠标，等提示真正画出来再截图。
        if (SCENES.get(sceneIndex).view() == View.INVENTORY) {
            hoverCursor = hoverEffect(client, IDS.getFirst());
            stableUntil = ticks + STABLE_TICKS;
            stage = Stage.HOVER;
        } else {
            capture(client, SCENES.get(sceneIndex).name());
            stage = Stage.CAPTURE;
        }
    }

    private static Vec2 hoverEffect(Minecraft client, String id) {
        // 此方法由客户端 tick 调用；读同一帧格位，底框中心乘界面缩放换成窗口坐标。
        StatusIcons.Layout layout = StatusIcons.lastLayout();
        var slots = layout.slots();
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index) instanceof Single single && single.effect().id().equals(id)) {
                StatusLayout.Area cell = layout.cells().get(index);
                int half = StatusLayout.FRAME_PIXELS / 2;
                float scale = (float) client.getWindow().getGuiScale();
                return new Vec2((cell.left() + half) * scale, (cell.top() + half) * scale);
            }
        }
        throw new AssertionError("没有找到状态的单个图标：" + id);
    }

    private static void verifyNotes(Minecraft client) {
        // 从界面框架实际取说明，必须含功法库的灰字；不能把空来源或漏登记当作通过。
        for (String id : IDS) {
            var effect = client.player.getEffect(TechniqueStatusBenefits.get(id).effect());
            var expected = TechniqueEffectTooltips.lines(effect);
            if (TechniqueStatusBenefits.tissue(effect).isPresent()) {
                var actual = StatusNotesAccess.notes(effect);
                if (expected.isEmpty() || !actual.containsAll(expected)) {
                    throw new AssertionError("功法状态图标缺少说明行：" + id);
                }
            } else if (!expected.isEmpty()) {
                throw new AssertionError("非组织状态不应提供功法说明：" + id);
            }
        }
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
            finish(null);
        }
    }

    private void finish(String failure) {
        // 保存可机器读取的验收结果，世界和客户端的退出交给客户端测试框架。
        failureMessage = failure;
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nfailure=" + failure
                    + "\nscenes=" + sceneIndex + "\n");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
