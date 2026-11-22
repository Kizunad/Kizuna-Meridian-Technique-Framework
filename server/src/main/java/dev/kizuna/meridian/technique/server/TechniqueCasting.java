package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

/** 无身体缓存的施放编排；每次调用都通过可信宿主读取或提交。 */
public final class TechniqueCasting {
    private static final long NO_PREVIOUS_MILLIS = Long.MIN_VALUE;
    private final TechniqueRegistry registry;

    /**
     * 一次可信时钟推进产生的效果意图和可选终止回执。
     *
     * @param infusionBegan 这次推进把附行写进了身体时，是哪一个附行、还剩多久
     */
    public record AdvanceResult(CastOutcome outcome, List<EffectIntent> effects,
                                Optional<TechniqueHost.Commit> recoveryCommit, long advancedMillis,
                                Optional<InfusionBegan> infusionBegan) {
        public AdvanceResult {
            // 调度结果在交给宿主前固定下来，避免调用方修改效果顺序。
            effects = List.copyOf(effects);
            recoveryCommit = Objects.requireNonNull(recoveryCommit);
            Objects.requireNonNull(infusionBegan);
        }

        public AdvanceResult(CastOutcome outcome, List<EffectIntent> effects,
                             Optional<TechniqueHost.Commit> recoveryCommit, long advancedMillis) {
            // 多数推进不写附行。
            this(outcome, effects, recoveryCommit, advancedMillis, Optional.empty());
        }

        private AdvanceResult with(Optional<InfusionBegan> began) {
            // 写附行和收势落在同一个边界时，收势的结果也要带上这条。
            return new AdvanceResult(outcome, effects, recoveryCommit, advancedMillis, began);
        }
    }

    /** 刚写进身体的附行。 */
    public record InfusionBegan(ElementalInfusion infusion, long remainingMillis) { }

    public TechniqueCasting(TechniqueRegistry registry) {
        // 只持有不可变定义，运行状态全部位于宿主的身体与回执中。
        this.registry = Objects.requireNonNull(registry);
    }

    public CastOutcome cast(TechniqueHost host, CastRequest request) {
        // 没有僵直记录的调用方等同于从未受击。
        return cast(host, request, NO_PREVIOUS_MILLIS);
    }

    public CastOutcome cast(TechniqueHost host, CastRequest request, long staggeredUntilMillis) {
        // 请求边界错误与损坏存档分别报告，避免把非法输入写入进度；僵直到期时刻使用身体时钟。
        try {
            TechniqueProtocol.validateCast(request);
        } catch (IllegalArgumentException exception) {
            return failure(request.getCastId(), INVALID_REQUEST);
        }
        try {
            return startOrReplay(host, request, staggeredUntilMillis);
        } catch (TechniqueErrors.Rejected exception) {
            return failure(request.getCastId(), exception);
        } catch (TechniqueErrors.HostRejected exception) {
            return hostFailure(request.getCastId(), exception);
        } catch (MeridianErrors.CapabilityRejected | MeridianErrors.IntakeRejected exception) {
            return failure(request.getCastId(), INSUFFICIENT_CAPABILITY);
        } catch (MeridianErrors.TransportRejected exception) {
            return failure(request.getCastId(), INVALID_STATE);
        } catch (IllegalArgumentException exception) {
            return failure(request.getCastId(), INVALID_STATE);
        }
    }

