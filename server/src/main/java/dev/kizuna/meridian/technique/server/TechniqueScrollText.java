package dev.kizuna.meridian.technique.server;

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
        long total = phases.getWarmupMillis() + phases.getActiveMillis() + phases.getRecoveryMillis();
        String phasesText = "起手 " + seconds(phases.getWarmupMillis()) + " / 出招 "
                + seconds(phases.getActiveMillis()) + " / 收势 " + seconds(phases.getRecoveryMillis());
        boolean held = definition.getEffectsList().stream()
                .anyMatch(effect -> effect.hasWeaponStrike() || effect.hasBreakBlocks());
        boolean dash = definition.getEffectsList().stream().anyMatch(effect -> effect.hasImpulse());
        String heldText = held ? "需要" : "不需要";
        String movementText = decimal(TechniqueMovement.multiplier(definition));
        String dashText = dash ? "是" : "否";
        return List.of("真元：" + qiCost(definition) + "　冷却：" + seconds(definition.getCooldownMillis()) + " 秒",
                "施放：" + seconds(total) + " 秒（" + phasesText + " 秒）",
                "持物：" + heldText + "　移动：" + movementText + " 倍　冲刺：" + dashText);
    }

    private static String qiCost(TechniqueDefinition definition) {
        // EACH 的实际总额取决于绑定数量，阅读页明确标记每脉费用而不伪造总量。
        var costs = new ArrayList<String>();
        for (var cost : definition.getCost().getQiList()) {
            String suffix = " 元";
            if (cost.getDistribution() == QiDistribution.EACH) {
                suffix = " 元/脉";
            }
            costs.add(decimal(cost.getAmount().getValue()) + suffix);
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

    private static String decimal(double value) {
        // 整数费用不带无意义的 .0，小数费用保留目录精度。
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
