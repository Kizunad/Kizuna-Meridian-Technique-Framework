package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.Optional;

import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

/** 无身体缓存的施放编排；每次调用都通过可信宿主读取或提交。 */
public final class TechniqueCasting {
    private final TechniqueRegistry registry;

    public TechniqueCasting(TechniqueRegistry registry) {
        // 只持有不可变定义，运行状态全部位于宿主的身体与回执中。
        this.registry = java.util.Objects.requireNonNull(registry);
    }

    public CastOutcome cast(TechniqueHost host, CastRequest request) {
        // 请求边界错误与损坏存档分别报告，避免把非法输入写入进度。
        try {
            TechniqueProtocol.validateCast(request);
        } catch (IllegalArgumentException exception) {
            return failure(request.getCastId(), INVALID_REQUEST, exception.getMessage());
        }
        try {
            Optional<CastOutcome> previous = replay(host, request);
            if (previous.isPresent()) {
                return previous.get();
            }
            BodySnapshot body = host.inspect();
            if (!body.id().value().equals(request.getBodyId())) {
                throw TechniqueErrors.rejected(BODY_UNAVAILABLE, "请求身体与宿主绑定身体不一致");
            }
            TechniqueProgress progress = TechniqueState.read(body);
            TechniqueDefinition definition = requireCastable(body, progress, request);
            BindingOutcome result = TechniqueBinder.bind(body, definition);
            if (!result.hasBinding()) {
                throw TechniqueErrors.rejected(result.getFailure(), result.getReason());
            }
            var start = new TechniqueActivities.Start(body, definition, request, progress, result.getBinding());
            BodyActivity activity = TechniqueActivities.start(start);
            return submitStart(host, start, activity);
        } catch (TechniqueErrors.Rejected exception) {
            return failure(request.getCastId(), exception.code, exception.getMessage());
        } catch (TechniqueErrors.HostRejected exception) {
            return hostFailure(request.getCastId(), exception);
        } catch (MeridianErrors.CapabilityRejected | MeridianErrors.IntakeRejected exception) {
            return failure(request.getCastId(), INSUFFICIENT_CAPABILITY, "组织或器官无法承担本次做功与动作占用");
        } catch (MeridianErrors.TransportRejected exception) {
            return failure(request.getCastId(), INVALID_STATE, "费用无法在当前身体数值精度下安全结算");
        } catch (IllegalArgumentException exception) {
            return failure(request.getCastId(), INVALID_STATE, exception.getMessage());
        }
    }

    private TechniqueDefinition requireCastable(BodySnapshot body, TechniqueProgress progress, CastRequest request) {
        // 先检查生命、目录与可信知识记录，再检查当前占用和身体时钟冷却。
        if (body.vitality().dead() || body.vitality().remainingSeconds() == 0) {
            throw TechniqueErrors.rejected(BODY_UNAVAILABLE, "身体已经死亡或寿命耗尽");
        }
        TechniqueDefinition definition = registry.find(request.getTechnique())
                .orElseThrow(() -> TechniqueErrors.rejected(UNKNOWN_TECHNIQUE, "目录中没有该功法或版本"));
        if (progress.getKnownList().stream().noneMatch(known -> known.getTechnique().equals(request.getTechnique()))) {
            throw TechniqueErrors.rejected(NOT_KNOWN, "尚未学会该功法");
        }
        if (progress.hasActiveCast()) {
            throw TechniqueErrors.rejected(ALREADY_CASTING, "当前已有施放，必须等收势结束");
        }
        long cooldownEnd = progress.getCooldownsOrDefault(definition.getId(), 0);
        if (body.settledAt().elapsedMillis() < cooldownEnd) {
            throw TechniqueErrors.rejected(COOLING_DOWN, "功法冷却中，结束身体时刻为 " + cooldownEnd + " 毫秒");
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
        return outcome(active, phase(active, active.getStartedMillis()), commit.revision());
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
            throw TechniqueErrors.rejected(INVALID_STATE, "宿主历史活动的进度类型错误");
        }
        TechniqueProgress progress = TechniqueState.decode(payload.getValue().toByteArray());
        if (!progress.hasActiveCast()) {
            throw TechniqueErrors.rejected(INVALID_STATE, "宿主起手回执缺少原始施放记录");
        }
        ActiveCast active = progress.getActiveCast();
        if (!active.getRequest().equals(request)) {
            throw TechniqueErrors.rejected(CAST_ID_CONFLICT, "同一 cast_id 已用于不同施放请求");
        }
        CastOutcome replayed = outcome(active, phase(active, active.getStartedMillis()), commit.revision())
                .toBuilder()
                .setReplayed(true /* replayed */)
                .build();
        return Optional.of(replayed);
    }

