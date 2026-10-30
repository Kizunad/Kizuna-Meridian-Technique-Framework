package dev.kizuna.meridian.technique.fabric.client;

import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import dev.kizuna.meridian.technique.server.TechniqueScrollText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
        super(Text.translatable("screen.meridian_technique.scroll"));
        // 界面只持有已确认学习卷的不可变展示数据。
        this.scroll = scroll;
    }

    @Override
    public boolean shouldPause() {
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
        addDrawableChild(new TechniqueScrollCloseButton(buttonBounds, this::close));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 固定画布随窗口等比缩放，正文仅绘制在纸面中央视口内。
        context.fill(0, 0, width, height, SCREEN_COLOR);
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(layout.originX(), layout.originY(), 0);
        matrices.scale(layout.scale(), layout.scale(), 1);
        drawBackground(context);
        drawTitle(context);
        drawContent(context);
        drawFooter(context);
        matrices.pop();
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 交给 Screen，背包与使用键按当前设置匹配。
        if (TechniqueScrollInput.closesKey(keyCode, scanCode,
                client.options.inventoryKey::matchesKey, client.options.useKey::matchesKey)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 按钮、右键和纸面外点击只关闭页面，不向服务端重复学习。
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (TechniqueScrollInput.closesMouse(mouseX, mouseY, button,
                layout.background(), client.options.useKey::matchesMouse)) {
            close();
            return true;
        }
        return false;
    }

    private void drawBackground(DrawContext context) {
        // 按整张纹理等比缩放，避免把绘制尺寸误当成左上角的采样区域。
        TechniqueScrollLayout.Bounds bounds = TechniqueScrollLayout.BACKGROUND;
        int textureWidth = TechniqueScrollLayout.TEXTURE_WIDTH;
        int textureHeight = TechniqueScrollLayout.TEXTURE_HEIGHT;
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(bounds.left(), bounds.top(), 0);
        matrices.scale(bounds.width() / (float) textureWidth, bounds.height() / (float) textureHeight, 1);
        context.drawTexture(background, 0, 0, 0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        matrices.pop();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        // 滚轮只改变纸面正文的起始行，不影响目录数据。
        if (!layout.content().contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, verticalAmount);
        }
        int direction = (int) Math.signum(verticalAmount);
        scrollOffset = Math.max(0, Math.min(maximumScrollOffset(),
                scrollOffset - direction * SCROLL_STEP_ROWS));
        return true;
    }

    private Identifier background() {
        // 缺图时使用库内通用纸面，并按原始卷资源 ID 去重告警。
        Identifier requested = TechniqueScrollClient.background(scroll);
        return TechniqueScrollBackgrounds.resolve(requested,
                resource -> client.getResourceManager().getResource(resource).isPresent(),
                resource -> LOG.warn("卷轴阅读背景缺失：{}，改用通用纸面", resource));
    }

    private List<ContentRow> contentRows() {
        // 名称、说明和数值共用目录投影，式之间留空行，正文不加粗。
        int textWidth = TechniqueScrollLayout.CONTENT.width();
        var rows = new ArrayList<ContentRow>();
        appendWrapped(rows, Text.translatable("screen.meridian_technique.learned"), textWidth, BODY_COLOR);
        List<String> prerequisites = scroll.prerequisites();
        if (!prerequisites.isEmpty()) {
            Text label = Text.translatable("screen.meridian_technique.prerequisites")
                    .append(String.join("、", prerequisites));
            appendWrapped(rows, label, textWidth, BODY_COLOR);
        }
        for (var technique : scroll.techniques()) {
            appendWrapped(rows, Text.empty(), textWidth, BODY_COLOR);
            String key = TechniqueScrollText.key("technique", technique.reference().getId());
            String name = TechniqueScrollText.name(key, technique.fallbackName(), Language.getInstance()::get);
            appendWrapped(rows, Text.literal(name), textWidth, BODY_COLOR);
            appendWrapped(rows, Text.literal(technique.description()), textWidth, DESCRIPTION_COLOR);
            for (String detail : TechniqueScrollText.details(technique.definition())) {
                appendWrapped(rows, Text.literal(detail), textWidth, DESCRIPTION_COLOR);
            }
        }
        appendWrapped(rows, Text.empty(), textWidth, BODY_COLOR);
        appendWrapped(rows, Text.translatable("screen.meridian_technique.combat_help"), textWidth, DESCRIPTION_COLOR);
        return List.copyOf(rows);
    }

    private void appendWrapped(List<ContentRow> rows, Text text, int textWidth, int color) {
        // 每行按逻辑纸面宽度换行，屏幕缩放不会改变换行或越过纸边。
        if (text.getString().isEmpty()) {
            rows.add(new ContentRow(OrderedText.EMPTY, color));
            return;
        }
        for (OrderedText line : textRenderer.wrapLines(text, textWidth)) {
            rows.add(new ContentRow(line, color));
        }
    }

    private void drawTitle(DrawContext context) {
        // 书名与卷名在标题区域内横向缩放，长标题仍完整保留。
        String key = TechniqueScrollText.key("item", scroll.itemId());
        String title = TechniqueScrollText.name(key, scroll.displayName(), Language.getInstance()::get);
        int titleWidth = Math.max(1, textRenderer.getWidth(title));
        TechniqueScrollLayout.Bounds paper = TechniqueScrollLayout.PAPER;
        float titleScale = Math.min(TITLE_SCALE, paper.width() / (float) titleWidth);
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(TechniqueScrollLayout.CANVAS_WIDTH / CENTER_DIVISOR, paper.top(), 0);
        matrices.scale(titleScale, TITLE_SCALE, 1);
        context.drawText(textRenderer, title, -titleWidth / CENTER_DIVISOR, 0, TITLE_COLOR, false /* shadow */);
        matrices.pop();
    }

    private void drawFooter(DrawContext context) {
        // 小字提示跟随背包与使用键改绑，常驻于纸面正文以外。
        Text hint = Text.translatable("screen.meridian_technique.close_hint",
                client.options.inventoryKey.getBoundKeyLocalizedText(),
                client.options.useKey.getBoundKeyLocalizedText());
        int textWidth = Math.max(1, textRenderer.getWidth(hint));
        float fit = Math.min(HINT_SCALE, TechniqueScrollLayout.PAPER.width() / (float) textWidth);
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(TechniqueScrollLayout.CANVAS_WIDTH / CENTER_DIVISOR, HINT_Y, 0);
        matrices.scale(fit, fit, 1);
        context.drawText(textRenderer, hint, -textWidth / CENTER_DIVISOR, 0, DESCRIPTION_COLOR, false /* shadow */);
        matrices.pop();
    }

    private void drawContent(DrawContext context) {
        // Scissor 独立使用屏幕坐标，确保所有换行都留在纸面中区。
        TechniqueScrollLayout.Bounds content = layout.content();
        context.enableScissor(content.left(), content.top(), content.right(), content.bottom());
        int visibleRows = TechniqueScrollLayout.visibleRows(textRenderer.fontHeight);
        for (int row = 0; row < visibleRows; row++) {
            int contentIndex = scrollOffset + row;
            if (contentIndex >= contentRows.size()) {
                break;
            }
            int y = TechniqueScrollLayout.CONTENT.top() + row * TechniqueScrollLayout.LINE_HEIGHT;
            ContentRow rowContent = contentRows.get(contentIndex);
            context.drawText(textRenderer, rowContent.text(),
                    TechniqueScrollLayout.CONTENT.left(), y, rowContent.color(), false /* shadow */);
        }
        context.disableScissor();
    }

    private int maximumScrollOffset() {
        // 短卷轴保持顶部对齐，长正文最多滚到最后一行。
        int visibleRows = TechniqueScrollLayout.visibleRows(textRenderer.fontHeight);
        return Math.max(0, contentRows.size() - visibleRows);
    }

    private record ContentRow(OrderedText text, int color) {}
}
