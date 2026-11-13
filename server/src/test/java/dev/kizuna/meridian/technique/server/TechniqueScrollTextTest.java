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

    @Test
    public void heldRowNamesTheBowCrossbowOrBrewTheFormNeeds() {
        // 射击式和用药的式不打兵器，原来都写成「不需要」；现在写明要拿什么。
        var catalog = TechniqueCatalogManifest.parseText("scroll_held", """
                schema_version: 1 namespace: "sample"
                definitions { id: "sample:bow" version: 1 projectile_weapon: BOW
                  phases { warmup_millis: 100 active_millis: 0 recovery_millis: 100 } }
                definitions { id: "sample:crossbow" version: 1 projectile_weapon: CROSSBOW
                  phases { warmup_millis: 100 active_millis: 0 recovery_millis: 100 } }
                definitions { id: "sample:drink" version: 1 brew_item { physical_uses: DRINK }
                  phases { warmup_millis: 100 active_millis: 0 recovery_millis: 100 } }
                definitions { id: "sample:splash" version: 1 projectile_weapon: HELD_SPLASH_BREW
                  brew_item { physical_uses: SPLASH }
                  phases { warmup_millis: 100 active_millis: 0 recovery_millis: 100 } }
                definitions { id: "sample:either" version: 1 brew_item { physical_uses: DRINK physical_uses: SPLASH }
                  phases { warmup_millis: 100 active_millis: 0 recovery_millis: 100 } }
                definitions { id: "sample:bare" version: 1
                  phases { warmup_millis: 100 active_millis: 0 recovery_millis: 100 } }
                """);
        var expected = List.of("弓", "弩", "饮用巫药", "喷溅巫药", "巫药", "不需要");
        for (int index = 0; index < expected.size(); index++) {
            String row = TechniqueScrollText.details(catalog.getDefinitions(index)).get(2);
            assertTrue(row, row.startsWith("持物：" + expected.get(index) + "　"));
        }
    }

    @Test
    public void elementCostNamesItsElementAndBalanceKeepsTotal() {
        // 本行真元费用标出行名，按余额分摊写整式总额而非每脉费用。
        var catalog = TechniqueCatalogManifest.parseText("scroll_element", """
                schema_version: 1 namespace: "sample"
                definitions {
                  id: "sample:metal" version: 1
                  cost { qi { amount { value: 0.3 unit: QI } distribution: BY_BALANCE
                    matching: ATTRIBUTE_AT_LEAST_PHASE attribute: "meridian:metal" phase: FREE } }
                  phases { warmup_millis: 2600 recovery_millis: 2400 }
                  cooldown_millis: 22000
                }
                """);
        assertEquals("真元：0.3 元（金）　冷却：22 秒", TechniqueScrollText.details(catalog.getDefinitions(0)).get(0));
    }

    @Test
    public void summaryLineGivesRealmAndQiCostFromTheCatalog() {
        // 卷轴每式一行：最低境界加费用；本行费用写成「金行真元」，不耗真元也写明，EACH 标出每脉。
        var catalog = TechniqueCatalogManifest.parseText("scroll_summary", """
                schema_version: 1 namespace: "sample"
                definitions {
                  id: "sample:metal" version: 1 minimum_realm: WANDERING_QI
                  cost { qi { amount { value: 0.3 unit: QI } distribution: BY_BALANCE
                    matching: ATTRIBUTE_AT_LEAST_PHASE attribute: "meridian:metal" phase: FREE } }
                }
                definitions { id: "sample:refine" version: 1 minimum_realm: TRUE_QI }
                definitions {
                  id: "sample:step" version: 1
                  cost { qi { amount { value: 2.5 unit: QI } distribution: EACH }
                    qi { amount { value: 1 unit: QI } distribution: BY_BALANCE
                      matching: ATTRIBUTE_AT_LEAST_PHASE attribute: "sample:void" phase: FREE } }
                }
                """);
        assertEquals("游炁，金行真元 0.3", TechniqueScrollText.summary(catalog.getDefinitions(0)));
        assertEquals("真炁，不耗真元", TechniqueScrollText.summary(catalog.getDefinitions(1)));
        assertEquals("真元 2.5/脉 + 指定属性真元 1", TechniqueScrollText.summary(catalog.getDefinitions(2)));
    }
}
