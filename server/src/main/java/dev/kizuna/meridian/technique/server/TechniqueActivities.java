package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.*;
import dev.kizuna.meridian.error.MeridianErrors;
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
    private static final String WORK_PRECHECK_CAST_ID = "work_precheck";

    private TechniqueActivities() {
        // 活动依据调用方快照生成，权威状态仍由宿主提交。
    }

    record Start(BodySnapshot body, TechniqueDefinition definition, CastRequest request,
                 TechniqueProgress progress, CastBinding selection,
                 TechniqueAddonCostPayers addonCostPayers) {
        Start(BodySnapshot body, TechniqueDefinition definition, CastRequest request,
              TechniqueProgress progress, CastBinding selection) {
            // 既有直接活动测试不含附属费用，使用空支付方索引。
            this(body, definition, request, progress, selection, TechniqueAddonCostPayers.empty());
        }

        TechniqueBinding binding() {
            // 身体绑定与选手结果使用同一份起手事实。
            return selection.binding();
        }
    }

    record CastBinding(TechniqueBinding binding, Hand hand, Entry entry) {
        CastBinding(TechniqueBinding binding, Hand hand) {
            // 无持物和承接的既有调用保持零偏移语义。
            this(binding, hand, new Entry(0, HeldItemRules.getDefaultInstance()));
        }
    }

    record Entry(long clipStart, String blockedAttackEventId, HeldItemRules heldRules,
                 LegRules legRules, ArmRules armRules) {
        Entry(long clipStart, HeldItemRules heldRules) {
            // 不含肢体打击的起手不带腿法和拳掌规则。
            this(clipStart, "", heldRules, LegRules.getDefaultInstance(), ArmRules.getDefaultInstance());
        }

        Entry(long clipStart, HeldItemRules heldRules, LegRules legRules, ArmRules armRules) {
            // 兼容不消费阻挡事实的普通起手。
            this(clipStart, "", heldRules, legRules, armRules);
        }
    }

    static BodyActivity start(Start start) {
        // 费用、动作和冷却都依据同一个快照 revision，任一校验失败都没有提交。
        BodySnapshot body = start.body();
        TechniqueDefinition definition = start.definition();
        TechniqueBinding binding = start.binding();
        Map<String, BoundSlot> slots = binding.getSlotsList().stream()
                .collect(Collectors.toMap(BoundSlot::getSlotId, Function.identity()));
        Accounts accounts = accounts(body, definition.getCost().getQiList(), slots);
        ActiveCast initialActive = activeCast(start);
        TechniqueScheduler.ModifierPlan modifierPlan = TechniqueScheduler.modifierPlan(definition, initialActive);
        ActiveCast active = initialActive.toBuilder().addAllPendingModifiers(modifierPlan.pending()).build();
        List<BodyMessages.CapabilityModifier> startingModifiers = modifierPlan.starting();
        List<BodyMessages.CapabilityModifier> modifiers = modifierPlan.all();
        List<ActivityEffort> efforts = efforts(start, slots, modifiers);
        TechniqueScheduledWork.validateStart(start, slots, modifiers);
        TechniqueProgress progress = startedProgress(start, active, startingModifiers);
        Any payload = TechniqueState.pack(progress);
        Map<String, Any> addonProgress = addonProgress(start);
        ActivityAction action = action(definition, active, slots.values().stream().toList());
        var plan = BodyActivity.newBuilder()
                .addAllAccounts(accounts.changes())
                .setReleasedQi(BodyProtobuf.toInventory(accounts.released()))
                .addAllEfforts(efforts)
                .addStartActions(action)
                .addAllAddModifiers(startingModifiers)
                .putProgress(TechniqueState.KEY, payload)
                .putAllProgress(addonProgress);
        if (start.progress().hasActiveCast()) {
            plan.addStopActions(start.progress().getActiveCast().getActionInstanceId());
        }
        BodyActivity activity = plan.build();

        // 预检使用 Framework 的纯计算器，失败抛异常；候选结果不写入任何存储。
        BodyActivityExecution.apply(body, activity, TechniqueHost.CALLER_ID);
        return activity;
    }

    private static Map<String, Any> addonProgress(Start start) {
        // 按声明顺序计算附属候选状态，拒绝时尚未构造或提交最终活动。
        BodySnapshot candidate = start.body();
        Map<String, Any> updates = new LinkedHashMap<>();
        for (AddonCost cost : start.definition().getCost().getAddonCostsList()) {
            String key = cost.getKey();
            TechniqueAddonCostPayer payer = start.addonCostPayers().find(key)
                    .orElseThrow(() -> new IllegalStateException("目录未固定附属费用支付方：" + key));
            TechniqueAddonCostPayer.Payment payment = payer.pay(candidate, cost);
            if (!payment.accepted()) {
                throw TechniqueErrors.rejected(INSUFFICIENT_ADDON_COST, payment.reason(),
                        payment.failureTranslationKey());
            }
            Any progress = payment.progress();
            candidate = AddonProgress.replace(candidate, key, progress);
            updates.put(key, progress);
        }
        return Map.copyOf(updates);
    }

    private static TechniqueProgress startedProgress(Start start, ActiveCast active,
                                                     List<BodyMessages.CapabilityModifier> modifiers) {
        // 承接保存前式原边界，新式独立记录正常冷却及起手登记曲线到期。
        TechniqueDefinition definition = start.definition();
        var progress = start.progress().toBuilder()
                .setActiveCast(active)
                .removeGuardBreakEnds(definition.getId())
                .putCurveEnds(definition.getId(), curveEnd(modifiers));
        if (!TechniqueSustained.unknownEnd(active)) {
            long cooldownEnd = addTime(active.getRecoveryEndMillis(), definition.getCooldownMillis());
            progress.putCooldowns(definition.getId(), cooldownEnd);
        }
        if (start.progress().hasActiveCast()) {
            progress.setLastCast(TechniqueFollowUps.history(start.progress().getActiveCast(), CastPhase.FINISHED));
        }
        if (consumesBlockedAttack(start)) {
            progress.clearBlockedAttack();
        }
        return progress.build();
    }

    static TechniqueProgress endedProgress(TechniqueProgress progress, long endedMillis) {
        // 装载清理不赋予新的承接机会，取消承接起手仍保留阻挡窗口以便重试。
        return endedProgress(progress, endedMillis, CastPhase.CANCELLED);
    }

    static TechniqueProgress endedProgress(TechniqueProgress progress, long endedMillis, CastPhase ending) {
        // 提前终止从实际终止时刻计冷却；晚处理的正常收势仍从固定收势边界计时。
        ActiveCast active = progress.getActiveCast();
        LastCast history = endedHistory(active, ending, endedMillis);
        var stopped = progress.toBuilder().clearActiveCast();
        boolean cancelledFollowUp = ending != CastPhase.FINISHED && active.hasConsumedBlockedAttack();
        if (ending == CastPhase.FINISHED || !cancelledFollowUp) {
            stopped.setLastCast(history);
        }
        if (active.hasCooldownMillis()) {
            long cooldownStart = endedMillis;
            if (ending == CastPhase.FINISHED) {
                cooldownStart = active.getRecoveryEndMillis();
            }
            long cooldownEnd = addTime(cooldownStart, active.getCooldownMillis());
            stopped.putCooldowns(active.getRequest().getTechnique().getId(), cooldownEnd);
        }
        if (cancelledFollowUp && !progress.hasBlockedAttack()) {
            stopped.setBlockedAttack(active.getConsumedBlockedAttack());
        }
        return stopped.build();
    }

    private static LastCast endedHistory(ActiveCast active, CastPhase ending, long endedMillis) {
        // 持续式提前终止时只留下实际停止边界，不把未知的零时刻当作历史终点。
        LastCast history = TechniqueFollowUps.history(active, ending);
        if (active.hasSustained() && ending != CastPhase.FINISHED) {
            return history.toBuilder()
                    .setActiveEndMillis(endedMillis)
                    .setRecoveryEndMillis(endedMillis)
                    .build();
        }
        return history;
    }

    static BodyActivity stopAt(TechniqueProgress progress, long endedMillis) {
        // 恢复和卸载默认记为取消，不将中断误认作正常结束。
        return stopAt(progress, endedMillis, CastPhase.CANCELLED);
    }

    static BodyActivity stopAt(TechniqueProgress progress, long endedMillis, CastPhase ending) {
        // 曲线保持原到期时间，只原子更新施放、动作和实际冷却。
        TechniqueProgress stopped = endedProgress(progress, endedMillis, ending);
        Any payload = TechniqueState.pack(stopped);
        ActiveCast active = progress.getActiveCast();
        var plan = BodyActivity.newBuilder()
                .addStopActions(active.getActionInstanceId())
                .putProgress(TechniqueState.KEY, payload);
        TechniqueGuardStamina.stopOccupancy(active, plan);
        return plan.build();
    }

    static BodyActivity learn(TechniqueProgress progress, List<KnownTechnique> known) {
        // 学会只改写本库附属进度，不触碰身体资源、动作或其他附属状态。
        TechniqueProgress learned = progress.toBuilder()
                .addAllKnown(known)
                .build();
        Any payload = TechniqueState.pack(learned);
        return BodyActivity.newBuilder()
                .putProgress(TechniqueState.KEY, payload)
                .build();
    }

    private static ActiveCast activeCast(Start start) {
        // 固定本次边界与原始请求，目录卸载、升级和重启都不改变收势行为。
        CastTiming timing = castTiming(start);
        ActiveCast.Builder active = activeBuilder(start, timing);
        addOptionalCastRules(active, start);
        return active.build();
    }

    private static CastTiming castTiming(Start start) {
        // 起手只读取一次组织动作速度，并把缩放后的阶段边界冻结进活动存档。
        BodySnapshot body = start.body();
        long started = body.settledAt().elapsedMillis();
        CastPhases phases = start.definition().getPhases();
        long clipStart = start.selection().entry().clipStart();
        double tempo = TechniqueTempo.multiplier(body, start.binding().getSlotsList());
        long warmupMillis = remainingWarmup(phases.getWarmupMillis(), clipStart);
        long warmupEnd = addTime(started, TechniqueTempo.scale(warmupMillis, tempo));
        if (TechniqueSustained.enabled(start.definition())) {
            return new CastTiming(started, clipStart, warmupEnd, 0, 0, tempo);
        }
        long activeEnd = addTime(warmupEnd, TechniqueTempo.scale(phases.getActiveMillis(), tempo));
        long recoveryEnd = addTime(activeEnd, TechniqueTempo.scale(phases.getRecoveryMillis(), tempo));
        return new CastTiming(started, clipStart, warmupEnd, activeEnd, recoveryEnd, tempo);
    }

    private static ActiveCast.Builder activeBuilder(Start start, CastTiming timing) {
        // 活动主体保存起手请求、费用序号和效果时序，供重启后继续使用同一事实。
        TechniqueDefinition definition = start.definition();
        CastPhases phases = definition.getPhases();
        CastRequest request = start.request();
        CastBinding selection = start.selection();
        Entry entry = selection.entry();
        long startedMillis = timing.startedMillis();
        long clipStartMillis = timing.clipStartMillis();
        double tempo = timing.tempo();
        String encodedCastId = TechniqueValues.encodeCastId(request.getCastId());
        ActiveCast.Builder active = ActiveCast.newBuilder()
                .setRequest(request)
                .setStartedMillis(startedMillis)
                .setWarmupEndMillis(timing.warmupEndMillis())
                .setActiveEndMillis(timing.activeEndMillis())
                .setRecoveryEndMillis(timing.recoveryEndMillis())
                .setActionInstanceId("technique." + encodedCastId)
                .setBinding(start.binding())
                .setInterruptibleByDamage(phases.getInterruptibleByDamage())
                .setSelectedHand(selection.hand())
                .setMovementSpeedMultiplier(TechniqueMovement.multiplier(definition))
                .setTempoMultiplier(tempo)
                .setAllowUseWhileCasting(definition.getAllowUseWhileCasting())
                .setAllowOffHandUseWhileCasting(definition.getAllowOffHandUseWhileCasting())
                .addAllOffHandUseBlockTags(definition.getOffHandUseBlockTagsList())
                .setCooldownMillis(definition.getCooldownMillis())
                .setClipStartMillis(clipStartMillis)
                .addAllPaidWorkSequences(TechniqueScheduledWork.startingSequences(definition, clipStartMillis))
                .setHeldItemRules(entry.heldRules())
                .addAllPresentations(TechniqueScheduler.presentations(definition, clipStartMillis, tempo))
                .addAllPredecessorCleanup(predecessorCleanup(start.progress(), startedMillis));
        if (consumesBlockedAttack(start)) {
            active.setConsumedBlockedAttack(start.progress().getBlockedAttack());
        }
        return active;
    }

    private static boolean consumesBlockedAttack(Start start) {
        // 承接只暂存与本次计划匹配的事实，普通起手不触碰阻挡状态。
        String eventId = start.selection().entry().blockedAttackEventId();
        if (eventId.isEmpty() || !start.progress().hasBlockedAttack()) {
            return false;
        }
        return start.progress().getBlockedAttack().getEventId().equals(eventId);
    }

    private static void addOptionalCastRules(ActiveCast.Builder active, Start start) {
        // 只有目录明确声明的附加规则才写入活动，缺省状态保持协议兼容。
        TechniqueDefinition definition = start.definition();
        Entry entry = start.selection().entry();
        if (TechniqueSustained.enabled(definition)) {
            active.setSustained(TechniqueSustained.freeze(definition, active.getTempoMultiplier()));
        }
        if (TechniqueLimbStrikes.usesLegs(definition)) {
            active.setLegRules(entry.legRules());
        }
        if (TechniqueLimbStrikes.usesArms(definition)) {
            active.setArmRules(entry.armRules());
        }
        if (definition.hasLockTarget()) {
            active.setLockTarget(definition.getLockTarget());
        }
        if (definition.hasTargetLockDistance()) {
            active.setTargetLockDistance(definition.getTargetLockDistance());
        }
    }

    private record CastTiming(long startedMillis, long clipStartMillis, long warmupEndMillis,
                              long activeEndMillis, long recoveryEndMillis, double tempo) {
    }

    private static long remainingWarmup(long warmupMillis, long clipStart) {
        // 承接只能跳过预备段，负值代表损坏的目录或承接记录，不能截成零继续施放。
        long remaining = warmupMillis - clipStart;
        if (remaining < 0) {
            throw new IllegalArgumentException("承接跳过的预备时长超过起手时长");
        }
        return remaining;
    }

    private static List<EffectIntent> predecessorCleanup(TechniqueProgress progress, long started) {
        // 服务已确认更早的清理回执，只保存直接前式以限制连续承接的存档大小。
        if (!progress.hasActiveCast()) {
            return List.of();
        }
        ActiveCast previous = progress.getActiveCast();
        return TechniqueScheduler.stopEffects(previous, started, started).stream()
                .filter(effect -> effect.getCastId().equals(previous.getRequest().getCastId())).toList();
    }

    static long curveEnd(List<BodyMessages.CapabilityModifier> modifiers) {
        // 已登记修正不提前撤销，重施必须等待最晚曲线到期。
        long latest = 0;
        for (BodyMessages.CapabilityModifier modifier : modifiers) {
            long duration = modifier.getCurve(modifier.getCurveCount() - 1).getOffsetMillis();
            latest = Math.max(latest, addTime(modifier.getStartMillis(), duration));
        }
        return latest;
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

    static boolean affordable(BodySnapshot body, TechniqueDefinition definition, TechniqueBinding binding) {
        // 预检复用起手的费用分配和扣账计算，不提交账户或改变权威版本。
        Map<String, BoundSlot> slots = binding.getSlotsList().stream()
                .collect(Collectors.toMap(BoundSlot::getSlotId, Function.identity()));
        try {
            accounts(body, definition.getCost().getQiList(), slots);
            return true;
        } catch (TechniqueErrors.Rejected rejected) {
            return false;
        }
    }

    static boolean workable(BodySnapshot body, TechniqueDefinition definition, TechniqueBinding binding) {
        // 按不承接、从头起手的边界，与起手同序预检起手做功和全部时间线做功；
        // 承接会跳过的预备段也算上，所以只会比真实起手更严。候选身体不提交，也不改变权威版本。
        CastRequest request = CastRequest.newBuilder()
                .setBodyId(body.id().value())
                .setTechnique(TechniqueRegistry.reference(definition))
                .setCastId(WORK_PRECHECK_CAST_ID)
                .build();
        var selection = new CastBinding(binding, Hand.HAND_UNSPECIFIED);
        var start = new Start(body, definition, request, TechniqueProgress.getDefaultInstance(), selection);
        Map<String, BoundSlot> slots = binding.getSlotsList().stream()
                .collect(Collectors.toMap(BoundSlot::getSlotId, Function.identity()));
        try {
            List<BodyMessages.CapabilityModifier> modifiers =
                    TechniqueScheduler.modifierPlan(definition, activeCast(start)).all();
            efforts(start, slots, modifiers);
            TechniqueScheduledWork.validateStart(start, slots, modifiers);
            return true;
        } catch (TechniqueErrors.Rejected | MeridianErrors.CapabilityRejected
                 | MeridianErrors.IntakeRejected | MeridianErrors.TransportRejected rejected) {
            return false;
        }
    }

    private static Accounts accounts(BodySnapshot body, List<QiCost> costs, Map<String, BoundSlot> slots) {
        // 器官缓存与经脉内腔可有相同局部 ID，同种账户的多笔费用只提交一次余额替换。
        var originals = new LinkedHashMap<AccountId, QiInventory>();
        var replacements = new LinkedHashMap<AccountId, QiInventory>();
        var released = new ArrayList<QiInventory>();
        var namesByKind = new LinkedHashMap<ModuleKind, Map<String, String>>();
        for (QiCost cost : costs) {
            BoundSlot slot = slots.get(cost.getSlotId());
            ModuleKind kind = slot.getModule();
            Map<String, String> names = namesByKind.computeIfAbsent(kind, module -> accountNames(body, module));
            Function<String, QiInventory> balance = id -> {
                var account = new AccountId(kind, id);
                var original = originals.computeIfAbsent(account, key -> key.inventory(body));
                return replacements.getOrDefault(account, original);
            };
            for (CostAllocation allocation : allocations(cost, slot, balance, names)) {
                String moduleId = allocation.moduleId();
                QiInventory previous = balance.apply(moduleId);
                var payer = new TechniqueQiPayment.Payer(TechniqueNames.meridians(readable(List.of(moduleId), names)),
                        slot.getModuleIdsCount() > 1);
                QiInventory debit = TechniqueQiPayment.select(previous, cost, allocation.amount(), payer);
                replacements.put(new AccountId(kind, moduleId), QiAccounting.debit(previous, debit));
                released.add(debit);
            }
        }
        return new Accounts(accountChanges(originals, replacements), QiInventory.combine(released));
    }

    private static List<QiAccountChange> accountChanges(Map<AccountId, QiInventory> originals,
                                                         Map<AccountId, QiInventory> replacements) {
        // 全部费用可付后才按每个账户的原库存与最终余额生成活动。
        var changes = new ArrayList<QiAccountChange>();
        for (var replacement : replacements.entrySet()) {
            var account = replacement.getKey();
            var change = account.change()
                    .setExpected(BodyProtobuf.toInventory(originals.get(account)))
                    .setReplacement(BodyProtobuf.toInventory(replacement.getValue()))
                    .build();
            changes.add(change);
        }
        return List.copyOf(changes);
    }

    private static Map<String, String> accountNames(BodySnapshot body, ModuleKind kind) {
        // 余额不足时显示实际付款模块的名字。
        var names = new LinkedHashMap<String, String>();
        if (kind == ModuleKind.ORGAN) {
            for (var organ : body.structure().organs()) {
                names.put(organ.id().value(), TechniqueScrollText.safeName(organ.displayName(), ""));
            }
            return names;
        }
        for (Anatomy.MeridianDefinition meridian : body.structure().meridians()) {
            names.put(meridian.id().value(), TechniqueScrollText.safeName(meridian.displayName(), ""));
        }
        return names;
    }

    private static List<String> readable(List<String> moduleIds, Map<String, String> names) {
        // 按经脉 ID 的顺序取名字，说明里先写的总是同一条。
        return moduleIds.stream().map(moduleId -> names.getOrDefault(moduleId, ""))
                .filter(name -> !name.isEmpty()).toList();
    }

    private static MeridianState opened(MeridianState state) {
        // 扣费前确认经脉仍已开拓；按余额分摊也先检查全部绑定经脉，再计算可付余额。
        if (state.opening().stage() != MeridianOpening.Stage.OPENED) {
            throw TechniqueErrors.rejected(MERIDIAN_NOT_OPENED);
        }
        return state;
    }

    private static List<CostAllocation> allocations(QiCost cost, BoundSlot slot,
                                                    Function<String, QiInventory> balance,
                                                    Map<String, String> names) {
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
            case BY_BALANCE -> balanceAllocations(cost, moduleIds, balance, names);
            default -> throw TechniqueErrors.rejected(INVALID_STATE);
        };
    }

    private static List<CostAllocation> balanceAllocations(QiCost cost, List<String> moduleIds,
                                                           Function<String, QiInventory> balance,
                                                           Map<String, String> names) {
        // 各经脉按可付余额比例分担；没有可付余额的经脉不分摊，合计不足时与单脉不足给出同一原因。
        double amount = cost.getAmount().getValue();
        var payable = new LinkedHashMap<String, Double>();
        for (String moduleId : moduleIds) {
            double available = TechniqueQiPayment.payable(balance.apply(moduleId), cost).stream()
                    .mapToDouble(Qi::amount)
                    .sum();
            if (available >= QiAccounting.MIN_TRANSFER_QI) {
                payable.put(moduleId, available);
            }
        }
        double total = payable.values().stream().mapToDouble(Double::doubleValue).sum();
        if (payable.isEmpty() || total < amount) {
            throw TechniqueErrors.rejected(TechniqueQiPayment.shortage(cost),
                    TechniqueQiPayment.shortageReason(cost, amount, total,
                            new TechniqueQiPayment.Payer(TechniqueNames.meridians(readable(moduleIds, names)), false),
                            moduleIds.stream().map(balance).toList()));
        }
        // 余额最大的经脉（同额取字典序靠前者）吸收余数和不足最小转移量的小份额。
        String largest = payable.entrySet().stream()
                .max(Map.Entry.<String, Double>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .orElseThrow()
                .getKey();
        var allocations = new ArrayList<CostAllocation>();
        double assigned = 0;
        for (Map.Entry<String, Double> entry : payable.entrySet()) {
            double share = Math.min(entry.getValue(), amount * entry.getValue() / total);
            if (entry.getKey().equals(largest) || share < QiAccounting.MIN_TRANSFER_QI) {
                continue;
            }
            allocations.add(new CostAllocation(entry.getKey(), share));
            assigned += share;
        }
        // total ≥ amount 时余数至多超出最大余额一个最小转移量级，截断只舍去这类零头和浮点尾差。
        double rest = Math.min(payable.get(largest), amount - assigned);
        allocations.add(new CostAllocation(largest, rest));
        return List.copyOf(allocations);
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

    private static List<ActivityEffort> efforts(Start start, Map<String, BoundSlot> slots,
                                                List<BodyMessages.CapabilityModifier> modifiers) {
        // 原有 cost.work 仍在起手支付，时间线做功由开始事件独立提交。
        TechniqueDefinition definition = start.definition();
        var costs = new ArrayList<>(definition.getCost().getWorkList());
        for (int sequence : TechniqueScheduledWork.startingSequences(definition,
                start.selection().entry().clipStart())) {
            costs.add(definition.getEffects(sequence).getWork());
        }
        return efforts(withModifiers(start.body(), modifiers), costs, slots);
    }

    static List<ActivityEffort> efforts(BodySnapshot body, List<WorkCost> costs, Map<String, BoundSlot> slots) {
        // 力量尚无活动字段，按同一 revision 先校验完整做功时段；活动仍复核功率与耐力。
        var efforts = new ArrayList<ActivityEffort>();
        BodySnapshot candidate = body;
        for (WorkCost work : costs) {
            BoundSlot slot = slots.get(work.getSlotId());
            for (String moduleId : slot.getModuleIdsList()) {
                var effort = ActivityEffort.newBuilder()
                        .setWatts(work.getPower().getValue())
                        .setSeconds(work.getDuration().getValue())
                        .setTraining(work.getTraining());
                if (slot.getModule() == ModuleKind.TISSUE) {
                    candidate = exertTissue(candidate, moduleId, work);
                    effort.setTissueId(moduleId);
                } else {
                    requireOrganWork(candidate, moduleId, work);
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

    private static BodySnapshot exertTissue(BodySnapshot body, String tissueId, WorkCost work) {
        // 组织做功沿用 Framework 预检；出不了这份力时说出是哪块筋肉、要多大功。
        var load = new TissueCapabilities.Exertion(new Anatomy.TissueId(tissueId),
                work.getForce().getValue(), work.getPower().getValue(), work.getDuration().getValue());
        try {
            return TissueCapabilities.exert(body, load);
        } catch (MeridianErrors.CapabilityRejected exception) {
            String name = body.structure().tissues().stream().filter(tissue -> tissue.id().value().equals(tissueId))
                    .map(Anatomy.TissueDefinition::displayName).findFirst().orElse("");
            String force = work.getForce().getValue() > 0
                    ? TechniqueScrollText.decimalText(work.getForce().getValue()) + " N、" : "";
            throw TechniqueErrors.rejected(INSUFFICIENT_CAPABILITY, TechniqueScrollText.safeName(name, "筋肉")
                    + "出不了这份力：要 " + force + workText(work));
        }
    }

    private static void requireOrganWork(BodySnapshot body, String organId, WorkCost work) {
        // 器官起手前按 Framework 同一做功预算预检，免得活动提交时只剩一句「过于疲惫」。
        var id = new Anatomy.OrganId(organId);
        OrganCapabilities.WorkBudget budget = OrganCapabilities.workBudget(body, id);
        if (budget.allows(work.getPower().getValue(), work.getDuration().getValue())) {
            return;
        }
        String name = body.structure().organs().stream().filter(organ -> organ.id().equals(id))
                .map(Anatomy.OrganDefinition::displayName).findFirst().orElse("");
        throw TechniqueErrors.rejected(INSUFFICIENT_CAPABILITY, TechniqueScrollText.safeName(name, "器官")
                + "没有余力：要 " + workText(work));
    }

    private static String workText(WorkCost work) {
        // 功率和时长按目录原值写出，例如「10 W × 2 s」。
        return TechniqueScrollText.decimalText(work.getPower().getValue()) + " W × "
                + TechniqueScrollText.decimalText(work.getDuration().getValue()) + " s";
    }

    static BodySnapshot withModifiers(BodySnapshot body,
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

    private record AccountId(ModuleKind kind, String id) {
        QiInventory inventory(BodySnapshot body) {
            // 只从所绑定的器官缓存或已开经脉内腔支付。
            if (kind == ModuleKind.ORGAN) {
                return body.organStates().stream().filter(state -> state.id().value().equals(id))
                        .findFirst().orElseThrow().buffer();
            }
            var channel = body.meridianStates().stream().filter(state -> state.id().value().equals(id))
                    .findFirst().orElseThrow();
            return opened(channel).lumenQi();
        }

        QiAccountChange.Builder change() {
            // 活动写回与读余额相同的账户种类和模块。
            var change = QiAccountChange.newBuilder();
            if (kind == ModuleKind.ORGAN) {
                return change.setOrganId(id);
            }
            return change.setLumenId(id);
        }
    }

    private record Accounts(List<QiAccountChange> changes, QiInventory released) { }

    private record CostAllocation(String moduleId, double amount) { }
}
