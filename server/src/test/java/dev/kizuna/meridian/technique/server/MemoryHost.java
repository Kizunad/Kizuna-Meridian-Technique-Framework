package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodyId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.*;
import dev.kizuna.meridian.server.BodyCommandHandler;
import dev.kizuna.meridian.server.InMemoryBodyStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 假宿主负责内存持久化，费用结算仍由真实 Framework 处理器验证。 */
final class MemoryHost implements TechniqueHost {
    final InMemoryBodyStore store;
    final BodyId bodyId;
    private final BodyCommandHandler<String> commands;
    long nowMillis;
    int activitySubmissions;
    private FailureCode nextFailure = FailureCode.FAILURE_CODE_UNSPECIFIED;

    MemoryHost(BodySnapshot body) {
        // 测试身份固定绑定一具身体，生产身份认证由具体宿主完成。
        this(new InMemoryBodyStore(List.of(body), List.of()), body.id());
    }

    private MemoryHost(InMemoryBodyStore store, BodyId bodyId) {
        // 宿主的时钟和 Framework 命令处理器共享同一权威存储。
        this.store = store;
        this.bodyId = bodyId;
        this.nowMillis = current().settledAt().elapsedMillis();
        this.commands = new BodyCommandHandler<>(
                (context, command) -> Optional.of(TechniqueHost.CALLER_ID),
                (context, command, body) -> true,
                (context, body) -> timePlan(body), store, (context, receipt, before, after) -> { });
    }

    BodySnapshot current() {
        // 只读取存储，不隐式推进时间，方便比较拒绝前后的完全相同状态。
        return store.find(bodyId).orElseThrow();
    }

    @Override
    public BodySnapshot inspect() {
        // 先按旧动作结时；TIME_NOT_ADVANCED 表示身体已经在同一时刻。
        BodySnapshot body = current();
        if (body.vitality().dead()) {
            return body;
        }
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(bodyId.value())
                .setExpectedRevision(body.revision())
                .setAdvanceTime(AdvanceTime.getDefaultInstance())
                .build();
        ResponseEnvelope response = execute("settle_" + UUID.randomUUID(), command, Route.NORMAL);
        if (response.hasFailure() && response.getFailure().getCode() != FailureCode.TIME_NOT_ADVANCED) {
            throw new TechniqueErrors.HostRejected(response.getFailure().getCode());
        }
        return current();
    }

    @Override
    public Optional<Commit> findActivity(String requestId) {
        // 模拟已授权的固定调用身份；活动回执与身体一起持久保存。
        return store.transact(bodyId, transaction -> transaction.receipt(TechniqueHost.CALLER_ID, requestId))
                .map(receipt -> new Commit(receipt.getCommitted().getRevision(),
                        receipt.getCommand().getExecuteActivity()));
    }

    @Override
    public Commit submit(Submission submission) {
        // 测试注入拒绝也不暂存候选；正常路径必须经过 handleActivity。
        activitySubmissions++;
        if (nextFailure != FailureCode.FAILURE_CODE_UNSPECIFIED) {
            FailureCode failure = nextFailure;
            nextFailure = FailureCode.FAILURE_CODE_UNSPECIFIED;
            throw new TechniqueErrors.HostRejected(failure);
        }
        BodyCommand command = BodyCommand.newBuilder()
                .setBodyId(bodyId.value())
                .setExpectedRevision(submission.expectedRevision())
                .setExecuteActivity(submission.activity())
                .build();
        ResponseEnvelope response = execute(submission.requestId(), command, Route.ACTIVITY);
        if (response.hasFailure()) {
            throw new TechniqueErrors.HostRejected(response.getFailure().getCode());
        }
        return new Commit(response.getCommandCommitted().getRevision(), submission.activity());
    }

    void rejectNextActivity() {
        // 权限失败证明计划已生成也不能先落本库的冷却或动作状态。
        nextFailure = FailureCode.ACCESS_DENIED;
    }

    MemoryHost restore() {
        // 模拟快照二进制存档与回执恢复，释放账户也一起保留。
        BodySnapshot restored = BodyProtobuf.decodeSnapshot(BodyProtobuf.encodeSnapshot(current()));
        var restoredStore = new InMemoryBodyStore(List.of(restored), store.receipts(), store.environmentQi());
        return new MemoryHost(restoredStore, bodyId);
    }

    private TimeAdvancePlan timePlan(BodySnapshot body) {
        // 时钟由宿主决定，请求无法伪造目标时刻。
        var target = BodyProtobuf.toMessage(body).getSettledAt().toBuilder().setElapsedMillis(nowMillis).build();
        return TimeAdvancePlan.newBuilder()
                .setTarget(target)
                .setBiologicalSecondsPerSimulationSecond(1)
                .setMode(TimeAdvanceMode.ACTIVE)
                .build();
    }

    private ResponseEnvelope execute(String requestId, BodyCommand command, Route route) {
        // 唯一差异是可信入口，编码、授权、守恒和回执均使用 Framework。
        RequestEnvelope envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setBodyCommand(command)
                .build();
        byte[] payload = MeridianProtocol.encodeRequest(envelope);
        byte[] response;
        if (route == Route.ACTIVITY) {
            response = commands.handleActivity("trusted", payload);
        } else {
            response = commands.handle("trusted", payload);
        }
        return MeridianProtocol.decodeResponse(response);
    }

    private enum Route { NORMAL, ACTIVITY }
}
