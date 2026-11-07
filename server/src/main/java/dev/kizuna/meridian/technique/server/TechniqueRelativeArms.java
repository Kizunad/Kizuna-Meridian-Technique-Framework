package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 内容声明左右组织集合，功法库只按实际手位选择，不猜测解剖身份。 */
final class TechniqueRelativeArms {
    private TechniqueRelativeArms() {
        // 不读取人类附属或实体，左右候选完全来自目录。
    }

    static void validate(AddonRegistry addons, List<BodyMessages.TemplateReference> templates,
                         TechniqueDefinition definition) {
        // 相对手臂只用于声明武器类别的组织槽，左右集合须已由模板证明存在。
        for (TechniqueSlot slot : definition.getSlotsList()) {
            if (!slot.hasRelativeArm()) {
                continue;
            }
            RelativeArmSlot arm = slot.getRelativeArm();
            if (!definition.hasProjectileWeapon() || slot.getModule() != ModuleKind.TISSUE
                    || (arm.getArm() != DrawStrengthArm.OTHER_ARM && arm.getArm() != DrawStrengthArm.WEAPON_ARM)) {
                throw TechniqueErrors.invalid(slot.getId(), "相对手臂只允许远程式的组织槽");
            }
            Set<String> known = matchingIds(addons, templates, slot);
            validateSide(arm.getLeftModuleIdsList(), known, slot);
            validateSide(arm.getRightModuleIdsList(), known, slot);
            if (arm.getLeftModuleIdsList().stream().anyMatch(arm.getRightModuleIdsList()::contains)) {
                throw TechniqueErrors.invalid(slot.getId(), "左右手臂的组织集合不能重叠");
            }
        }
    }

    private static Set<String> matchingIds(AddonRegistry addons, List<BodyMessages.TemplateReference> templates,
                                            TechniqueSlot slot) {
        // 显式组织身份仍须满足槽位标签与类型，不能用左右表绕过正常目录约束。
        Set<String> ids = new HashSet<>();
        for (BodyMessages.TemplateReference reference : templates) {
            var template = addons.findTemplate(reference.getId(), reference.getVersion()).orElseThrow();
            for (BodyMessages.TissueDefinition tissue : template.getStructure().getTissuesList()) {
                if (tissue.getTagsList().containsAll(slot.getTagsList())
                        && (slot.getKind().isEmpty() || slot.getKind().equals(tissue.getKind()))) {
                    ids.add(tissue.getId());
                }
            }
        }
        return ids;
    }

    private static void validateSide(List<String> ids, Set<String> known, TechniqueSlot slot) {
        // 两侧分别满足候选下限，重复、过多与不存在的组织都在启动时拒绝。
        if (ids.isEmpty() || ids.size() < slot.getMinimumCount() || ids.size() > TechniqueValues.MAX_MODULES
                || new HashSet<>(ids).size() != ids.size() || !known.containsAll(ids)) {
            throw TechniqueErrors.invalid(slot.getId(), "左右手臂组织候选缺失、重复、超限或未匹配模板");
        }
    }

    static List<String> moduleIds(RelativeArmSlot arm, Hand hand, HandSide mainHandSide) {
        // 先换算持武器的解剖侧，再按武器臂或另一条臂决定目录候选。
        HandSide weaponSide;
        if (hand == Hand.OFF_HAND) {
            weaponSide = opposite(mainHandSide);
        } else {
            weaponSide = mainHandSide;
        }
        HandSide selectedSide;
        if (arm.getArm() == DrawStrengthArm.OTHER_ARM) {
            selectedSide = opposite(weaponSide);
        } else {
            selectedSide = weaponSide;
        }
        if (selectedSide == HandSide.LEFT) {
            return arm.getLeftModuleIdsList();
        }
        return arm.getRightModuleIdsList();
    }

    private static HandSide opposite(HandSide side) {
        // 调用前已验证左右侧，交换只处理两个有效值。
        return side == HandSide.LEFT ? HandSide.RIGHT : HandSide.LEFT;
    }
}
