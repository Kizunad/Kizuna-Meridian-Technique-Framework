package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUp;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUpRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LastCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.ALREADY_CASTING;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.FOLLOW_UP_REQUIRED;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.FOLLOW_UP_UNAVAILABLE;

/** 承接窗口只依据身体时钟与最近施放事实，不接受客户端预输入。 */
final class TechniqueFollowUps {
    private TechniqueFollowUps() {
        // 玩家施放和 AI 共用同一窗口判定，避免两套承接规则漂移。
    }

    static void validate(TechniqueDefinition definition, Map<TechniqueReference, TechniqueDefinition> definitions) {
        // 承接引用精确版本，跳过范围必须完全位于后式起手内。
        FollowUpRules rules = definition.getFollowUp();
        int transitionCount = rules.getTransitionsCount();
        if (transitionCount > TechniqueValues.MAX_EFFECTS
                || (rules.getOnlyFollowUp() && transitionCount == 0)) {
            throw TechniqueErrors.invalid("follow_up", "承接声明数量非法或只能承接却没有前式");
        }
        var previous = new HashSet<TechniqueReference>();
        for (FollowUp transition : rules.getTransitionsList()) {
            TechniqueReference reference = transition.getPrevious();
            TechniqueProtocol.validateReference(reference);
            long startMillis = transition.getStartMillis();
            if (!definitions.containsKey(reference) || reference.equals(TechniqueRegistry.reference(definition))
                    || !previous.add(reference) || !transition.hasAfterRecoveryMillis()
                    || !transition.hasStartMillis() || startMillis < 0
                    || startMillis >= definition.getPhases().getWarmupMillis()) {
                throw TechniqueErrors.invalid(
                        "follow_up",
                        "前式不存在、重复、自引用或起播时刻不在起手段内");
            }
            TechniqueValues.quantity(transition.getRecoveryStartRatio(), Unit.RATIO, 0, 1, "recovery_start_ratio");
            TechniqueValues.millis(transition.getAfterRecoveryMillis(), "after_recovery_millis");
        }
    }

    static Optional<FollowUp> available(TechniqueDefinition definition, TechniqueProgress progress, long now) {
        // 当前施放优先；存在当前施放时不能退回更早的历史制造承接窗口。
        LastCast previous;
        if (progress.hasActiveCast()) {
            ActiveCast active = progress.getActiveCast();
            CastPhase phase = TechniqueScheduler.phase(active, now);
            if (phase != CastPhase.RECOVERING && phase != CastPhase.FINISHED) {
                return Optional.empty();
            }
            previous = history(active, CastPhase.FINISHED);
        } else {
            LastCast last = progress.getLastCast();
            if (!progress.hasLastCast() || last.getEnding() != CastPhase.FINISHED) {
                return Optional.empty();
            }
            previous = last;
        }
        for (FollowUp transition : definition.getFollowUp().getTransitionsList()) {
            if (transition.getPrevious().equals(previous.getTechnique()) && inWindow(previous, transition, now)) {
                return Optional.of(transition);
            }
        }
        return Optional.empty();
    }

    static long clipStart(TechniqueDefinition definition, TechniqueProgress progress, CastRequest request, long now) {
        // AUTO 在合法窗口内自动承接；显式承接在窗外失败，普通式仍可单独起手。
        Optional<FollowUp> transition = available(definition, progress, now);
        if (transition.isPresent()) {
            return transition.get().getStartMillis();
        }
        if (request.getEntry() == CastEntry.FOLLOW_UP) {
            throw TechniqueErrors.rejected(FOLLOW_UP_UNAVAILABLE);
        }
        if (definition.getFollowUp().getOnlyFollowUp()) {
            throw TechniqueErrors.rejected(FOLLOW_UP_REQUIRED);
        }
        if (progress.hasActiveCast()) {
            throw TechniqueErrors.rejected(ALREADY_CASTING);
        }
        return 0;
    }

    static LastCast history(ActiveCast active, CastPhase ending) {
        // 保存原计划边界，提前承接也不改变前式正常冷却和承接时窗。
        return LastCast.newBuilder()
                .setTechnique(active.getRequest().getTechnique())
                .setCastId(active.getRequest().getCastId())
                .setActiveEndMillis(active.getActiveEndMillis())
                .setRecoveryEndMillis(active.getRecoveryEndMillis())
                .setEnding(ending)
                .build();
    }

    private static boolean inWindow(LastCast previous, FollowUp transition, long now) {
        // 窗口两端包含边界；用减法比较结束后时长避免长整型时钟溢出。
        long activeEnd = previous.getActiveEndMillis();
        long recoveryEnd = previous.getRecoveryEndMillis();
        long recovery = recoveryEnd - activeEnd;
        long startOffset = (long) Math.ceil(recovery * transition.getRecoveryStartRatio().getValue());
        long start = activeEnd + startOffset;
        if (now < start) {
            return false;
        }
        return now <= recoveryEnd || now - recoveryEnd <= transition.getAfterRecoveryMillis();
    }
}
