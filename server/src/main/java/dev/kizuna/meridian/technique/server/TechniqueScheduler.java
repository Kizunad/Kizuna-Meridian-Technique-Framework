package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 施放效果的无宿主调度器；只构造修正和世界效果意图。 */
public final class TechniqueScheduler {
    private static final String MODIFIER_NAMESPACE = "meridian_technique";
    private static final String MODIFIER_SOURCE = TechniqueHost.CALLER_ID;
    private static final long BEFORE_START_OFFSET = -1;

    private TechniqueScheduler() {
        // 调度不保存游标，调用方以自己的事件回执记录上次分发时刻。
    }

    public enum EndState {
        RUNNING,
        RECOVERY_DUE
    }

    /** 一次时钟推进中到期的世界效果，以及是否应该提交收势。 */
    public record Due(List<EffectIntent> effects, EndState endState) {
        public Due {
            // 结果与调度器本身都不持有可变列表，重复查询不会改变已经生成的意图。
            effects = List.copyOf(effects);
            if (endState == null) {
                throw new IllegalArgumentException("效果调度结束状态缺失");
            }
        }

        public boolean recoveryDue() {
            // 用命名状态表达收势边界，调用方不需要解释裸布尔参数。
            return endState == EndState.RECOVERY_DUE;
        }
    }

    public static List<BodyMessages.CapabilityModifier> modifiers(TechniqueDefinition definition,
                                                                    ActiveCast active) {
        // 每个组织都获得独立 ID，曲线从起手时钟加上效果偏移开始运行。
        Map<String, BoundSlot> slots = new HashMap<>();
        for (BoundSlot slot : active.getBinding().getSlotsList()) {
            slots.put(slot.getSlotId(), slot);
        }
        var modifiers = new ArrayList<BodyMessages.CapabilityModifier>();
        String encodedCastId = TechniqueValues.encodeCastId(active.getRequest().getCastId());
        int sequence = 0;
        for (TechniqueEffect original : definition.getEffectsList()) {
            Optional<TechniqueEffect> projected = project(original, active.getClipStartMillis());
            if (projected.isEmpty() || !projected.get().hasTissueModifier()) {
                continue;
            }
            TechniqueEffect effect = projected.get();
            TissueModifier tissueModifier = effect.getTissueModifier();
            BoundSlot bound = slots.get(tissueModifier.getSlotId());
            if (bound == null || bound.getModule() != ModuleKind.TISSUE) {
                throw new IllegalArgumentException("修正效果引用了无效组织槽位");
            }
            long startMillis = addTime(active.getStartedMillis(), effect.getAtMillis());
            for (String tissueId : bound.getModuleIdsList()) {
                String modifierId = MODIFIER_NAMESPACE + ":cast/" + encodedCastId + "/" + sequence;
                modifiers.add(modifier(tissueModifier, modifierId, tissueId, startMillis));
                sequence++;
            }
        }
        return List.copyOf(modifiers);
    }

    public static Due due(TechniqueDefinition definition, ActiveCast active,
                          long previousMillis, long currentMillis) {
        // 使用 (previous, current] 区间，宿主按事件边界推进即可避免重复发送效果。
        if (previousMillis > currentMillis) {
            throw new IllegalArgumentException("效果调度时刻倒退");
        }
        long startedMillis = active.getStartedMillis();
        long recoveryEndMillis = active.getRecoveryEndMillis();
        long previousOffset = relativeOffset(startedMillis, previousMillis);
        long currentOffset = relativeOffset(startedMillis, currentMillis);
        double movementMultiplier = TechniqueMovement.multiplier(active);
        var effects = new ArrayList<EffectIntent>();
        if (previousMillis < startedMillis && currentMillis >= startedMillis) {
            effects.addAll(active.getPredecessorCleanupList());
        }
        if (movementMultiplier < 1 && previousMillis < startedMillis && currentMillis >= startedMillis) {
            effects.add(movement(active, MovementAction.LOCK));
        }
        effects.addAll(worldEffects(definition, active, previousOffset, currentOffset));
        if (movementMultiplier < 1
                && previousMillis < recoveryEndMillis && currentMillis >= recoveryEndMillis) {
            effects.add(movement(active, MovementAction.UNLOCK));
        }
        EndState endState = EndState.RUNNING;
        if (currentMillis >= recoveryEndMillis) {
            endState = EndState.RECOVERY_DUE;
        }
        return new Due(effects, endState);
    }

