package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiAccounting;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
            case ANY_ATTRIBUTE_AT_LEAST_PHASE -> flexible(inventory, phase, amount);
            default -> throw TechniqueErrors.rejected(INVALID_STATE);
        };
    }

    private static QiInventory exact(QiInventory inventory, QiCost cost, Qi.QiPhase phase, double amount) {
        // 精确匹配不会用其他属性或更高相态抵扣，保留旧目录的既有语义。
        Qi entry = inventory.entries().stream()
                .filter(qi -> qi.phase() == phase && qi.attribute().id().value().equals(cost.getAttribute()))
                .findFirst()
                .orElseThrow(() -> TechniqueErrors.rejected(INSUFFICIENT_QI));
        if (entry.amount() < amount) {
            throw TechniqueErrors.rejected(INSUFFICIENT_QI);
        }
        Qi selected = entry.withAmount(amount);
        QiInventory debit = new QiInventory(List.of(selected));
        QiAccounting.validateTransferable(debit);
        return debit;
    }

    private static QiInventory flexible(QiInventory inventory, Qi.QiPhase minimumPhase, double amount) {
        // 从低相态到高相态、同相态按属性 ID 取用，排序不受库存消息的排列影响。
        List<Qi> candidates = inventory.entries().stream()
                .filter(qi -> qi.phase().compareTo(minimumPhase) >= 0)
                .sorted(Comparator.comparing(Qi::phase).thenComparing(qi -> qi.attribute().id().value()))
                .toList();
        return take(candidates, amount);
    }

    private static QiInventory take(List<Qi> candidates, double amount) {
        // 剩余费用归零表示已支付；不对重新累加的浮点总额要求逐位相等。
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
            throw TechniqueErrors.rejected(INSUFFICIENT_QI);
        }
        return new QiInventory(selected);
    }
}
