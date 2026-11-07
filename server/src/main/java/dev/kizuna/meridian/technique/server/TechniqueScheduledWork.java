package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 时间线只负责选择开始事件，做功与已付进度仍由 Framework 原子保存。 */
final class TechniqueScheduledWork {
    static final long MILLIS_PER_SECOND = 1_000;

    private TechniqueScheduledWork() {
        // 所有计算读取当前身体，不保存独立费用或任务账本。
    }

    static void validateStart(TechniqueActivities.Start start, Map<String, BoundSlot> slots,
                               List<BodyMessages.CapabilityModifier> modifiers) {
        // 起手以当前身体连续预检全部未跳过费用；预检候选不会提交，真元只收一次。
        TechniqueDefinition definition = start.definition();
        long clipStart = start.selection().entry().clipStart();
        BodySnapshot candidate = TechniqueActivities.withModifiers(start.body(), modifiers);
        for (List<WorkCost> costs : costsByStart(definition, clipStart).values()) {
            if (!costs.isEmpty()) {
                var efforts = TechniqueActivities.efforts(candidate, costs, slots);
                BodyActivity activity = BodyActivity.newBuilder().addAllEfforts(efforts).build();
                // 同刻费用受单笔活动上限约束，前段候选疲劳继续影响后段预检。
                candidate = BodyActivityExecution.apply(candidate, activity, TechniqueHost.CALLER_ID);
            }
        }
    }

    private static Map<Long, List<WorkCost>> costsByStart(TechniqueDefinition definition, long clipStart) {
        // 起手费用与恰在起播点开始的段合并，其余段按时刻保留独立活动边界。
        var groups = new TreeMap<Long, List<WorkCost>>();
        groups.put(clipStart, new ArrayList<>(definition.getCost().getWorkList()));
        for (TechniqueEffect effect : definition.getEffectsList()) {
            long atMillis = effect.getAtMillis();
            if (effect.hasWork() && atMillis >= clipStart) {
                groups.computeIfAbsent(atMillis, ignored -> new ArrayList<>()).add(effect.getWork());
            }
        }
        return groups;
    }

    static List<Integer> startingSequences(TechniqueDefinition definition, long clipStart) {
        // 恰在起播点开始的段随起手原子支付，跳过的预备段不补收。
        var sequences = new ArrayList<Integer>();
        for (int index = 0; index < definition.getEffectsCount(); index++) {
            TechniqueEffect effect = definition.getEffects(index);
            if (effect.hasWork() && effect.getAtMillis() == clipStart) {
                sequences.add(index);
            }
        }
        return List.copyOf(sequences);
    }

    static long boundary(TechniqueDefinition definition, ActiveCast active, long previousMillis, long nowMillis) {
        // 有分段做功时逐个事件边界推进，迟到 tick 仍按先做功、后射击的原顺序处理。
        if (previousMillis > nowMillis) {
            throw new IllegalArgumentException("分段做功时刻倒退");
        }
        List<TechniqueEffect> effects = definition.getEffectsList();
        if (effects.stream().noneMatch(TechniqueEffect::hasWork) && active.getPendingModifiersCount() == 0) {
            return nowMillis;
        }
        long startedMillis = active.getStartedMillis();
        long clipStartMillis = active.getClipStartMillis();
        if (previousMillis < startedMillis) {
            return startedMillis;
        }
        for (TechniqueEffect effect : effects) {
            long atMillis = effect.getAtMillis();
            if (atMillis < clipStartMillis) {
                continue;
            }
            long scheduledMillis = startedMillis + atMillis - clipStartMillis;
            if (scheduledMillis > previousMillis && scheduledMillis <= nowMillis) {
                return scheduledMillis;
            }
        }
        return nowMillis;
    }

    static BodySnapshot payDue(TechniqueHost host, TechniqueDefinition definition, BodySnapshot body,
                               long boundaryMillis) {
        // 同刻多段放入同一活动；费用与原目录序号一起提交，回执丢失后可从身体恢复。
        TechniqueProgress progress = TechniqueState.read(body);
        ActiveCast active = progress.getActiveCast();
        long startedMillis = active.getStartedMillis();
        long clipStartMillis = active.getClipStartMillis();
        List<Integer> paidSequences = active.getPaidWorkSequencesList();
        var sequences = new ArrayList<Integer>();
        var costs = new ArrayList<WorkCost>();
        for (int index = 0; index < definition.getEffectsCount(); index++) {
            TechniqueEffect effect = definition.getEffects(index);
            long atMillis = effect.getAtMillis();
            if (!effect.hasWork() || atMillis < clipStartMillis || paidSequences.contains(index)) {
                continue;
            }
            long scheduledMillis = startedMillis + atMillis - clipStartMillis;
            if (scheduledMillis <= boundaryMillis) {
                sequences.add(index);
                costs.add(effect.getWork());
            }
        }
        if (sequences.isEmpty()) {
            return body;
        }
        BodyActivity activity = payment(body, progress, costs, sequences);
        String requestId = "technique_work_" + active.getRequest().getCastId() + "_" + sequences.getFirst();
        long revision = body.revision();
        TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(requestId, revision, activity));
        if (commit.revision() != revision + 1 || !commit.activity().equals(activity)) {
            throw new IllegalStateException("宿主返回的分段做功回执与计划不一致");
        }
        return host.inspect().body();
    }

    private static BodyActivity payment(BodySnapshot body, TechniqueProgress progress,
                                        List<WorkCost> costs, List<Integer> sequences) {
        // 到时读取新的伤势、疲劳和曲线，整段能力不足时不写费用或完成标记。
        ActiveCast active = progress.getActiveCast();
        Map<String, BoundSlot> slots = active.getBinding().getSlotsList().stream()
                .collect(Collectors.toMap(BoundSlot::getSlotId, Function.identity()));
        var efforts = TechniqueActivities.efforts(body, costs, slots);
        ActiveCast paid = active.toBuilder().addAllPaidWorkSequences(sequences).build();
        TechniqueProgress updated = progress.toBuilder().setActiveCast(paid).build();
        var payload = TechniqueState.pack(updated);
        BodyActivity activity = BodyActivity.newBuilder()
                .addAllEfforts(efforts)
                .putProgress(TechniqueState.KEY, payload)
                .build();
        // Framework 再检查当前整段功率、耐力及组织训练资格，失败抛异常。
        BodyActivityExecution.apply(body, activity, TechniqueHost.CALLER_ID);
        return activity;
    }
}
