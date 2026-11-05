package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueStatusCurves.Phase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 状态能力的唯一修正入口；原子替换自有修正，不接触施法曲线或施放进度。 */
public final class TechniqueStatusTissues {
    private static final String PREFIX = "meridian_technique:status/";
    private static final Pattern OWNED_ID = Pattern.compile(
            "meridian_technique:status/[0-9a-f]+/[0-9a-f-]{36}/[0-9]+");

    private TechniqueStatusTissues() {
        // 宿主提供串行的权威身体访问，本服务不保存身体副本。
    }

    public static TissueBenefitLease replace(TechniqueHost host, TechniqueDefinition definition,
                                             StatusBenefit benefit, TissueBenefitLease previous, Phase phase) {
        // 旧组与新组在同笔活动替换，容量或 revision 拒绝不会留下两份加成。
        TechniqueBenefits.validate(benefit);
        if (!previous.equals(TissueBenefitLease.getDefaultInstance())
                && !previous.getStatusId().equals(benefit.getId())) {
            throw new IllegalArgumentException("刷新不能借用其他状态的修正归属");
        }
        BodySnapshot body = host.inspect().body();
        BindingOutcome binding = TechniqueBinder.bindBenefit(body, definition, benefit);
        if (!binding.hasBinding()) {
            throw new IllegalArgumentException("状态组织绑定失败：" + binding.getFailure());
        }
        List<CapabilityModifier> modifiers = modifiers(body, binding.getBinding(), benefit, phase);
        BodyActivity activity = BodyActivity.newBuilder()
                .addAllRemoveModifiers(owned(body, previous))
                .addAllAddModifiers(modifiers)
                .build();
        commit(host, body, activity);
        return TissueBenefitLease.newBuilder()
                .setStatusId(benefit.getId())
                .setBodyId(body.id().value())
                .setTotalTicks(phase.totalTicks())
                .addAllModifierIds(modifiers.stream().map(CapabilityModifier::getId).toList())
                .build();
    }

    public static void remove(TechniqueHost host, TissueBenefitLease lease) {
        // 自然到期已清掉的 ID 不再提交，重复撤销为幂等空操作。
        BodySnapshot body = host.inspect().body();
        List<String> ids = owned(body, lease);
        if (!ids.isEmpty()) {
            BodyActivity activity = BodyActivity.newBuilder().addAllRemoveModifiers(ids).build();
            commit(host, body, activity);
        }
    }

    public static void validateRemovals(BodyActivity activity) {
        // 允许撤销的活动只能管理状态修正，旧 cast ID 和夹带费用、动作或进度一律拒绝。
        if (activity.getRemoveModifiersCount() == 0) {
            return;
        }
        BodyActivity otherFields = activity.toBuilder().clearAddModifiers().clearRemoveModifiers().build();
        if (!otherFields.equals(BodyActivity.getDefaultInstance())) {
            throw TechniqueErrors.invalid("activity.remove_modifiers", "状态撤销活动不能夹带其他操作");
        }
        for (String id : activity.getRemoveModifiersList()) {
            validateOwnedId(id);
        }
        for (CapabilityModifier modifier : activity.getAddModifiersList()) {
            validateOwnedId(modifier.getId());
            if (!modifier.getSource().equals(TechniqueHost.CALLER_ID)) {
                throw TechniqueErrors.invalid("activity.add_modifiers", "状态修正来源不匹配");
            }
        }
    }

    public static void validateLease(TissueBenefitLease lease) {
        // 持久化归属必须属于同一状态，不能借伪造元数据移除其他状态或历史功法。
        TechniqueValues.id(lease.getStatusId(), "status_id");
        if (lease.getBodyId().isBlank() || lease.getTotalTicks() <= 0
                || lease.getModifierIdsCount() > dev.kizuna.meridian.core.CapabilityModifier.MAX_PER_BODY) {
            throw new IllegalArgumentException("状态修正归属元数据非法");
        }
        String prefix = statusPrefix(lease.getStatusId());
        var unique = new HashSet<String>();
        for (String id : lease.getModifierIdsList()) {
            validateOwnedId(id);
            if (!id.startsWith(prefix) || !unique.add(id)) {
                throw new IllegalArgumentException("状态修正归属身份不匹配或重复");
            }
        }
    }

    private static List<String> owned(BodySnapshot body, TissueBenefitLease lease) {
        // 身体身份变化时不沿用旧组织；只有快照中仍存在且来源匹配的登记 ID 可以撤销。
        if (lease.equals(TissueBenefitLease.getDefaultInstance())) {
            return List.of();
        }
        validateLease(lease);
        if (!body.id().value().equals(lease.getBodyId())) {
            return List.of();
        }
        var ids = new HashSet<>(lease.getModifierIdsList());
        return body.modifiers().stream()
                .filter(modifier -> modifier.source().value().equals(TechniqueHost.CALLER_ID))
                .map(modifier -> modifier.id().value())
                .filter(ids::contains)
                .toList();
    }

    private static List<CapabilityModifier> modifiers(BodySnapshot body, TechniqueBinding binding,
                                                     StatusBenefit benefit, Phase phase) {
        // 每次替换使用新代号；Framework 明确禁止同笔删除后重用已有修正 ID。
        String prefix = statusPrefix(benefit.getId()) + UUID.randomUUID() + "/";
        var modifiers = new ArrayList<CapabilityModifier>();
        long start = body.settledAt().elapsedMillis();
        for (TissueModifier profile : benefit.getTissue().getModifiersList()) {
            BoundSlot slot = binding.getSlotsList().stream()
                    .filter(candidate -> candidate.getSlotId().equals(profile.getSlotId())).findFirst().orElseThrow();
            var curve = TechniqueStatusCurves.points(profile, phase);
            for (String tissue : slot.getModuleIdsList()) {
                CapabilityModifier modifier = CapabilityModifier.newBuilder()
                        .setId(prefix + modifiers.size())
                        .setSource(TechniqueHost.CALLER_ID)
                        .setTissueId(tissue)
                        .setStartMillis(start)
                        .addAllCurve(curve)
                        .setStrengthPeak(profile.getStrengthPeak().getValue())
                        .setBurstPeak(profile.getBurstPeak().getValue())
                        .setEndurancePeak(profile.getEndurancePeak().getValue())
                        .setRecoveryPeak(profile.getRecoveryPeak().getValue())
                        .build();
                modifiers.add(modifier);
            }
        }
        return List.copyOf(modifiers);
    }

    private static void commit(TechniqueHost host, BodySnapshot body, BodyActivity activity) {
        // 实际能力、数量限制及 revision 由 Framework 的公开活动入口原子复核。
        validateRemovals(activity);
        String requestId = "technique_status_" + UUID.randomUUID();
        TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(requestId, body.revision(), activity));
        if (commit.revision() != body.revision() + 1 || !commit.activity().equals(activity)) {
            throw new IllegalStateException("状态修正回执与提交计划不一致");
        }
    }

    private static String statusPrefix(String id) {
        // 十六进制保留完整命名空间，避免不同内容状态共享归属路径。
        return PREFIX + HexFormat.of().formatHex(id.getBytes(StandardCharsets.UTF_8)) + "/";
    }

    private static void validateOwnedId(String id) {
        // 受限入口从协议层拒绝所有旧施法曲线与非状态修正身份。
        if (!OWNED_ID.matcher(id).matches()) {
            throw TechniqueErrors.invalid("activity.remove_modifiers", "只能撤销状态宿主拥有的修正");
        }
    }
}