    private static List<EffectIntent> worldEffects(TechniqueDefinition definition, ActiveCast active,
                                                   long previousOffset, long currentOffset) {
        // 表现停止按表现声明次序编号，相同内容和时刻的两个效果仍有独立身份。
        var effects = new ArrayList<EffectIntent>();
        int presentationIndex = 0;
        long recoveryOffset = active.getRecoveryEndMillis() - active.getStartedMillis();
        for (int index = 0; index < definition.getEffectsCount(); index++) {
            Optional<TechniqueEffect> projected = project(definition.getEffects(index), active.getClipStartMillis());
            if (projected.isEmpty()) {
                continue;
            }
            TechniqueEffect effect = projected.get();
            // 组织修正走身体活动；目标锚点的声音已并入同刻打击；两者都不单独成为世界意图。
            if (effect.getOperationCase() == TechniqueEffect.OperationCase.TISSUE_MODIFIER
                    || TechniqueSoundCues.targetAnchored(effect)) {
                continue;
            }
            long atMillis = effect.getAtMillis();
            if (atMillis > previousOffset && atMillis <= currentOffset) {
                effects.add(effectIntent(definition, active, effect, index));
            }
            if (effect.getOperationCase() == TechniqueEffect.OperationCase.PRESENTATION
                    && effect.getPresentation().getDuration() == PresentationDuration.UNTIL_RECOVERY_END
                    && recoveryOffset > previousOffset
                    && recoveryOffset <= currentOffset
                    && atMillis <= currentOffset) {
                effects.add(presentationStop(active, effect, presentationIndex));
            }
            if (effect.hasPresentation()) {
                presentationIndex++;
            }
        }
        return List.copyOf(effects);
    }

    public static List<EffectIntent> stopEffects(TechniqueDefinition definition, ActiveCast active,
                                                  long previousMillis, long currentMillis) {
        // 显式提供目录的调用方从该定义获取表现声明，其他状态保持起手时的值。
        ActiveCast saved = active.toBuilder()
                .clearPresentations()
                .addAllPresentations(presentations(definition, active.getClipStartMillis()))
                .build();
        return stopEffects(saved, previousMillis, currentMillis);
    }

    public static List<EffectIntent> stopEffects(ActiveCast active, long previousMillis, long currentMillis) {
        // 只有上一个已确认分发时刻已经达到表现生效点，才允许补发停止意图。
        if (previousMillis > currentMillis) {
            throw new IllegalArgumentException("效果调度时刻倒退");
        }
        long previousOffset = relativeOffset(active.getStartedMillis(), previousMillis);
        var effects = new ArrayList<>(active.getPredecessorCleanupList());
        for (int index = 0; index < active.getPresentationsCount(); index++) {
            TechniqueEffect effect = active.getPresentations(index);
            if (effect.getAtMillis() <= previousOffset) {
                effects.add(presentationStop(active, effect, index));
            }
        }
        if (TechniqueMovement.multiplier(active) < 1) {
            effects.add(movement(active, MovementAction.UNLOCK));
        }
        return List.copyOf(effects);
    }

    public static List<EffectIntent> effectsAt(TechniqueDefinition definition, ActiveCast active,
                                               long millis) {
        // 单点查询把该时刻前一毫秒作为区间起点，适合宿主按事件边界逐次分发。
        long previousMillis = Long.MIN_VALUE;
        if (millis != Long.MIN_VALUE) {
            previousMillis = millis - 1;
        }
        return due(definition, active, previousMillis, millis).effects();
    }

