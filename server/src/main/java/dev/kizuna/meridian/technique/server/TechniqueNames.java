package dev.kizuna.meridian.technique.server;

import java.util.List;
import java.util.Map;

/** 玩家可读的境界、五行与部位列表写法，卷轴、拒绝说明和状态提示共用。 */
public final class TechniqueNames {
    private static final int LISTED_NAMES = 4;
    private static final List<String> REALMS = List.of("", "游炁", "真炁", "化元", "凝元", "天纹", "化虚");
    private static final Map<String, String> ELEMENTS = Map.of(
            "meridian:metal", "金",
            "meridian:wood", "木",
            "meridian:water", "水",
            "meridian:fire", "火",
            "meridian:earth", "土");

    private TechniqueNames() {
        // 只做展示映射，不参与规则判断。
    }

    public static String realm(int realmValue) {
        // 未声明境界返回空串，调用方据此省略境界一项；越界值同样视作未声明。
        if (realmValue <= 0 || realmValue >= REALMS.size()) {
            return "";
        }
        return REALMS.get(realmValue);
    }

    public static String element(String attributeId) {
        // 内置五行之外的属性没有可读名称，只写「指定属性」。
        return ELEMENTS.getOrDefault(attributeId, "指定属性");
    }

    public static String elementQi(String attributeId) {
        // 五行写成「金行真元」，其他属性写成「指定属性真元」。
        String element = element(attributeId);
        if (ELEMENTS.containsKey(attributeId)) {
            return element + "行真元";
        }
        return element + "真元";
    }

    public static String list(List<String> names) {
        // 部位较多时只列前几处，再写共几处，动作栏一行放得下。
        if (names.size() <= LISTED_NAMES) {
            return String.join("、", names);
        }
        return String.join("、", names.subList(0, LISTED_NAMES)) + " 等 " + names.size() + " 处";
    }
}
