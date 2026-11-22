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
        // 预检使用全部计划曲线，但不会因此把待登记曲线写进权威身体。
        return modifierPlan(definition, active).all();
    }

    record ModifierPlan(List<BodyMessages.CapabilityModifier> starting,
                        List<BodyMessages.CapabilityModifier> pending) {
        List<BodyMessages.CapabilityModifier> all() {
            // 预检候选同时包含起手登记与到时登记的曲线。
            var modifiers = new ArrayList<>(starting);
            modifiers.addAll(pending);
            return List.copyOf(modifiers);
        }
    }

    static ModifierPlan modifierPlan(TechniqueDefinition definition, ActiveCast active) {
        // 每个组织都获得独立 ID，曲线从起手时钟加上效果偏移开始运行。
        Map<String, BoundSlot> slots = boundSlots(active);
        var starting = new ArrayList<BodyMessages.CapabilityModifier>();
        var pending = new ArrayList<BodyMessages.CapabilityModifier>();
        String encodedCastId = TechniqueValues.encodeCastId(active.getRequest().getCastId());
        double tempo = TechniqueTempo.multiplier(active);
        int sequence = 0;
        for (TechniqueEffect original : definition.getEffectsList()) {
            Optional<TechniqueEffect> projected = project(original, active.getClipStartMillis(), tempo);
            if (projected.isEmpty() || !projected.get().hasTissueModifier()) {
                continue;
            }
            TechniqueEffect effect = projected.get();
            TissueModifier tissueModifier = effect.getTissueModifier();
            BoundSlot bound = slots.get(tissueModifier.getSlotId());
            if (bound == null || bound.getModule() != ModuleKind.TISSUE) {
                throw new IllegalArgumentException("修正效果引用了无效组织槽位");
            }
            long effectOffsetMillis = effect.getAtMillis();
            long startMillis = addTime(active.getStartedMillis(), effectOffsetMillis);
            List<BodyMessages.CapabilityModifier> destination;
            if (tissueModifier.getActivation() == ModifierActivation.AT_EFFECT_TIME && effectOffsetMillis > 0) {
                destination = pending;
            } else {
                destination = starting;
            }
            for (String tissueId : bound.getModuleIdsList()) {
                String modifierId = MODIFIER_NAMESPACE + ":cast/" + encodedCastId + "/" + sequence;
                destination.add(modifier(tissueModifier, modifierId, tissueId, startMillis));
                sequence++;
            }
        }
        return new ModifierPlan(List.copyOf(starting), List.copyOf(pending));
    }

    private static Map<String, BoundSlot> boundSlots(ActiveCast active) {
        // 按槽位身份定位本次绑定，不在逐条效果处理中重复扫描。
        var slots = new HashMap<String, BoundSlot>();
        for (BoundSlot slot : active.getBinding().getSlotsList()) {
            slots.put(slot.getSlotId(), slot);
        }
        return slots;
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
        double tempo = TechniqueTempo.multiplier(active);
        var effects = new ArrayList<EffectIntent>();
        if (previousMillis < startedMillis && currentMillis >= startedMillis) {
            effects.addAll(active.getPredecessorCleanupList());
            if (active.hasLockTarget() && active.getLockTarget()) {
                effects.add(targetLock(active));
            }
        }
        if ((movementMultiplier < 1 || active.hasSustained())
                && previousMillis < startedMillis && currentMillis >= startedMillis) {
            effects.add(movement(active, MovementAction.LOCK));
        }
        effects.addAll(worldEffects(definition, active, previousOffset, currentOffset, tempo));
        if (!TechniqueSustained.unknownEnd(active) && (movementMultiplier < 1 || active.hasSustained())
                && (previousMillis < recoveryEndMillis || active.hasSustained())
                && currentMillis >= recoveryEndMillis) {
            effects.add(movement(active, MovementAction.UNLOCK));
        }
        EndState endState = EndState.RUNNING;
        if (!TechniqueSustained.unknownEnd(active) && currentMillis >= recoveryEndMillis) {
            endState = EndState.RECOVERY_DUE;
        }
        return new Due(effects, endState);
    }

    private static List<EffectIntent> worldEffects(TechniqueDefinition definition, ActiveCast active,
                                                   long previousOffset, long currentOffset, double tempo) {
        // 表现停止按表现声明次序编号，相同内容和时刻的两个效果仍有独立身份。
        var effects = new ArrayList<EffectIntent>();
        int presentationIndex = 0;
        for (int index = 0; index < definition.getEffectsCount(); index++) {
            Optional<TechniqueEffect> projected = project(definition.getEffects(index), active.getClipStartMillis(),
                    tempo);
            if (projected.isEmpty()) {
                continue;
            }
            TechniqueEffect effect = projected.get();
            // 修正、做功和附行走身体活动；目标锚点的声音并入同刻打击，不单独成为世界意图。
            if (effect.getOperationCase() == TechniqueEffect.OperationCase.TISSUE_MODIFIER
                    || effect.hasWork()
                    || effect.hasGuard()
                    || effect.hasElementalInfusion()
                    || TechniqueSoundCues.targetAnchored(effect)) {
                continue;
            }
            if (effect.hasBlockWork() || effect.hasOrganRefine()) {
                addPeriodicEffects(effects, new PeriodicContext(definition, active, effect, index,
                        previousOffset, currentOffset, tempo));
                continue;
            }
            long atMillis = effect.getAtMillis();
            if (atMillis > previousOffset && atMillis <= currentOffset) {
                effects.add(effectIntent(new EffectContext(definition, active, effect, index,
                        atMillis, "effect_" + index, tempo)));
            }
            if (presentationEnds(active, effect, previousOffset, currentOffset)) {
                effects.add(presentationStop(active, effect, presentationIndex));
            }
            if (effect.hasPresentation()) {
                presentationIndex++;
            }
        }
        return List.copyOf(effects);
    }

    private static boolean presentationEnds(ActiveCast active, TechniqueEffect effect,
                                            long previousOffset, long currentOffset) {
        // 结束边界未定时不发停止；松开当刻已经推进过也要补交同身份停止。
        if (!effect.hasPresentation() || TechniqueSustained.unknownEnd(active)) {
            return false;
        }
        PresentationDuration duration = effect.getPresentation().getDuration();
        long endMillis;
        if (duration == PresentationDuration.UNTIL_ACTIVE_END) {
            endMillis = active.getActiveEndMillis();
        } else if (duration == PresentationDuration.UNTIL_RECOVERY_END) {
            endMillis = active.getRecoveryEndMillis();
        } else {
            return false;
        }
        long endOffset = endMillis - active.getStartedMillis();
        return endOffset <= currentOffset && effect.getAtMillis() <= currentOffset
                && (endOffset > previousOffset || active.hasSustained());
    }

    private static void addPeriodicEffects(List<EffectIntent> effects, PeriodicContext context) {
        // 每个原始边界单独缩放，避免间隔向上取整的累计误差丢掉最后一次推进。
        TechniqueEffect original = context.definition().getEffects(context.index());
        Period period = period(original);
        long firstOffset = original.getAtMillis() - context.active().getClipStartMillis();
        for (long sequence = 0; sequence < period.count(); sequence++) {
            long originalOffset = firstOffset + sequence * period.intervalMillis();
            long atMillis = TechniqueTempo.scale(originalOffset, context.tempo());
            if (atMillis > context.previousOffset() && atMillis <= context.currentOffset()) {
                String effectId = period.prefix() + context.index() + "_" + sequence;
                effects.add(effectIntent(new EffectContext(context.definition(), context.active(),
                        context.effect(), context.index(), atMillis, effectId, context.tempo())));
            }
        }
    }

    private static Period period(TechniqueEffect original) {
        // block_work 含持续时间终点那一次推进；炼化的窗口终点不再起批。
        if (original.hasOrganRefine()) {
            OrganRefine refine = original.getOrganRefine();
            return new Period(refine.getBatchMillis(), TechniqueOrganRefines.batchCount(refine), "organ_refine_");
        }
        BlockWork work = original.getBlockWork();
        long count = work.getDurationMillis() / work.getIntervalMillis() + 1;
        return new Period(work.getIntervalMillis(), count, "block_work_");
    }

    /**
     * 这一次施放最后一次出效果的时刻（相对起手的毫秒）：之后只剩身体收回来。
     * 表现本身不算；承接跳过的那一段、动作节奏放慢都按这一次施放实际的算。
     */
    public static long effectsEnd(TechniqueDefinition definition, ActiveCast active) {
        double tempo = TechniqueTempo.multiplier(active);
        long clipStart = active.getClipStartMillis();
        long last = 0;
        for (TechniqueEffect original : definition.getEffectsList()) {
            if (original.hasPresentation()) {
                continue;
            }
            long offset = original.getAtMillis() - clipStart;
            if (original.hasBlockWork() || original.hasOrganRefine()) {
                Period period = period(original);
                offset += (period.count() - 1) * period.intervalMillis();
            }
            if (offset >= 0) {
                last = Math.max(last, TechniqueTempo.scale(offset, tempo));
            }
        }
        return last;
    }

    public static List<EffectIntent> stopEffects(TechniqueDefinition definition, ActiveCast active,
                                                  long previousMillis, long currentMillis) {
        // 显式提供目录的调用方从该定义获取表现声明，其他状态保持起手时的值。
        double tempo = TechniqueTempo.multiplier(active);
        ActiveCast saved = active.toBuilder()
                .clearPresentations()
                .addAllPresentations(presentations(definition, active.getClipStartMillis(), tempo))
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
        if (TechniqueMovement.multiplier(active) < 1 || active.hasSustained()) {
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
        if (TechniqueSustained.unknownEnd(active) || millis < active.getActiveEndMillis()) {
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
                .setTempoPeak(tempoPeak(definition))
                .build();
    }

    private static double tempoPeak(TissueModifier definition) {
        // 缺省动作速度峰值表示不改变速度，不能把 optional 的零默认值写进身体。
        if (!definition.hasTempoPeak()) {
            return 1;
        }
        return definition.getTempoPeak().getValue();
    }

    private static EffectIntent.Builder intentHeader(EffectContext context) {
        // 目标和输入方向沿用起手请求；宿主决定实体朝向和实际世界变化。
        ActiveCast active = context.active();
        EffectIntent.Builder intent = EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setTechnique(active.getRequest().getTechnique())
                .setTargetId(active.getRequest().getTargetId())
                .setEffectId(context.effectId())
                .setScheduledMillis(addTime(active.getStartedMillis(), context.scheduledOffset()))
                .setSelectedHand(active.getSelectedHand())
                .setDirection(active.getRequest().getDirection());
        if (active.hasLockTarget()) {
            intent.setLockTarget(active.getLockTarget());
        }
        return intent;
    }

    private static EffectIntent effectIntent(EffectContext context) {
        // 公共头取自起手请求，再按效果种类填一种世界意图。
        TechniqueDefinition definition = context.definition();
        ActiveCast active = context.active();
        TechniqueEffect effect = context.effect();
        int index = context.index();
        EffectIntent.Builder intent = intentHeader(context);
        switch (effect.getOperationCase()) {
            case IMPULSE -> intent.setImpulse(resolvedImpulse(active, effect.getImpulse()));
            case STATUS_BENEFIT -> intent.setStatusBenefit(effect.getStatusBenefit());
            case WEAPON_STRIKE -> {
                List<SoundCue> hitSounds = TechniqueSoundCues.hitSounds(definition, index);
                intent.setWeaponStrike(weaponStrike(active, effect.getWeaponStrike(), hitSounds));
            }
            case LEG_STRIKE -> {
                List<SoundCue> hitSounds = TechniqueSoundCues.hitSounds(definition, index);
                intent.setLegStrike(legStrike(active, effect.getLegStrike(), hitSounds));
            }
            case LIMB_STRIKE -> intent.setLimbStrike(
                    limbStrike(active, effect.getLimbStrike(), TechniqueSoundCues.hitSounds(definition, index)));
            case BREAK_BLOCKS -> intent.setBreakBlocks(breakBlocks(active, effect.getBreakBlocks()));
            case LAUNCH_PROJECTILE -> intent.setLaunchProjectile(
                    launchProjectile(active, effect.getLaunchProjectile()));
            case BLOCK_WORK -> intent.setBlockWork(effect.getBlockWork());
            case GRAPPLE -> intent.setGrapple(TechniqueGrapples.intent(effect.getGrapple(), active.getArmRules()));
            case THROW -> intent.setThrow(TechniqueGrapples.intent(effect.getThrow(), active.getArmRules()));
            case USE_BREW -> intent.setUseBrew(effect.getUseBrew());
            case CONSUME_HELD_ITEM -> intent.setConsumeHeldItem(consumeHeldItem(active));
            case ORGAN_REFINE -> intent.setOrganRefine(
                    TechniqueOrganRefines.intent(active, definition.getEffects(index), context.tempo()));
            case SOUND -> intent.setSound(effect.getSound());
            case PRESENTATION -> intent.setPresentation(presentationIntent(effect.getPresentation(), context.tempo()));
            default -> throw new IllegalArgumentException("组织修正与目标声音不产生独立的世界效果意图");
        }
        return intent.build();
    }

    private static ConsumeHeldItemIntent consumeHeldItem(ActiveCast active) {
        // 整叠物品由宿主处理，策略冻结在起手时的有效持物规则中。
        return ConsumeHeldItemIntent.newBuilder()
                .setStrategy(active.getHeldItemRules().getConsumptionStrategy())
                .build();
    }

    private static PresentationIntent presentationIntent(PresentationEffect presentation, double tempo) {
        // 同一施放的表现与事件使用同一个起手倍率。
        return PresentationIntent.newBuilder()
                .setId(presentation.getId())
                .setAction(presentationAction(presentation.getDuration()))
                .setStartMillis(presentation.getStartMillis())
                .setPlaybackSpeedMultiplier(tempo)
                .build();
    }

    private record PeriodicContext(TechniqueDefinition definition, ActiveCast active,
                                   TechniqueEffect effect, int index, long previousOffset,
                                   long currentOffset, double tempo) {
    }

    private record Period(long intervalMillis, long count, String prefix) {
    }

    private record EffectContext(TechniqueDefinition definition, ActiveCast active,
                                 TechniqueEffect effect, int index, long scheduledOffset,
                                 String effectId, double tempo) {
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

    private static LegStrikeIntent legStrike(ActiveCast active, LegStrike strike,
                                             List<SoundCue> hitSounds) {
        // 腿击只携带书级靴子规则，不携带手持规则或选定手位。
        return LegStrikeIntent.newBuilder()
                .setStrike(strike)
                .setLegRules(active.getLegRules())
                .addAllHitSounds(hitSounds)
                .build();
    }

    private static LimbStrikeIntent limbStrike(ActiveCast active, LimbStrike strike,
                                               List<SoundCue> hitSounds) {
        // 肢击按出力肢带上起手保存的书级规则：出拳掌带拳掌规则，出腿带鞋规则。
        LimbStrikeIntent.Builder intent = LimbStrikeIntent.newBuilder()
                .setStrike(strike)
                .addAllHitSounds(hitSounds);
        if (strike.getLimbCase() == LimbStrike.LimbCase.ARM) {
            intent.setArmRules(active.getArmRules());
        } else {
            intent.setLegRules(active.getLegRules());
        }
        return intent.build();
    }

    private static BreakBlocksIntent breakBlocks(ActiveCast active, BreakBlocks blocks) {
        // 劈门也带同一规则，宿主可在当前手持物品不合格时直接落空。
        return BreakBlocksIntent.newBuilder()
                .setBlocks(blocks)
                .setHeldItemRules(active.getHeldItemRules())
                .build();
    }

    private static LaunchProjectileIntent launchProjectile(ActiveCast active,
                                                            LaunchProjectile projectile) {
        // 射击效果携带起手时冻结的书级持物规则，宿主按选定手执行弹药和方向事实。
        return LaunchProjectileIntent.newBuilder()
                .setProjectile(projectile)
                .setHeldItemRules(active.getHeldItemRules())
                .setCastStartedMillis(active.getStartedMillis())
                .setClipStartMillis(active.getClipStartMillis())
                .build();
    }

    private static EffectIntent targetLock(ActiveCast active) {
        // 目标锁定在起手边界发送一次，宿主按施放者和目标事实保存锁定结果。
        TargetLockIntent targetLock = TargetLockIntent.newBuilder()
                .setMaximumDistance(active.getTargetLockDistance())
                .build();
        return EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setTechnique(active.getRequest().getTechnique())
                .setTargetId(active.getRequest().getTargetId())
                .setEffectId("target_lock")
                .setScheduledMillis(active.getStartedMillis())
                .setSelectedHand(active.getSelectedHand())
                .setDirection(active.getRequest().getDirection())
                .setLockTarget(true /* lockTarget */)
                .setTargetLock(targetLock)
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
                .setTechnique(active.getRequest().getTechnique())
                .setTargetId(active.getRequest().getTargetId())
                .setDirection(active.getRequest().getDirection())
                .setEffectId("presentation_stop_" + index)
                .setScheduledMillis(cleanupTime(active))
                .setSelectedHand(active.getSelectedHand())
                .setPresentation(presentation)
                .build();
    }

    private static EffectIntent movement(ActiveCast active, MovementAction action) {
        // 沿用限速与清理的稳定身份，倍率和右键放行都冻结于施放快照；宿主按 LOCK 判定施放中并拦截原版输入。
        var movement = MovementIntent.newBuilder().setAction(action);
        if (action == MovementAction.LOCK) {
            movement.setMovementSpeedMultiplier(TechniqueMovement.multiplier(active));
            movement.setAllowUseWhileCasting(active.getAllowUseWhileCasting());
            movement.setAllowOffHandUseWhileCasting(active.getAllowOffHandUseWhileCasting());
            movement.addAllOffHandUseBlockTags(active.getOffHandUseBlockTagsList());
        }
        String effectId = "movement_lock";
        if (action == MovementAction.UNLOCK) {
            effectId = "movement_unlock";
        }
        return EffectIntent.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setTechnique(active.getRequest().getTechnique())
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
        return cleanupTime(active);
    }

    private static long cleanupTime(ActiveCast active) {
        // 持续式起手时结束边界未知，停止载荷采用不变的起手时刻保证重试身份一致。
        if (active.hasSustained()) {
            return active.getStartedMillis();
        }
        return active.getRecoveryEndMillis();
    }

    static List<TechniqueEffect> presentations(TechniqueDefinition definition, long clipStart,
                                                double tempo) {
        // 保存投影后的持续表现，缺少目录时仍能用相同序号停止。
        return definition.getEffectsList().stream()
                .map(effect -> project(effect, clipStart, tempo))
                .flatMap(Optional::stream)
                .filter(TechniqueEffect::hasPresentation)
                .toList();
    }

    private static Optional<TechniqueEffect> project(TechniqueEffect effect, long clipStart,
                                                     double tempo) {
        // 预备段世界效果和修正不发；跨过起播点的持续剪辑从对应时刻接着播放。
        long originalAt = effect.getAtMillis();
        if (originalAt >= clipStart) {
            long projectedAt = TechniqueTempo.scale(originalAt - clipStart, tempo);
            TechniqueEffect.Builder projected = effect.toBuilder().setAtMillis(projectedAt);
            if (effect.hasBlockWork()) {
                projected.setBlockWork(TechniqueTempo.scale(effect.getBlockWork(), tempo));
            }
            if (effect.hasGrapple()) {
                projected.setGrapple(TechniqueGrapples.scale(effect.getGrapple(), tempo));
            }
            if (effect.hasThrow()) {
                projected.setThrow(TechniqueGrapples.scale(effect.getThrow(), tempo));
            }
            if (effect.hasPresentation()) {
                PresentationEffect presentation = effect.getPresentation().toBuilder()
                        .setStartMillis(TechniqueTempo.scale(
                                effect.getPresentation().getStartMillis(), tempo))
                        .build();
                projected.setPresentation(presentation);
            }
            return Optional.of(projected.build());
        }
        PresentationEffect originalPresentation = effect.getPresentation();
        if (effect.hasPresentation() && originalPresentation.getDuration() == PresentationDuration.UNTIL_RECOVERY_END) {
            PresentationEffect presentation = originalPresentation.toBuilder()
                    .setStartMillis(TechniqueTempo.scale(
                            addTime(originalPresentation.getStartMillis(), clipStart - originalAt), tempo))
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