    public CastOutcome advance(TechniqueHost host) {
        // 宿主调度到事件边界即可，不按毫秒循环，也不生成效果意图。
        return transition(host, Signal.CLOCK);
    }

    public CastOutcome interruptByDamage(TechniqueHost host) {
        // 伤害事实必须由可信宿主提供，仅起手窗口内允许打断。
        return transition(host, Signal.DAMAGE);
    }

    public CastOutcome cancelOnUnload(TechniqueHost host) {
        // 必须在冻结时间或撤销宿主访问权之前完成停止提交。
        return transition(host, Signal.UNLOAD);
    }

    public CastOutcome cancelOnDisconnect(TechniqueHost host) {
        // 下线只清除当前动作和施放，已经支付的费用与冷却保留。
        return transition(host, Signal.DISCONNECT);
    }

    private CastOutcome transition(TechniqueHost host, Signal signal) {
        // 每次从权威进度恢复状态，库对象重建不会影响边界或动作身份。
        String castId = "";
        try {
            BodySnapshot body = host.inspect();
            TechniqueProgress progress = TechniqueState.read(body);
            if (!progress.hasActiveCast()) {
                return CastOutcome.newBuilder()
                        .setPhase(CastPhase.IDLE)
                        .setRevision(body.revision())
                        .build();
            }
            ActiveCast active = progress.getActiveCast();
            castId = active.getRequest().getCastId();
            long nowMillis = body.settledAt().elapsedMillis();
            CastPhase phase = phase(active, nowMillis);
            CastPhase ending = ending(active, phase, signal);
            if (ending == CastPhase.CAST_PHASE_UNSPECIFIED) {
                return outcome(active, phase, body.revision());
            }
            BodyActivity activity = TechniqueActivities.stop(progress);
            var submission = new TechniqueHost.Submission("technique_end_" + castId, body.revision(), activity);
            TechniqueHost.Commit commit = host.submit(submission);
            validateCommit(body, activity, commit);
            return outcome(active, ending, commit.revision());
        } catch (TechniqueErrors.HostRejected exception) {
            return hostFailure(castId, exception);
        } catch (IllegalArgumentException exception) {
            return failure(castId, INVALID_STATE, exception.getMessage());
        }
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

    private static CastPhase phase(ActiveCast active, long millis) {
        // 半开区间使零生效段直接进入收势，边界伤害不会误打断已完成起手。
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

    private static CastOutcome outcome(ActiveCast active, CastPhase phase, long revision) {
        // 成功与进行中状态携带原绑定，可由调用方核对施放身份。
        return CastOutcome.newBuilder()
                .setCastId(active.getRequest().getCastId())
                .setPhase(phase)
                .setRevision(revision)
                .setBinding(active.getBinding())
                .build();
    }

    private static CastOutcome failure(String castId, TechniqueFailure code, String reason) {
        // 所有业务失败均携带原因码及一句中文说明，可直接显示给调用者。
        return CastOutcome.newBuilder()
                .setCastId(castId)
                .setFailure(code)
                .setReason(reason)
                .build();
    }

    private static CastOutcome hostFailure(String castId, TechniqueErrors.HostRejected exception) {
        // Framework 拒绝保留原码；常见情况给出中文解释，避免用户界面出现枚举名。
        String reason = switch (exception.failure()) {
            case REVISION_CONFLICT -> "身体状态已变化，请重试施放";
            case ACCESS_DENIED -> "宿主没有授权本次操作";
            case BODY_DEAD -> "身体已经死亡";
            case TIME_REQUIRES_SETTLEMENT -> "宿主尚未将身体结算到当前时刻";
            default -> "宿主无法提交本次身体活动";
        };
        return failure(castId, FRAMEWORK_REJECTED, reason).toBuilder()
                .setFrameworkFailure(exception.failure())
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
