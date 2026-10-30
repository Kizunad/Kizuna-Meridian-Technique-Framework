package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;

import java.util.ArrayList;
import java.util.List;

/** 收集清单与编程式目录，直到全部收齐才进行整体校验。 */
public final class TechniqueCatalogs {
    private final List<TechniqueCatalogSource> sources = new ArrayList<>();

    public void register(String source, AddonRegistry addons, TechniqueCatalog catalog) {
        // 登记只保存不可变候选，允许引用尚未收集的书、卷和式。
        sources.add(new TechniqueCatalogSource(source, addons, catalog));
    }

    public void register(String source, TechniqueRegistry registry) {
        // 兼容旧 Java 入口，重新合并原始目录而非拼接已建索引。
        List<TechniqueCatalogSource> originals = registry.sources();
        for (int index = 0; index < originals.size(); index++) {
            TechniqueCatalogSource original = originals.get(index);
            register(source + " catalogs[" + index + "]", original.addons(), original.catalog());
        }
    }

    public boolean isEmpty() {
        // 无任何目录声明时保持服务尚未安装的既有行为。
        return sources.isEmpty();
    }

    public List<TechniqueCatalogSource> sources() {
        // 物品注册只读取已收集声明，不提前要求晚到代码目录的跨来源引用完整。
        return List.copyOf(sources);
    }

    public TechniqueRegistry build() {
        // 任一错误都会抛出，调用方拿不到部分成功的注册表。
        return TechniqueRegistry.loadSources(sources);
    }
}