    public static CastPhase phase(ActiveCast active, long millis) {
        // 三段使用半开区间；零时长阶段不会制造虚假的打断窗口。
        if (millis < active.getWarmupEndMillis()) {
            return CastPhase.WARMUP;
        }
        if (millis < active.getActiveEndMillis()) {
            return CastPhase.EFFECTIVE;
        }
        if (millis < active.getRecoveryEndMillis()) {
            return CastPhase.RECOVERING;
        }
        return CastPhase.FINISHED;
    }

    private static BodyMessages.CapabilityModifier modifier(TissueModifier definition,
                                                              String id, String tissueId,
                                                              long startMillis) {
        // Framework 的活动校验会再次检查来源、组织、曲线期限和唯一 ID。
        return BodyMessages.CapabilityModifier.newBuilder()
                .setId(id)
                .setSource(MODIFIER_SOURCE)
                .setTissueId(tissueId)
                .setStartMillis(startMillis)
                .addAllCurve(definition.getCurveList())
                .setStrengthPeak(definition.getStrengthPeak().getValue())
                .setBurstPeak(definition.getBurstPeak().getValue())
                .setEndurancePeak(definition.getEndurancePeak().getValue())
                .setRecoveryPeak(definition.getRecoveryPeak().getValue())
                .build();
    }

    private static EffectIntent effectIntent(TechniqueDefinition definition, ActiveCast active,
                                             TechniqueEffect effect, int index) {
        // 目标和输入方向沿用起手请求；宿主决定实体朝向和实际世界变化。
        EffectIntent.Builder intent = EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setTargetId(active.getRequest().getTargetId())
                .setEffectId("effect_" + index)
                .setScheduledMillis(addTime(active.getStartedMillis(), effect.getAtMillis()))
                .setSelectedHand(active.getSelectedHand())
                .setDirection(active.getRequest().getDirection());
        switch (effect.getOperationCase()) {
            case IMPULSE -> intent.setImpulse(resolvedImpulse(active, effect.getImpulse()));
            case STATUS_BENEFIT -> intent.setStatusBenefit(effect.getStatusBenefit());
            case WEAPON_STRIKE -> {
                List<SoundCue> hitSounds = TechniqueSoundCues.hitSounds(definition, index);
                intent.setWeaponStrike(weaponStrike(active, effect.getWeaponStrike(), hitSounds));
            }
            case BREAK_BLOCKS -> intent.setBreakBlocks(breakBlocks(active, effect.getBreakBlocks()));
            case SOUND -> intent.setSound(effect.getSound());
            case PRESENTATION -> {
                PresentationIntent presentation = PresentationIntent.newBuilder()
                        .setId(effect.getPresentation().getId())
                        .setAction(presentationAction(effect.getPresentation().getDuration()))
                        .setStartMillis(effect.getPresentation().getStartMillis())
                        .build();
                intent.setPresentation(presentation);
            }
            default -> throw new IllegalArgumentException("组织修正与目标声音不产生独立的世界效果意图");
        }
        return intent.build();
    }

    private static WeaponStrikeIntent weaponStrike(ActiveCast active, WeaponStrike strike,
                                                   List<SoundCue> hitSounds) {
        // 保存起手的书级规则，打击时由宿主结合当前物品解释；目标声音随该击在每个受伤目标处播放。
        return WeaponStrikeIntent.newBuilder()
                .setStrike(strike)
                .setHeldItemRules(active.getHeldItemRules())
                .addAllHitSounds(hitSounds)
                .build();
    }

    private static BreakBlocksIntent breakBlocks(ActiveCast active, BreakBlocks blocks) {
        // 劈门也带同一规则，宿主可在当前手持物品不合格时直接落空。
        return BreakBlocksIntent.newBuilder()
                .setBlocks(blocks)
                .setHeldItemRules(active.getHeldItemRules())
                .build();
    }

    private static Impulse resolvedImpulse(ActiveCast active, Impulse impulse) {
        // INPUT 没有水平输入时采用声明的默认方向，未声明时向前。
        if (impulse.getDirection() != ImpulseDirection.INPUT) {
            return impulse;
        }
        HorizontalDirection direction = active.getRequest().getDirection();
        if (direction.getX() != 0 || direction.getZ() != 0) {
            return impulse;
        }
        ImpulseDirection fallback = ImpulseDirection.FORWARD;
        if (impulse.hasInputDefaultDirection()) {
            fallback = impulse.getInputDefaultDirection();
        }
        return impulse.toBuilder()
                .setDirection(fallback)
                .clearInputDefaultDirection()
                .build();
    }

