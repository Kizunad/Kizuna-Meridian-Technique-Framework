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
    /**
     * 路由确认一次管这么多 tick（10 秒）；过了就把其余宿主也问一遍，期间才给轻量读数。
     * 认领宿主 inspect 失败时不等到期，当场确认；这个周期只管 inspect 一直成功时多久复查一次归属。
     */
    static final int CONFIRM_TICKS = 200;

    private final List<TechniqueHostEntrypoint> entrypoints;
    /**
     * 实体到认领宿主的路由。只记「归谁」，不记身体：每次解析仍向这个宿主 inspect 一次。
     * 失效条件：该宿主回 UNBOUND（实体卸载、换了归属）时作废并重新探测全部宿主；
     * 实体卸载或玩家下线时由服务调用 forget 作废。没有认领者的结果不记。
     * 路由每隔 CONFIRM_TICKS 个 tick、或认领宿主 inspect 失败时，把其余宿主也问一遍；
     * 另有宿主认领同一实体时路由作废，下一次解析重新探测并报多重认领，不会被路由盖住。
     */
    private final Map<UUID, Route> routes = new HashMap<>();
    private long tick;

    public TechniqueHostDirectory(List<? extends TechniqueHostEntrypoint> entrypoints) {
        // 入口列表来自 Fabric 加载器；复制后一次解析不会受外部集合变化影响。
        this.entrypoints = List.copyOf(entrypoints);
    }

    /** 服务端每 tick 调一次，路由确认的有效期按它计。 */
    public void tick() {
        // 只推进计数，不主动查询任何宿主。
        tick++;
    }

    public Optional<TechniqueHost> resolve(UUID entityUuid) {
        // 已知归属时只问认领它的宿主；路由在这次查询里作废了（它不再认领，或另有宿主也认领）就回到逐个探测。
        Objects.requireNonNull(entityUuid);
        String entityId = entityUuid.toString();
        Route route = routes.get(entityUuid);
        if (route != null) {
            TechniqueProtocol.HostReply reply = ask(entityUuid, entityId, route);
            if (routes.get(entityUuid) == route) {
                return Optional.of(new RoutedHost(entityUuid, new Claim(route.entrypoint, reply)));
            }
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
        Claim claim = claims.get(0);
        var route = new Route(claim.entrypoint(), tick);
        route.inspectFailed = !answeredBody(claim.reply());
        routes.put(entityUuid, route);
        return Optional.of(new RoutedHost(entityUuid, claim));
    }

    private TechniqueProtocol.HostReply ask(UUID entityUuid, String entityId, Route route) {
        // 向认领宿主 inspect 一次并记下成败；它回 UNBOUND 时作废路由，由调用方决定要不要重新探测。
        // 确认过期或这次 inspect 失败时，顺带把其余宿主问一遍：还有别人认领就作废路由。
        // 这里不抛多重认领：施放中的查询和清理不该被接线错误打断，下一次解析重新探测时照常报错。
        TechniqueProtocol.HostReply reply = exchange(route.entrypoint, inspectRequest(entityId));
        if (reply.response().getResultCase() == TechniqueHostResponse.ResultCase.UNBOUND) {
            routes.remove(entityUuid, route);
            return reply;
        }
        route.inspectFailed = !answeredBody(reply);
        if (route.inspectFailed || tick - route.confirmedTick >= CONFIRM_TICKS) {
            if (claimedByAnother(entityId, route)) {
                routes.remove(entityUuid, route);
            } else {
                route.confirmedTick = tick;
            }
        }
        return reply;
    }

    private boolean claimedByAnother(String entityId, Route route) {
        // 其余宿主都应回 UNBOUND；有一个不是，就是第二个认领者。
        for (TechniqueHostEntrypoint entrypoint : entrypoints) {
            if (entrypoint == route.entrypoint) {
                continue;
            }
            TechniqueProtocol.HostReply other = exchange(entrypoint, inspectRequest(entityId));
            if (other.response().getResultCase() != TechniqueHostResponse.ResultCase.UNBOUND) {
                return true;
            }
        }
        return false;
    }

    private static boolean answeredBody(TechniqueProtocol.HostReply reply) {
        // inspect 成功就是回了身体；FAILURE 和别的结果都算失败。
        return reply.response().getResultCase() == TechniqueHostResponse.ResultCase.BODY;
    }

    /** 实体离开世界时作废它的路由；下次解析重新探测全部宿主。 */
    public void forget(UUID entityUuid) {
        // 没有记录时什么也不做。
        routes.remove(Objects.requireNonNull(entityUuid));
    }

    /**
     * 已知归属的实体的轻量读数；为空时调用方退回 inspect。
     * 只在 inspect 也会成功时给：路由上最近一次 inspect 失败、或确认已经过期时为空，
     * 调用方随后的 inspect 会重新确认路由。
     */
    public Optional<TechniqueBodyStamp> bodyStamp(UUID entityUuid) {
        // 只走已经确认过的路由，不为了读两个数去探测归属。
        Route route = routes.get(Objects.requireNonNull(entityUuid));
        if (route == null || route.inspectFailed || tick - route.confirmedTick >= CONFIRM_TICKS) {
            return Optional.empty();
        }
        return route.entrypoint.bodyStamp(entityUuid);
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

    /** 一条路由：认领宿主、最近一次把所有宿主都问过的 tick、最近一次向认领宿主 inspect 是否失败。 */
    private static final class Route {
        private final TechniqueHostEntrypoint entrypoint;
        private long confirmedTick;
        private boolean inspectFailed;

        private Route(TechniqueHostEntrypoint entrypoint, long confirmedTick) {
            // 首次探测时所有宿主都问过，从这一 tick 起算。
            this.entrypoint = entrypoint;
            this.confirmedTick = confirmedTick;
        }
    }

    private final class RoutedHost implements TechniqueHost {
        private final UUID entity;
        private final String entityUuid;
        private final TechniqueHostEntrypoint entrypoint;
        private final Map<String, Commit> receipts = new HashMap<>();
        private final Map<String, EffectIntent> effectReceipts = new HashMap<>();
        private TechniqueProtocol.HostReply initialReply;

        private RoutedHost(UUID entity, Claim claim) {
            // 保留探测回执只用于第一次读取，之后每次操作都重新向宿主确认当前身体。
            this.entity = entity;
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
                reply = reinspect();
            }
            return inspection(reply);
        }

        private TechniqueProtocol.HostReply reinspect() {
            // 路由还指着这个宿主时经目录查询，成败和定期确认都记在路由上；路由已经换人或作废时只问自己。
            Route route = routes.get(entity);
            if (route == null || route.entrypoint != entrypoint) {
                return exchange(entrypoint, inspectRequest(entityUuid));
            }
            return ask(entity, entityUuid, route);
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
