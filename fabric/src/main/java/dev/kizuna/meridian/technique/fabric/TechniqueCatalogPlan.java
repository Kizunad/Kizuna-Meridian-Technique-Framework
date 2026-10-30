package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.technique.server.TechniqueCatalogPhase;
import dev.kizuna.meridian.technique.server.TechniqueCatalogSource;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 固定发现顺序；初始化目录只读一次，依赖世界的入口留到服务器启动。 */
public final class TechniqueCatalogPlan {
    private final List<Contribution> contributions;

    TechniqueCatalogPlan(List<Contribution> contributions) {
        // 已排序的来源不会因两种生命周期的实际求值时间而换位。
        this.contributions = List.copyOf(contributions);
    }

    public List<TechniqueCatalogSource> earlySources() {
        // 物品注册表冻结后不能新增身份，晚到目录明确不生成卷轴。
        return contributions.stream()
                .filter(contribution -> contribution.phase() == TechniqueCatalogPhase.MOD_INITIALIZATION)
                .flatMap(contribution -> contribution.catalogs().get().stream())
                .toList();
    }

    public TechniqueRegistry registry() {
        // 每次服务器启动重取晚到目录，然后与初始化声明整体校验。
        var sources = new ArrayList<TechniqueCatalogSource>();
        for (Contribution contribution : contributions) {
            sources.addAll(contribution.catalogs().get());
        }
        if (sources.isEmpty()) {
            return null;
        }
        return TechniqueRegistry.loadSources(sources);
    }

    record Contribution(TechniqueCatalogPhase phase, Supplier<List<TechniqueCatalogSource>> catalogs) {
        static Contribution prepare(TechniqueCatalogPhase phase, Supplier<List<TechniqueCatalogSource>> catalogs) {
            // 提前目录在准备时固定，避免物品与服务器服务读到不同的文件或回调结果。
            if (phase == TechniqueCatalogPhase.MOD_INITIALIZATION) {
                List<TechniqueCatalogSource> collected = List.copyOf(catalogs.get());
                return new Contribution(phase, () -> collected);
            }
            return new Contribution(phase, catalogs);
        }
    }
}
