package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCostMatching;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.INSUFFICIENT_ATTRIBUTE_QI;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.INSUFFICIENT_QI;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.INVALID_STATE;

/** 按目录声明选择真实库存，任何费用匹配方式都不转换属性或密度。 */
final class TechniqueQiPayment {
    private TechniqueQiPayment() {
        // 选择仅构造扣款候选，余额修改仍由 Framework 原子活动负责。
    }

    /** 付不起时说明里写的出处：经脉名字，以及这笔是不是多条经脉里一条的份额。 */
    record Payer(String meridians, boolean oneOfSeveral) { }

    static QiInventory select(QiInventory inventory, QiCost cost, double amount, Payer meridian) {
        // 旧声明保持同种精确支付，新声明才允许汇总符合最低相态的库存。出处只用于付不起时的说明。
        Qi.QiPhase phase = Qi.QiPhase.valueOf(cost.getPhase().name());
        return switch (cost.getMatching()) {
            case EXACT_VARIANT -> exact(inventory, cost, phase, amount, meridian);
            case ANY_ATTRIBUTE_AT_LEAST_PHASE, ATTRIBUTE_AT_LEAST_PHASE -> take(inventory, amount, cost, meridian);
            default -> throw TechniqueErrors.rejected(INVALID_STATE);
        };
    }

    static List<Qi> payable(QiInventory inventory, QiCost cost) {
        // 只列出本项能付的库存；从低相态到高相态、同相态按属性 ID 取用，排序不受库存消息的排列影响。
        Qi.QiPhase phase = Qi.QiPhase.valueOf(cost.getPhase().name());
        return inventory.entries().stream()
                .filter(qi -> matches(qi, cost, phase))
                .sorted(Comparator.comparing(Qi::phase).thenComparing(qi -> qi.attribute().id().value()))
                .toList();
    }

    static TechniqueFailure shortage(QiCost cost) {
        // 指定属性的费用单列原因，玩家能分清是缺本行真元还是缺任意真元。
        if (cost.getMatching() == QiCostMatching.ATTRIBUTE_AT_LEAST_PHASE) {
            return INSUFFICIENT_ATTRIBUTE_QI;
        }
        return INSUFFICIENT_QI;
    }

    static String shortageReason(QiCost cost, double needed, double available, Payer payer,
                                 List<QiInventory> inventories) {
        // 付不起时写明哪里的哪种真元不够、要什么形态、要多少、现在能付多少；可付额向下取两位，不把差一点写成够了。
        // 例：「手太阴肺经的真元不够：这条要气态以上 0.09，可付 0；另有游离态 116，不算」。
        // 费用摊到几条经脉时写「这条要」，数字是这一条的份额，和卷轴上的总费用不是一个数。
        String kind = "真元";
        boolean exactElement = cost.getMatching() == QiCostMatching.EXACT_VARIANT
                && !cost.getAttribute().equals(Qi.QiAttribute.NONE.id().value());
        if (cost.getMatching() == QiCostMatching.ATTRIBUTE_AT_LEAST_PHASE || exactElement) {
            kind = TechniqueNames.elementQi(cost.getAttribute());
        }
        String where = payer.meridians().isEmpty() ? "" : payer.meridians() + "的";
        String share = payer.oneOfSeveral() ? "这条要" : "要";
        return where + kind + "不够：" + share + phaseText(cost) + " " + amount(needed, RoundingMode.UP)
                + "，可付 " + amount(available, RoundingMode.DOWN) + thinnerText(cost, inventories);
    }

    private static String phaseText(QiCost cost) {
        // 精确匹配写形态本身；最低形态是游离态时什么都能付，不写。
        Qi.QiPhase phase = Qi.QiPhase.valueOf(cost.getPhase().name());
        if (cost.getMatching() == QiCostMatching.EXACT_VARIANT) {
            return TechniqueNames.phase(phase);
        }
        return phase == Qi.QiPhase.FREE ? "" : TechniqueNames.phase(phase) + "以上";
    }