    private static EffectIntent presentationStop(ActiveCast active, TechniqueEffect effect, int index) {
        // 收势或提前终止时按原表现身份发停止意图，停止不携带剪辑起播时刻。
        PresentationIntent presentation = PresentationIntent.newBuilder()
                .setId(effect.getPresentation().getId())
                .setAction(PresentationAction.STOP)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setTargetId(active.getRequest().getTargetId())
                .setDirection(active.getRequest().getDirection())
                .setEffectId("presentation_stop_" + index)
                .setScheduledMillis(active.getRecoveryEndMillis())
                .setSelectedHand(active.getSelectedHand())
                .setPresentation(presentation)
                .build();
    }

    private static EffectIntent movement(ActiveCast active, MovementAction action) {
        // 沿用限速与清理的稳定身份，倍率冻结于施放快照。
        var movement = MovementIntent.newBuilder().setAction(action);
        if (action == MovementAction.LOCK) {
            movement.setMovementSpeedMultiplier(TechniqueMovement.multiplier(active));
        }
        String effectId = "movement_lock";
        if (action == MovementAction.UNLOCK) {
            effectId = "movement_unlock";
        }
        return EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setEffectId(effectId)
                .setScheduledMillis(movementTime(active, action))
                .setSelectedHand(active.getSelectedHand())
                .setMovement(movement)
                .build();
    }

    private static long movementTime(ActiveCast active, MovementAction action) {
        // 清理效果保留计划边界，提前终止重试也使用相同载荷和身份。
        if (action == MovementAction.LOCK) {
            return active.getStartedMillis();
        }
        return active.getRecoveryEndMillis();
    }

    static List<TechniqueEffect> presentations(TechniqueDefinition definition, long clipStart) {
        // 保存投影后的持续表现，缺少目录时仍能用相同序号停止。
        return definition.getEffectsList().stream()
                .map(effect -> project(effect, clipStart))
                .flatMap(Optional::stream)
                .filter(TechniqueEffect::hasPresentation)
                .toList();
    }

    private static Optional<TechniqueEffect> project(TechniqueEffect effect, long clipStart) {
        // 预备段世界效果和修正不发；跨过起播点的持续剪辑从对应时刻接着播放。
        long originalAt = effect.getAtMillis();
        if (originalAt >= clipStart) {
            return Optional.of(effect.toBuilder().setAtMillis(originalAt - clipStart).build());
        }
        PresentationEffect originalPresentation = effect.getPresentation();
        if (effect.hasPresentation() && originalPresentation.getDuration() == PresentationDuration.UNTIL_RECOVERY_END) {
            PresentationEffect presentation = originalPresentation.toBuilder()
                    .setStartMillis(addTime(originalPresentation.getStartMillis(), clipStart - originalAt))
                    .build();
            TechniqueEffect projected = effect.toBuilder()
                    .setAtMillis(0)
                    .setPresentation(presentation)
                    .build();
            return Optional.of(projected);
        }
        return Optional.empty();
    }

    private static PresentationAction presentationAction(PresentationDuration duration) {
        // 一次性表现立即播放，持续表现由宿主保持到收势边界。
        if (duration == PresentationDuration.ONCE) {
            return PresentationAction.PLAY_ONCE;
        }
        return PresentationAction.START;
    }

    private static long relativeOffset(long startedMillis, long currentMillis) {
        // 起手前返回哨兵，其余时刻换算为相对起手的偏移，供事件边界比较。
        if (currentMillis < startedMillis) {
            return BEFORE_START_OFFSET;
        }
        return currentMillis - startedMillis;
    }

    private static long addTime(long startMillis, long offsetMillis) {
        // 身体时钟接近 long 上限时拒绝生成无法落账的修正起点。
        try {
            return Math.addExact(startMillis, offsetMillis);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("效果起点超出身体时钟范围", exception);
        }
    }
}
