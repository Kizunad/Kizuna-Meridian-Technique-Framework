package dev.kizuna.meridian.technique.fabric.client;

import java.util.function.IntPredicate;

/** 关闭输入只读取当前绑定，不把默认 E 或使用键写死。 */
final class TechniqueScrollInput {
    private static final int RIGHT_MOUSE_BUTTON = 1;

    private TechniqueScrollInput() {
        // 纯规则不接触世界或学习请求。
    }

    static boolean closesKey(int keyCode, int scanCode, KeyMatcher inventory, KeyMatcher use) {
        // 键码和扫描码一起传给原版匹配器，兼容用户改绑。
        return inventory.matches(keyCode, scanCode) || use.matches(keyCode, scanCode);
    }

    static boolean closesMouse(double x, double y, int button,
                               TechniqueScrollLayout.Bounds paper, IntPredicate use) {
        // 右键、当前使用鼠标键与纸面外点击都可合卷。
        return button == RIGHT_MOUSE_BUTTON || use.test(button) || !paper.contains(x, y);
    }

    @FunctionalInterface
    interface KeyMatcher {
        boolean matches(int keyCode, int scanCode);
    }
}
