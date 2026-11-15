package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUp;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.FollowUpRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LastCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ProjectileWeaponCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueProgress;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.ALREADY_CASTING;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.FOLLOW_UP_REQUIRED;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.FOLLOW_UP_UNAVAILABLE;

/**
 * 承接窗口只依据身体时钟与最近施放事实，不采信客户端报的时间。
 * 按早了的按键可以由服务端替玩家记一小会儿（输入缓冲），到窗口打开时由服务端按自己的时钟再试，判定仍在这里。
 */
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
            TechniqueDefinition previousDefinition = definitions.get(reference);
            if (previousDefinition == null || reference.equals(TechniqueRegistry.reference(definition))
                    || !previous.add(reference) || !transition.hasAfterRecoveryMillis()
                    || !transition.hasStartMillis() || startMillis < 0
                    || startMillis >= definition.getPhases().getWarmupMillis()) {
                throw TechniqueErrors.invalid(
                        "follow_up",
                        "前式不存在、重复、自引用或起播时刻不在起手段内");
            }
            TechniqueValues.quantity(transition.getRecoveryStartRatio(), Unit.RATIO, 0, 1, "recovery_start_ratio");
            TechniqueValues.millis(transition.getAfterRecoveryMillis(), "after_recovery_millis");
            validateWeaponGroup(transition, previousDefinition, definition);
        }
    }

    private static void validateWeaponGroup(FollowUp transition, TechniqueDefinition previous,
                                             TechniqueDefinition next) {
        // 弓弩换兵承接显式记录前后类别，普通近战承接继续省略这两个字段。
        boolean hasPrevious = transition.hasPreviousProjectileWeapon();
        boolean hasNext = transition.hasNextProjectileWeapon();
        if (hasPrevious != hasNext) {
            throw TechniqueErrors.invalid("follow_up.projectile_weapon", "前后武器类别必须同时声明");
        }
        boolean crossesProjectileWeapons = previous.hasProjectileWeapon() && next.hasProjectileWeapon()
                && previous.getProjectileWeapon() != next.getProjectileWeapon();
        if (crossesProjectileWeapons && !hasPrevious) {
            throw TechniqueErrors.invalid("follow_up.projectile_weapon", "换兵承接必须声明前后武器类别");
        }
        if (!hasPrevious) {
            return;
        }
        validateWeaponCategory(transition.getPreviousProjectileWeapon(), "previous_projectile_weapon");
        validateWeaponCategory(transition.getNextProjectileWeapon(), "next_projectile_weapon");
        if (!previous.hasProjectileWeapon()
                || previous.getProjectileWeapon() != transition.getPreviousProjectileWeapon()
                || !next.hasProjectileWeapon()
                || next.getProjectileWeapon() != transition.getNextProjectileWeapon()) {
            throw TechniqueErrors.invalid("follow_up.projectile_weapon", "承接类别与前后功法不一致");
        }
    }

    private static void validateWeaponCategory(ProjectileWeaponCategory category, String path) {
        // 未指定和未知值都不能作为换兵分组。
        if (category == ProjectileWeaponCategory.PROJECTILE_WEAPON_CATEGORY_UNSPECIFIED
                || category == ProjectileWeaponCategory.UNRECOGNIZED) {
            throw TechniqueErrors.invalid("follow_up." + path, "武器类别必须是弓或弩");
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
            if (transition.getPrevious().equals(previous.getTechnique())
                    && inWindow(previous, transition, now)) {
                return Optional.of(transition);
            }
        }
        return Optional.empty();
    }

    /** 收招段：最后一次出效果以后再过这么久，前式剩下的收招才可以被下一式顶掉（留两 tick，保证那次效果已经发出去）。 */
    static final long WIND_DOWN_MARGIN_MILLIS = 100;

    /**
     * 前式从这一刻（相对它的起手）起进入收招段：出招段已经过了、效果也都出完了，只剩身体收回来。
     * 目录里已经没有前式时为空，那就只能等它走完。
     */
    static OptionalLong windDownStart(ActiveCast active, Optional<TechniqueDefinition> definition) {
        if (definition.isEmpty()) {
            return OptionalLong.empty();
        }
        long effectsEnd = TechniqueScheduler.effectsEnd(definition.get(), active) + WIND_DOWN_MARGIN_MILLIS;
        return OptionalLong.of(Math.max(active.getActiveEndMillis() - active.getStartedMillis(), effectsEnd));
    }

    static long clipStart(TechniqueDefinition definition, TechniqueProgress progress, CastRequest request, long now,
                          OptionalLong windDownStart) {
        // AUTO 在合法窗口内自动承接；显式承接在窗外失败，普通式仍可单独起手。
        // 前式已经在收招段时，别的式也可以直接起手：前式的收招到此为止，它的冷却照原计划算。
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
        if (progress.hasActiveCast() && (windDownStart.isEmpty()
                || now - progress.getActiveCast().getStartedMillis() < windDownStart.getAsLong())) {
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
        long recoveryEnd = previous.getRecoveryEndMillis();
        if (now < windowOpen(previous.getActiveEndMillis(), recoveryEnd, transition)) {
            return false;
        }
        return now <= recoveryEnd || now - recoveryEnd <= transition.getAfterRecoveryMillis();
    }

    static long windowOpen(long activeEnd, long recoveryEnd, FollowUp transition) {
        // 收势过了声明的比例才开窗。
        long recovery = recoveryEnd - activeEnd;
        return activeEnd + (long) Math.ceil(recovery * transition.getRecoveryStartRatio().getValue());
    }
}
