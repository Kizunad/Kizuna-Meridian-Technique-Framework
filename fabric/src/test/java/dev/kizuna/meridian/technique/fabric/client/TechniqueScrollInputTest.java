package dev.kizuna.meridian.technique.fabric.client;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TechniqueScrollInputTest {
    private static final int DEFAULT_INVENTORY = 69;
    private static final int REMAPPED_INVENTORY = 73;
    private static final int REMAPPED_USE = 85;
    private static final int SCAN_CODE = 12;
    private static final int LEFT_BUTTON = 0;
    private static final int RIGHT_BUTTON = 1;
    private static final int REMAPPED_MOUSE_USE = 4;
    private static final int INSIDE = 50;
    private static final int OUTSIDE = 101;
    private static final TechniqueScrollLayout.Bounds PAPER = new TechniqueScrollLayout.Bounds(0, 0, 100, 100);

    @Test
    public void keyboardFollowsInventoryAndUseBindingsIncludingScanCodes() {
        // E 改为 I 后旧键不合卷，使用键和扫描码绑定仍有效。
        TechniqueScrollInput.KeyMatcher inventory = (key, scan) -> key == REMAPPED_INVENTORY;
        TechniqueScrollInput.KeyMatcher use = (key, scan) -> key == REMAPPED_USE || scan == SCAN_CODE;
        assertFalse(TechniqueScrollInput.closesKey(DEFAULT_INVENTORY, 0, inventory, use));
        assertTrue(TechniqueScrollInput.closesKey(REMAPPED_INVENTORY, 0, inventory, use));
        assertTrue(TechniqueScrollInput.closesKey(REMAPPED_USE, 0, inventory, use));
        assertTrue(TechniqueScrollInput.closesKey(0, SCAN_CODE, inventory, use));
    }

    @Test
    public void mouseClosesOutsidePaperOnRightClickAndOnRemappedUse() {
        // 纸内左键保留按钮交互，纸外、右键或改绑使用鼠标键立即合卷。
        assertFalse(TechniqueScrollInput.closesMouse(INSIDE, INSIDE, LEFT_BUTTON, PAPER,
                button -> button == REMAPPED_MOUSE_USE));
        assertTrue(TechniqueScrollInput.closesMouse(OUTSIDE, INSIDE, LEFT_BUTTON, PAPER, button -> false));
        assertTrue(TechniqueScrollInput.closesMouse(INSIDE, INSIDE, RIGHT_BUTTON, PAPER, button -> false));
        assertTrue(TechniqueScrollInput.closesMouse(INSIDE, INSIDE, REMAPPED_MOUSE_USE, PAPER,
                button -> button == REMAPPED_MOUSE_USE));
    }
}
