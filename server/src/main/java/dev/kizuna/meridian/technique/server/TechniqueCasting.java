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

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

/** 无身体缓存的施放编排；每次调用都通过可信宿主读取或提交。 */
public final class TechniqueCasting {
    private static final long NO_PREVIOUS_MILLIS = Long.MIN_VALUE;
    private final TechniqueRegistry registry;

    /** 一次可信时钟推进产生的效果意图和可选终止回执。 */
    public record AdvanceResult(CastOutcome outcome, List<EffectIntent> effects,
                                Optional<TechniqueHost.Commit> recoveryCommit, long advancedMillis) {
        public AdvanceResult {
            // 调度结果在交给宿主前固定下来，避免调用方修改效果顺序。
            effects = List.copyOf(effects);
            recoveryCommit = Objects.requireNonNull(recoveryCommit);
        }
    }

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
            return failure(request.getCastId(), exception.code);
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
        TechniqueDefinition definition = requireCastable(body, progress, request, staggeredUntilMillis);
        long clipStart = TechniqueFollowUps.clipStart(definition, progress, request,
                body.settledAt().elapsedMillis());
        TechniqueActivities.CastBinding selection = bindCast(definition, inspection, clipStart);
        var start = new TechniqueActivities.Start(body, definition, request, progress, selection);
        BodyActivity activity = TechniqueActivities.start(start);
        return submitStart(host, start, activity);
    }

    private TechniqueActivities.CastBinding bindCast(TechniqueDefinition definition,
                                                      TechniqueHost.Inspection inspection, long clipStart) {
        // 身体绑定与选手都基于同一次宿主查询，任一失败都不能生成扣费活动。
        BindingOutcome result = TechniqueBinder.bind(inspection.body(), definition);
        if (!result.hasBinding()) {
            throw TechniqueErrors.rejected(result.getFailure());
        }
        Hand hand = selectHand(definition, inspection.heldItems());
        HeldItemRules rules = registry.bookOf(TechniqueRegistry.reference(definition))
                .map(TechniqueBook::getHeldItemRules).orElse(HeldItemRules.getDefaultInstance());
        var entry = new TechniqueActivities.Entry(clipStart, rules);
        return new TechniqueActivities.CastBinding(result.getBinding(), hand, entry);
    }

    private Hand selectHand(TechniqueDefinition definition, HeldItems items) {
        // 无书级持物声明的功法沿用空手语义，持物规则只从当前目录获取。
        Optional<TechniqueBook> book = registry.bookOf(TechniqueRegistry.reference(definition));
        if (book.isEmpty() || !book.get().hasHeldItemRules()) {
            return Hand.HAND_UNSPECIFIED;
        }
        return TechniqueHeldItems.select(book.get().getHeldItemRules(), items);
    }

    private TechniqueDefinition requireCastable(BodySnapshot body, TechniqueProgress progress, CastRequest request,
                                                long staggeredUntilMillis) {
        // 先检查生命、目录与可信知识记录，再检查受击僵直和身体时钟冷却。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw TechniqueErrors.rejected(BODY_UNAVAILABLE);
        }
        TechniqueDefinition definition = registry.find(request.getTechnique())
                .orElseThrow(() -> TechniqueErrors.rejected(UNKNOWN_TECHNIQUE));
        if (progress.getKnownList().stream().noneMatch(known -> known.getTechnique().equals(request.getTechnique()))) {
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
        return definition;
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
        // 只需要阶段状态的调用方不提供效果游标，效果意图仍由带时刻的入口负责生成。
        return advance(host, NO_PREVIOUS_MILLIS).outcome();
    }

    public AdvanceResult advance(TechniqueHost host, long previousMillis) {
        // 先读取已结算身体时钟，再由纯调度器生成意图，最后按需提交收势活动。
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
            TechniqueScheduler.Due due = due(active, previousMillis, nowMillis);
            CastPhase current = TechniqueScheduler.phase(active, nowMillis);
            if (!due.recoveryDue()) {
                return new AdvanceResult(outcome(active, current, body.revision()),
                        due.effects(), Optional.empty(), nowMillis);
            }
            return stop(host, body, progress, CastPhase.FINISHED, due.effects());
        } catch (TechniqueErrors.HostRejected exception) {
            CastOutcome failure = hostFailure(castId, exception);
            return new AdvanceResult(failure, List.of(), Optional.empty(), previousMillis);
        } catch (IllegalArgumentException exception) {
            CastOutcome failure = failure(castId, INVALID_STATE);
            return new AdvanceResult(failure, List.of(), Optional.empty(), previousMillis);
        }
    }

    public AdvanceResult interruptByStagger(TechniqueHost host, long previousMillis) {
        // 僵直由伤害附属按命中剂量判定后传入；仅起手窗口内的可打断式被打断。
        return transition(host, Signal.STAGGER, previousMillis);
    }

    public AdvanceResult cancelOnUnload(TechniqueHost host, long previousMillis) {
        // 必须在冻结时间或撤销宿主访问权之前完成停止提交。
        return transition(host, Signal.UNLOAD, previousMillis);
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
            List<EffectIntent> effects = stopEffects(active, previousMillis, nowMillis);
            return stop(host, body, progress, ending, effects);
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
            if (currentMillis >= active.getRecoveryEndMillis()) {
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

    private enum Signal { CLOCK, STAGGER, UNLOAD, DISCONNECT }
}
