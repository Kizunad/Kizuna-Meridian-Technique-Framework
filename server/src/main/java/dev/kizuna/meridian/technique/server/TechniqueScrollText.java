package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BrewUseMethod;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCostMatching;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiDistribution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 阅读文字只投影目录，不执行绑定或推测玩家当前支付能力。 */
public final class TechniqueScrollText {
    private static final long MILLIS_PER_SECOND = 1000;

    private TechniqueScrollText() {
        // 纯展示计算供界面与两端测试复用。
    }

    public static String key(String prefix, String id) {
        // 语言键采用 Minecraft 通用命名规则，保留目录身份的命名空间。
        return prefix + "." + id.replace(':', '.').replace('/', '.');
    }

    public static String name(String key, String fallback, Function<String, String> translate) {
        // 语言条目优先，其次目录显示名；缺失或错误条目都不能泄漏原始 ID。
        String translated = translate.apply(key);
        if (translated != null && !translated.equals(key) && !translated.isBlank() && !translated.contains(":")) {
            return translated;
        }
        return safeName(fallback, "未命名");
    }

    public static String safeName(String candidate, String fallback) {
        // 缺少显示数据时保留可读占位，不把 ns:id 当作可显示名称。
        if (candidate == null || candidate.isBlank() || candidate.contains(":")) {
            return fallback;
        }
        return candidate;
    }

    public static List<String> details(TechniqueDefinition definition) {
        // 起手、出招、收势和冷却均按目录毫秒转换，费用保持 EACH 与 EVEN 的差异。
        var phases = definition.getPhases();
        if (TechniqueSustained.enabled(definition)) {
            // 按住才持续的式没有固定的出招时长：只写起手和收势，中间写明看按多久。
            return List.of("真元：" + qiCost(definition) + "　冷却：" + seconds(definition.getCooldownMillis()) + " 秒",
                    "施放：起手 " + seconds(phases.getWarmupMillis()) + " 秒，按住多久持续多久，松手后收势 "
                            + seconds(phases.getRecoveryMillis()) + " 秒",
                    "持物：" + heldText(definition) + "　移动：" + decimal(TechniqueMovement.multiplier(definition))
                            + " 倍　冲刺：否");
        }
        long total = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
        String phasesText = "起手 " + seconds(phases.getWarmupMillis()) + " / 出招 "
                + seconds(phases.getActiveMillis()) + " / 收势 " + seconds(phases.getRecoveryMillis());
        boolean dash = definition.getEffectsList().stream().anyMatch(effect -> effect.hasImpulse());
        String heldText = heldText(definition);
        String movementText = decimal(TechniqueMovement.multiplier(definition));
        String dashText = dash ? "是" : "否";
        return List.of("真元：" + qiCost(definition) + "　冷却：" + seconds(definition.getCooldownMillis()) + " 秒",
                "施放：" + seconds(total) + " 秒（" + phasesText + " 秒）",
                "持物：" + heldText + "　移动：" + movementText + " 倍　冲刺：" + dashText);
    }

    private static String heldText(TechniqueDefinition definition) {
        // 用药的式写要哪种巫药，射击式写弓或弩，兵器打击和破门写需要；其余不看手里拿什么。
        if (definition.hasBrewItem()) {
            var uses = definition.getBrewItem().getPhysicalUsesList();
            if (uses.size() == 1 && uses.get(0) == BrewUseMethod.DRINK) {
                return "饮用巫药";
            }
            if (uses.size() == 1 && uses.get(0) == BrewUseMethod.SPLASH) {
                return "喷溅巫药";
            }
            return "巫药";
        }
        if (definition.hasProjectileWeapon()) {
            return switch (definition.getProjectileWeapon()) {
                case BOW -> "弓";
                case CROSSBOW -> "弩";
                case HELD_SPLASH_BREW -> "喷溅巫药";
                default -> "需要";
            };
        }
        boolean held = definition.getEffectsList().stream()
                .anyMatch(effect -> effect.hasWeaponStrike() || effect.hasBreakBlocks());
        return held ? "需要" : "不需要";
    }

    public static String summary(TechniqueDefinition definition) {
        // 卷轴提示每式一行：最低境界和真元费用，例如「游炁，金行真元 0.3」；未声明境界时只写费用。
        var parts = new ArrayList<String>();
        String realm = TechniqueNames.realm(definition.getMinimumRealmValue());
        if (!realm.isEmpty()) {
            parts.add(realm);
        }
        var costs = new ArrayList<String>();
        for (var cost : definition.getCost().getQiList()) {
            String kind = "真元";
            if (cost.getMatching() == QiCostMatching.ATTRIBUTE_AT_LEAST_PHASE) {
                kind = TechniqueNames.elementQi(cost.getAttribute());
            }
            String perMeridian = cost.getDistribution() == QiDistribution.EACH ? "/脉" : "";
            costs.add(kind + " " + decimal(cost.getAmount().getValue()) + perMeridian);
        }
        parts.add(costs.isEmpty() ? "不耗真元" : String.join(" + ", costs));
        return String.join("，", parts);
    }

    private static String qiCost(TechniqueDefinition definition) {
        // EACH 的实际总额取决于绑定数量，阅读页明确标记每脉费用而不伪造总量。
        var costs = new ArrayList<String>();
        for (var cost : definition.getCost().getQiList()) {
            String suffix = " 元";
            if (cost.getDistribution() == QiDistribution.EACH) {
                suffix = " 元/脉";
            }
            String attribute = "";
            if (cost.getMatching() == QiCostMatching.ATTRIBUTE_AT_LEAST_PHASE) {
                // 指定属性的费用标出行名；内置五行之外的属性没有可读名称，只写「指定属性」。
                attribute = "（" + TechniqueNames.element(cost.getAttribute()) + "）";
            }
            costs.add(decimal(cost.getAmount().getValue()) + suffix + attribute);
        }
        if (costs.isEmpty()) {
            return "0 元";
        }
        return String.join(" + ", costs);
    }

    private static String seconds(long millis) {
        // 十进制换算不受系统地区的小数分隔符影响。
        return BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(MILLIS_PER_SECOND))
                .stripTrailingZeros().toPlainString();
    }

    public static String decimalText(double value) {
        // 供其他说明复用同一种数字写法。
        return decimal(value);
    }

    private static String decimal(double value) {
        // 整数费用不带无意义的 .0，小数费用保留目录精度。
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
