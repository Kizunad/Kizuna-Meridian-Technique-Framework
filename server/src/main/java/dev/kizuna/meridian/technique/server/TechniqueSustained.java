package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.HashSet;

/** 持续施放的定义、冻结边界与收势活动；权威状态只保存在身体进度。 */
final class TechniqueSustained {
    private static final int MAX_TAGS = 16;
    private static final double FULL_ANGLE = 360;

    private TechniqueSustained() {
        // 纯规则不持有宿主会话。
    }

    static boolean enabled(TechniqueDefinition definition) {
        // 未声明模式的旧目录保持有限施放。
        return definition.getPhases().getActiveDuration() == ActiveDuration.ACTIVE_DURATION_UNTIL_RELEASE;
    }

    static boolean unknownEnd(ActiveCast active) {
        // 零结束边界只在持续且尚未接受收势时表示未知。
        return active.hasSustained() && !active.getSustained().hasEndRequestedMillis();
    }

    static long boundary(ActiveCast active, long previousMillis, long nowMillis) {
        // 迟到推进仍逐个经过真实阶段边界，零长度阶段不制造通知。
        if (!active.hasSustained()) {
            return nowMillis;
        }
        long boundary = nowMillis;
        if (active.getWarmupEndMillis() > previousMillis) {
            boundary = Math.min(boundary, active.getWarmupEndMillis());
        }
        if (!unknownEnd(active)) {
            if (active.getActiveEndMillis() > previousMillis) {
                boundary = Math.min(boundary, active.getActiveEndMillis());
            }
            if (active.getRecoveryEndMillis() > previousMillis) {
                boundary = Math.min(boundary, active.getRecoveryEndMillis());
            }
        }
        return boundary;
    }

    static void validateDefinition(TechniqueDefinition definition) {
        // 持续、守效果与独立 AI 角色成组；普通式不开放新的持续表现枚举。
        boolean sustained = enabled(definition);
        long guards = definition.getEffectsList().stream().filter(TechniqueEffect::hasGuard).count();
        boolean guardRole = definition.getAiHints().getSupport().getRole() == AiRole.AI_ROLE_GUARD;
        if (!sustained) {
            boolean activePresentation = definition.getEffectsList().stream().anyMatch(effect ->
                    effect.hasPresentation()
                            && effect.getPresentation().getDuration() == PresentationDuration.UNTIL_ACTIVE_END);
            if (guards != 0 || guardRole || activePresentation) {
                throw TechniqueErrors.invalid("guard", "守及生效段表现只允许用于持续施放");
            }
            return;
        }
        if (definition.getCategory() != Category.DEFENSE || guards != 1 || !guardRole
                || definition.hasFollowUp()) {
            throw TechniqueErrors.invalid("guard", "持续守式须为防御类别、独立守角色且只有一个守效果，不能承接");
        }
        SupportAiHints support = definition.getAiHints().getSupport();
        if (support.hasOrder() || support.hasMinimumFatigueRatio() || support.hasRequiresMeleeWeapon()
                || support.hasRefreshBeforeExpiryMillis()) {
            throw TechniqueErrors.invalid("guard.support", "守角色不使用普通辅助调度参数");
        }
        for (TechniqueEffect effect : definition.getEffectsList()) {
            validateEffect(definition, effect);
        }
    }

    private static void validateEffect(TechniqueDefinition definition, TechniqueEffect effect) {
        // 只开放有界的起手表现、自身声音和守声明，不把持续模式扩展成无限伤害。
        long warmup = definition.getPhases().getWarmupMillis();
        if (effect.getAtMillis() > warmup) {
            throw TechniqueErrors.invalid("guard.effects", "持续式效果不能晚于起手结束");
        }
        if (effect.hasGuard()) {
            validateGuard(effect.getGuard());
            if (effect.getAtMillis() != warmup) {
                throw TechniqueErrors.invalid("guard.at_millis", "守效果必须位于起手结束");
            }
        } else if (!effect.hasPresentation()
                && !(effect.hasSound() && effect.getSound().getAnchor() == SoundAnchor.CASTER)) {
            throw TechniqueErrors.invalid("guard.effects", "持续式只允许守、表现与自身声音");
        }
    }

    static void validateGuard(Guard guard) {
        // 标签只检查公共身份格式，实际伤害类型归伤害适配器解释。
        TechniqueValues.positive(guard.getAngle(), Unit.DEGREE, FULL_ANGLE, "guard.angle");
        TechniqueValues.quantity(guard.getDamageMultiplier(), Unit.RATIO, 0, 1, "guard.damage_multiplier");
        if (guard.hasStamina()) {
            var stamina = guard.getStamina();
            validatePositive(stamina.getJoulesPerSecond(), TechniqueValues.MAX_QUANTITY);
            validatePositive(stamina.getJoulesPerDamagePrevented(), TechniqueValues.MAX_QUANTITY);
            validatePositive(stamina.getRestartRatio(), 1);
            validatePositive(stamina.getBreakMillis(), TechniqueValues.MAX_MILLIS);
        }
        var tags = new HashSet<String>();
        if (guard.getDamageTagsCount() == 0 || guard.getDamageTagsCount() > MAX_TAGS) {
            throw TechniqueErrors.invalid("guard.damage_tags", "伤害标签数量超限或为空");
        }
        for (String tag : guard.getDamageTagsList()) {
            TechniqueValues.id(tag, "guard.damage_tags");
            if (!tags.add(tag)) {
                throw TechniqueErrors.invalid("guard.damage_tags", "伤害标签重复");
            }
        }
    }

