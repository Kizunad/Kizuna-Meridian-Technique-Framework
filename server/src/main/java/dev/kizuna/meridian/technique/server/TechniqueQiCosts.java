package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.QiAccounting;

import java.util.ArrayList;
import java.util.List;

/** 目录校验与起手扣费共用的平摊规则。 */
final class TechniqueQiCosts {
    private TechniqueQiCosts() {
        // 分配仅依赖声明总额与实际绑定数量，不读取身体账户。
    }

    static List<Double> evenShares(double amount, int count) {
        // 末份吸收余数；最小单位的尾差仅在 double 合计仍严格等于总额时修正。
        if (count < 1 || !Double.isFinite(amount)) {
            throw TechniqueErrors.invalid("cost.qi.amount", "平摊费用或绑定数量非法");
        }
        double share = amount / count;
        if (share < QiAccounting.MIN_TRANSFER_QI) {
            throw TechniqueErrors.invalid("cost.qi.amount", "平摊费用低于 Framework 的最小可流转单位");
        }
        var shares = new ArrayList<Double>();
        double allocated = 0;
        for (int index = 0; index < count; index++) {
            double allocation = share;
            if (index == count - 1) {
                allocation = Math.max(QiAccounting.MIN_TRANSFER_QI, amount - allocated);
            }
            shares.add(allocation);
            allocated += allocation;
        }
        if (allocated != amount) {
            throw TechniqueErrors.invalid("cost.qi.amount", "平摊费用无法同时满足最小单位与总额守恒");
        }
        return List.copyOf(shares);
    }

    static void validateEvenShares(double amount, int minimumCount, int maximumCount) {
        // 校验每个允许数量的实际份额；任一份额或总额非法时抛出异常。
        for (int count = minimumCount; count <= maximumCount; count++) {
            evenShares(amount, count);
        }
    }
}
