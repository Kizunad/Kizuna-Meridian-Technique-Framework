package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

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
            if (registry.applies(technique, template) && unlocked(body, registry.find(technique).orElseThrow())) {
                references.add(technique);
            }
        }
        registry.definitions().stream()
                .filter(TechniqueDefinition::getInnate)
                .filter(definition -> registry.applies(TechniqueRegistry.reference(definition), template))
                .filter(definition -> unlocked(body, definition))
                .map(TechniqueRegistry::reference)
                .forEach(references::add);
        return List.copyOf(references);
    }

    private static boolean unlocked(BodySnapshot body, TechniqueDefinition definition) {
        // 只读境界和开拓里程碑；当前伤势、余额与疲劳只影响起式。
        if (!definition.hasInnateUnlock()) {
            return true;
        }
        int realm = BodyMessages.Realm.valueOf(body.realm().name()).getNumber();
        if (realm < definition.getMinimumRealmValue()) {
            return false;
        }
        var opened = body.meridianStates().stream()
                .filter(state -> state.opening().stage() == MeridianOpening.Stage.OPENED)
                .map(MeridianState::id)
                .collect(Collectors.toSet());
        var tags = body.structure().meridians().stream()
                .filter(channel -> opened.contains(channel.id()))
                .flatMap(channel -> channel.tags().stream())
                .map(DefinitionId::value)
                .collect(Collectors.toSet());
        return tags.containsAll(definition.getInnateUnlock().getOpenedMeridianTagsList());
    }

    public static boolean contains(TechniqueRegistry registry, BodySnapshot body,
                                   TechniqueReference reference) {
        // 施放和展示使用同一份精确版本集合。
        return references(registry, body).contains(reference);
    }
}
