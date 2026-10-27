package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 通过 entrypoint 发现实体身体宿主，并把三类操作路由到唯一认领者。 */
public final class TechniqueHostDirectory {
    private final List<TechniqueHostEntrypoint> entrypoints;

    public TechniqueHostDirectory(List<? extends TechniqueHostEntrypoint> entrypoints) {
        // 入口列表来自 Fabric 加载器；复制后一次解析不会受外部集合变化影响。
        this.entrypoints = List.copyOf(entrypoints);
    }

    public Optional<TechniqueHost> resolve(UUID entityUuid) {
        // 先用 inspect 探测认领关系，只有一个宿主返回身体时才发布路由。
        Objects.requireNonNull(entityUuid);
        String entityId = entityUuid.toString();
        var claims = new ArrayList<Claim>();
        for (TechniqueHostEntrypoint entrypoint : entrypoints) {
            TechniqueHostResponse response = exchange(entrypoint, inspectRequest(entityId));
            if (response.getResultCase() == TechniqueHostResponse.ResultCase.UNBOUND) {
                continue;
            }
            claims.add(new Claim(entrypoint, response));
        }
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        if (claims.size() > 1) {
            throw new MultipleHostsException(entityId);
        }
        return Optional.of(new RoutedHost(entityId, claims.get(0)));
    }

    private static TechniqueHostResponse exchange(TechniqueHostEntrypoint entrypoint,
                                                  TechniqueHostRequest request) {
        // entrypoint 只交换有界 Protobuf，响应必须回显 request_id 才能防止串包。
        byte[] payload = TechniqueProtocol.encodeHost(request);
        TechniqueHostResponse response = TechniqueProtocol.decodeHostResponse(entrypoint.exchange(payload));
        if (!response.getRequestId().equals(request.getRequestId())) {
            throw new IllegalStateException("功法宿主回执 request_id 不一致");
        }
        return response;
    }

    private static TechniqueHostRequest inspectRequest(String entityId) {
        // 探测请求不携带身体 ID，宿主只能按实体身份决定是否认领。
        return TechniqueHostRequest.newBuilder()
                .setEntityUuid(entityId)
                .setRequestId("technique_inspect_" + UUID.randomUUID())
                .setInspect(true)
                .build();
    }

    private record Claim(TechniqueHostEntrypoint entrypoint, TechniqueHostResponse response) { }

    private final class RoutedHost implements TechniqueHost {
        private final String entityUuid;
        private final TechniqueHostEntrypoint entrypoint;
        private final Map<String, Commit> receipts = new HashMap<>();
        private final Map<String, EffectIntent> effectReceipts = new HashMap<>();
        private TechniqueHostResponse initialResponse;

        private RoutedHost(String entityUuid, Claim claim) {
            // 保留探测回执只用于第一次读取，之后每次操作都重新向宿主确认当前身体。
            this.entityUuid = entityUuid;
            this.entrypoint = claim.entrypoint();
            this.initialResponse = claim.response();
        }

        @Override
        public BodySnapshot inspect() {
            // 初次探测的身体可直接使用，后续读取必须重新结算宿主时钟。
            TechniqueHostResponse response = initialResponse;
            initialResponse = null;
            if (response == null) {
                response = exchange(entrypoint, inspectRequest(entityUuid));
            }
            return body(response);
        }

        @Override
        public Optional<Commit> findActivity(String requestId) {
            // 当前宿主协议没有独立回执查询；同一服务实例保留已提交的可信回执。
            return Optional.ofNullable(receipts.get(requestId));
        }

        @Override
        public Commit submit(Submission submission) {
            // 活动提交保留原 request_id，宿主可据此复用 Framework 的持久幂等回执。
            TechniqueHostRequest request = TechniqueHostRequest.newBuilder()
                    .setEntityUuid(entityUuid)
                    .setRequestId(submission.requestId())
                    .setExpectedRevision(submission.expectedRevision())
                    .setActivity(submission.activity())
                    .build();
            TechniqueHostResponse response = exchange(entrypoint, request);
            if (response.getResultCase() != TechniqueHostResponse.ResultCase.ACTIVITY) {
                throw rejected(response);
            }
            TechniqueActivityCommit activity = response.getActivity();
            if (!activity.getActivity().equals(submission.activity())) {
                throw new IllegalStateException("功法宿主回执活动与提交计划不一致");
            }
            Commit commit = new Commit(activity.getRevision(), activity.getActivity());
            receipts.put(submission.requestId(), commit);
            return commit;
        }

        @Override
        public EffectCommit submitEffect(EffectSubmission submission) {
            // 世界副作用只交给当前认领者，未认领或失败都不能推进效果游标。
            EffectIntent previous = effectReceipts.get(submission.requestId());
            if (previous != null) {
                if (!previous.equals(submission.effect())) {
                    throw new IllegalStateException("功法效果回执 request_id 重复且内容不一致");
                }
                return new EffectCommit(submission.requestId());
            }
            TechniqueHostRequest request = TechniqueHostRequest.newBuilder()
                    .setEntityUuid(entityUuid)
                    .setRequestId(submission.requestId())
                    .setEffect(submission.effect())
                    .build();
            TechniqueHostResponse response = exchange(entrypoint, request);
            if (response.getResultCase() != TechniqueHostResponse.ResultCase.EFFECT_ACCEPTED) {
                throw rejected(response);
            }
            effectReceipts.put(submission.requestId(), submission.effect());
            return new EffectCommit(submission.requestId());
        }

        private BodySnapshot body(TechniqueHostResponse response) {
            // 只有身体回执能进入状态机，未认领响应统一作为身体不存在处理。
            if (response.getResultCase() != TechniqueHostResponse.ResultCase.BODY) {
                throw rejected(response);
            }
            return BodyProtobuf.fromMessage(response.getBody());
        }

        private TechniqueErrors.HostRejected rejected(TechniqueHostResponse response) {
            // 宿主拒绝保留原码；未认领及错误结果类型都不能继续使用旧身体。
            if (response.getResultCase() == TechniqueHostResponse.ResultCase.FAILURE) {
                return new TechniqueErrors.HostRejected(response.getFailure().getCode());
            }
            return new TechniqueErrors.HostRejected(FailureCode.BODY_NOT_FOUND);
        }
    }

    public static final class MultipleHostsException extends RuntimeException {
        public MultipleHostsException(String entityUuid) {
            // 多重认领会让实体同时关联多个身体，必须作为接线错误停止。
            super("实体同时被多个功法宿主认领：" + entityUuid);
        }
    }
}
