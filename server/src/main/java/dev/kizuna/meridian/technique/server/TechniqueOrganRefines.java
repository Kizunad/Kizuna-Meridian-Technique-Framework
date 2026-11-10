package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefine;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.OrganRefineIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;

import java.util.HashSet;
import java.util.List;

/** 器官炼化的声明边界与起批意图；取料、加工和送回都由宿主按自己的器官规则执行。 */
final class TechniqueOrganRefines {
    private TechniqueOrganRefines() {
        // 纯规则工具，不持有施放状态，也不接触身体账户。
    }

    static void validate(OrganRefine refine) {
        // 批次间隔和起批窗口必须有界，否则目录可生成无限量的宿主事件。
        TechniqueValues.id(refine.getRecipeFamily(), "organ_refine.recipe_family");
        TechniqueValues.millis(refine.getBatchMillis(), "organ_refine.batch_millis");
        TechniqueValues.millis(refine.getDurationMillis(), "organ_refine.duration_millis");
        if (refine.getBatchMillis() <= 0 || refine.getDurationMillis() < refine.getBatchMillis()) {
            throw TechniqueErrors.invalid("organ_refine", "批次间隔必须大于零且不超过起批窗口");
        }
    }

    static long batchCount(OrganRefine refine) {
        // 批次在 [at, at + duration) 内起，窗口终点本身不再起批。
        return Math.ceilDiv(refine.getDurationMillis(), refine.getBatchMillis());
    }

    static OrganRefineIntent intent(ActiveCast active, TechniqueEffect original, double tempo) {
        // 模块沿用起手绑定；批次间隔与窗口终点按起手冻结的动作速度缩放。
        OrganRefine refine = original.getOrganRefine();
        long windowOffset = original.getAtMillis() - active.getClipStartMillis() + refine.getDurationMillis();
        long windowEndMillis = Math.addExact(active.getStartedMillis(), TechniqueTempo.scale(windowOffset, tempo));
        return OrganRefineIntent.newBuilder()
                .addAllOrganIds(moduleIds(active, refine.getOrganSlotId()))
                .addAllMeridianIds(moduleIds(active, refine.getMeridianSlotId()))
                .setRecipeFamily(refine.getRecipeFamily())
                .setBatchMillis(TechniqueTempo.scale(refine.getBatchMillis(), tempo))
                .setWindowEndMillis(windowEndMillis)
                .build();
    }

    static void validateIntent(OrganRefineIntent intent) {
        // 宿主收到的模块必须是非空、不重复的局部 ID，时间不能为负。
        validateModules(intent.getOrganIdsList(), "organ_refine.organ_ids");
        validateModules(intent.getMeridianIdsList(), "organ_refine.meridian_ids");
        TechniqueValues.id(intent.getRecipeFamily(), "organ_refine.recipe_family");
        if (intent.getBatchMillis() <= 0 || intent.getWindowEndMillis() < 0) {
            throw TechniqueErrors.invalid("organ_refine", "批次间隔必须为正，窗口终点不能为负");
        }
    }

    private static void validateModules(List<String> moduleIds, String path) {
        // 绑定上限与功法槽位一致，重复模块会让宿主对同一器官起两批。
        if (moduleIds.isEmpty() || moduleIds.size() > TechniqueValues.MAX_MODULES
                || new HashSet<>(moduleIds).size() != moduleIds.size()) {
            throw TechniqueErrors.invalid(path, "模块列表必须非空、不重复且不超过上限");
        }
        for (String moduleId : moduleIds) {
            TechniqueValues.localId(moduleId, path);
        }
    }

    private static List<String> moduleIds(ActiveCast active, String slotId) {
        // 起手绑定已经按槽位记录实际模块，批次不重新选择。
        return active.getBinding().getSlotsList().stream()
                .filter(slot -> slot.getSlotId().equals(slotId))
                .flatMap(slot -> slot.getModuleIdsList().stream())
                .toList();
    }
}
