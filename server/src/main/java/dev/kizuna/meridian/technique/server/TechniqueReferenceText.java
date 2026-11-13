package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BoundSlot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUp;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ModuleKind;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBinding;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 学会以后随时能查的一式明细：卷轴正文那几行，加上境界、承接关系和这具身体要用的部位。 */
public final class TechniqueReferenceText {
    private TechniqueReferenceText() {
        // 只投影目录和绑定结果，不判断现在放不放得出。
    }

    /** 目录里写死的部分：数值三行、最低境界、能接在谁后面、收势时能接谁。 */
    public static List<String> lines(TechniqueRegistry registry, TechniqueDefinition definition) {
        var lines = new ArrayList<String>(TechniqueScrollText.details(definition));
        String realm = TechniqueNames.realm(definition.getMinimumRealmValue());
        if (!realm.isEmpty()) {
            lines.add("境界：" + realm + "以上");
        }
        List<String> previous = definition.getFollowUp().getTransitionsList().stream()
                .map(FollowUp::getPrevious)
                .map(reference -> name(registry, reference.getId())).distinct().toList();
        if (definition.getFollowUp().getOnlyFollowUp()) {
            lines.add("承接：只能接在" + TechniqueNames.list(previous) + "的收势后，不能单独起手");
        } else if (!previous.isEmpty()) {
            lines.add("承接：可以接在" + TechniqueNames.list(previous) + "的收势后");
        }
        List<String> next = registry.definitions().stream()
                .filter(candidate -> candidate.getFollowUp().getTransitionsList().stream()
                        .anyMatch(transition -> transition.getPrevious().getId().equals(definition.getId())))
                .map(candidate -> name(registry, candidate.getId())).distinct().toList();
        if (!next.isEmpty()) {
            lines.add("后招：收势时可接" + TechniqueNames.list(next));
        }
        return List.copyOf(lines);
    }

    /** 这具身体放这一式时用到的部位，按经脉、筋肉、器官各一行；名字来自身体结构。 */
    public static List<String> modules(BodySnapshot body, TechniqueBinding binding) {
        // 同一种部位的几个槽合成一行，同一处只写一次。
        var names = new LinkedHashMap<ModuleKind, List<String>>();
        for (BoundSlot slot : binding.getSlotsList()) {
            List<String> listed = names.computeIfAbsent(slot.getModule(), ignored -> new ArrayList<>());
            for (String moduleId : slot.getModuleIdsList().stream().sorted().toList()) {
                String name = TechniqueScrollText.safeName(displayName(body, slot.getModule(), moduleId), "");
                if (!name.isEmpty() && !listed.contains(name)) {
                    listed.add(name);
                }
            }
        }
        var lines = new ArrayList<String>();
        for (ModuleKind kind : List.of(ModuleKind.MERIDIAN, ModuleKind.TISSUE, ModuleKind.ORGAN)) {
            List<String> listed = names.getOrDefault(kind, List.of());
            if (!listed.isEmpty()) {
                lines.add(word(kind) + "：" + TechniqueNames.list(listed));
            }
        }
        return List.copyOf(lines);
    }

    private static String name(TechniqueRegistry registry, String techniqueId) {
        // 承接关系里写式名；目录里已经没有的式写「未知的式」，不露出资源 ID。
        return registry.findLatest(techniqueId)
                .map(definition -> TechniqueScrollText.safeName(definition.getPresentation().getDisplayName(), "未命名"))
                .orElse("未知的式");
    }

    private static String displayName(BodySnapshot body, ModuleKind kind, String moduleId) {
        // 三种部位各查各的结构表。
        var structure = body.structure();
        return switch (kind) {
            case MERIDIAN -> structure.meridians().stream().filter(item -> item.id().value().equals(moduleId))
                    .map(Anatomy.MeridianDefinition::displayName).findFirst().orElse("");
            case TISSUE -> structure.tissues().stream().filter(item -> item.id().value().equals(moduleId))
                    .map(Anatomy.TissueDefinition::displayName).findFirst().orElse("");
            case ORGAN -> structure.organs().stream().filter(item -> item.id().value().equals(moduleId))
                    .map(Anatomy.OrganDefinition::displayName).findFirst().orElse("");
            default -> "";
        };
    }

    private static String word(ModuleKind kind) {
        // 和绑定失败说明里的叫法一致。
        return switch (kind) {
            case MERIDIAN -> "经脉";
            case ORGAN -> "器官";
            default -> "筋肉";
        };
    }
}
