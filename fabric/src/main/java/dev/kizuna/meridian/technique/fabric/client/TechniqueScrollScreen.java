package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import dev.kizuna.meridian.technique.server.TechniqueScrollText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/** 学习成功后展示卷轴目录文字的只读界面。 */
final class TechniqueScrollScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll_screen");
    private static final int SCREEN_COLOR = 0xFF17130F;
    private static final int TITLE_COLOR = 0xFF392C1E;
    private static final int BODY_COLOR = 0xFF382E22;
    private static final int DESCRIPTION_COLOR = 0xFF62533D;
    private static final int SCROLL_STEP_ROWS = 3;
    private static final int CENTER_DIVISOR = 2;
    private static final float TITLE_SCALE = 1.25f;
    private static final float HINT_SCALE = 0.7f;
    private static final int HINT_Y = 225;
    private static final int BUTTON_Y = 242;
    private static final int BUTTON_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 24;
    private final TechniqueScrollCatalog.Scroll scroll;
    private TechniqueScrollLayout.Geometry layout;
    private Identifier background;
    private List<ContentRow> contentRows = List.of();
    private int scrollOffset;

    TechniqueScrollScreen(TechniqueScrollCatalog.Scroll scroll) {
        super(Component.translatable("screen.meridian_technique.scroll"));
        // 界面只持有已确认学习卷的不可变展示数据。
        this.scroll = scroll;
    }

    @Override
    public boolean isPauseScreen() {
        // 阅读期间世界继续运行。
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // ESC 直接关闭只读界面，不发送任何服务端请求。
        return true;
    }

    @Override
    protected void init() {
        // resize 时重算纸面视口和文字换行，保留有效滚动位置。
        layout = TechniqueScrollLayout.calculate(width, height);
        background = background();
        contentRows = contentRows();
        scrollOffset = Math.min(scrollOffset, maximumScrollOffset());
        int buttonWidth = Math.round(BUTTON_WIDTH * layout.scale());
        int buttonY = Math.round(layout.originY() + BUTTON_Y * layout.scale());
        int buttonHeight = Math.round(BUTTON_HEIGHT * layout.scale());
        int buttonX = (width - buttonWidth) / CENTER_DIVISOR;
        var buttonBounds = new TechniqueScrollLayout.Bounds(buttonX, buttonY,
                buttonX + buttonWidth, buttonY + buttonHeight);
        addRenderableWidget(new TechniqueScrollCloseButton(buttonBounds, this::onClose));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // 固定画布随窗口等比缩放，正文仅绘制在纸面中央视口内。
        // 26.2 在上一层先画原版背景，这里的不透明底色把它整个盖住，观感与 1.20.1 相同。
        graphics.fill(0, 0, width, height, SCREEN_COLOR);
        var matrices = graphics.pose();
        matrices.pushMatrix();
        matrices.translate(layout.originX(), layout.originY());
        matrices.scale(layout.scale(), layout.scale());
        drawBackground(graphics);
        drawTitle(graphics);
        drawContent(graphics);
        drawFooter(graphics);
        matrices.popMatrix();
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // ESC 交给 Screen，背包与使用键按当前设置匹配。
        var options = minecraft.options;
        if (TechniqueScrollInput.closesKey(event.key(), event.scancode(),
                keyMatcher(options.keyInventory, event), keyMatcher(options.keyUse, event))) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 按钮、右键和纸面外点击只关闭页面，不向服务端重复学习。
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (TechniqueScrollInput.closesMouse(event.x(), event.y(), event.button(),
                layout.background(), mouseMatcher(minecraft.options.keyUse, event))) {
            onClose();
            return true;
        }
        return false;
    }

    private static TechniqueScrollInput.KeyMatcher keyMatcher(KeyMapping mapping, KeyEvent event) {
        // 26.2 的按键匹配只收事件对象；规则层仍按键码和扫描码判断，这里沿用本次修饰键重建事件。
        return (keyCode, scanCode) -> mapping.matches(new KeyEvent(keyCode, scanCode, event.modifiers()));
    }

    private static IntPredicate mouseMatcher(KeyMapping mapping, MouseButtonEvent event) {
        // 同上：按规则层给出的鼠标键重建事件，位置和修饰键沿用本次点击。
        return button -> mapping.matchesMouse(new MouseButtonEvent(event.x(), event.y(),
                new MouseButtonInfo(button, event.modifiers())));
    }

    private void drawBackground(GuiGraphicsExtractor graphics) {
        // 按整张纹理等比缩放，避免把绘制尺寸误当成左上角的采样区域。
        TechniqueScrollLayout.Bounds bounds = TechniqueScrollLayout.BACKGROUND;
        int textureWidth = TechniqueScrollLayout.TEXTURE_WIDTH;
        int textureHeight = TechniqueScrollLayout.TEXTURE_HEIGHT;
        var matrices = graphics.pose();
        matrices.pushMatrix();
        matrices.translate(bounds.left(), bounds.top());
        matrices.scale(bounds.width() / (float) textureWidth, bounds.height() / (float) textureHeight);
        graphics.blit(RenderPipelines.GUI_TEXTURED, background, 0, 0, 0, 0,
                textureWidth, textureHeight, textureWidth, textureHeight);
        matrices.popMatrix();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 滚轮只改变纸面正文的起始行，不影响目录数据；26.2 多传横向滚动，正文只看纵向。
        if (!layout.content().contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int direction = (int) Math.signum(scrollY);
        scrollOffset = Math.max(0, Math.min(maximumScrollOffset(),
                scrollOffset - direction * SCROLL_STEP_ROWS));
        return true;
    }

    private Identifier background() {
        // 缺图时使用库内通用纸面，并按原始卷资源 ID 去重告警。
        Identifier requested = TechniqueScrollClient.background(scroll);
        return TechniqueScrollBackgrounds.resolve(requested,
                resource -> minecraft.getResourceManager().getResource(resource).isPresent(),
                resource -> LOG.warn("卷轴阅读背景缺失：{}，改用通用纸面", resource));
    }

    private List<ContentRow> contentRows() {
        // 名称、说明和数值共用目录投影，式之间留空行，正文不加粗。
        int textWidth = TechniqueScrollLayout.CONTENT.width();
        var rows = new ArrayList<ContentRow>();
        appendWrapped(rows, Component.translatable("screen.meridian_technique.learned"), textWidth, BODY_COLOR);
        List<String> prerequisites = scroll.prerequisites();
        if (!prerequisites.isEmpty()) {
            Component label = Component.translatable("screen.meridian_technique.prerequisites")
                    .append(String.join("、", prerequisites));
            appendWrapped(rows, label, textWidth, BODY_COLOR);
        }
        for (var technique : scroll.techniques()) {
            appendWrapped(rows, Component.empty(), textWidth, BODY_COLOR);
            String key = TechniqueScrollText.key("technique", technique.reference().getId());
            String name = TechniqueScrollText.name(key, technique.fallbackName(), Language.getInstance()::getOrDefault);
            appendWrapped(rows, Component.literal(name), textWidth, BODY_COLOR);
            appendWrapped(rows, Component.literal(technique.description()), textWidth, DESCRIPTION_COLOR);
            for (String detail : TechniqueScrollText.details(technique.definition())) {
                appendWrapped(rows, Component.literal(detail), textWidth, DESCRIPTION_COLOR);
            }
        }
        appendWrapped(rows, Component.empty(), textWidth, BODY_COLOR);
        appendWrapped(rows, Component.translatable("screen.meridian_technique.combat_help"), textWidth, DESCRIPTION_COLOR);
        return List.copyOf(rows);
    }

    private void appendWrapped(List<ContentRow> rows, Component text, int textWidth, int color) {
        // 每行按逻辑纸面宽度换行，屏幕缩放不会改变换行或越过纸边。
        if (text.getString().isEmpty()) {
            rows.add(new ContentRow(FormattedCharSequence.EMPTY, color));
            return;
        }
        for (FormattedCharSequence line : font.split(text, textWidth)) {
            rows.add(new ContentRow(line, color));
        }
    }

    private void drawTitle(GuiGraphicsExtractor graphics) {
        // 书名与卷名在标题区域内横向缩放，长标题仍完整保留。
        String key = TechniqueScrollText.key("item", scroll.itemId());
        String title = TechniqueScrollText.name(key, scroll.displayName(), Language.getInstance()::getOrDefault);
        int titleWidth = Math.max(1, font.width(title));
        TechniqueScrollLayout.Bounds paper = TechniqueScrollLayout.PAPER;
        float titleScale = Math.min(TITLE_SCALE, paper.width() / (float) titleWidth);
        var matrices = graphics.pose();
        matrices.pushMatrix();
        matrices.translate(TechniqueScrollLayout.CANVAS_WIDTH / CENTER_DIVISOR, paper.top());
        matrices.scale(titleScale, TITLE_SCALE);
        graphics.text(font, title, -titleWidth / CENTER_DIVISOR, 0, TITLE_COLOR, false /* shadow */);
        matrices.popMatrix();
    }

    private void drawFooter(GuiGraphicsExtractor graphics) {
        // 小字提示跟随背包与使用键改绑，常驻于纸面正文以外。
        Component hint = Component.translatable("screen.meridian_technique.close_hint",
                minecraft.options.keyInventory.getTranslatedKeyMessage(),
                minecraft.options.keyUse.getTranslatedKeyMessage());
        int textWidth = Math.max(1, font.width(hint));
        float fit = Math.min(HINT_SCALE, TechniqueScrollLayout.PAPER.width() / (float) textWidth);
        var matrices = graphics.pose();
        matrices.pushMatrix();
        matrices.translate(TechniqueScrollLayout.CANVAS_WIDTH / CENTER_DIVISOR, HINT_Y);
        matrices.scale(fit, fit);
        graphics.text(font, hint, -textWidth / CENTER_DIVISOR, 0, DESCRIPTION_COLOR, false /* shadow */);
        matrices.popMatrix();
    }

    private void drawContent(GuiGraphicsExtractor graphics) {
        // Scissor 独立使用屏幕坐标，确保所有换行都留在纸面中区。
        // 26.2 的 Scissor 会套用当前变换，这里临时回到单位变换，按已向外取整的屏幕边界登记。
        TechniqueScrollLayout.Bounds content = layout.content();
        var matrices = graphics.pose();
        matrices.pushMatrix();
        matrices.identity();
        graphics.enableScissor(content.left(), content.top(), content.right(), content.bottom());
        matrices.popMatrix();
        int visibleRows = TechniqueScrollLayout.visibleRows(font.lineHeight);
        for (int row = 0; row < visibleRows; row++) {
            int contentIndex = scrollOffset + row;
            if (contentIndex >= contentRows.size()) {
                break;
            }
            int y = TechniqueScrollLayout.CONTENT.top() + row * TechniqueScrollLayout.LINE_HEIGHT;
            ContentRow rowContent = contentRows.get(contentIndex);
            graphics.text(font, rowContent.text(),
                    TechniqueScrollLayout.CONTENT.left(), y, rowContent.color(), false /* shadow */);
        }
        graphics.disableScissor();
    }

    private int maximumScrollOffset() {
        // 短卷轴保持顶部对齐，长正文最多滚到最后一行。
        int visibleRows = TechniqueScrollLayout.visibleRows(font.lineHeight);
        return Math.max(0, contentRows.size() - visibleRows);
    }

    private record ContentRow(FormattedCharSequence text, int color) {}
}