    private static void validatePositive(double value, double maximum) {
        // 缺省为零的字段也在目录载入时拒绝。
        if (!Double.isFinite(value) || value <= 0 || value > maximum) {
            throw TechniqueErrors.invalid("guard.stamina", "守式体力参数须为范围内的有限正数");
        }
    }

    static SustainedCast freeze(TechniqueDefinition definition, double tempo) {
        // 起手冻结参数，目录变化不改变已支付的施放。
        Guard guard = definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasGuard)
                .findFirst()
                .orElseThrow()
                .getGuard();
        long recovery = TechniqueTempo.scale(definition.getPhases().getRecoveryMillis(), tempo);
        return SustainedCast.newBuilder()
                .setRecoveryMillis(recovery)
                .setGuard(guard)
                .build();
    }

    static void validateActive(ActiveCast active) {
        // 有请求的两个边界必须能由首次接受时刻精确重算。
        SustainedCast sustained = active.getSustained();
        TechniqueValues.millis(sustained.getRecoveryMillis(), "sustained.recovery_millis");
        validateGuard(sustained.getGuard());
        if (sustained.hasStaminaPaidMillis() && (!sustained.getGuard().hasStamina()
                || sustained.getStaminaPaidMillis() < active.getWarmupEndMillis()
                || sustained.hasEndRequestedMillis() && sustained.getStaminaPaidMillis() > active.getActiveEndMillis())
                || sustained.getExhausted() && (!sustained.hasEndRequestedMillis()
                    || !sustained.getGuard().hasStamina())
                || sustained.hasWeaponContactOnBreak() && !sustained.getExhausted()) {
            throw TechniqueErrors.invalid("sustained.stamina", "守式结算时刻或破防原因与施放边界不符");
        }
        long warmup = active.getWarmupEndMillis() - active.getStartedMillis();
        TechniqueValues.millis(warmup, "sustained.warmup_millis");
        long finiteDuration = warmup + sustained.getRecoveryMillis();
        if (finiteDuration == 0 || finiteDuration > TechniqueValues.MAX_MILLIS
                || active.getClipStartMillis() != 0 || active.getPendingModifiersCount() != 0
                || active.getPaidWorkSequencesCount() != 0) {
            throw TechniqueErrors.invalid("sustained", "持续施放有限阶段或未完成效果非法");
        }
        if (unknownEnd(active)) {
            if (active.getActiveEndMillis() != 0 || active.getRecoveryEndMillis() != 0) {
                throw TechniqueErrors.invalid("sustained", "未收势时不能保存预估结束边界");
            }
            return;
        }
        long requested = sustained.getEndRequestedMillis();
        long activeEnd = Math.max(active.getWarmupEndMillis(), requested);
        long recoveryEnd = addTime(activeEnd, sustained.getRecoveryMillis());
        if (requested < active.getStartedMillis() || active.getActiveEndMillis() != activeEnd
                || active.getRecoveryEndMillis() != recoveryEnd) {
            throw TechniqueErrors.invalid("sustained", "收势时刻与已保存边界不一致");
        }
    }

    static BodyActivity release(BodySnapshot body, long requestedMillis) {
        // 收势时解除守式的肌肉占用，施放动作本身仍留到收势结束。
        TechniqueProgress progress = TechniqueState.read(body);
        return release(progress, requestedMillis, progress.getActiveCast().getSustained());
    }

    static BodyActivity release(TechniqueProgress progress, long requestedMillis, SustainedCast guard) {
        // 松手和破防共用确定边界的事务，破防另外保存身体时钟截止。
        ActiveCast active = progress.getActiveCast();
        SustainedCast sustained = guard.toBuilder()
                .setEndRequestedMillis(requestedMillis)
                .build();
        long activeEnd = Math.max(active.getWarmupEndMillis(), requestedMillis);
        long recoveryEnd = addTime(activeEnd, sustained.getRecoveryMillis());
        ActiveCast released = active.toBuilder()
                .setSustained(sustained)
                .setActiveEndMillis(activeEnd)
                .setRecoveryEndMillis(recoveryEnd)
                .build();
        long cooldownEnd = addTime(recoveryEnd, active.getCooldownMillis());
        var updated = progress.toBuilder()
                .setActiveCast(released)
                .putCooldowns(active.getRequest().getTechnique().getId(), cooldownEnd);
        if (guard.getExhausted()) {
            long deadline = addTime(requestedMillis, guard.getGuard().getStamina().getBreakMillis());
            updated.putGuardBreakEnds(active.getRequest().getTechnique().getId(), deadline);
        }
        var plan = BodyActivity.newBuilder().putProgress(TechniqueState.KEY, TechniqueState.pack(updated.build()));
        TechniqueGuardStamina.stopOccupancy(active, plan);
        return plan.build();
    }

    private static long addTime(long start, long duration) {
        // 溢出是非法边界，不能把环绕结果提交给 Framework。
        try {
            return Math.addExact(start, duration);
        } catch (ArithmeticException overflow) {
            throw TechniqueErrors.invalid("sustained", "结束时刻超出身体时钟范围");
        }
    }
}
