package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** 已学与天生式的只读合并；天生式没有学习来源或学习时刻。 */
public final class TechniqueKnowledge {
    private TechniqueKnowledge() {
        // 知识始终来自给定目录和身体进度，不建立实体缓存。
    }

    public static List<TechniqueReference> references(TechniqueRegistry registry, TechniqueProgress progress) {
        // 保留旧学习顺序，再以稳定顺序补天生的精确版本。
        var references = new LinkedHashSet<TechniqueReference>();
        progress.getKnownList().forEach(known -> references.add(known.getTechnique()));
        registry.definitions().stream()
                .filter(TechniqueDefinition::getInnate)
                .sorted(Comparator.comparing(TechniqueDefinition::getId)
                        .thenComparingInt(TechniqueDefinition::getVersion))
                .map(TechniqueRegistry::reference)
                .forEach(references::add);
        return List.copyOf(references);
    }

    public static boolean contains(TechniqueRegistry registry, TechniqueProgress progress,
                                   TechniqueReference reference) {
        // 施放和展示使用同一份精确版本集合。
        return references(registry, progress).contains(reference);
    }
}
