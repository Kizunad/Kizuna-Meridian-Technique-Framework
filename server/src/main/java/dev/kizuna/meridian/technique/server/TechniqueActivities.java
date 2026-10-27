package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

/** 起手只生成一份活动，所有资源变化最终由宿主原子提交。 */
final class TechniqueActivities {
    private static final int MAX_EFFORTS = 32;

    private TechniqueActivities() {
        // 活动依据调用方快照生成，权威状态仍由宿主提交。
    }

    record Start(BodySnapshot body, TechniqueDefinition definition, CastRequest request,
                 TechniqueProgress progress, TechniqueBinding binding) { }

    static BodyActivity start(Start start) {
        // 费用、动作和冷却都依据同一个快照 revision，任一校验失败都没有提交。
        BodySnapshot body = start.body();
        TechniqueDefinition definition = start.definition();
        TechniqueBinding binding = start.binding();
        Map<String, BoundSlot> slots = binding.getSlotsList().stream()
                .collect(Collectors.toMap(BoundSlot::getSlotId, Function.identity()));
        Accounts accounts = accounts(body, definition.getCost().getQiList(), slots);
        ActiveCast active = activeCast(start);
        List<BodyMessages.CapabilityModifier> modifiers = TechniqueScheduler.modifiers(definition, active);
        List<ActivityEffort> efforts = efforts(start, slots, modifiers);
        long cooldownEnd = addTime(active.getStartedMillis(), definition.getCooldownMillis());
        TechniqueProgress progress = start.progress().toBuilder()
                .setActiveCast(active)
                .putCooldowns(definition.getId(), cooldownEnd)
                .build();
        Any payload = TechniqueState.pack(progress);
        ActivityAction action = action(definition, active, slots.values().stream().toList());
        BodyActivity activity = BodyActivity.newBuilder()
                .addAllAccounts(accounts.changes())
                .setReleasedQi(BodyProtobuf.toInventory(accounts.released()))
                .addAllEfforts(efforts)
                .addStartActions(action)
                .addAllAddModifiers(modifiers)
                .putProgress(TechniqueState.KEY, payload)
                .build();

        // 预检使用 Framework 的纯计算器，失败抛异常；候选结果不写入任何存储。
        BodyActivityExecution.apply(body, activity, TechniqueHost.CALLER_ID);
        return activity;
    }

    static BodyActivity stop(TechniqueProgress progress) {
        // 结束只释放动作占用并清除当前施放，已付费用和冷却保持原值。
        ActiveCast active = progress.getActiveCast();
        TechniqueProgress stopped = progress.toBuilder().clearActiveCast().build();
        Any payload = TechniqueState.pack(stopped);
        return BodyActivity.newBuilder()
                .addStopActions(active.getActionInstanceId())
                .putProgress(TechniqueState.KEY, payload)
                .build();
    }

    static BodyActivity learn(TechniqueProgress progress, KnownTechnique known) {
        // 学会只改写本库附属进度，不触碰身体资源、动作或其他附属状态。
        TechniqueProgress learned = progress.toBuilder()
                .addKnown(known)
                .build();
        Any payload = TechniqueState.pack(learned);
        return BodyActivity.newBuilder()
                .putProgress(TechniqueState.KEY, payload)
                .build();
    }

    private static ActiveCast activeCast(Start start) {
        // 固定本次边界与原始请求，目录卸载、升级和重启都不改变收势行为。
        long started = start.body().settledAt().elapsedMillis();
        CastPhases phases = start.definition().getPhases();
        long warmupEnd = addTime(started, phases.getWarmupMillis());
        long activeEnd = addTime(warmupEnd, phases.getActiveMillis());
        long recoveryEnd = addTime(activeEnd, phases.getRecoveryMillis());
        String encodedCastId = TechniqueValues.encodeCastId(start.request().getCastId());
        return ActiveCast.newBuilder()
                .setRequest(start.request())
                .setStartedMillis(started)
                .setWarmupEndMillis(warmupEnd)
                .setActiveEndMillis(activeEnd)
                .setRecoveryEndMillis(recoveryEnd)
                .setActionInstanceId("technique." + encodedCastId)
                .setBinding(start.binding())
                .setInterruptibleByDamage(phases.getInterruptibleByDamage())
                .build();
    }

    private static ActivityAction action(TechniqueDefinition technique, ActiveCast active, List<BoundSlot> slots) {
        // 槽位可共用同一模块，动作占用取并集，不能产生重复组织或器官引用。
        var tissues = new LinkedHashSet<String>();
        var organs = new LinkedHashSet<String>();
        for (BoundSlot slot : slots) {
            if (slot.getModule() == ModuleKind.TISSUE) {
                tissues.addAll(slot.getModuleIdsList());
            } else if (slot.getModule() == ModuleKind.ORGAN) {
                organs.addAll(slot.getModuleIdsList());
            }
        }
        BodyMessages.ActionDefinition definition = BodyMessages.ActionDefinition.newBuilder()
                .setId(technique.getId())
                .setVersion(technique.getVersion())
                .setDisplayName("功法施放：" + technique.getId())
                .addAllTissueIds(tissues)
                .addAllOrganIds(organs)
                .build();
        return ActivityAction.newBuilder()
                .setInstanceId(active.getActionInstanceId())
                .setDefinition(definition)
                .build();
    }

