package dev.kizuna.meridian.technique.fabric.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 打击反馈配置：缺的键用默认值，写错了报错。 */
public final class TechniqueFeelSettingsTest {
    @Test
    public void missingKeysDefaultToOn() {
        assertEquals(new TechniqueFeelSettings(true, true), TechniqueFeelSettings.parse(""));
    }

    @Test
    public void bloodCanBeTurnedOffAlone() {
        // 只关血：粒子还出，换成不带血的。
        assertEquals(new TechniqueFeelSettings(true, false), TechniqueFeelSettings.parse("blood = false\n"));
        assertEquals(new TechniqueFeelSettings(false, true), TechniqueFeelSettings.parse("hit_particles = false\n"));
    }

    @Test
    public void wrongTypeOrBrokenFileIsRejected() {
        // 写成字符串或者语法坏了都不悄悄当默认值。
        assertThrows(RuntimeException.class, () -> TechniqueFeelSettings.parse("blood = \"no\"\n"));
        assertThrows(IllegalArgumentException.class, () -> TechniqueFeelSettings.parse("blood = \n"));
    }
}
