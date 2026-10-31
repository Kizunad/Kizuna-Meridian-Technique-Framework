package dev.kizuna.meridian.technique.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 完整装配中经学习回调打开真实 Screen，验证改键、按钮与滚动截图。 */
public final class TechniqueScrollClientTest implements ClientModInitializer {
    private static final int READY_TICKS = 60;
    private static final int TIMEOUT_TICKS = 2400;
    private static final int SETTLE_FRAMES = 12;
    private static final int SCROLL_TO_END = -100;
    private static final int GUI_TWO = 2;
    private static final int GUI_THREE = 3;
    private static final int HALF = 2;
    private static final int EXTRA_MOUSE_BUTTON = 4;
    private static final int NO_MODIFIERS = 0;
    private static final double SCROLL_DOWN = -1;
    private static final String LIGHT = "meridian_basic_techniques:light_step_volume_scroll";
    private static final String UPPER = "meridian_door_breaker:upper_scroll";
    private static final Path SCREENS = Path.of("../scroll-screens");
    private static final Path REPORT = Path.of("../clienttest-results.txt");
    private static final List<Scene> SCENES = List.of(
            new Scene("light-step-2", LIGHT, GUI_TWO, 0),
            new Scene("upper-2", UPPER, GUI_TWO, 0),
            new Scene("upper-end-2", UPPER, GUI_TWO, SCROLL_TO_END),
            new Scene("light-step-3", LIGHT, GUI_THREE, 0),
            new Scene("upper-3", UPPER, GUI_THREE, 0),
            new Scene("upper-end-3", UPPER, GUI_THREE, SCROLL_TO_END));
    private int ticks;
    private int frames;
    private int sceneIndex;
    private int closeChecks;
    private boolean finished;
    private TechniqueScrollScreen shown;
    private boolean capturing;
    private CompletableFuture<Void> reload;

    private record Scene(String name, String item, int scale, int scroll) { }

