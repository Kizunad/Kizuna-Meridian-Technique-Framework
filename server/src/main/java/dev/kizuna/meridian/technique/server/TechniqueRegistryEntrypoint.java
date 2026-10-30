package dev.kizuna.meridian.technique.server;

/** 兼容的编程式目录入口；启动时与其他入口和清单重新合并校验。 */
@FunctionalInterface
public interface TechniqueRegistryEntrypoint {
    /** 返回本提供者的目录，所有提供者收集完成后才发布服务器注册表。 */
    TechniqueRegistry registry();

    default TechniqueCatalogPhase phase() {
        // 旧入口维持服务器启动时求值；可提前提供的静态目录应显式选择初始化阶段。
        return TechniqueCatalogPhase.SERVER_STARTED;
    }
}
