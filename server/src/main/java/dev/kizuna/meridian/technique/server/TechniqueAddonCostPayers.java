package dev.kizuna.meridian.technique.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 目录加载时冻结的附属费用支付方索引。 */
public final class TechniqueAddonCostPayers {
    private final Map<String, TechniqueAddonCostPayer> payers;
    private final boolean mutable;

    public TechniqueAddonCostPayers() {
        // Fabric 入口先收集支付方，目录发布前由注册表复制为不可变索引。
        this.payers = new LinkedHashMap<>();
        this.mutable = true;
    }

    private TechniqueAddonCostPayers(Map<String, TechniqueAddonCostPayer> payers) {
        // 冻结索引只供目录校验和施放读取。
        this.payers = Map.copyOf(payers);
        this.mutable = false;
    }

    public void register(TechniqueAddonCostPayer payer) {
        // 同一费用键必须只有一个可信支付方，避免模组加载顺序改变扣费语义。
        if (!mutable) {
            throw new IllegalStateException("附属费用支付方索引已冻结");
        }
        Objects.requireNonNull(payer, "payer");
        String key = payer.key();
        TechniqueValues.id(key, "addon_cost_payer.key");
        if (payers.putIfAbsent(key, payer) != null) {
            throw new IllegalArgumentException("附属费用支付方重复：" + key);
        }
    }

    public Optional<TechniqueAddonCostPayer> find(String key) {
        // 目录校验和施放均按完整费用键精确解析。
        return Optional.ofNullable(payers.get(key));
    }

    public TechniqueAddonCostPayers frozen() {
        // 服务器注册表不暴露可变入口，支付方身份在目录加载时固定。
        return new TechniqueAddonCostPayers(payers);
    }

    public List<TechniqueAddonCostPayer> all() {
        // 编程式目录合并保留已登记的支付方，返回不可变快照。
        return List.copyOf(payers.values());
    }

    public static TechniqueAddonCostPayers empty() {
        // 没有附属费用的旧目录使用空索引，通过相同硬校验路径加载。
        return new TechniqueAddonCostPayers(Map.of());
    }
}
