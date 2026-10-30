package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;

import java.util.Objects;

/** 目录原文及其校验上下文；来源包含模组和资源路径或代码入口。 */
public record TechniqueCatalogSource(String source, AddonRegistry addons, TechniqueCatalog catalog) {
    public TechniqueCatalogSource {
        // 来源与模板注册表随不可变目录保留，合并时不丢失外部引用的校验依据。
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(addons, "addons");
        Objects.requireNonNull(catalog, "catalog");
        if (source.isBlank()) {
            throw new IllegalArgumentException("目录来源不能为空");
        }
    }

    public String location(String field) {
        // 使用 Protobuf 字段名及从零开始的下标定位声明。
        return source + " " + field;
    }
}
