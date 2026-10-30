package dev.kizuna.meridian.technique.fabric.client;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TechniqueScrollLayoutTest {
    private static final List<WindowSize> WINDOWS = List.of(
            new WindowSize(320, 240), new WindowSize(854, 480),
            new WindowSize(1920, 1080), new WindowSize(1080, 1920), new WindowSize(3440, 1440));
    private static final int MAX_GUI_SCALE = 6;
    private static final int FONT_HEIGHT = 9;
    private static final double EPSILON = 0.0001;
    private static final TechniqueScrollLayout.Bounds SOURCE_PAPER =
            new TechniqueScrollLayout.Bounds(320, 176, 1216, 848);

    @Test
    public void scaledTextViewportStaysInsidePaperAtCommonGuiSizes() {
        // 包含竖屏、超宽屏和非整除 GUI 缩放；使用原版向上取整的逻辑尺寸。
        for (WindowSize window : WINDOWS) {
            for (int guiScale = 1; guiScale <= MAX_GUI_SCALE; guiScale++) {
                int width = (int) Math.ceil(window.width() / (double) guiScale);
                int height = (int) Math.ceil(window.height() / (double) guiScale);
                assertFits(width, height);
            }
        }
    }

    @Test
    public void titleAndBodyStayInCentralBlankAreaOfFullTexture() {
        // 原图公共留白区域独立于逻辑画布；背景比例和四边映射必须一致。
        var background = TechniqueScrollLayout.BACKGROUND;
        double textureScaleX = TechniqueScrollLayout.TEXTURE_WIDTH / (double) background.width();
        double textureScaleY = TechniqueScrollLayout.TEXTURE_HEIGHT / (double) background.height();
        assertEquals(textureScaleX, textureScaleY, EPSILON);
        var paper = TechniqueScrollLayout.PAPER;
        assertTrue((paper.left() - background.left()) * textureScaleX >= SOURCE_PAPER.left());
        assertTrue((paper.right() - background.left()) * textureScaleX <= SOURCE_PAPER.right());
        assertTrue((paper.top() - background.top()) * textureScaleY >= SOURCE_PAPER.top());
        assertTrue((paper.bottom() - background.top()) * textureScaleY <= SOURCE_PAPER.bottom());
        assertInside(paper, TechniqueScrollLayout.CONTENT);
        assertTrue(paper.top() + FONT_HEIGHT < TechniqueScrollLayout.CONTENT.top());
    }

    @Test
    public void lastVisibleLineFitsAndNextLineWouldCrossPaper() {
        // 长正文只能显示完整行，底部不能出现被裁掉半行的文字。
        int visibleRows = TechniqueScrollLayout.visibleRows(FONT_HEIGHT);
        var content = TechniqueScrollLayout.CONTENT;
        int lastLineBottom = content.top() + (visibleRows - 1) * TechniqueScrollLayout.LINE_HEIGHT + FONT_HEIGHT;
        assertTrue(visibleRows > 0);
        assertTrue(lastLineBottom <= content.bottom());
        assertTrue(lastLineBottom + TechniqueScrollLayout.LINE_HEIGHT > content.bottom());
        assertEquals(0, TechniqueScrollLayout.visibleRows(content.height() + 1));
    }

    private static void assertInside(TechniqueScrollLayout.Bounds outer,
                                     TechniqueScrollLayout.Bounds inner) {
        // 正文边界与纸张边界逐边比较，避免依赖某一档 GUI 缩放。
        assertTrue(inner.left() >= outer.left());
        assertTrue(inner.top() >= outer.top());
        assertTrue(inner.right() <= outer.right());
        assertTrue(inner.bottom() <= outer.bottom());
    }

    private static void assertFits(int width, int height) {
        // 同时检查缩放正值、纸面屏幕边界和中心正文裁切边界。
        TechniqueScrollLayout.Geometry layout = TechniqueScrollLayout.calculate(width, height);
        var background = layout.background();
        var paper = layout.paper();
        var content = layout.content();
        assertTrue(layout.scale() > 0);
        assertTrue(background.left() >= 0);
        assertTrue(background.top() >= 0);
        assertTrue(background.right() <= width);
        assertTrue(background.bottom() <= height);
        assertInside(background, paper);
        assertInside(paper, content);
        assertTrue(content.contains(content.left(), content.top()));
        assertFalse(content.contains(content.right(), content.bottom()));
    }

    private record WindowSize(int width, int height) {}
}
