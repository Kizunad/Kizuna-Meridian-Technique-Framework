package dev.kizuna.meridian.technique.server;

/** Fabric 内容附属登记自己的附属费用支付方。 */
@FunctionalInterface
public interface TechniqueAddonCostEntrypoint {
    String ENTRYPOINT_ID = "meridian_technique_addon_cost";

    /** 在目录校验前登记附属费用支付方。 */
    void registerPayers(TechniqueAddonCostPayers payers);
}