    private CastOutcome startOrReplay(TechniqueHost host, CastRequest request, long staggeredUntilMillis) {
        // 持久回执与活动施放优先重放；新请求基于同一次宿主查询完成校验、绑定和提交。
        Optional<CastOutcome> previous = replay(host, request);
        if (previous.isPresent()) {
            return previous.get();
        }
        TechniqueHost.Inspection inspection = host.inspect();
        BodySnapshot body = inspection.body();
        if (!body.id().value().equals(request.getBodyId())) {
            throw TechniqueErrors.rejected(BODY_UNAVAILABLE);
        }
        TechniqueProgress progress = TechniqueState.read(body);
        Optional<CastOutcome> activeReplay = replayActive(body, progress, request);
        if (activeReplay.isPresent()) {
            return activeReplay.get();
        }
        TechniqueDefinition definition = requireCastable(body, progress, request, staggeredUntilMillis, inspection);
        OptionalLong windDownStart = OptionalLong.empty();
        if (progress.hasActiveCast()) {
            ActiveCast active = progress.getActiveCast();
            windDownStart = TechniqueFollowUps.windDownStart(active, registry.find(active.getRequest().getTechnique()));
        }
        TechniqueFollowUps.EntryPlan entry = TechniqueFollowUps.plan(definition, progress, request,
                body.settledAt().elapsedMillis(), windDownStart);
        TechniqueActivities.CastBinding selection = bindCast(definition, inspection, entry);
        var start = new TechniqueActivities.Start(body, definition, request, progress, selection);
        BodyActivity activity = TechniqueActivities.start(start);
        return submitStart(host, start, activity);
    }

    private TechniqueActivities.CastBinding bindCast(TechniqueDefinition definition,
                                                      TechniqueHost.Inspection inspection,
                                                      TechniqueFollowUps.EntryPlan entryPlan) {
        // 身体绑定与选手都基于同一次宿主查询，任一失败都不能生成扣费活动。
        Optional<TechniqueBook> book = registry.bookOf(TechniqueRegistry.reference(definition));
        HeldItemRules heldRules = TechniqueHeldItems.rulesFor(registry, definition);
        Hand hand = selectHand(definition, inspection.heldItems(), heldRules);
        BindingOutcome result = TechniqueBinder.bind(inspection.body(), definition,
                hand, inspection.heldItems().getMainHandSide());
        if (!result.hasBinding()) {
            throw TechniqueErrors.rejected(result.getFailure(), result.getReason());
        }
        LegRules legRules = book.map(TechniqueBook::getLegRules).orElse(LegRules.getDefaultInstance());
        ArmRules armRules = book.map(TechniqueBook::getArmRules).orElse(ArmRules.getDefaultInstance());
        var entry = new TechniqueActivities.Entry(entryPlan.clipStartMillis(), entryPlan.blockedEventId(),
                heldRules, legRules, armRules);
        return new TechniqueActivities.CastBinding(result.getBinding(), hand, entry);
    }

    private Hand selectHand(TechniqueDefinition definition, HeldItems items, HeldItemRules rules) {
        // 无书级持物声明的功法沿用空手语义，持物规则只从当前目录获取。
        if (definition.hasBrewItem()) {
            TechniqueBrewEffects.requireHeld(definition.getBrewItem(), items);
            return Hand.MAIN_HAND;
        }
        if (!definition.hasProjectileWeapon() && rules.equals(HeldItemRules.getDefaultInstance())) {
            return Hand.HAND_UNSPECIFIED;
        }
        return TechniqueHeldItems.select(rules, items, definition);
    }

