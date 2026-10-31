package dev.kizuna.meridian.technique.fabric.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/** 纸签图片按钮；只复用原版焦点和无障碍输入，不绘制原版控件皮肤。 */
final class TechniqueScrollCloseButton extends AbstractButton {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("meridian_technique", "textures/gui/scroll/close_button.png");
    private static final int FRAME_WIDTH = 96;
    private static final int FRAME_HEIGHT = 24;
    private static final int FRAME_COUNT = 3;
    private static final int HOVER_FRAME = 1;
    private static final int PRESSED_FRAME = 2;
    private static final int HALF = 2;
    private static final int LABEL_COLOR = 0xff392c1e;
    private final Runnable close;
    private boolean pressed;

    TechniqueScrollCloseButton(TechniqueScrollLayout.Bounds bounds, Runnable close) {
        super(bounds.left(), bounds.top(), bounds.width(), bounds.height(),
                Component.translatable("screen.meridian_technique.close"));
        // 背景来自独立生成素材，标签使用当前语言和原版字体。
        this.close = close;
    }

    @Override
    protected void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
        // 先绘制图片状态，再居中叠加文字；不调用原版按钮背景。
        int frame = 0;
        if (pressed) {
            frame = PRESSED_FRAME;
        } else if (isHovered() || isFocused()) {
            frame = HOVER_FRAME;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        context.blit(TEXTURE, getX(), getY(), width, height,
                0, frame * FRAME_HEIGHT, FRAME_WIDTH, FRAME_HEIGHT, FRAME_WIDTH, FRAME_HEIGHT * FRAME_COUNT);
        var renderer = Minecraft.getInstance().font;
        int textWidth = renderer.width(getMessage());
        int textX = getX() + (width - textWidth) / HALF;
        int textY = getY() + (height - renderer.lineHeight) / HALF;
        context.drawString(renderer, getMessage(), textX, textY, LABEL_COLOR, false /* shadow */);
        context.flush();
        RenderSystem.disableBlend();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // 松开时再合卷，按住期间可见下压状态。
        pressed = true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // 按下后拖出按钮可取消，键盘激活仍走 onPress。
        if (!pressed || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        pressed = false;
        if (isMouseOver(mouseX, mouseY)) {
            onPress();
        }
        return true;
    }

    @Override
    public void onPress() {
        // 合卷仅关闭客户端阅读页。
        close.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput builder) {
        // 图片替换不丢失按钮名称和键盘辅助提示。
        defaultButtonNarrationText(builder);
    }
}
