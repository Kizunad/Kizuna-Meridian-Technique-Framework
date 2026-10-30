package dev.kizuna.meridian.technique.server;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TechniqueScrollTextTest {
    @Test
    public void translatedNameWinsAndMissingOrInvalidNamesNeverExposeIds() {
        // 翻译、目录回退和缺失名称都走界面共用的解析规则。
        String key = "technique.sample.step";
        var language = Map.of(key, "轻步");
        assertEquals("轻步", TechniqueScrollText.name(key, "跃步", language::get));
        assertEquals("跃步", TechniqueScrollText.name(key, "跃步", value -> value));
        assertEquals("跃步", TechniqueScrollText.name(key, "跃步", value -> "sample:step"));
        assertEquals("未命名", TechniqueScrollText.name(key, "sample:step", value -> value));
        assertEquals("未命名", TechniqueScrollText.name(key, "", value -> null));
    }

    @Test
    public void numericRowsReflectCatalogCostsTimingAndMovement() {
        // 非整数时长与 EACH 费用不应被四舍五入为整秒或误写成整式总额。
        var catalog = TechniqueCatalogManifest.parseText("scroll_fixture", """
                schema_version: 1 namespace: "sample"
                definitions {
                  id: "sample:move" version: 1
                  cost { qi { amount { value: 2.5 unit: QI } distribution: EACH } }
                  phases { warmup_millis: 280 active_millis: 20 recovery_millis: 200 }
                  cooldown_millis: 3000 movement_speed_multiplier: 0.4
                  effects { impulse {} }
                  effects { weapon_strike {} }
                }
                """);
        List<String> rows = TechniqueScrollText.details(catalog.getDefinitions(0));
        assertEquals("真元：2.5 元/脉　冷却：3 秒", rows.get(0));
        assertEquals("施放：0.5 秒（起手 0.28 / 出招 0.02 / 收势 0.2 秒）", rows.get(1));
        assertEquals("持物：需要　移动：0.4 倍　冲刺：是", rows.get(2));
        var empty = catalog.getDefinitions(0).toBuilder()
                .clearCost()
                .clearEffects()
                .clearMovementSpeedMultiplier()
                .build();
        assertTrue(TechniqueScrollText.details(empty).get(0).startsWith("真元：0 元"));
        assertFalse(TechniqueScrollText.details(empty).get(2).contains("是"));
    }
}
