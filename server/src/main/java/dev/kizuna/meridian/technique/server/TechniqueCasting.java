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
                                Optional<TechniqueHost.Commit> recoveryCommit) {
        public AdvanceResult {
            // 调度结果在交给宿主前固定下来，避免调用方修改效果顺序。
            effects = List.copyOf(effects);
            recoveryCommit = Objects.requireNonNull(recoveryCommit);
        }
    }

    public TechniqueCasting(TechniqueRegistry registry) {
        // 只持有不可变定义，运行状态全部位于宿主的身体与回执中。
        this.registry = java.util.Objects.requireNonNull(registry);
    }

    public CastOutcome cast(TechniqueHost host, CastRequest request) {
        // 请求边界错误与损坏存档分别报告，避免把非法输入写入进度。
        try {
            TechniqueProtocol.validateCast(request);
        } catch (IllegalArgumentException exception) {
            return failure(request.getCastId(), INVALID_REQUEST);
        }
        try {
            Optional<CastOutcome> previous = replay(host, request);
            if (previous.isPresent()) {
                return previous.get();
            }
            BodySnapshot body = host.inspect();
            if (!body.id().value().equals(request.getBodyId())) {
                throw TechniqueErrors.rejected(BODY_UNAVAILABLE);
            }
            TechniqueProgress progress = TechniqueState.read(body);
            TechniqueDefinition definition = requireCastable(body, progress, request);
            BindingOutcome result = TechniqueBinder.bind(body, definition);
            if (!result.hasBinding()) {
                throw TechniqueErrors.rejected(result.getFailure());
            }
            var start = new TechniqueActivities.Start(body, definition, request, progress, result.getBinding());
            BodyActivity activity = TechniqueActivities.start(start);
            return submitStart(host, start, activity);
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

    private TechniqueDefinition requireCastable(BodySnapshot body, TechniqueProgress progress, CastRequest request) {
        // 先检查生命、目录与可信知识记录，再检查当前占用和身体时钟冷却。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw TechniqueErrors.rejected(BODY_UNAVAILABLE);
        }
        TechniqueDefinition definition = registry.find(request.getTechnique())
                .orElseThrow(() -> TechniqueErrors.rejected(UNKNOWN_TECHNIQUE));
        if (progress.getKnownList().stream().noneMatch(known -> known.getTechnique().equals(request.getTechnique()))) {
            throw TechniqueErrors.rejected(NOT_KNOWN);
        }
        if (progress.hasActiveCast()) {
            throw TechniqueErrors.rejected(ALREADY_CASTING);
        }
        long cooldownEnd = progress.getCooldownsOrDefault(definition.getId(), 0);
        if (body.settledAt().elapsedMillis() < cooldownEnd) {
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
        validateCommit(body, activity, commit);
        ActiveCast active = TechniqueState.decode(activity.getProgressOrThrow(TechniqueState.KEY)
                .getValue().toByteArray()).getActiveCast();
        return outcome(active, TechniqueScheduler.phase(active, active.getStartedMillis()), commit.revision());
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
                .setReplayed(true /* replayed */)
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
            BodySnapshot body = host.inspect();
            TechniqueProgress progress = TechniqueState.read(body);
            if (!progress.hasActiveCast()) {
                CastOutcome idle = CastOutcome.newBuilder()
                        .setPhase(CastPhase.IDLE)
                        .setRevision(body.revision())
                        .build();
                return new AdvanceResult(idle, List.of(), Optional.empty());
            }
            ActiveCast active = progress.getActiveCast();
            castId = active.getRequest().getCastId();
            long nowMillis = body.settledAt().elapsedMillis();
            TechniqueScheduler.Due due = due(active, previousMillis, nowMillis);
            CastPhase current = TechniqueScheduler.phase(active, nowMillis);
            if (!due.recoveryDue()) {
                return new AdvanceResult(outcome(active, current, body.revision()),
                        due.effects(), Optional.empty());
            }
            BodyActivity activity = TechniqueActivities.stop(progress);
            var submission = new TechniqueHost.Submission("technique_end_" + castId,
                    body.revision(), activity);
            TechniqueHost.Commit commit = host.submit(submission);
            validateCommit(body, activity, commit);
            CastOutcome finished = outcome(active, CastPhase.FINISHED, commit.revision());
            return new AdvanceResult(finished, due.effects(), Optional.of(commit));
        } catch (TechniqueErrors.HostRejected exception) {
            CastOutcome failure = hostFailure(castId, exception);
            return new AdvanceResult(failure, List.of(), Optional.empty());
        } catch (IllegalArgumentException exception) {
            CastOutcome failure = failure(castId, INVALID_STATE);
            return new AdvanceResult(failure, List.of(), Optional.empty());
        }
    }

    public AdvanceResult interruptByDamage(TechniqueHost host, long previousMillis) {
        // 伤害事实必须由可信宿主提供，仅起手窗口内允许打断。
        return transition(host, Signal.DAMAGE, previousMillis);
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
            BodySnapshot body = host.inspect();
            TechniqueProgress progress = TechniqueState.read(body);
            if (!progress.hasActiveCast()) {
                CastOutcome idle = CastOutcome.newBuilder()
                        .setPhase(CastPhase.IDLE)
                        .setRevision(body.revision())
                        .build();
                return new AdvanceResult(idle, List.of(), Optional.empty());
            }
            ActiveCast active = progress.getActiveCast();
            castId = active.getRequest().getCastId();
            long nowMillis = body.settledAt().elapsedMillis();
            if (previousMillis > nowMillis) {
                throw new IllegalArgumentException("效果调度时刻倒退");
            }
            CastPhase phase = TechniqueScheduler.phase(active, nowMillis);
            CastPhase ending = ending(active, phase, signal);
            if (ending == CastPhase.CAST_PHASE_UNSPECIFIED) {
                return new AdvanceResult(outcome(active, phase, body.revision()),
                        List.of(), Optional.empty());
            }
            List<EffectIntent> effects = stopEffects(active, previousMillis, nowMillis);
            BodyActivity activity = TechniqueActivities.stop(progress);
            var submission = new TechniqueHost.Submission("technique_end_" + castId, body.revision(), activity);
            TechniqueHost.Commit commit = host.submit(submission);
            validateCommit(body, activity, commit);
            return new AdvanceResult(outcome(active, ending, commit.revision()),
                    effects, Optional.of(commit));
        } catch (TechniqueErrors.HostRejected exception) {
            CastOutcome rejected = hostFailure(castId, exception);
            return new AdvanceResult(rejected, List.of(), Optional.empty());
        } catch (IllegalArgumentException exception) {
            CastOutcome invalid = failure(castId, INVALID_STATE);
            return new AdvanceResult(invalid, List.of(), Optional.empty());
        }
    }

    private List<EffectIntent> stopEffects(ActiveCast active, long previousMillis, long currentMillis) {
        // 目录卸载后仍可清动作，但没有定义可据以生成表现停止意图。
        Optional<TechniqueDefinition> definition = registry.find(active.getRequest().getTechnique());
        if (definition.isEmpty()) {
            return List.of();
        }
        return TechniqueScheduler.stopEffects(definition.get(), active, previousMillis, currentMillis);
    }

    private static CastPhase ending(ActiveCast active, CastPhase current, Signal signal) {
        // 收势边界优先于取消；可打断标志不影响卸载或下线清理。
        if (current == CastPhase.FINISHED) {
            return CastPhase.FINISHED;
        }
        if (signal == Signal.UNLOAD || signal == Signal.DISCONNECT) {
            return CastPhase.CANCELLED;
        }
        if (signal == Signal.DAMAGE && current == CastPhase.WARMUP && active.getInterruptibleByDamage()) {
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
            return new TechniqueScheduler.Due(List.of(), endState);
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

    private enum Signal { CLOCK, DAMAGE, UNLOAD, DISCONNECT }
}
