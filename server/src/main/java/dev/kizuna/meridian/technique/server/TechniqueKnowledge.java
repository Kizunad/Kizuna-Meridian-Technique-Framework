package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
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

    public static List<TechniqueReference> references(TechniqueRegistry registry, BodySnapshot body) {
        // 当前模板和学习记录都来自调用方持有的同一具身体。
        return references(registry, body, TechniqueState.read(body));
    }

    static List<TechniqueReference> references(TechniqueRegistry registry, BodySnapshot body,
                                               TechniqueProgress progress) {
        // 同次检查读过的进度或刚提交的学习记录按该身体的模板投影。
        var template = body.template();
        var references = new LinkedHashSet<TechniqueReference>();
        for (var known : progress.getKnownList()) {
            var technique = known.getTechnique();
            if (registry.applies(technique, template)) {
                references.add(technique);
            }
        }
        registry.definitions().stream()
                .filter(TechniqueDefinition::getInnate)
                .sorted(Comparator.comparing(TechniqueDefinition::getId)
                        .thenComparingInt(TechniqueDefinition::getVersion))
                .map(TechniqueRegistry::reference)
                .filter(reference -> registry.applies(reference, template))
                .forEach(references::add);
        return List.copyOf(references);
    }

    public static boolean contains(TechniqueRegistry registry, BodySnapshot body,
                                   TechniqueReference reference) {
        // 施放和展示使用同一份精确版本集合。
        return references(registry, body).contains(reference);
    }
}