    private static String thinnerText(QiCost cost, List<QiInventory> inventories) {
        // 经脉里有真元、只是形态不够时单独说一句，免得玩家补满了还看到「可付 0」。
        if (cost.getMatching() == QiCostMatching.EXACT_VARIANT) {
            return "";
        }
        Qi.QiPhase phase = Qi.QiPhase.valueOf(cost.getPhase().name());
        boolean anyAttribute = cost.getMatching() == QiCostMatching.ANY_ATTRIBUTE_AT_LEAST_PHASE;
        List<Qi> thinner = inventories.stream().flatMap(inventory -> inventory.entries().stream())
                .filter(qi -> qi.phase().compareTo(phase) < 0)
                .filter(qi -> anyAttribute || qi.attribute().id().value().equals(cost.getAttribute()))
                .toList();
        String total = amount(thinner.stream().mapToDouble(Qi::amount).sum(), RoundingMode.DOWN);
        if (total.equals("0")) {
            return "";
        }
        List<Qi.QiPhase> phases = thinner.stream().map(Qi::phase).distinct().toList();
        String name = phases.size() == 1 ? TechniqueNames.phase(phases.get(0)) : "形态不够的";
        return "；另有" + name + " " + total + "，不算";
    }

    private static String amount(double value, RoundingMode rounding) {
        // 两位小数足够区分费用档位，整数不带小数点。
        return BigDecimal.valueOf(value).setScale(2, rounding).stripTrailingZeros().toPlainString();
    }

    private static boolean matches(Qi qi, QiCost cost, Qi.QiPhase phase) {
        // 三种匹配共用同一过滤，按余额分摊与实际扣款看到相同的可付库存。
        boolean attributeMatches = qi.attribute().id().value().equals(cost.getAttribute());
        return switch (cost.getMatching()) {
            case EXACT_VARIANT -> qi.phase() == phase && attributeMatches;
            case ANY_ATTRIBUTE_AT_LEAST_PHASE -> qi.phase().compareTo(phase) >= 0;
            case ATTRIBUTE_AT_LEAST_PHASE -> qi.phase().compareTo(phase) >= 0 && attributeMatches;
            default -> throw TechniqueErrors.rejected(INVALID_STATE);
        };
    }

    private static QiInventory exact(QiInventory inventory, QiCost cost, Qi.QiPhase phase, double amount,
                                     Payer meridian) {
        // 精确匹配不会用其他属性或更高相态抵扣，保留旧目录的既有语义。
        Qi entry = inventory.entries().stream()
                .filter(qi -> qi.phase() == phase && qi.attribute().id().value().equals(cost.getAttribute()))
                .findFirst()
                .orElseThrow(() -> TechniqueErrors.rejected(INSUFFICIENT_QI,
                        shortageReason(cost, amount, 0, meridian, List.of(inventory))));
        if (entry.amount() < amount) {
            throw TechniqueErrors.rejected(INSUFFICIENT_QI,
                    shortageReason(cost, amount, entry.amount(), meridian, List.of(inventory)));
        }
        Qi selected = entry.withAmount(amount);
        QiInventory debit = new QiInventory(List.of(selected));
        QiAccounting.validateTransferable(debit);
        return debit;
    }

    private static QiInventory take(QiInventory inventory, double amount, QiCost cost, Payer meridian) {
        // 剩余费用归零表示已支付；不对重新累加的浮点总额要求逐位相等。付不起时写明要多少、这一处能付多少。
        var selected = new ArrayList<Qi>();
        double remaining = amount;
        for (Qi entry : payable(inventory, cost)) {
            double portion = Math.min(remaining, entry.amount());
            if (portion < QiAccounting.MIN_TRANSFER_QI || entry.amount() - portion == entry.amount()) {
                continue;
            }
            selected.add(entry.withAmount(portion));
            remaining -= portion;
            if (remaining == 0) {
                break;
            }
        }
        if (remaining != 0) {
            throw TechniqueErrors.rejected(shortage(cost),
                    shortageReason(cost, amount, amount - remaining, meridian, List.of(inventory)));
        }
        return new QiInventory(selected);
    }
}
