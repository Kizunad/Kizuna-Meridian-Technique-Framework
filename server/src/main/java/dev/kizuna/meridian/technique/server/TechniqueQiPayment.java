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

    static QiInventory select(QiInventory inventory, QiCost cost, double amount) {
        // 旧声明保持同种精确支付，新声明才允许汇总符合最低相态的库存。
        Qi.QiPhase phase = Qi.QiPhase.valueOf(cost.getPhase().name());
        return switch (cost.getMatching()) {
            case EXACT_VARIANT -> exact(inventory, cost, phase, amount);
            case ANY_ATTRIBUTE_AT_LEAST_PHASE, ATTRIBUTE_AT_LEAST_PHASE -> take(payable(inventory, cost), amount, cost);
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

    static String shortageReason(QiCost cost, double needed, double available) {
        // 合计付不起时写明缺哪种真元、要多少、现在能付多少；可付额向下取两位，不把差一点写成够了。
        String kind = "真元";
        if (cost.getMatching() == QiCostMatching.ATTRIBUTE_AT_LEAST_PHASE) {
            kind = TechniqueNames.elementQi(cost.getAttribute());
        }
        return kind + "不够：要 " + amount(needed, RoundingMode.UP) + "，可付 " + amount(available, RoundingMode.DOWN);
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

    private static QiInventory exact(QiInventory inventory, QiCost cost, Qi.QiPhase phase, double amount) {
        // 精确匹配不会用其他属性或更高相态抵扣，保留旧目录的既有语义。
        Qi entry = inventory.entries().stream()
                .filter(qi -> qi.phase() == phase && qi.attribute().id().value().equals(cost.getAttribute()))
                .findFirst()
                .orElseThrow(() -> TechniqueErrors.rejected(INSUFFICIENT_QI, shortageReason(cost, amount, 0)));
        if (entry.amount() < amount) {
            throw TechniqueErrors.rejected(INSUFFICIENT_QI, shortageReason(cost, amount, entry.amount()));
        }
        Qi selected = entry.withAmount(amount);
        QiInventory debit = new QiInventory(List.of(selected));
        QiAccounting.validateTransferable(debit);
        return debit;
    }

    private static QiInventory take(List<Qi> candidates, double amount, QiCost cost) {
        // 剩余费用归零表示已支付；不对重新累加的浮点总额要求逐位相等。付不起时写明要多少、这一处能付多少。
        var selected = new ArrayList<Qi>();
        double remaining = amount;
        for (Qi entry : candidates) {
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
            throw TechniqueErrors.rejected(shortage(cost), shortageReason(cost, amount, amount - remaining));
        }
        return new QiInventory(selected);
    }
}
