package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 定义不可变，加载失败不会发布半份目录。 */
public final class TechniqueRegistry {
    private static final int MAX_DEFINITIONS = 1024;
    private final Map<TechniqueReference, TechniqueDefinition> definitions;

    private TechniqueRegistry(Map<TechniqueReference, TechniqueDefinition> definitions) {
        // 生成消息本身不可变，复制索引后无需额外包装定义。
        this.definitions = Map.copyOf(definitions);
    }

    public static TechniqueRegistry load(AddonRegistry addons, List<TechniqueCatalog> catalogs) {
        // 先逐目录完成结构和外部引用校验，任何失败都保留调用方的旧目录。
        var definitions = new HashMap<TechniqueReference, TechniqueDefinition>();
        for (TechniqueCatalog catalog : List.copyOf(catalogs)) {
            TechniqueValues.knownFields(catalog);
            TechniqueValues.localId(catalog.getNamespace(), "namespace");
            if (catalog.getSchemaVersion() != TechniqueValues.SCHEMA_VERSION
                    || catalog.getDefinitionsCount() > MAX_DEFINITIONS) {
                throw TechniqueErrors.invalid("catalog", "版本不支持或功法数量超限");
            }
            Map<ModuleKind, Set<String>> tags = registeredTags(addons, catalog);
            for (TechniqueDefinition definition : catalog.getDefinitionsList()) {
                TechniqueDefinitions.validate(addons, tags, definition);
                if (!definition.getId().startsWith(catalog.getNamespace() + ":")) {
                    throw TechniqueErrors.invalid(definition.getId(), "功法 ID 不属于目录命名空间");
                }
                TechniqueReference reference = reference(definition);
                if (definitions.putIfAbsent(reference, definition) != null) {
                    throw TechniqueErrors.invalid(definition.getId(), "功法 ID 和版本重复");
                }
            }
        }
        if (definitions.size() > MAX_DEFINITIONS) {
            throw TechniqueErrors.invalid("catalogs", "功法总数超限");
        }
        return new TechniqueRegistry(definitions);
    }

    public Optional<TechniqueDefinition> find(TechniqueReference reference) {
        // 只按精确版本查找，不以其他版本兜底改变已学记录的含义。
        return Optional.ofNullable(definitions.get(reference));
    }

    public List<TechniqueDefinition> definitions() {
        // 按身份排序，保证不同加载顺序下目录遍历结果一致。
        return definitions.values().stream()
                .sorted(java.util.Comparator.comparing(TechniqueDefinition::getId)
                        .thenComparingInt(TechniqueDefinition::getVersion))
                .toList();
    }

    static TechniqueReference reference(TechniqueDefinition definition) {
        // 所有运行入口使用同一对内容身份与版本。
        return TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
    }

    private static Map<ModuleKind, Set<String>> registeredTags(AddonRegistry addons, TechniqueCatalog catalog) {
        // Framework 标签是开放字符串；显式模板引用限定本目录允许使用的标签词汇。
        var tags = new HashMap<ModuleKind, Set<String>>();
        tags.put(ModuleKind.MERIDIAN, new HashSet<>());
        tags.put(ModuleKind.TISSUE, new HashSet<>());
        tags.put(ModuleKind.ORGAN, new HashSet<>());
        var references = new HashSet<BodyMessages.TemplateReference>();
        if (catalog.getBindingTemplatesCount() > MAX_DEFINITIONS) {
            throw TechniqueErrors.invalid("binding_templates", "模板数量超限");
        }
        for (BodyMessages.TemplateReference reference : catalog.getBindingTemplatesList()) {
            if (!references.add(reference)) {
                throw TechniqueErrors.invalid("binding_templates", "模板引用重复");
            }
            BodyMessages.BodyTemplate template = addons.findTemplate(reference.getId(), reference.getVersion())
                    .orElseThrow(() -> TechniqueErrors.invalid(reference.getId(), "绑定模板未登记或版本不存在"));
            BodyMessages.BodyStructure structure = template.getStructure();
            structure.getMeridiansList().forEach(module -> tags.get(ModuleKind.MERIDIAN)
                    .addAll(module.getTagsList()));
            structure.getTissuesList().forEach(module -> tags.get(ModuleKind.TISSUE).addAll(module.getTagsList()));
            structure.getOrgansList().forEach(module -> tags.get(ModuleKind.ORGAN).addAll(module.getTagsList()));
        }
        return tags;
    }
}
