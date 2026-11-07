package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.TissueCapabilities;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

/** 每次使用当前身体重新绑定，不缓存跨 revision 的模块选择。 */
public final class TechniqueBinder {
    private enum Purpose { CAST, STATUS }

    private TechniqueBinder() {
        // 绑定只是查询，不开启经脉、不占用组织。
    }

    public static BindingOutcome bind(BodySnapshot body, TechniqueDefinition definition) {
        // 没有手位事实时只允许不依赖相对手臂的槽位。
        return bind(body, definition, Hand.HAND_UNSPECIFIED, HandSide.HAND_SIDE_UNSPECIFIED);
    }

    public static BindingOutcome bind(BodySnapshot body, TechniqueDefinition definition,
                                      Hand selectedHand, HandSide mainHandSide) {
        // 身体与境界先检查，槽位错误随后携带结构化原因码。
        if (definition.getSlotsList().stream().anyMatch(TechniqueSlot::hasRelativeArm)
                && ((selectedHand != Hand.MAIN_HAND && selectedHand != Hand.OFF_HAND)
                        || (mainHandSide != HandSide.LEFT && mainHandSide != HandSide.RIGHT))) {
            return failure(INVALID_REQUEST);
        }
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            return failure(BODY_UNAVAILABLE);
        }
        int realmNumber = BodyMessages.Realm.valueOf(body.realm().name()).getNumber();
        if (realmNumber < definition.getMinimumRealmValue()) {
            return failure(REALM_TOO_LOW);
        }
        return bindSlots(body, definition, selectedHand, mainHandSide, Purpose.CAST);
    }

    public static BindingOutcome bindBenefit(BodySnapshot body, TechniqueDefinition definition, StatusBenefit benefit) {
        // 命令状态只要求存活身体与描述引用的组织，疲劳耗尽也应允许恢复状态。
        var vitality = body.vitality();
        if (vitality.dead() || vitality.remainingSeconds() == 0) {
            return failure(BODY_UNAVAILABLE);
        }
        Set<String> ids = benefit.getTissue().getModifiersList().stream()
                .map(TissueModifier::getSlotId).collect(Collectors.toSet());
        List<TechniqueSlot> slots = definition.getSlotsList().stream()
                .filter(slot -> ids.contains(slot.getId())).toList();
        if (slots.size() != ids.size() || slots.stream().anyMatch(slot -> slot.getModule() != ModuleKind.TISSUE)) {
            return failure(INVALID_REQUEST);
        }
        TechniqueDefinition tissues = definition.toBuilder().clearSlots().addAllSlots(slots).build();
        return bindSlots(body, tissues, Hand.HAND_UNSPECIFIED, HandSide.HAND_SIDE_UNSPECIFIED, Purpose.STATUS);
    }

    private static BindingOutcome bindSlots(BodySnapshot body, TechniqueDefinition definition,
                                            Hand selectedHand, HandSide mainHandSide, Purpose purpose) {
        // 对每个定义槽选择同一快照上的有效组织，并保留稳定字典序。
        BodyMessages.BodySnapshot message = BodyProtobuf.toMessage(body);
        var binding = TechniqueBinding.newBuilder()
                .setBodyId(body.id().value())
                .setRevision(body.revision());
        for (TechniqueSlot slot : definition.getSlotsList()) {
            List<String> armIds = List.of();
            if (slot.hasRelativeArm()) {
                armIds = TechniqueRelativeArms.moduleIds(slot.getRelativeArm(), selectedHand, mainHandSide);
            }
            Set<String> selectedArmIds = Set.copyOf(armIds);
            List<Candidate> matching = candidates(body, message, slot, purpose).stream()
                    .filter(candidate -> !slot.hasRelativeArm() || selectedArmIds.contains(candidate.id()))
                    .filter(candidate -> candidate.tags().containsAll(slot.getTagsList()))
                    .filter(candidate -> slot.getKind().isEmpty() || slot.getKind().equals(candidate.kind()))
                    .sorted(Comparator.comparing(Candidate::id))
                    .toList();
            List<String> selected = matching.stream()
                    .filter(candidate -> candidate.failure() == TECHNIQUE_FAILURE_UNSPECIFIED)
                    .limit(slot.getMaximumCount())
                    .map(Candidate::id)
                    .toList();
            if (selected.size() < slot.getMinimumCount()) {
                return missingSlot(matching);
            }
            BoundSlot bound = BoundSlot.newBuilder()
                    .setSlotId(slot.getId())
                    .setModule(slot.getModule())
                    .addAllModuleIds(selected)
                    .build();
            binding.addSlots(bound);
        }
        TechniqueBinding result = binding.build();
        return BindingOutcome.newBuilder().setBinding(result).build();
    }

    private static List<Candidate> candidates(BodySnapshot body, BodyMessages.BodySnapshot message,
                                               TechniqueSlot slot, Purpose purpose) {
        // 同一快照的定义和状态先建立索引，避免在每个候选中反复扫描 HP。
        Map<String, Double> partHp = message.getPartStatesList().stream()
                .collect(Collectors.toMap(BodyMessages.PartState::getId, BodyMessages.PartState::getHp));
        return switch (slot.getModule()) {
            case MERIDIAN -> meridians(message, slot);
            case TISSUE -> tissues(body, message, slot, partHp, purpose);
            case ORGAN -> organs(message, partHp);
            default -> List.of();
        };
    }

    private static List<Candidate> meridians(BodyMessages.BodySnapshot body, TechniqueSlot slot) {
        // 费用来源必须实际已开拓；损伤状态与开拓状态分别检查。
        Map<String, BodyMessages.MeridianState> states = body.getMeridianStatesList().stream()
                .collect(Collectors.toMap(BodyMessages.MeridianState::getId, Function.identity()));
        var candidates = new ArrayList<Candidate>();
        for (BodyMessages.MeridianDefinition definition : body.getStructure().getMeridiansList()) {
            BodyMessages.MeridianState state = states.get(definition.getId());
            TechniqueFailure failure = TECHNIQUE_FAILURE_UNSPECIFIED;
            if (state.getCondition() == BodyMessages.MeridianCondition.SEVERED) {
                failure = MODULE_UNAVAILABLE;
            } else if (slot.getRequireOpened()
                    && state.getOpening().getStage() != BodyMessages.MeridianOpeningStage.OPENED) {
                failure = MERIDIAN_NOT_OPENED;
            }
            candidates.add(new Candidate(definition.getId(), "", definition.getTagsList(), failure));
        }
        return candidates;
    }

    private static List<Candidate> tissues(BodySnapshot body, BodyMessages.BodySnapshot message,
                                          TechniqueSlot slot, Map<String, Double> partHp, Purpose purpose) {
        // 力量读取 Framework 的当前有效能力，伤势和疲劳不能被模板基础值掩盖。
        Map<String, BodyMessages.TissueState> states = message.getTissueStatesList().stream()
                .collect(Collectors.toMap(BodyMessages.TissueState::getId, Function.identity()));
        var candidates = new ArrayList<Candidate>();
        for (BodyMessages.TissueDefinition definition : message.getStructure().getTissuesList()) {
            BodyMessages.TissueState state = states.get(definition.getId());
            TechniqueFailure failure = TECHNIQUE_FAILURE_UNSPECIFIED;
            if (state.getHp() == 0 || partHp.get(definition.getPartId()) == 0
                    || (purpose == Purpose.CAST && state.getFatigue() >= definition.getFatigueCapacity())) {
                failure = MODULE_UNAVAILABLE;
            } else if (purpose == Purpose.CAST && slot.hasMinimumStrength()) {
                Anatomy.TissueCapacity available = TissueCapabilities.available(body,
                        new Anatomy.TissueId(definition.getId()));
                if (available.strengthNewtons() < slot.getMinimumStrength().getValue()) {
                    failure = INSUFFICIENT_CAPABILITY;
                }
            }
            candidates.add(new Candidate(definition.getId(), definition.getKind(), definition.getTagsList(), failure));
        }
        return candidates;
    }

    private static List<Candidate> organs(BodyMessages.BodySnapshot body, Map<String, Double> partHp) {
        // 器官无需做功能力也可以被占用，但零 HP 或所属部位失能时不可绑定。
        Map<String, BodyMessages.OrganState> states = body.getOrganStatesList().stream()
                .collect(Collectors.toMap(BodyMessages.OrganState::getId, Function.identity()));
        var candidates = new ArrayList<Candidate>();
        for (BodyMessages.OrganDefinition definition : body.getStructure().getOrgansList()) {
            BodyMessages.OrganState state = states.get(definition.getId());
            TechniqueFailure failure = TECHNIQUE_FAILURE_UNSPECIFIED;
            if (state.getHp() == 0 || partHp.get(definition.getPartId()) == 0) {
                failure = MODULE_UNAVAILABLE;
            }
            candidates.add(new Candidate(definition.getId(), definition.getKind(), definition.getTagsList(), failure));
        }
        return candidates;
    }

    private static BindingOutcome missingSlot(List<Candidate> matching) {
        // 匹配了标签但不可用时返回首个确定原因；没有匹配则明确指出缺少模块。
        for (Candidate candidate : matching) {
            TechniqueFailure candidateFailure = candidate.failure();
            if (candidateFailure != TECHNIQUE_FAILURE_UNSPECIFIED) {
                return failure(candidateFailure);
            }
        }
        return failure(MISSING_MODULE);
    }

    private static BindingOutcome failure(TechniqueFailure code) {
        // 绑定失败保留原因码，说明使用玩家能理解的身体感受。
        return BindingOutcome.newBuilder()
                .setFailure(code)
                .setReason(TechniqueErrors.playerReason(code))
                .build();
    }

    private record Candidate(String id, String kind, List<String> tags, TechniqueFailure failure) { }
}
