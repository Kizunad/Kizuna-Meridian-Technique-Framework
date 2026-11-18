package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyStructure;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TemplateReference;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 已校验的模板组基准与适用成员。 */
public final class TechniqueTemplateGroups {
    private record Group(TemplateReference base, Set<TemplateReference> members) {}
    record Binding(List<TemplateReference> vocabulary, Set<TemplateReference> members) {}
    private final Map<String, Group> groups;

    public static TechniqueTemplateGroups empty() {
        // 只含直绑模板的目录不需要读取组清单。
        return new TechniqueTemplateGroups(Map.of());
    }

    private TechniqueTemplateGroups(Map<String, Group> groups) {
        // 成员只在整批加载时填写，此后通过只读的目录绑定访问。
        this.groups = Map.copyOf(groups);
    }

    public static TechniqueTemplateGroups load(AddonRegistry addons, List<TemplateGroupSource> sources) {
        // 先登记所有基准及声明者的成员，再解析跨模组追加，顺序不影响组的内容。
        var groups = new LinkedHashMap<String, Group>();
        for (var source : sources) {
            for (var declaration : source.manifest().getGroupsList()) {
                String id = declaration.getId();
                TechniqueValues.id(id, source.source());
                var group = new Group(declaration.getBase(), new LinkedHashSet<>());
                if (groups.putIfAbsent(id, group) != null) {
                    throw TechniqueErrors.invalid(source.source(), "模板组重复声明：" + id);
                }
                addMembers(addons, group, declaration.getMembersList(), source.source() + " " + id);
            }
        }
        for (var source : sources) {
            for (var addition : source.manifest().getAdditionsList()) {
                String id = addition.getGroupId();
                Group group = groups.get(id);
                if (group == null) {
                    throw TechniqueErrors.invalid(source.source(), "追加到未声明的模板组：" + id);
                }
                addMembers(addons, group, addition.getMembersList(), source.source() + " " + id);
            }
        }
        return new TechniqueTemplateGroups(groups);
    }

    private static void addMembers(AddonRegistry addons, Group group, List<TemplateReference> members, String source) {
        // 即使成员表为空，也必须验证基准模板已经按精确版本登记。
        BodyStructure base = structure(addons, group.base(), source);
        for (var member : members) {
            String location = source + " " + member.getId() + "@" + member.getVersion();
            if (!group.members().add(member)) {
                throw TechniqueErrors.invalid(location, "模板组成员重复");
            }
            BodyStructure candidate = structure(addons, member, source);
            validateShape(base, candidate, location);
        }
    }

    private static BodyStructure structure(AddonRegistry addons, TemplateReference reference, String source) {
        // 与绑定目录同样使用 Framework 的版本化模板注册表。
        return addons.findTemplate(reference.getId(), reference.getVersion())
                .orElseThrow(() -> TechniqueErrors.invalid(source,
                        "模板未登记：" + reference.getId() + "@" + reference.getVersion()))
                .getStructure();
    }

    private static void validateShape(BodyStructure base, BodyStructure member, String location) {
        // 模块 ID 可以不同；槽位需要的组织种类与标签必须同时出现在一个组织上。
        var parts = new HashSet<String>();
        member.getPartsList().forEach(part -> parts.add(part.getId()));
        for (var part : base.getPartsList()) {
            if (!parts.contains(part.getId())) {
                throw TechniqueErrors.invalid(location, "缺部位：" + part.getId());
            }
        }
        for (var tissue : base.getTissuesList()) {
            boolean matches = member.getTissuesList().stream().anyMatch(candidate ->
                    tissue.getKind().equals(candidate.getKind())
                            && candidate.getTagsList().containsAll(tissue.getTagsList()));
            if (!matches) {
                throw TechniqueErrors.invalid(location,
                        "缺组织种类或对应标签：" + tissue.getKind() + tissue.getTagsList());
            }
        }
        var tags = new HashSet<String>();
        member.getMeridiansList().forEach(meridian -> tags.addAll(meridian.getTagsList()));
        for (var meridian : base.getMeridiansList()) {
            for (String tag : meridian.getTagsList()) {
                if (!tags.contains(tag)) {
                    throw TechniqueErrors.invalid(location, "缺经脉标签：" + tag);
                }
            }
        }
    }

    Binding bind(TechniqueCatalog catalog) {
        // 直绑项进入词表和适用范围；组只以基准提供词表，以所有成员提供范围。
        var vocabulary = new LinkedHashSet<>(catalog.getBindingTemplatesList());
        if (vocabulary.size() != catalog.getBindingTemplatesCount()) {
            throw TechniqueErrors.invalid("binding_templates", "模板引用重复");
        }
        var members = new LinkedHashSet<>(vocabulary);
        var ids = new HashSet<String>();
        for (String id : catalog.getBindingTemplateGroupsList()) {
            Group group = groups.get(id);
            if (group == null) {
                throw TechniqueErrors.invalid("binding_template_groups", "模板组未登记：" + id);
            }
            if (!ids.add(id)) {
                throw TechniqueErrors.invalid("binding_template_groups", "模板组引用重复：" + id);
            }
            vocabulary.add(group.base());
            members.addAll(group.members());
        }
        if (vocabulary.isEmpty()) {
            throw TechniqueErrors.invalid("binding_templates", "目录至少绑定一个模板或模板组");
        }
        return new Binding(List.copyOf(vocabulary), Set.copyOf(members));
    }

    public static TechniqueCatalog templateContext(List<TemplateGroupSource> sources) {
        // 供内容包读取器按实际基准与成员模板的命名空间收集依赖。
        var references = sources.stream().flatMap(TemplateGroupSource::templates).distinct().toList();
        return TechniqueCatalog.newBuilder()
                .addAllBindingTemplates(references)
                .build();
    }

    public TechniqueCatalog validationContext(TechniqueCatalog catalog) {
        // 给旧模板注册表工厂提供实际需要的模板，目录来源本身保留绑定组。
        return catalog.toBuilder()
                .clearBindingTemplateGroups()
                .clearBindingTemplates()
                .addAllBindingTemplates(bind(catalog).vocabulary())
                .build();
    }
}
