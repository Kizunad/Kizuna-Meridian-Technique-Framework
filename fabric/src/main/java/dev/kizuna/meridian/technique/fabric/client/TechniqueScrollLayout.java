package dev.kizuna.meridian.technique.fabric.client;

/** 固定卷轴画布与正文纸面区域的 GUI 缩放计算。 */
final class TechniqueScrollLayout {
    static final int CANVAS_WIDTH = 420;
    static final int CANVAS_HEIGHT = 270;
    static final int TEXTURE_WIDTH = 1536;
    static final int TEXTURE_HEIGHT = 1024;
    static final Bounds BACKGROUND = new Bounds(24, 14, 396, 262);
    static final Bounds PAPER = new Bounds(104, 58, 316, 218);
    static final Bounds CONTENT = new Bounds(PAPER.left(), 80, PAPER.right(), PAPER.bottom());
    static final int LINE_HEIGHT = 11;
    private static final int SCREEN_MARGIN = 8;
    private static final int SCREEN_MARGIN_COUNT = 2;

    private TechniqueScrollLayout() {
        // 所有卷轴共享固定画布，不依赖实际窗口缩放档位。
    }

    static Geometry calculate(int screenWidth, int screenHeight) {
        // 缩放后画布始终居中，纸面和正文区域按同一变换计算。
        float availableWidth = Math.max(0, screenWidth - SCREEN_MARGIN_COUNT * SCREEN_MARGIN);
        float availableHeight = Math.max(0, screenHeight - SCREEN_MARGIN_COUNT * SCREEN_MARGIN);
        float scale = Math.min(availableWidth / CANVAS_WIDTH, availableHeight / CANVAS_HEIGHT);
        float originX = (screenWidth - CANVAS_WIDTH * scale) / SCREEN_MARGIN_COUNT;
        float originY = (screenHeight - CANVAS_HEIGHT * scale) / SCREEN_MARGIN_COUNT;
        Bounds background = screenBounds(originX, originY, scale, BACKGROUND);
        Bounds paper = screenBounds(originX, originY, scale, PAPER);
        Bounds content = screenBounds(originX, originY, scale, CONTENT);
        return new Geometry(scale, originX, originY, background, paper, content);
    }

    private static Bounds screenBounds(float originX, float originY, float scale, Bounds bounds) {
        // Scissor 边界向外取整，缩放后不会裁掉正文首尾像素。
        int screenLeft = (int) Math.floor(originX + bounds.left() * scale);
        int screenTop = (int) Math.floor(originY + bounds.top() * scale);
        int screenRight = (int) Math.ceil(originX + bounds.right() * scale);
        int screenBottom = (int) Math.ceil(originY + bounds.bottom() * scale);
        return new Bounds(screenLeft, screenTop, screenRight, screenBottom);
    }

    static int visibleRows(int fontHeight) {
        // 只容纳完整字高；字体高于视口时不能错误地显示一行。
        int textHeight = CONTENT.height();
        if (fontHeight > textHeight) {
            return 0;
        }
        return (textHeight - fontHeight) / LINE_HEIGHT + 1;
    }

    record Geometry(float scale, float originX, float originY,
                    Bounds background, Bounds paper, Bounds content) {}

    record Bounds(int left, int top, int right, int bottom) {
        int width() {
            // 逻辑宽度同时用于纹理缩放、标题约束和正文换行。
            return right - left;
        }

        int height() {
            // 逻辑高度同时用于纹理缩放和完整正文行计数。
            return bottom - top;
        }

        boolean contains(double x, double y) {
            // 鼠标滚动只在正文视口内移动内容。
            return x >= left && x < right && y >= top && y < bottom;
        }
    }
}