    private static Accounts accounts(BodySnapshot body, List<QiCost> costs, Map<String, BoundSlot> slots) {
        // 多项费用可命中同一经脉，先在临时余额中连续扣除，最终只提交一个账户变更。
        Map<String, MeridianState> states = body.meridianStates().stream()
                .collect(Collectors.toMap(state -> state.id().value(), Function.identity()));
        var replacements = new LinkedHashMap<String, QiInventory>();
        var released = new ArrayList<QiInventory>();
        for (QiCost cost : costs) {
            BoundSlot slot = slots.get(cost.getSlotId());
            for (CostAllocation allocation : allocations(cost, slot)) {
                String moduleId = allocation.moduleId();
                MeridianState state = states.get(moduleId);
                if (state.opening().stage() != MeridianOpening.Stage.OPENED) {
                    throw TechniqueErrors.rejected(MERIDIAN_NOT_OPENED);
                }
                QiInventory previous = replacements.getOrDefault(moduleId, state.lumenQi());
                QiInventory debit = selectCost(previous, cost, allocation.amount());
                replacements.put(moduleId, QiAccounting.debit(previous, debit));
                released.add(debit);
            }
        }
        var changes = new ArrayList<QiAccountChange>();
        for (var replacement : replacements.entrySet()) {
            String moduleId = replacement.getKey();
            QiAccountChange change = QiAccountChange.newBuilder()
                    .setLumenId(moduleId)
                    .setExpected(BodyProtobuf.toInventory(states.get(moduleId).lumenQi()))
                    .setReplacement(BodyProtobuf.toInventory(replacement.getValue()))
                    .build();
            changes.add(change);
        }
        return new Accounts(List.copyOf(changes), QiInventory.combine(released));
    }

    private static List<CostAllocation> allocations(QiCost cost, BoundSlot slot) {
        // EVEN 使用稳定的经脉顺序，最后一条吸收浮点余数，确保总扣费仍等于声明总量。
        List<String> moduleIds = slot.getModuleIdsList().stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        double amount = cost.getAmount().getValue();
        return switch (cost.getDistribution()) {
            case EACH -> moduleIds.stream()
                    .map(moduleId -> new CostAllocation(moduleId, amount))
                    .toList();
            case EVEN -> evenAllocations(moduleIds, amount);
            default -> throw TechniqueErrors.rejected(INVALID_STATE);
        };
    }

    private static List<CostAllocation> evenAllocations(List<String> moduleIds, double amount) {
        // 与目录共用实际份额计算，余数固定归字典序最后的经脉。
        List<Double> shares = TechniqueQiCosts.evenShares(amount, moduleIds.size());
        var allocations = new ArrayList<CostAllocation>();
        for (int index = 0; index < moduleIds.size(); index++) {
            allocations.add(new CostAllocation(moduleIds.get(index), shares.get(index)));
        }
        return List.copyOf(allocations);
    }

    private static QiInventory selectCost(QiInventory inventory, QiCost cost, double amount) {
        // 只匹配相态和属性，释放保持库存的实际密度，不发生隐式属性转换。
        Qi.QiPhase phase = Qi.QiPhase.valueOf(cost.getPhase().name());
        Qi entry = inventory.entries().stream()
                .filter(qi -> qi.phase() == phase && qi.attribute().id().value().equals(cost.getAttribute()))
                .findFirst()
                .orElseThrow(() -> TechniqueErrors.rejected(INSUFFICIENT_QI));
        if (entry.amount() < amount) {
            throw TechniqueErrors.rejected(INSUFFICIENT_QI);
        }
        Qi selected = entry.withAmount(amount);
        QiInventory debit = new QiInventory(List.of(selected));
        QiAccounting.validateTransferable(debit);
        return debit;
    }

    private static List<ActivityEffort> efforts(Start start, Map<String, BoundSlot> slots,
                                                List<BodyMessages.CapabilityModifier> modifiers) {
        // 力量尚无活动字段，按同一 revision 先校验完整做功时段；活动仍复核功率与耐力。
        var efforts = new ArrayList<ActivityEffort>();
        BodySnapshot candidate = withModifiers(start.body(), modifiers);
        for (WorkCost work : start.definition().getCost().getWorkList()) {
            BoundSlot slot = slots.get(work.getSlotId());
            for (String moduleId : slot.getModuleIdsList()) {
                var effort = ActivityEffort.newBuilder()
                        .setWatts(work.getPower().getValue())
                        .setSeconds(work.getDuration().getValue());
                if (slot.getModule() == ModuleKind.TISSUE) {
                    var load = new TissueCapabilities.Exertion(new Anatomy.TissueId(moduleId),
                            work.getForce().getValue(), work.getPower().getValue(), work.getDuration().getValue());
                    candidate = TissueCapabilities.exert(candidate, load);
                    effort.setTissueId(moduleId);
                } else {
                    effort.setOrganId(moduleId);
                }
                efforts.add(effort.build());
            }
        }
        if (efforts.size() > MAX_EFFORTS) {
            throw TechniqueErrors.rejected(INSUFFICIENT_CAPABILITY);
        }
        return List.copyOf(efforts);
    }

    private static BodySnapshot withModifiers(BodySnapshot body,
                                               List<BodyMessages.CapabilityModifier> modifiers) {
        // 预检力量时纳入同一笔起手活动新增的修正，保持预检和 Framework 执行顺序一致。
        if (modifiers.isEmpty()) {
            return body;
        }
        BodyMessages.BodySnapshot message = BodyProtobuf.toMessage(body).toBuilder()
                .addAllModifiers(modifiers)
                .build();
        return BodyProtobuf.fromMessage(message);
    }

    private static long addTime(long startMillis, long durationMillis) {
        // 身体时钟接近 long 上限时拒绝整次施放，不产生环绕后的免费冷却。
        try {
            return Math.addExact(startMillis, durationMillis);
        } catch (ArithmeticException exception) {
            throw TechniqueErrors.rejected(INVALID_STATE);
        }
    }

    private record Accounts(List<QiAccountChange> changes, QiInventory released) { }

    private record CostAllocation(String moduleId, double amount) { }
}
