package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
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
    /**
     * 实体到认领宿主的路由。只记「归谁」，不记身体：每次解析仍向这个宿主 inspect 一次。
     * 失效条件：该宿主回 UNBOUND（实体卸载、换了归属）时作废并重新探测全部宿主；
     * 实体卸载或玩家下线时由服务调用 forget 作废。没有认领者的结果不记。
     */
    private final Map<UUID, TechniqueHostEntrypoint> routes = new HashMap<>();

    public TechniqueHostDirectory(List<? extends TechniqueHostEntrypoint> entrypoints) {
        // 入口列表来自 Fabric 加载器；复制后一次解析不会受外部集合变化影响。
        this.entrypoints = List.copyOf(entrypoints);
    }

    public Optional<TechniqueHost> resolve(UUID entityUuid) {
        // 已知归属时只问认领它的宿主；它不再认领就作废路由，回到逐个探测。
        Objects.requireNonNull(entityUuid);
        String entityId = entityUuid.toString();
        TechniqueHostEntrypoint routed = routes.get(entityUuid);
        if (routed != null) {
            TechniqueProtocol.HostReply reply = exchange(routed, inspectRequest(entityId));
            if (reply.response().getResultCase() != TechniqueHostResponse.ResultCase.UNBOUND) {
                return Optional.of(new RoutedHost(entityUuid, new Claim(routed, reply)));
            }
            routes.remove(entityUuid);
        }
        return probe(entityUuid, entityId);
    }

    private Optional<TechniqueHost> probe(UUID entityUuid, String entityId) {
        // 用 inspect 探测认领关系，只有一个宿主返回身体时才发布路由。
        var claims = new ArrayList<Claim>();
        for (TechniqueHostEntrypoint entrypoint : entrypoints) {
            TechniqueProtocol.HostReply reply = exchange(entrypoint, inspectRequest(entityId));
            if (reply.response().getResultCase() == TechniqueHostResponse.ResultCase.UNBOUND) {
                continue;
            }
            claims.add(new Claim(entrypoint, reply));
        }
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        if (claims.size() > 1) {
            throw new MultipleHostsException(entityId);
        }
        routes.put(entityUuid, claims.get(0).entrypoint());
        return Optional.of(new RoutedHost(entityUuid, claims.get(0)));
    }

    /** 实体离开世界时作废它的路由；下次解析重新探测全部宿主。 */
    public void forget(UUID entityUuid) {
        // 没有记录时什么也不做。
        routes.remove(Objects.requireNonNull(entityUuid));
    }

    /** 已知归属的实体的轻量读数；没有路由或宿主答不出时为空，调用方退回 inspect。 */
    public Optional<TechniqueBodyStamp> bodyStamp(UUID entityUuid) {
        // 只走已经确认过的路由，不为了读两个数去探测归属。
        TechniqueHostEntrypoint routed = routes.get(Objects.requireNonNull(entityUuid));
        if (routed == null) {
            return Optional.empty();
        }
        return routed.bodyStamp(entityUuid);
    }

    private static TechniqueProtocol.HostReply exchange(TechniqueHostEntrypoint entrypoint,
                                                        TechniqueHostRequest request) {
        // entrypoint 只交换有界 Protobuf，响应必须回显 request_id 才能防止串包。
        byte[] payload = TechniqueProtocol.encodeHost(request);
        TechniqueProtocol.HostReply reply = TechniqueProtocol.decodeHostReply(entrypoint.exchange(payload));
        if (!reply.response().getRequestId().equals(request.getRequestId())) {
            throw new IllegalStateException("功法宿主回执 request_id 不一致");
        }
        return reply;
    }

    private static TechniqueHostRequest inspectRequest(String entityId) {
        // 探测请求不携带身体 ID，宿主只能按实体身份决定是否认领。
        return TechniqueHostRequest.newBuilder()
                .setEntityUuid(entityId)
                .setRequestId("technique_inspect_" + UUID.randomUUID())
                .setInspect(true)
                .build();
    }

    private record Claim(TechniqueHostEntrypoint entrypoint, TechniqueProtocol.HostReply reply) { }

    private final class RoutedHost implements TechniqueHost {
        private final String entityUuid;
        private final TechniqueHostEntrypoint entrypoint;
        private final Map<String, Commit> receipts = new HashMap<>();
        private final Map<String, EffectIntent> effectReceipts = new HashMap<>();
        private TechniqueProtocol.HostReply initialReply;

        private RoutedHost(UUID entity, Claim claim) {
            // 保留探测回执只用于第一次读取，之后每次操作都重新向宿主确认当前身体。
            this.entityUuid = entity.toString();
            this.entrypoint = claim.entrypoint();
            this.initialReply = claim.reply();
        }

        @Override
        public Inspection inspect() {
            // 初次探测的身体可直接使用，后续读取必须重新结算宿主时钟。
            TechniqueProtocol.HostReply reply = initialReply;
            initialReply = null;
            if (reply == null) {
                reply = exchange(entrypoint, inspectRequest(entityUuid));
            }
            return inspection(reply);
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
            TechniqueHostResponse response = exchange(entrypoint, request).response();
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
            TechniqueHostResponse response = exchange(entrypoint, request).response();
            if (response.getResultCase() != TechniqueHostResponse.ResultCase.EFFECT_ACCEPTED) {
                throw rejected(response);
            }
            effectReceipts.put(submission.requestId(), submission.effect());
            return new EffectCommit(submission.requestId());
        }

        private Inspection inspection(TechniqueProtocol.HostReply reply) {
            // 只有身体回执能进入状态机，未认领响应统一作为身体不存在处理。
            TechniqueHostResponse response = reply.response();
            if (response.getResultCase() != TechniqueHostResponse.ResultCase.BODY) {
                throw rejected(response);
            }
            // 解码这份回执时已经把身体转成快照并校验过，直接用，不再转第二遍。
            BodySnapshot body = reply.body().orElseThrow();
            return new Inspection(body, response.getHeldItems(), response.getInspectFacts());
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
