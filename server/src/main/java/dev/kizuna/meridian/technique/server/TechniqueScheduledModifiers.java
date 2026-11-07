package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CapabilityModifier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.CurvePoint;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;

import java.util.ArrayList;
import java.util.List;

/** 到时登记的曲线与待办进度共同提交，终止施放不会留下尚未登记的能力变化。 */
final class TechniqueScheduledModifiers {
    private static final String REQUEST_PREFIX = "technique_modifier_";

    private TechniqueScheduledModifiers() {
        // 待办只保存在身体附属进度，不建立独立调度队列。
    }

    static BodySnapshot applyDue(TechniqueHost host, BodySnapshot body, long boundaryMillis) {
        // 当前边界以前的待办一次提交，响应丢失后重新 inspect 即可识别已经登记的曲线。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            return body;
        }
        TechniqueProgress progress = TechniqueState.read(body);
        ActiveCast active = progress.getActiveCast();
        var due = new ArrayList<CapabilityModifier>();
        var pending = new ArrayList<CapabilityModifier>();
        for (CapabilityModifier modifier : active.getPendingModifiersList()) {
            if (modifier.getStartMillis() <= boundaryMillis) {
                due.add(modifier);
            } else {
                pending.add(modifier);
            }
        }
        if (due.isEmpty()) {
            return body;
        }
        BodyActivity activity = activity(body, progress, due, pending);
        String requestId = REQUEST_PREFIX + active.getRequest().getCastId()
                + "_" + active.getPendingModifiersCount();
        long revision = body.revision();
        TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(requestId, revision, activity));
        if (commit.revision() != revision + 1 || !commit.activity().equals(activity)) {
            throw new IllegalStateException("宿主返回的曲线登记回执与计划不一致");
        }
        return host.inspect().body();
    }

    static BodySnapshot settleBeforeStop(TechniqueHost host, BodySnapshot body) {
        // 登记失败不能阻止取消；丢响应时重新读取身体以保留实际上已经提交的曲线。
        try {
            return applyDue(host, body, body.settledAt().elapsedMillis());
        } catch (TechniqueErrors.HostRejected | MeridianErrors.IntakeRejected | IllegalArgumentException exception) {
            return host.inspect().body();
        }
    }

    static long boundary(ActiveCast active, long previousMillis, long nowMillis) {
        // 迟到推进逐个停在登记时刻，保证更早曲线先于后续做功或世界效果生效。
        if (previousMillis > nowMillis) {
            throw new IllegalArgumentException("曲线登记时刻倒退");
        }
        long next = nowMillis;
        for (CapabilityModifier modifier : active.getPendingModifiersList()) {
            long scheduledMillis = modifier.getStartMillis();
            if (scheduledMillis > previousMillis && scheduledMillis <= nowMillis) {
                next = Math.min(next, scheduledMillis);
            }
        }
        return next;
    }

    private static BodyActivity activity(BodySnapshot body, TechniqueProgress progress,
                                         List<CapabilityModifier> due, List<CapabilityModifier> pending) {
        // 曲线先于同刻做功加入；已登记曲线的到期限制与清除待办保持同一事务。
        ActiveCast active = progress.getActiveCast();
        ActiveCast updatedActive = active.toBuilder()
                .clearPendingModifiers()
                .addAllPendingModifiers(pending)
                .build();
        String techniqueId = active.getRequest().getTechnique().getId();
        long curveEnd = Math.max(progress.getCurveEndsOrDefault(techniqueId, 0), TechniqueActivities.curveEnd(due));
        TechniqueProgress updated = progress.toBuilder()
                .setActiveCast(updatedActive)
                .putCurveEnds(techniqueId, curveEnd)
                .build();
        var payload = TechniqueState.pack(updated);
        BodyActivity activity = BodyActivity.newBuilder()
                .addAllAddModifiers(remainingCurves(due, body.settledAt().elapsedMillis()))
                .putProgress(TechniqueState.KEY, payload)
                .build();
        // Framework 复核组织、容量和来源；失败抛异常且保留待办。
        BodyActivityExecution.apply(body, activity, TechniqueHost.CALLER_ID);
        return activity;
    }

    private static List<CapabilityModifier> remainingCurves(List<CapabilityModifier> due, long nowMillis) {
        // 迟到不能回写过去时钟，也不能把完整曲线后移而延长增益或虚脱。
        var remaining = new ArrayList<CapabilityModifier>();
        for (CapabilityModifier modifier : due) {
            long duration = modifier.getCurve(modifier.getCurveCount() - 1).getOffsetMillis();
            long elapsed = nowMillis - modifier.getStartMillis();
            if (elapsed <= 0) {
                remaining.add(modifier);
            } else if (elapsed < duration) {
                remaining.add(remainingCurve(modifier, nowMillis, elapsed));
            }
        }
        return List.copyOf(remaining);
    }

    private static CapabilityModifier remainingCurve(CapabilityModifier modifier, long nowMillis, long elapsed) {
        // 在当前线性段插入当前值，其余拐点与原绝对到期时刻保持不变。
        var points = new ArrayList<CurvePoint>();
        for (int index = 1; index < modifier.getCurveCount(); index++) {
            CurvePoint right = modifier.getCurve(index);
            if (right.getOffsetMillis() <= elapsed) {
                continue;
            }
            if (points.isEmpty()) {
                CurvePoint left = modifier.getCurve(index - 1);
                double fraction = (double) (elapsed - left.getOffsetMillis())
                        / (right.getOffsetMillis() - left.getOffsetMillis());
                double value = left.getValue() + (right.getValue() - left.getValue()) * fraction;
                points.add(point(0, value));
            }
            points.add(point(right.getOffsetMillis() - elapsed, right.getValue()));
        }
        return modifier.toBuilder()
                .setStartMillis(nowMillis)
                .clearCurve()
                .addAllCurve(points)
                .build();
    }

    private static CurvePoint point(long offsetMillis, double value) {
        // Framework 允许首点非零，用当前插值表达迟到后的剩余曲线。
        return CurvePoint.newBuilder()
                .setOffsetMillis(offsetMillis)
                .setValue(value)
                .build();
    }
}
