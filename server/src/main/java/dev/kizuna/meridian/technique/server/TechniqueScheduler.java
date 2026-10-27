package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        int sequence = 0;
        for (TechniqueEffect effect : definition.getEffectsList()) {
            if (effect.getOperationCase() != TechniqueEffect.OperationCase.TISSUE_MODIFIER) {
                continue;
            }
            TissueModifier tissueModifier = effect.getTissueModifier();
            BoundSlot bound = slots.get(tissueModifier.getSlotId());
            if (bound == null || bound.getModule() != ModuleKind.TISSUE) {
                throw new IllegalArgumentException("修正效果引用了无效组织槽位");
            }
            long startMillis = addTime(active.getStartedMillis(), effect.getAtMillis());
            for (String tissueId : bound.getModuleIdsList()) {
                String modifierId = MODIFIER_NAMESPACE + ":" + active.getRequest().getCastId()
                        + "/" + sequence;
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
        long previousOffset = relativeOffset(active.getStartedMillis(), previousMillis);
        long currentOffset = relativeOffset(active.getStartedMillis(), currentMillis);
        var effects = new ArrayList<EffectIntent>();
        for (TechniqueEffect effect : definition.getEffectsList()) {
            if (effect.getOperationCase() == TechniqueEffect.OperationCase.TISSUE_MODIFIER) {
                continue;
            }
            long atMillis = effect.getAtMillis();
            if (atMillis > previousOffset && atMillis <= currentOffset) {
                effects.add(effectIntent(active, effect));
            }
            if (effect.getOperationCase() == TechniqueEffect.OperationCase.PRESENTATION
                    && effect.getPresentation().getDuration() == PresentationDuration.UNTIL_RECOVERY_END
                    && active.getRecoveryEndMillis() > previousMillis
                    && active.getRecoveryEndMillis() <= currentMillis
                    && atMillis <= currentOffset) {
                effects.add(presentationStop(active, effect.getPresentation().getId()));
            }
        }
        EndState endState = EndState.RUNNING;
        if (currentMillis >= active.getRecoveryEndMillis()) {
            endState = EndState.RECOVERY_DUE;
        }
        return new Due(effects, endState);
    }

    public static List<EffectIntent> stopEffects(TechniqueDefinition definition, ActiveCast active,
                                                  long previousMillis, long currentMillis) {
        // 只有上一个已确认分发时刻已经达到表现生效点，才允许补发停止意图。
        if (previousMillis > currentMillis) {
            throw new IllegalArgumentException("效果调度时刻倒退");
        }
        long previousOffset = relativeOffset(active.getStartedMillis(), previousMillis);
        var effects = new ArrayList<EffectIntent>();
        for (TechniqueEffect effect : definition.getEffectsList()) {
            if (effect.getOperationCase() != TechniqueEffect.OperationCase.PRESENTATION) {
                continue;
            }
            PresentationEffect presentation = effect.getPresentation();
            if (presentation.getDuration() == PresentationDuration.UNTIL_RECOVERY_END
                    && effect.getAtMillis() <= previousOffset) {
                effects.add(presentationStop(active, presentation.getId()));
            }
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

    private static EffectIntent effectIntent(ActiveCast active, TechniqueEffect effect) {
        // 目标和输入方向沿用起手请求；宿主决定实体朝向和实际世界变化。
        EffectIntent.Builder intent = EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setTargetId(active.getRequest().getTargetId())
                .setDirection(active.getRequest().getDirection());
        switch (effect.getOperationCase()) {
            case IMPULSE -> intent.setImpulse(resolvedImpulse(active, effect.getImpulse()));
            case PRESENTATION -> {
                PresentationIntent presentation = PresentationIntent.newBuilder()
                        .setId(effect.getPresentation().getId())
                        .setAction(presentationAction(effect.getPresentation().getDuration()))
                        .build();
                intent.setPresentation(presentation);
            }
            default -> throw new IllegalArgumentException("组织修正不产生世界效果意图");
        }
        return intent.build();
    }

    private static Impulse resolvedImpulse(ActiveCast active, Impulse impulse) {
        // INPUT 没有水平输入时明确转成前方，非零输入仍由宿主读取 EffectIntent.direction。
        if (impulse.getDirection() != ImpulseDirection.INPUT) {
            return impulse;
        }
        HorizontalDirection direction = active.getRequest().getDirection();
        if (direction.getX() != 0 || direction.getZ() != 0) {
            return impulse;
        }
        return impulse.toBuilder()
                .setDirection(ImpulseDirection.FORWARD)
                .build();
    }

    private static EffectIntent presentationStop(ActiveCast active, String id) {
        // 持续表现到收势边界统一发停止意图；组织修正不走这条路径。
        PresentationIntent presentation = PresentationIntent.newBuilder()
                .setId(id)
                .setAction(PresentationAction.STOP)
                .build();
        return EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setTargetId(active.getRequest().getTargetId())
                .setDirection(active.getRequest().getDirection())
                .setPresentation(presentation)
                .build();
    }

    private static PresentationAction presentationAction(PresentationDuration duration) {
        // 一次性表现立即播放，持续表现由宿主保持到收势边界。
        if (duration == PresentationDuration.ONCE) {
            return PresentationAction.PLAY_ONCE;
        }
        return PresentationAction.START;
    }

    private static long relativeOffset(long startedMillis, long currentMillis) {
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
