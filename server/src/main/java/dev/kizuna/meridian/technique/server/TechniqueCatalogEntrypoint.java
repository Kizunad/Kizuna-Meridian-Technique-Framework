package dev.kizuna.meridian.technique.server;

/** 编程式收集入口；可以声明对其他模组清单的引用，收集时不单独建表。 */
@FunctionalInterface
public interface TechniqueCatalogEntrypoint {
    /** 使用给定来源登记原始目录，所有模组收集完后再由启动器统一校验。 */
    void registerCatalogs(String source, TechniqueCatalogs catalogs);

    default TechniqueCatalogPhase phase() {
        // 原始声明通常不依赖世界，可在两端注册物品前收集。
        return TechniqueCatalogPhase.MOD_INITIALIZATION;
    }
}
