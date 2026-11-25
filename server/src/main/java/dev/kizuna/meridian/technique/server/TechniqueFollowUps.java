package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ActiveCast;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastEntry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastPhase;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttack;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BlockedAttackSource;
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
    static final long BLOCKED_ATTACK_WINDOW_MILLIS = 600;

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
            boolean ordinaryBlockedTransition = isOrdinaryAttack(reference);
            if (!ordinaryBlockedTransition) {
                TechniqueProtocol.validateReference(reference);
            }
            long startMillis = transition.getStartMillis();
            TechniqueDefinition previousDefinition;
            if (ordinaryBlockedTransition) {
                previousDefinition = null;
            } else {
                previousDefinition = definitions.get(reference);
            }
            if ((!ordinaryBlockedTransition && (previousDefinition == null
                    || reference.equals(TechniqueRegistry.reference(definition))
                    || TechniqueSustained.enabled(previousDefinition)
                    || !previous.add(reference))) || (ordinaryBlockedTransition
                    && transition.getBlockedAttackSourcesCount() == 0) || !transition.hasAfterRecoveryMillis()
                    || !transition.hasStartMillis() || startMillis < 0
                    || startMillis >= definition.getPhases().getWarmupMillis()) {
                throw TechniqueErrors.invalid(
                        "follow_up", "前式不存在、重复、自引用或起播时刻不在起手段内");
            }
            TechniqueValues.quantity(transition.getRecoveryStartRatio(), Unit.RATIO, 0, 1, "recovery_start_ratio");
            TechniqueValues.millis(transition.getAfterRecoveryMillis(), "after_recovery_millis");
            validateBlockedAttackSources(transition);
            if (ordinaryBlockedTransition) {
                if (transition.hasPreviousProjectileWeapon() || transition.hasNextProjectileWeapon()) {
                    throw TechniqueErrors.invalid(
                            "follow_up.projectile_weapon", "普通攻击承接不能声明投射武器类别");
                }
            } else {
                validateWeaponGroup(transition, previousDefinition, definition);
            }
        }
    }

    static boolean isOrdinaryAttack(TechniqueReference previous) {
        // 目录用空标识和零版本表示被挡的普通攻击。
        return previous.getId().isEmpty() && previous.getVersion() == 0;
    }

    private static void validateBlockedAttackSources(FollowUp transition) {
        // 来源列表只接受不同的可信阻挡类别，空列表保留普通承接语义。
        var sources = new HashSet<BlockedAttackSource>();
        for (BlockedAttackSource source : transition.getBlockedAttackSourcesList()) {
            if (source == BlockedAttackSource.BLOCKED_ATTACK_SOURCE_UNSPECIFIED
                    || source == BlockedAttackSource.UNRECOGNIZED || !sources.add(source)) {
                throw TechniqueErrors.invalid("follow_up.blocked_attack_sources", "阻挡来源非法或重复");
            }
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
        // 目录展示和 AI 查询与正式起手读取同一承接计划。
        return availableTransition(definition, progress, now).map(AvailableTransition::transition);
    }

    static EntryPlan plan(TechniqueDefinition definition, TechniqueProgress progress, CastRequest request, long now,
                          OptionalLong windDownStart) {
        // 成功承接记录唯一被消费的阻挡事件，普通起手不触碰该事实。
        Optional<AvailableTransition> transition = availableTransition(definition, progress, now);
        if (transition.isPresent()) {
            AvailableTransition available = transition.orElseThrow();
            return new EntryPlan(available.transition().getStartMillis(), available.blockedEventId());
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
        return new EntryPlan(0, "");
    }

    private static Optional<AvailableTransition> availableTransition(TechniqueDefinition definition,
                                                                      TechniqueProgress progress, long now) {
        // 当前施放优先；存在当前施放时不能退回更早的历史制造承接窗口。
        Optional<LastCast> previous = Optional.empty();
        if (progress.hasActiveCast()) {
            ActiveCast active = progress.getActiveCast();
            if (active.hasSustained()) {
                return Optional.empty();
            }
            CastPhase phase = TechniqueScheduler.phase(active, now);
            if (phase != CastPhase.RECOVERING && phase != CastPhase.FINISHED) {
                return Optional.empty();
            }
            previous = Optional.of(history(active, CastPhase.FINISHED));
        } else {
            LastCast last = progress.getLastCast();
            if (progress.hasLastCast() && last.getEnding() == CastPhase.FINISHED) {
                previous = Optional.of(last);
            }
        }
        for (FollowUp transition : definition.getFollowUp().getTransitionsList()) {
            TechniqueReference reference = transition.getPrevious();
            if (!isOrdinaryAttack(reference)) {
                if (previous.isEmpty()) {
                    continue;
                }
                LastCast previousCast = previous.orElseThrow();
                if (!reference.equals(previousCast.getTechnique())
                        || !inWindow(previousCast, transition, now)) {
                    continue;
                }
            }
            Optional<String> blockedEventId = blockedEventId(transition, progress, now);
            if (blockedEventId.isPresent()) {
                return Optional.of(new AvailableTransition(transition, blockedEventId.orElseThrow()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> blockedEventId(FollowUp transition, TechniqueProgress progress, long now) {
        // 有来源约束的承接必须命中攻击者自己的可信事实，600 ms 边界包含在窗口内。
        if (transition.getBlockedAttackSourcesCount() == 0) {
            return Optional.of("");
        }
        if (!progress.hasBlockedAttack()) {
            return Optional.empty();
        }
        BlockedAttack blocked = progress.getBlockedAttack();
        if (now < blocked.getBlockedMillis()
                || now - blocked.getBlockedMillis() > BLOCKED_ATTACK_WINDOW_MILLIS
                || !transition.getBlockedAttackSourcesList().contains(blocked.getSource())) {
            return Optional.empty();
        }
        return Optional.of(blocked.getEventId());
    }

    record EntryPlan(long clipStartMillis, String blockedEventId) { }

    private record AvailableTransition(FollowUp transition, String blockedEventId) { }

    /** 收招段：最后一次出效果以后再过这么久，前式剩下的收招才可以被下一式顶掉（留两 tick，保证那次效果已经发出去）。 */
    static final long WIND_DOWN_MARGIN_MILLIS = 100;

    /**
     * 前式从这一刻（相对它的起手）起进入收招段：出招段已经过了、效果也都出完了，只剩身体收回来。
     * 目录里已经没有前式时为空，那就只能等它走完。
     */
    static OptionalLong windDownStart(ActiveCast active, Optional<TechniqueDefinition> definition) {
        // 持续施放必须完整收势，不能从最后一个效果反推替换时刻。
        if (active.hasSustained() || definition.isEmpty()) {
            return OptionalLong.empty();
        }
        long effectsEnd = TechniqueScheduler.effectsEnd(definition.get(), active) + WIND_DOWN_MARGIN_MILLIS;
        return OptionalLong.of(Math.max(active.getActiveEndMillis() - active.getStartedMillis(), effectsEnd));
    }

    static long clipStart(TechniqueDefinition definition, TechniqueProgress progress, CastRequest request, long now,
                          OptionalLong windDownStart) {
        // AUTO 在合法窗口内自动承接；显式承接在窗外失败，普通式仍可单独起手。
        // 前式已经在收招段时，别的式也可以直接起手：前式的收招到此为止，它的冷却照原计划算。
        return plan(definition, progress, request, now, windDownStart).clipStartMillis();
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
