package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.QiCost;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 目录加载时冻结的附属费用支付方索引。 */
public final class TechniqueAddonCostPayers {
    private final Map<String, TechniqueAddonCostPayer> payers;
    private final Map<String, String> qiPaymentOrgans;
    private final boolean mutable;

    public TechniqueAddonCostPayers() {
        // Fabric 入口先收集支付方，目录发布前由注册表复制为不可变索引。
        this.payers = new LinkedHashMap<>();
        this.qiPaymentOrgans = new LinkedHashMap<>();
        this.mutable = true;
    }

    private TechniqueAddonCostPayers(Map<String, TechniqueAddonCostPayer> payers, Map<String, String> qiPaymentOrgans) {
        // 冻结索引只供目录校验和施放读取。
        this.payers = Collections.unmodifiableMap(new LinkedHashMap<>(payers));
        this.qiPaymentOrgans = Map.copyOf(qiPaymentOrgans);
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

    public void registerQiPaymentOrgan(String templateId, String organId) {
        // 每种身体模板的经脉费用至多有一个付款器官；器官的健康和库存由施放时的快照决定。
        if (!mutable) {
            throw new IllegalStateException("附属费用支付方索引已冻结");
        }
        TechniqueValues.id(templateId, "qi_payment.template_id");
        TechniqueValues.localId(organId, "qi_payment.organ_id");
        if (qiPaymentOrgans.putIfAbsent(templateId, organId) != null) {
            throw new IllegalArgumentException("真元付款器官重复：" + templateId);
        }
    }

    Optional<String> qiPaymentOrgan(BodySnapshot body) {
        // 用权威身体模板选账户，施放请求不能指定付款器官。
        return Optional.ofNullable(qiPaymentOrgans.get(body.template().id().value()));
    }

    void copyTo(TechniqueAddonCostPayers target) {
        // 编程式目录合并也经过登记时的重复性检查。
        payers.values().forEach(target::register);
        qiPaymentOrgans.forEach(target::registerQiPaymentOrgan);
    }

    List<QiCost> qiCosts(BodySnapshot body, TechniqueDefinition technique) {
        // 真元倍率也适用于没有附属资源费用的式，每个支付方只查询一次。
        var costs = technique.getCost().getQiList();
        if (costs.isEmpty()) {
            return costs;
        }
        double product = 1;
        for (var payer : payers.values()) {
            double factor = payer.qiMultiplier(body, technique);
            if (!Double.isFinite(factor) || factor <= 0) {
                throw new IllegalArgumentException("起手真元倍率必须为有限正数");
            }
            product *= factor;
        }
        if (product == 1) {
            return costs;
        }
        double multiplier = product;
        return costs.stream().map(cost -> scaled(cost, multiplier)).toList();
    }

    private static QiCost scaled(QiCost cost, double multiplier) {
        // 先改变总价，再由原有的均摊、逐脉或余额分摊算法计算账户变更。
        var quantity = cost.getAmount();
        double amount = quantity.getValue() * multiplier;
        if (!Double.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("调整后的起手真元费用必须为有限正数");
        }
        var replacement = quantity.toBuilder().setValue(amount).build();
        return cost.toBuilder().setAmount(replacement).build();
    }

    public TechniqueAddonCostPayers frozen() {
        // 服务器注册表不暴露可变入口，支付方身份在目录加载时固定。
        return new TechniqueAddonCostPayers(payers, qiPaymentOrgans);
    }

    public List<TechniqueAddonCostPayer> all() {
        // 编程式目录合并保留已登记的支付方，返回不可变快照。
        return List.copyOf(payers.values());
    }

    public static TechniqueAddonCostPayers empty() {
        // 没有附属费用的旧目录使用空索引，通过相同硬校验路径加载。
        return new TechniqueAddonCostPayers(Map.of(), Map.of());
    }
}
