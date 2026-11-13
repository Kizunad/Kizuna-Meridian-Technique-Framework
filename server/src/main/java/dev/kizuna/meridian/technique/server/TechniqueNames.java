package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Qi;

import java.util.List;
import java.util.Map;

/** 玩家可读的境界、五行与部位列表写法，卷轴、拒绝说明和状态提示共用。 */
public final class TechniqueNames {
    private static final int LISTED_NAMES = 4;
    private static final List<String> REALMS = List.of("", "游炁", "真炁", "化元", "凝元", "天纹", "化虚");
    private static final Map<Qi.QiPhase, String> PHASES = Map.of(
            Qi.QiPhase.FREE, "游离态",
            Qi.QiPhase.GAS, "气态",
            Qi.QiPhase.LIQUID, "液态",
            Qi.QiPhase.SOLID, "固态",
            Qi.QiPhase.ORDERED, "有序态",
            Qi.QiPhase.COHERENT, "相干态");
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

    public static String phase(Qi.QiPhase phase) {
        // 真元形态的叫法和境界说明一致。
        return PHASES.get(phase);
    }

    public static String meridians(List<String> names) {
        // 扣费说明只占动作栏一行：一条经脉写名字，多条写第一条和总数；没有可读名字时不写。
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return names.get(0) + " 等 " + names.size() + " 条经脉";
    }

    public static String list(List<String> names) {
        // 部位较多时只列前几处，再写共几处，动作栏一行放得下。
        if (names.size() <= LISTED_NAMES) {
            return String.join("、", names);
        }
        return String.join("、", names.subList(0, LISTED_NAMES)) + " 等 " + names.size() + " 处";
    }
}
