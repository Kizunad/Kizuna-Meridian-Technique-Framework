package dev.kizuna.meridian.technique.server;

/** 内容附属提供已通过 Framework 与功法目录校验的不可变注册表。 */
@FunctionalInterface
public interface TechniqueRegistryEntrypoint {
    /** 返回当前服务器要使用的功法目录。 */
    TechniqueRegistry registry();
}