    private TechniqueDefinition requireCastable(BodySnapshot body, TechniqueProgress progress, CastRequest request,
                                                long staggeredUntilMillis, TechniqueHost.Inspection inspection) {
        // 先检查生命、目录与可信知识记录，再检查受击僵直和身体时钟冷却。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw TechniqueErrors.rejected(BODY_UNAVAILABLE);
        }
        TechniqueDefinition definition = registry.find(request.getTechnique())
                .orElseThrow(() -> TechniqueErrors.rejected(UNKNOWN_TECHNIQUE));
        var technique = request.getTechnique();
        if (!registry.applies(technique, body.template())) {
            throw TechniqueErrors.rejected(BODY_TEMPLATE_MISMATCH);
        }
        if (!TechniqueKnowledge.references(registry, body, progress).contains(technique)) {
            throw TechniqueErrors.rejected(NOT_KNOWN);
        }
        long cooldownEnd = progress.getCooldownsOrDefault(definition.getId(), 0);
        long curveEnd = progress.getCurveEndsOrDefault(definition.getId(), 0);
        long nowMillis = body.settledAt().elapsedMillis();
        if (nowMillis < staggeredUntilMillis) {
            throw TechniqueErrors.rejected(STAGGERED);
        }
        if (nowMillis < cooldownEnd || nowMillis < curveEnd) {
            throw TechniqueErrors.rejected(COOLING_DOWN);
        }
        validateGroundConditions(definition, inspection);
        return definition;
    }

    private void validateGroundConditions(TechniqueDefinition definition, TechniqueHost.Inspection inspection) {
        // 腿法和拳掌在绑定和扣费前读取同一次 inspect 的移动事实，失败只返回着地原因。
        boolean legs = TechniqueLimbStrikes.usesLegs(definition);
        boolean arms = TechniqueLimbStrikes.usesArms(definition);
        if (!legs && !arms) {
            return;
        }
        TechniqueBook book = registry.bookOf(TechniqueRegistry.reference(definition))
                .orElseThrow(() -> TechniqueErrors.rejected(INVALID_STATE));
        if (TechniqueInspection.standsOnGround(inspection.facts())) {
            return;
        }
        if (legs && book.getLegRules().getRequireGrounded()) {
            throw TechniqueErrors.rejected(NOT_GROUNDED);
        }
        if (arms && book.getArmRules().getRequireGrounded()) {
            throw TechniqueErrors.rejected(NOT_GROUNDED, TechniqueErrors.ARMS_NOT_GROUNDED);
        }
    }

    private CastOutcome submitStart(TechniqueHost host, TechniqueActivities.Start start, BodyActivity activity) {
        // 成功回执是唯一发布依据；并发同 ID 已提交时重新读取回执而不重新扣款。
        BodySnapshot body = start.body();
        TechniqueHost.Commit commit;
        try {
            var submission = new TechniqueHost.Submission(startId(start.request().getCastId()),
                    body.revision(), activity);
            commit = host.submit(submission);
        } catch (TechniqueErrors.HostRejected exception) {
            if (exception.failure() == FailureCode.REVISION_CONFLICT
                    || exception.failure() == FailureCode.REQUEST_ID_CONFLICT) {
                Optional<CastOutcome> previous = replay(host, start.request());
                if (previous.isPresent()) {
                    return previous.get();
                }
            }
            throw exception;
        }
        if (commit.revision() > body.revision()) {
            validateCommit(body, activity, commit);
        }
        ActiveCast active = TechniqueState.decode(activity.getProgressOrThrow(TechniqueState.KEY)
                .getValue().toByteArray()).getActiveCast();
        if (commit.revision() < 1) {
            throw new IllegalStateException("宿主返回的活动回执 revision 无效");
        }
        CastOutcome started = outcome(active, TechniqueScheduler.phase(active, active.getStartedMillis()),
                commit.revision());
        if (commit.revision() <= body.revision()) {
            return started.toBuilder()
                    .setReplayed(true)
                    .build();
        }
        return started;
    }

    private Optional<CastOutcome> replay(TechniqueHost host, CastRequest request) {
        // 成功活动回执保留原始请求，施放结束、目录卸载和重启后仍能识别重复 ID。
        Optional<TechniqueHost.Commit> previous = host.findActivity(startId(request.getCastId()));
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        TechniqueHost.Commit commit = previous.get();
        Any payload = commit.activity().getProgressOrThrow(TechniqueState.KEY);
        if (!payload.is(TechniqueProgress.class)) {
            throw TechniqueErrors.rejected(INVALID_STATE);
        }
        TechniqueProgress progress = TechniqueState.decode(payload.getValue().toByteArray());
        if (!progress.hasActiveCast()) {
            throw TechniqueErrors.rejected(INVALID_STATE);
        }
        ActiveCast active = progress.getActiveCast();
        if (!active.getRequest().equals(request)) {
            throw TechniqueErrors.rejected(CAST_ID_CONFLICT);
        }
        CastOutcome replayed = outcome(active, TechniqueScheduler.phase(active, active.getStartedMillis()),
                commit.revision())
                .toBuilder()
                .setReplayed(true)
                .build();
        return Optional.of(replayed);
    }

    private Optional<CastOutcome> replayActive(BodySnapshot body, TechniqueProgress progress,
                                                CastRequest request) {
        // 宿主回执索引暂时不可见时，已保存的 active_cast 仍能安全识别同一请求。
        if (!progress.hasActiveCast()) {
            return Optional.empty();
        }
        ActiveCast active = progress.getActiveCast();
        CastRequest activeRequest = active.getRequest();
        if (!activeRequest.getCastId().equals(request.getCastId())) {
            return Optional.empty();
        }
        if (!activeRequest.equals(request)) {
            throw TechniqueErrors.rejected(CAST_ID_CONFLICT);
        }
        CastOutcome replayed = outcome(active, TechniqueScheduler.phase(active, active.getStartedMillis()),
                body.revision())
                .toBuilder()
                .setReplayed(true)
                .build();
        return Optional.of(replayed);
    }

    public CastOutcome advance(TechniqueHost host) {
        // 仅查询阶段的宿主仍逐边界结算费用；世界效果须使用带游标的入口交付。
        long previousMillis = NO_PREVIOUS_MILLIS;
        AdvanceResult result = advance(host, previousMillis);
        for (int boundary = 0; boundary <= TechniqueValues.MAX_TIMELINE_EVENTS + 1; boundary++) {
            if (result.outcome().getFailure() != TECHNIQUE_FAILURE_UNSPECIFIED
                    || result.advancedMillis() <= previousMillis || result.recoveryCommit().isPresent()) {
                break;
            }
            previousMillis = result.advancedMillis();
            result = advance(host, previousMillis);
        }
        return result.outcome();
    }

    public AdvanceResult advance(TechniqueHost host, long previousMillis) {
        // 先读取已结算身体时钟，再由纯调度器生成意图，最后按需提交收势活动。
        return advance(host, () -> host.inspect().body(), previousMillis);
    }

    /**
     * 用调用方手里的身体推进，省掉一次重复查询。
     * body 必须是刚从同一宿主查询到的，而且查询之后没有向宿主提交过活动或效果；写过就要重新查询。
     */
    public AdvanceResult advance(TechniqueHost host, BodySnapshot body, long previousMillis) {
        // 与查询入口共用同一套推进和失败映射。
        Objects.requireNonNull(body);
        return advance(host, () -> body, previousMillis);
    }

    private AdvanceResult advance(TechniqueHost host, Supplier<BodySnapshot> current, long previousMillis) {
        // 身体的来源只有两种：现查，或调用方刚查到的同一份；失败映射不区分来源。
        String castId = "";
        try {
            BodySnapshot body = current.get();
            TechniqueProgress progress = TechniqueState.read(body);
            long nowMillis = body.settledAt().elapsedMillis();
            if (!progress.hasActiveCast()) {
                CastOutcome idle = CastOutcome.newBuilder()
                        .setPhase(CastPhase.IDLE)
                        .setRevision(body.revision())
                        .build();
                return new AdvanceResult(idle, List.of(), Optional.empty(), nowMillis);
            }
            ActiveCast active = progress.getActiveCast();
            castId = active.getRequest().getCastId();
            return advanceActive(host, body, active, previousMillis);
        } catch (TechniqueErrors.Rejected exception) {
            CastOutcome rejected = failure(castId, exception);
            return new AdvanceResult(rejected, List.of(), Optional.empty(), previousMillis);
        } catch (TechniqueErrors.HostRejected exception) {
            CastOutcome failure = hostFailure(castId, exception);
            return new AdvanceResult(failure, List.of(), Optional.empty(), previousMillis);
        } catch (MeridianErrors.CapabilityRejected | MeridianErrors.IntakeRejected exception) {
            CastOutcome failure = failure(castId, INSUFFICIENT_CAPABILITY);
            return new AdvanceResult(failure, List.of(), Optional.empty(), previousMillis);
        } catch (MeridianErrors.TransportRejected exception) {
            CastOutcome failure = failure(castId, BODY_UNAVAILABLE);
            return new AdvanceResult(failure, List.of(), Optional.empty(), previousMillis);
        } catch (IllegalArgumentException exception) {
            CastOutcome failure = failure(castId, INVALID_STATE);
            return new AdvanceResult(failure, List.of(), Optional.empty(), previousMillis);
        }
    }

    /**
     * 身体时钟走到 nowMillis 时，推进会不会什么都不做：没有到期的曲线、做功和效果，也不到收势。
     * 用调用方上次查询到的身体试算，走的是同一段推进逻辑，只是不碰宿主；
     * 凡是需要向宿主提交、或者拿不准的情况都返回 false，交给正常推进处理。
     * 调用方必须先确认宿主里已提交的还是 body 这一版。
     */
    public boolean quietAt(BodySnapshot body, long previousMillis, long nowMillis) {
        // 试算结果要同时满足：直接推到了当前时刻、没有效果、没有收势、身体版本没动。
        try {
            TechniqueProgress progress = TechniqueState.read(body);
            if (body.vitality().dead() || !progress.hasActiveCast()) {
                return false;
            }
            ActiveCast active = progress.getActiveCast();
            var sustained = active.getSustained();
            if (sustained.getGuard().hasStamina() && !sustained.hasEndRequestedMillis()
                    && nowMillis >= active.getWarmupEndMillis()) {
                return false;
            }
            if (active.hasSustained() && TechniqueScheduler.phase(active, previousMillis)
                    != TechniqueScheduler.phase(active, nowMillis)) {
                return false;
            }
            AdvanceResult result = advanceActive(READ_ONLY, body, active,
                    new Interval(previousMillis, nowMillis));
            return result.advancedMillis() == nowMillis && result.effects().isEmpty()
                    && result.recoveryCommit().isEmpty() && result.outcome().getRevision() == body.revision();
        } catch (RuntimeException needsHost) {
            // 试算途中要读写宿主，或进度本身有问题：都不算安静。
            return false;
        }
    }

    /** 一次推进覆盖的身体时钟区间：上次交付到哪一毫秒，这次推到哪一毫秒。 */
    private record Interval(long previousMillis, long nowMillis) { }

    /** 试算用的宿主：任何读写都拒绝，保证试算不产生副作用。 */
    private static final TechniqueHost READ_ONLY = new TechniqueHost() {
        @Override
        public Inspection inspect() {
            throw new UnsupportedOperationException("试算不查询宿主");
        }

        @Override
        public Optional<Commit> findActivity(String requestId) {
            throw new UnsupportedOperationException("试算不查询宿主");
        }

        @Override
        public Commit submit(Submission submission) {
            throw new UnsupportedOperationException("试算不提交活动");
        }

        @Override
        public EffectCommit submitEffect(EffectSubmission submission) {
            throw new UnsupportedOperationException("试算不提交效果");
        }
    };

    private AdvanceResult advanceActive(TechniqueHost host, BodySnapshot body, ActiveCast active,
                                        long previousMillis) {
        // 正常推进以身体已结清的时钟为当前时刻。
        return advanceActive(host, body, active, new Interval(previousMillis, body.settledAt().elapsedMillis()));
    }

    private AdvanceResult advanceActive(TechniqueHost host, BodySnapshot body, ActiveCast active,
                                        Interval interval) {
        // 分段费用先于同刻附行和世界效果支付；每次只跨一个事件边界，迟到时由服务继续推进。
        long previousMillis = interval.previousMillis();
        long nowMillis = interval.nowMillis();
        Optional<TechniqueDefinition> definition = registry.find(active.getRequest().getTechnique());
        long boundaryMillis;
        BodySnapshot paidBody;
        Optional<InfusionBegan> began = Optional.empty();
        if (definition.isPresent()) {
            TechniqueDefinition currentDefinition = definition.get();
            long workBoundary = TechniqueScheduledWork.boundary(
                    currentDefinition, active, previousMillis, nowMillis);
            long modifierBoundary = TechniqueScheduledModifiers.boundary(active, previousMillis, nowMillis);
            long infusionBoundary = TechniqueScheduledInfusions.boundary(
                    currentDefinition, active, previousMillis, nowMillis);
            long effectBoundary = Math.min(Math.min(workBoundary, modifierBoundary), infusionBoundary);
            boundaryMillis = TechniqueSustained.boundary(active, previousMillis, effectBoundary);
            BodySnapshot modifiedBody = TechniqueScheduledModifiers.applyDue(host, body, boundaryMillis);
            BodySnapshot workedBody = TechniqueScheduledWork.payDue(
                    host, currentDefinition, modifiedBody, boundaryMillis);
            paidBody = TechniqueScheduledInfusions.applyDue(host, currentDefinition, workedBody, boundaryMillis);
            if (paidBody != workedBody) {
                // applyDue 只有真写了附行才换一份身体回来。
                began = TechniqueInfusions.active(paidBody).map(written -> new InfusionBegan(written.getInfusion(),
                        TechniqueInfusions.remainingMillis(paidBody)));
            }
        } else {
            boundaryMillis = TechniqueScheduledModifiers.boundary(active, previousMillis, nowMillis);
            paidBody = TechniqueScheduledModifiers.applyDue(host, body, boundaryMillis);
        }
        TechniqueScheduler.Due due = leapsFromGround(host, definition,
                due(active, previousMillis, boundaryMillis));
        CastPhase current = TechniqueScheduler.phase(active, boundaryMillis);
        if (!due.recoveryDue()) {
            return new AdvanceResult(outcome(active, current, paidBody.revision()),
                    due.effects(), Optional.empty(), boundaryMillis, began);
        }
        return stop(host, paidBody, TechniqueState.read(paidBody), CastPhase.FINISHED, due.effects()).with(began);
    }

    private static TechniqueScheduler.Due leapsFromGround(TechniqueHost host,
                                                         Optional<TechniqueDefinition> definition,
                                                         TechniqueScheduler.Due due) {
        // 拳脚功法的向上冲量是蹬地起跳：到这一刻不站在地上就不给，水平那份照给，免得半空里再跳一次。
        // 只有真有向上冲量到期时才多查一次宿主；兵器功法不归着地规则管，照旧。
        boolean leaping = due.effects().stream().anyMatch(TechniqueCasting::leap);
        if (!leaping || definition.isEmpty() || !usesLimbs(definition.get())
                || TechniqueInspection.standsOnGround(host.inspect().facts())) {
            return due;
        }
        List<EffectIntent> kept = due.effects().stream().filter(effect -> !leap(effect)).toList();
        return new TechniqueScheduler.Due(kept, due.endState());
    }

    private static boolean leap(EffectIntent effect) {
        // 向上冲量只有 UP 一个方向，其余方向都是水平推进。
        return effect.hasImpulse() && effect.getImpulse().getDirection() == ImpulseDirection.UP;
    }

    private static boolean usesLimbs(TechniqueDefinition definition) {
        // 和起手的着地条件用同一个范围：出腿、出拳掌、擒摔。
        return TechniqueLimbStrikes.usesLegs(definition) || TechniqueLimbStrikes.usesArms(definition);
    }

    public AdvanceResult interruptByStagger(TechniqueHost host, long previousMillis) {
        // 僵直由伤害附属按命中剂量判定后传入；仅起手窗口内的可打断式被打断。
        return transition(host, Signal.STAGGER, previousMillis);
    }

    public AdvanceResult cancelOnUnload(TechniqueHost host, long previousMillis) {
        // 必须在冻结时间或撤销宿主访问权之前完成停止提交。
        return transition(host, Signal.UNLOAD, previousMillis);
    }

    AdvanceResult interruptSustained(TechniqueHost host, long previousMillis) {
        // 硬控立即终止持续式，有限式仍按原起手可打断规则处理。
        return transition(host, Signal.HARD_CONTROL, previousMillis);
    }

    public AdvanceResult cancelOnDisconnect(TechniqueHost host, long previousMillis) {
        // 下线只清除当前动作和施放，已经支付的费用与冷却保留。
        return transition(host, Signal.DISCONNECT, previousMillis);
    }

    private AdvanceResult transition(TechniqueHost host, Signal signal, long previousMillis) {
        // 每次从权威进度恢复状态，库对象重建不会影响边界或动作身份。
        String castId = "";
        try {
            BodySnapshot body = host.inspect().body();
            TechniqueProgress progress = TechniqueState.read(body);
            long nowMillis = body.settledAt().elapsedMillis();
            if (!progress.hasActiveCast()) {
                CastOutcome idle = CastOutcome.newBuilder()
                        .setPhase(CastPhase.IDLE)
                        .setRevision(body.revision())
                        .build();
                return new AdvanceResult(idle, List.of(), Optional.empty(), nowMillis);
            }
            ActiveCast active = progress.getActiveCast();
            castId = active.getRequest().getCastId();
            if (previousMillis > nowMillis) {
                throw new IllegalArgumentException("效果调度时刻倒退");
            }
            CastPhase phase = TechniqueScheduler.phase(active, nowMillis);
            CastPhase ending = ending(active, phase, signal);
            if (ending == CastPhase.CAST_PHASE_UNSPECIFIED) {
                return new AdvanceResult(outcome(active, phase, body.revision()),
                        List.of(), Optional.empty(), nowMillis);
            }
            BodySnapshot settledBody = TechniqueScheduledModifiers.settleBeforeStop(host, body);
            TechniqueProgress settledProgress = TechniqueState.read(settledBody);
            List<EffectIntent> effects = stopEffects(active, previousMillis, nowMillis);
            return stop(host, settledBody, settledProgress, ending, effects);
        } catch (TechniqueErrors.HostRejected exception) {
            CastOutcome rejected = hostFailure(castId, exception);
            return new AdvanceResult(rejected, List.of(), Optional.empty(), previousMillis);
        } catch (IllegalArgumentException exception) {
            CastOutcome invalid = failure(castId, INVALID_STATE);
            return new AdvanceResult(invalid, List.of(), Optional.empty(), previousMillis);
        }
    }

    private static AdvanceResult stop(TechniqueHost host, BodySnapshot body, TechniqueProgress progress,
                                      CastPhase ending, List<EffectIntent> effects) {
        // 正常收势与提前取消提交同一种原子活动，并返回实际读取到的身体时刻。
        ActiveCast active = progress.getActiveCast();
        String castId = active.getRequest().getCastId();
        BodyActivity activity = TechniqueActivities.stopAt(progress, body.settledAt().elapsedMillis(), ending);
        var submission = new TechniqueHost.Submission("technique_end_" + castId, body.revision(), activity);
        TechniqueHost.Commit commit = host.submit(submission);
        validateCommit(body, activity, commit);
        return new AdvanceResult(outcome(active, ending, commit.revision()),
                effects, Optional.of(commit), body.settledAt().elapsedMillis());
    }

    private List<EffectIntent> stopEffects(ActiveCast active, long previousMillis, long currentMillis) {
        // 表现声明与移动锁保存在施放状态中，目录卸载也能生成清理意图。
        return TechniqueScheduler.stopEffects(active, previousMillis, currentMillis);
    }

    private static CastPhase ending(ActiveCast active, CastPhase current, Signal signal) {
        // 收势边界优先于取消；可打断标志不影响卸载或下线清理，出手与收势中的僵直不打断。
        if (signal == Signal.HARD_CONTROL && active.hasSustained()) {
            return CastPhase.INTERRUPTED;
        }
        if (current == CastPhase.FINISHED) {
            return CastPhase.FINISHED;
        }
        if (signal == Signal.UNLOAD || signal == Signal.DISCONNECT) {
            return CastPhase.CANCELLED;
        }
        if (signal == Signal.STAGGER && current == CastPhase.WARMUP && active.getInterruptibleByDamage()) {
            return CastPhase.INTERRUPTED;
        }
        return CastPhase.CAST_PHASE_UNSPECIFIED;
    }

    private TechniqueScheduler.Due due(ActiveCast active, long previousMillis, long currentMillis) {
        // 功法目录可能在施放后卸载；此时仍能收势，但不再凭空解释世界效果。
        Optional<TechniqueDefinition> definition = registry.find(active.getRequest().getTechnique());
        if (definition.isEmpty()) {
            TechniqueScheduler.EndState endState = TechniqueScheduler.EndState.RUNNING;
            if (!TechniqueSustained.unknownEnd(active) && currentMillis >= active.getRecoveryEndMillis()) {
                endState = TechniqueScheduler.EndState.RECOVERY_DUE;
            }
            List<EffectIntent> cleanup = List.of();
            if (endState == TechniqueScheduler.EndState.RECOVERY_DUE) {
                cleanup = TechniqueScheduler.stopEffects(active, previousMillis, currentMillis);
            }
            return new TechniqueScheduler.Due(cleanup, endState);
        }
        return TechniqueScheduler.due(definition.get(), active, previousMillis, currentMillis);
    }

    private static CastOutcome outcome(ActiveCast active, CastPhase phase, long revision) {
        // 成功与进行中状态携带原绑定，可由调用方核对施放身份。
        return CastOutcome.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setPhase(phase)
                .setRevision(revision)
                .setBinding(active.getBinding())
                .build();
    }

    private static CastOutcome failure(String castId, TechniqueFailure code) {
        // 结构化失败码供宿主区分原因，玩家文本只保留身体感受。
        return CastOutcome.newBuilder()
                .setCastId(castId)
                .setFailure(code)
                .setReason(TechniqueErrors.playerReason(code))
                .build();
    }

    private static CastOutcome failure(String castId, TechniqueErrors.Rejected rejected) {
        // 拒绝已带具体说明时原样交给玩家，失败码仍供宿主区分。
        return failure(castId, rejected.code).toBuilder()
                .setReason(rejected.reason)
                .build();
    }

    private static CastOutcome hostFailure(String castId, TechniqueErrors.HostRejected exception) {
        // Framework 拒绝保留原码；常见情况给出中文解释，避免用户界面出现枚举名。
        FailureCode frameworkFailure = exception.failure();
        return failure(castId, FRAMEWORK_REJECTED).toBuilder()
                .setFrameworkFailure(frameworkFailure)
                .setReason(TechniqueErrors.frameworkPlayerReason(frameworkFailure))
                .build();
    }

    private static String startId(String castId) {
        // 不依赖连接或库实例，宿主恢复后仍以相同键查找 Framework 活动回执。
        return "technique_start_" + castId;
    }

    private static void validateCommit(BodySnapshot body, BodyActivity activity, TechniqueHost.Commit commit) {
        // 宿主必须提交原计划且只递增一次，错误回执不能被当成施放成功。
        if (commit.revision() != body.revision() + 1 || !commit.activity().equals(activity)) {
            throw new IllegalStateException("宿主返回的活动回执与提交计划不一致");
        }
    }

    private enum Signal { CLOCK, STAGGER, HARD_CONTROL, UNLOAD, DISCONNECT }
}