    @Override
    public void onInitializeClient() {
        // 页面稳定若干帧后才取图，避免把加载遮罩或页面打开前的画面当作证据。
        // 26.2 先收集再绘制，收集回调里只能登记截图，图像在显卡拷贝完成后异步交回。
        if (Boolean.getBoolean("meridian.leapClientTest")) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TechniqueScrollScreen) {
                ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, a) -> capture());
            }
        });
    }

    private void tick(Minecraft client) {
        // 只在隔离测试世界运行，语言资源完成重载后才打开第一卷。
        if (finished) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            finish("等待客户端世界或截图超时");
            return;
        }
        if (ticks < READY_TICKS || client.player == null || client.gui.overlay() != null) {
            return;
        }
        try {
            if (reload == null) {
                client.options.pauseOnLostFocus = false;
                client.options.languageCode = "zh_cn";
                client.getLanguageManager().setSelected("zh_cn");
                reload = client.reloadResourcePacks();
                Files.createDirectories(SCREENS);
                return;
            }
            if (!reload.isDone() || shown != null) {
                return;
            }
            reload.join();
            if (sceneIndex == SCENES.size()) {
                verifyCloseInputs(client);
                finish(null);
                return;
            }
            prepare(client, SCENES.get(sceneIndex));
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void prepare(Minecraft client, Scene scene) {
        // GUI 变更调用真实 resize，长卷滚动使用实际纸面坐标。
        client.options.guiScale().set(scene.scale());
        client.resizeGui();
        shown = open(scene.item());
        var layout = TechniqueScrollLayout.calculate(shown.width, shown.height);
        var content = layout.content();
        double x = (content.left() + content.right()) / (double) HALF;
        double y = (content.top() + content.bottom()) / (double) HALF;
        for (int step = 0; step > scene.scroll(); step--) {
            shown.mouseScrolled(x, y, 0, SCROLL_DOWN);
        }
        frames = 0;
    }

    private void capture() {
        // 保留原始帧缓冲，截图不做 OCR 或合成文字替换。
        // 收集阶段登记的拷贝读到的是上一帧完整画面；页面已稳定多帧，与当前帧相同。
        if (finished || shown == null || capturing || ++frames < SETTLE_FRAMES) {
            return;
        }
        var client = Minecraft.getInstance();
        capturing = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            Path file = SCREENS.resolve(SCENES.get(sceneIndex).name() + ".png");
            try (image) {
                image.writeToFile(file);
            } catch (Exception failure) {
                client.execute(() -> finish(failure.toString()));
                return;
            }
            client.execute(this::nextScene);
        });
    }

    private void nextScene() {
        // 截图写完后回到主线程关页，下一 tick 再打开下一个场景。
        capturing = false;
        if (finished) {
            return;
        }
        shown.onClose();
        shown = null;
        sceneIndex++;
    }

    private void verifyCloseInputs(Minecraft client) {
        // 真正调用 Screen 输入入口，覆盖 ESC、改绑后的键盘/鼠标、纸外和按钮。
        // 26.2 的输入入口改收事件对象，测试照旧逐个构造按键和鼠标事件。
        client.options.keyInventory.setKey(InputConstants.getKey(key(GLFW.GLFW_KEY_I)));
        client.options.keyUse.setKey(InputConstants.getKey(key(GLFW.GLFW_KEY_U)));
        var screen = open(LIGHT);
        screen.keyPressed(key(GLFW.GLFW_KEY_E));
        if (client.gui.screen() != screen) {
            throw new IllegalStateException("背包改键后旧 E 键仍关闭页面");
        }
        screen.keyPressed(key(GLFW.GLFW_KEY_I));
        assertClosed(client);
        open(LIGHT).keyPressed(key(GLFW.GLFW_KEY_ESCAPE));
        assertClosed(client);
        open(LIGHT).keyPressed(key(GLFW.GLFW_KEY_U));
        assertClosed(client);
        double centerX = client.getWindow().getGuiScaledWidth() / HALF;
        double centerY = client.getWindow().getGuiScaledHeight() / HALF;
        open(LIGHT).mouseClicked(mouse(centerX, centerY, GLFW.GLFW_MOUSE_BUTTON_RIGHT), false /* doubleClick */);
        assertClosed(client);
        client.options.keyUse.setKey(InputConstants.Type.MOUSE.getOrCreate(EXTRA_MOUSE_BUTTON));
        open(LIGHT).mouseClicked(mouse(centerX, centerY, EXTRA_MOUSE_BUTTON), false /* doubleClick */);
        assertClosed(client);
        open(LIGHT).mouseClicked(mouse(0, 0, GLFW.GLFW_MOUSE_BUTTON_LEFT), false /* doubleClick */);
        assertClosed(client);
        var buttonScreen = open(LIGHT);
        var button = buttonScreen.children().stream().filter(TechniqueScrollCloseButton.class::isInstance)
                .map(TechniqueScrollCloseButton.class::cast).findFirst().orElseThrow();
        var press = mouse(button.getX() + 1, button.getY() + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        buttonScreen.mouseClicked(press, false /* doubleClick */);
        buttonScreen.mouseReleased(press);
        assertClosed(client);
    }

    private static KeyEvent key(int keyCode) {
        // 只给键码，扫描码和修饰键都为 0，与 1.20.1 版测试传的值相同。
        return new KeyEvent(keyCode, 0, NO_MODIFIERS);
    }

    private static MouseButtonEvent mouse(double x, double y, int button) {
        // 无修饰键的单击。
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, NO_MODIFIERS));
    }

    private static TechniqueScrollScreen open(String itemId) {
        // 复用正式学习成功回调，不在测试中另写页面构造流程。
        var item = TechniqueScrollItems.scrolls().get(Identifier.parse(itemId));
        if (item == null) {
            throw new IllegalStateException("完整装配缺少卷轴：" + itemId);
        }
        TechniqueScrollClient.LEARNED.invoker().onLearned(item.scroll());
        return (TechniqueScrollScreen) Minecraft.getInstance().gui.screen();
    }

    private void assertClosed(Minecraft client) {
        // 页面必须回到游戏，不能只是消费了事件但仍留在阅读页。
        if (client.gui.screen() != null) {
            throw new IllegalStateException("关闭输入未关闭卷轴页面");
        }
        closeChecks++;
    }

    private void finish(String failure) {
        // 把客户端失败传回 Gradle，同时退出独立验收进程。
        finished = true;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\ncaptures=" + sceneIndex
                    + "\ncloseChecks=" + closeChecks + "\nerror=" + failure + "\n");
        } catch (Exception reportFailure) {
            throw new IllegalStateException(reportFailure);
        }
        Minecraft.getInstance().stop();
    }
}
