package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public class TechniqueStage5Test {
    private static final UUID ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String WRONG_REQUEST_ID = "wrong-request";
    private static final String SECOND_ID = "sample:second";
    private static final long FINISHED_MILLIS = WARMUP + ACTIVE + RECOVERY;
    private static final double INPUT_X = 3;
    private static final double INPUT_Z = 4;
    private static final double QI_TOLERANCE = 1e-9;

    @Test
    public void directoryRejectsUnboundAndMultipleClaims() {
        // 未认领时解析为空；两个宿主同时返回身体时停止而不是选择其中一个账本。
        assertTrue(new TechniqueHostDirectory(List.of()).resolve(ENTITY_UUID).isEmpty());
        MemoryHost first = new MemoryHost(body(Shape.TENDRIL));
        MemoryHost second = new MemoryHost(body(Shape.TENDRIL));
        TechniqueHostEntrypoint firstEntrypoint = entrypoint(first, ENTITY_UUID);
        TechniqueHostEntrypoint secondEntrypoint = entrypoint(second, ENTITY_UUID);
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(firstEntrypoint, secondEntrypoint));
        assertThrows(TechniqueHostDirectory.MultipleHostsException.class,
                () -> directory.resolve(ENTITY_UUID));
    }

    @Test
    public void directoryChecksRequestIdAndRoutesEffectOnly() {
        // 字节入口必须回显请求 ID；效果只接受 EffectIntent，不会被误当成身体活动。
        MemoryHost host = new MemoryHost(body(Shape.TENDRIL));
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(entrypoint(host, ENTITY_UUID)));
        TechniqueHost routed = directory.resolve(ENTITY_UUID).orElseThrow();
        EffectIntent effect = impulseIntent();
        TechniqueHost.EffectCommit commit = routed.submitEffect(
                new TechniqueHost.EffectSubmission("effect-1", effect));
        assertEquals("effect-1", commit.requestId());
        assertEquals(List.of(effect), host.effects);

        TechniqueHostEntrypoint mismatched = payload -> {
            TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
            return TechniqueProtocol.encodeHostResponse(TechniqueHostResponse.newBuilder()
                    .setRequestId(WRONG_REQUEST_ID)
                    .setUnbound(true)
                    .build());
        };
        TechniqueHostDirectory badDirectory = new TechniqueHostDirectory(List.of(mismatched));
        assertThrows(IllegalStateException.class, () -> badDirectory.resolve(ENTITY_UUID));
    }

    @Test
    public void learnPublishesKnownChangedAndKnownReturnsCommittedEntry() {
        // 学会只写进度并发通知，查询读取已提交的可信记录。
        Presentation presentation = Presentation.newBuilder()
                .setDescription("第二功法")
                .build();
        TechniqueDefinition second = definition().toBuilder()
                .setId(SECOND_ID)
                .setPresentation(presentation)
                .build();
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), TechniqueState.empty());
        MemoryHost host = new MemoryHost(initial);
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(entrypoint(host, ENTITY_UUID)));
        TechniqueService service = new TechniqueService(registry(second), directory);
        List<TechniqueService.KnownChanged> events = new ArrayList<>();
        service.addKnownChangedListener(events::add);

        List<TechniqueService.KnownEntry> learned = service.learn(ENTITY_UUID,
                TechniqueRegistry.reference(second));
        assertEquals(1, learned.size());
        assertEquals(SECOND_ID, learned.get(0).technique().getId());
        assertEquals("第二功法", learned.get(0).name());
        assertEquals(1, events.size());
        assertEquals(learned, events.get(0).known());
        assertEquals(SECOND_ID, service.known(ENTITY_UUID).get(0).technique().getId());
        assertEquals(learned, service.learn(ENTITY_UUID, TechniqueRegistry.reference(second)));
        assertEquals(1, events.size());
        assertEquals(1, host.activitySubmissions);
    }

    @Test
    public void castTickDispatchesForwardImpulseThenFinishes() {
        // tick 按身体时刻下发效果，零输入转成朝前，收势清除当前施放。
        TechniqueDefinition impulseDefinition = impulseDefinition();
        BodySnapshot prepared = withProgress(body(Shape.TENDRIL), progress(impulseDefinition));
        MemoryHost impulseHost = new MemoryHost(prepared);
        TechniqueService impulseService = service(impulseHost, impulseDefinition);
        CastRequest request = request(prepared, "stage5");
        CastOutcome cast = impulseService.cast(ENTITY_UUID, request);
        assertEquals(CastPhase.WARMUP, cast.getPhase());

        impulseHost.nowMillis = WARMUP;
        impulseService.tick();
        assertEquals(1, impulseHost.effects.size());
        assertEquals(ImpulseDirection.FORWARD, impulseHost.effects.get(0).getImpulse().getDirection());

        impulseHost.nowMillis = FINISHED_MILLIS;
        impulseService.tick();
        assertFalse(TechniqueState.read(impulseHost.current()).hasActiveCast());
    }

    @Test
    public void lostHostRemovesSessionInsteadOfRetryingEveryTick() {
        // 未认领和身体不存在都代表终止关系，后续 tick 不再访问旧宿主。
        for (TechniqueHostResponse.ResultCase refusal : List.of(TechniqueHostResponse.ResultCase.UNBOUND,
                TechniqueHostResponse.ResultCase.FAILURE)) {
            ControlledHost endpoint = controlledHost(definition());
            TechniqueService service = controlledService(endpoint, definition());
            assertEquals(CastPhase.WARMUP,
                    service.cast(ENTITY_UUID, request(endpoint.host.current(), "lost_host")).getPhase());
            endpoint.refusal = refusal;
            endpoint.failure = FailureCode.BODY_NOT_FOUND;
            service.tick();
            int exchanges = endpoint.exchanges;
            service.tick();
            assertEquals(exchanges, endpoint.exchanges);
            assertTrue(service.cancelOnUnload(ENTITY_UUID).isEmpty());
        }
    }

    @Test
    public void lostHostDuringEffectOrRecoveryRemovesSession() {
        // 失去认领既可能发生于读取，也可能发生于效果或收势提交。
        for (TechniqueHostRequest.OperationCase operation : List.of(TechniqueHostRequest.OperationCase.EFFECT,
                TechniqueHostRequest.OperationCase.ACTIVITY)) {
            TechniqueDefinition definition = impulseDefinition();
            ControlledHost endpoint = controlledHost(definition);
            TechniqueService service = controlledService(endpoint, definition);
            service.cast(ENTITY_UUID, request(endpoint.host.current(), "lost_during_submit"));
            endpoint.host.nowMillis = FINISHED_MILLIS;
            endpoint.refusedOperation = operation;
            endpoint.refusal = TechniqueHostResponse.ResultCase.UNBOUND;
            service.tick();
            int exchanges = endpoint.exchanges;
            service.tick();
            assertEquals(exchanges, endpoint.exchanges);
            assertTrue(service.cancelOnUnload(ENTITY_UUID).isEmpty());
        }
    }

    @Test
    public void temporaryRejectionRetriesButPermanentRejectionDropsSession() {
        // 并发冲突保留游标允许重试，权限拒绝不会永久占用服务会话。
        ControlledHost endpoint = controlledHost(impulseDefinition());
        TechniqueService service = controlledService(endpoint, impulseDefinition());
        service.cast(ENTITY_UUID, request(endpoint.host.current(), "retry"));
        endpoint.host.nowMillis = WARMUP;
        endpoint.refusal = TechniqueHostResponse.ResultCase.FAILURE;
        endpoint.failure = FailureCode.REVISION_CONFLICT;
        service.tick();
        assertTrue(endpoint.host.effects.isEmpty());
        endpoint.refusal = TechniqueHostResponse.ResultCase.RESULT_NOT_SET;
        service.tick();
        assertEquals(1, endpoint.host.effects.size());
        endpoint.refusal = TechniqueHostResponse.ResultCase.FAILURE;
        endpoint.failure = FailureCode.ACCESS_DENIED;
        service.tick();
        int exchanges = endpoint.exchanges;
        service.tick();
        assertEquals(exchanges, endpoint.exchanges);
    }

    @Test
    public void advancingHostClockDoesNotDispatchEffectTwice() {
        // 宿主在相邻 inspect 间结时前进，游标仍须对应真正生成意图的时刻。
        TechniqueDefinition definition = impulseDefinition();
        ControlledHost endpoint = controlledHost(definition);
        TechniqueService service = controlledService(endpoint, definition);
        service.cast(ENTITY_UUID, request(endpoint.host.current(), "moving_clock"));
        endpoint.host.nowMillis = WARMUP - 1;
        endpoint.clockStepMillis = 1;
        service.tick();
        service.tick();
        service.tick();
        assertEquals(1, endpoint.host.effects.size());
    }

    @Test
    public void servicePreservesInputDirectionAndTreatsExplicitZeroAsForward() {
        // 显式零向量与缺省方向一致，非零输入原样留给宿主解释。
        HorizontalDirection input = HorizontalDirection.newBuilder()
                .setX(INPUT_X)
                .setZ(INPUT_Z)
                .build();
        for (HorizontalDirection direction : List.of(HorizontalDirection.getDefaultInstance(), input)) {
            TechniqueDefinition definition = impulseDefinition();
            ControlledHost endpoint = controlledHost(definition);
            TechniqueService service = controlledService(endpoint, definition);
            CastRequest request = request(endpoint.host.current(), "direction").toBuilder()
                    .setDirection(direction)
                    .build();
            service.cast(ENTITY_UUID, request);
            endpoint.host.nowMillis = WARMUP;
            service.tick();
            EffectIntent effect = endpoint.host.effects.get(0);
            if (direction.equals(input)) {
                assertEquals(ImpulseDirection.INPUT, effect.getImpulse().getDirection());
                assertEquals(input, effect.getDirection());
            } else {
                assertEquals(ImpulseDirection.FORWARD, effect.getImpulse().getDirection());
            }
        }
    }

    @Test
    public void effectRetryPreservesBatchAfterPartialAcceptanceAndRecovery() {
        // 部分效果成功后拒绝时复用原批次；即使收势已提交也不能丢掉剩余意图。
        TechniqueDefinition single = impulseDefinition();
        TechniqueDefinition definition = single.toBuilder().addEffects(single.getEffects(0)).build();
        for (long nowMillis : List.of(WARMUP, FINISHED_MILLIS)) {
            ControlledHost endpoint = controlledHost(definition);
            TechniqueService service = controlledService(endpoint, definition);
            service.cast(ENTITY_UUID, request(endpoint.host.current(), "effect_retry"));
            endpoint.host.nowMillis = nowMillis;
            endpoint.rejectedEffectCall = 2;
            service.tick();
            assertEquals(1, endpoint.host.effects.size());
            endpoint.host.nowMillis++;
            service.tick();
            service.tick();
            assertEquals(2, endpoint.host.effects.size());
            assertEquals(3, endpoint.effectCalls);
        }
    }

    @Test
    public void castReplayDoesNotRewindEffectCursor() {
        // 相同施放请求重放不能重建会话并再次下发已确认的效果。
        TechniqueDefinition definition = impulseDefinition();
        ControlledHost endpoint = controlledHost(definition);
        TechniqueService service = controlledService(endpoint, definition);
        CastRequest request = request(endpoint.host.current(), "cursor_replay");
        service.cast(ENTITY_UUID, request);
        endpoint.host.nowMillis = WARMUP;
        service.tick();
        assertTrue(service.cast(ENTITY_UUID, request).getReplayed());
        service.tick();
        assertEquals(1, endpoint.host.effects.size());
    }

    @Test
    public void serviceRejectsUnknownAndUnlearnedClientReferences() {
        // 服务入口仍校验目录与可信知识，客户端不能绕过状态机。
        TechniqueDefinition definition = definition();
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), TechniqueState.empty());
        MemoryHost host = new MemoryHost(initial);
        TechniqueService service = service(host, definition);
        CastRequest unlearned = request(initial, "unlearned");
        assertEquals(TechniqueFailure.NOT_KNOWN, service.cast(ENTITY_UUID, unlearned).getFailure());
        TechniqueReference unknown = unlearned.getTechnique().toBuilder().setId(SECOND_ID).build();
        CastRequest invalid = unlearned.toBuilder().setTechnique(unknown).build();
        assertEquals(TechniqueFailure.UNKNOWN_TECHNIQUE, service.cast(ENTITY_UUID, invalid).getFailure());
        assertEquals(initial, host.current());
    }

    @Test
    public void castWithoutHostReturnsNoHost() {
        // 没有实体宿主时不调用状态机或创建身体，直接给出结构化 NO_HOST。
        BodySnapshot body = body(Shape.TENDRIL);
        CastOutcome outcome = new TechniqueService(registry(definition()), new TechniqueHostDirectory(List.of()))
                .cast(ENTITY_UUID, request(body, "no_host"));
        assertEquals(TechniqueFailure.NO_HOST, outcome.getFailure());
        assertFalse(outcome.getReason().isBlank());
    }

    @Test
    public void learnRejectsDefinitionOutsideCurrentRegistry() {
        // 直接调用服务也必须经过当前目录，不能只依赖 Fabric 命令的预先查找。
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), TechniqueState.empty());
        MemoryHost host = new MemoryHost(initial);
        TechniqueDefinition unregistered = definition().toBuilder()
                .setId("sample:unregistered")
                .build();
        TechniqueService service = new TechniqueService(registry(definition()),
                new TechniqueHostDirectory(List.of(entrypoint(host, ENTITY_UUID))));
        assertThrows(IllegalArgumentException.class, () -> service.learn(ENTITY_UUID, unregistered));
        assertEquals(initial, host.current());
    }

    @Test
    public void serviceDamageInterruptClearsActiveCastWithoutRefund() {
        // 服务级伤害入口清除起手动作，已支付的费用和冷却仍由 Framework 进度保留。
        TechniqueDefinition definition = definition();
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(initial);
        TechniqueService service = service(host, definition);
        CastOutcome cast = service.cast(ENTITY_UUID, request(initial, "damage_interrupt"));
        assertEquals(CastPhase.WARMUP, cast.getPhase());

        CastOutcome interrupted = service.interruptByDamage(ENTITY_UUID).orElseThrow();
        assertEquals(CastPhase.INTERRUPTED, interrupted.getPhase());
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertEquals(COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
    }

    @Test
    public void serviceUnloadClearsActiveCastWithoutRefund() {
        // 实体卸载入口在撤销宿主访问前提交收势，费用和冷却不因离开世界退回。
        TechniqueDefinition definition = definition();
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(initial);
        TechniqueService service = service(host, definition);
        CastOutcome cast = service.cast(ENTITY_UUID, request(initial, "unload"));
        assertEquals(CastPhase.WARMUP, cast.getPhase());

        CastOutcome cancelled = service.cancelOnUnload(ENTITY_UUID).orElseThrow();
        assertEquals(CastPhase.CANCELLED, cancelled.getPhase());
        assertFalse(TechniqueState.read(host.current()).hasActiveCast());
        assertEquals(COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
    }

    @Test
    public void activeCastReplaysWhenDirectoryReceiptIsRebuilt() {
        // 宿主已提交而服务回执索引丢失时，权威 active_cast 仍阻止重复扣费。
        TechniqueDefinition definition = definition();
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), progress(definition));
        MemoryHost host = new MemoryHost(initial);
        CastRequest request = request(initial, "directory_retry");
        CastOutcome first = service(host, definition).cast(ENTITY_UUID, request);

        CastOutcome replayed = service(host, definition).cast(ENTITY_UUID, request);
        assertTrue(replayed.getReplayed());
        assertEquals(first.getRevision(), replayed.getRevision());
        assertEquals(COST_QI, host.store.environmentQi().totalQi(), QI_TOLERANCE);
    }

    private static TechniqueHostEntrypoint entrypoint(MemoryHost host, UUID entityUuid) {
        // 测试入口复用真实 Framework 活动处理器，模拟 Fabric entrypoint 字节交换。
        return payload -> {
            TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
            if (!request.getEntityUuid().equals(entityUuid.toString())) {
                return TechniqueProtocol.encodeHostResponse(TechniqueHostResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setUnbound(true)
                        .build());
            }
            try {
                return route(host, request);
            } catch (TechniqueErrors.HostRejected exception) {
                RequestFailure failure = RequestFailure.newBuilder()
                        .setCode(exception.failure())
                        .build();
                return TechniqueProtocol.encodeHostResponse(TechniqueHostResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setFailure(failure)
                        .build());
            }
        };
    }

    private static TechniqueService service(MemoryHost host, TechniqueDefinition definition) {
        // 每个服务使用独立宿主目录，模拟 Fabric 服务器按实体解析唯一认领者。
        TechniqueHostDirectory directory = new TechniqueHostDirectory(List.of(entrypoint(host, ENTITY_UUID)));
        return new TechniqueService(registry(definition), directory);
    }

    private static byte[] route(MemoryHost host, TechniqueHostRequest request) {
        // 三种操作分别返回身体、原子活动回执和世界效果确认。
        return switch (request.getOperationCase()) {
            case INSPECT -> TechniqueProtocol.encodeHostResponse(TechniqueHostResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setBody(BodyProtobuf.toMessage(host.inspect()))
                    .build());
            case ACTIVITY -> {
                TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(request.getRequestId(),
                        request.getExpectedRevision(), request.getActivity()));
                TechniqueActivityCommit activity = TechniqueActivityCommit.newBuilder()
                        .setRevision(commit.revision())
                        .setActivity(commit.activity())
                        .build();
                yield TechniqueProtocol.encodeHostResponse(TechniqueHostResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setActivity(activity)
                        .build());
            }
            case EFFECT -> {
                host.submitEffect(new TechniqueHost.EffectSubmission(request.getRequestId(), request.getEffect()));
                yield TechniqueProtocol.encodeHostResponse(TechniqueHostResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setEffectAccepted(true)
                        .build());
            }
            default -> throw new IllegalArgumentException("缺少宿主操作");
        };
    }

    private static EffectIntent impulseIntent() {
        // 测试效果只声明输入方向和速度，世界执行仍留在假宿主记录器。
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
        return EffectIntent.newBuilder()
                .setCastId("effect")
                .setImpulse(impulse)
                .build();
    }

    private static TechniqueDefinition impulseDefinition() {
        // 冲量只在起手结束边界触发，便于跨 tick 检查效果去重。
        TechniqueEffect effect = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setImpulse(impulseIntent().getImpulse())
                .build();
        return definition().toBuilder().addEffects(effect).build();
    }

    private static ControlledHost controlledHost(TechniqueDefinition definition) {
        // 可控拒绝仍经过真实 Framework 身体和字节协议。
        BodySnapshot initial = withProgress(body(Shape.TENDRIL), progress(definition));
        return new ControlledHost(new MemoryHost(initial));
    }

    private static TechniqueService controlledService(ControlledHost endpoint, TechniqueDefinition definition) {
        // 每个服务独立保存会话、活动回执和效果回执。
        return new TechniqueService(registry(definition), new TechniqueHostDirectory(List.of(endpoint)));
    }

    private static final class ControlledHost implements TechniqueHostEntrypoint {
        private final MemoryHost host;
        private TechniqueHostResponse.ResultCase refusal = TechniqueHostResponse.ResultCase.RESULT_NOT_SET;
        private TechniqueHostRequest.OperationCase refusedOperation;
        private FailureCode failure = FailureCode.BODY_NOT_FOUND;
        private long clockStepMillis;
        private int exchanges;
        private int effectCalls;
        private int rejectedEffectCall;

        private ControlledHost(MemoryHost host) {
            // 控制项只影响测试协议边界，不绕开身体原子活动处理。
            this.host = host;
        }

        @Override
        public byte[] exchange(byte[] payload) {
            // 可分别在 inspect、activity、effect 边界模拟失去认领或暂时拒绝。
            exchanges++;
            TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
            TechniqueHostRequest.OperationCase operation = request.getOperationCase();
            if (refusal != TechniqueHostResponse.ResultCase.RESULT_NOT_SET
                    && (refusedOperation == null || refusedOperation == operation)) {
                return reject(request, refusal, failure);
            }
            if (operation == TechniqueHostRequest.OperationCase.EFFECT) {
                effectCalls++;
                if (effectCalls == rejectedEffectCall) {
                    return reject(request, TechniqueHostResponse.ResultCase.FAILURE, FailureCode.INTERNAL_ERROR);
                }
            }
            byte[] response = route(host, request);
            if (operation == TechniqueHostRequest.OperationCase.INSPECT) {
                host.nowMillis += clockStepMillis;
            }
            return response;
        }

        private static byte[] reject(TechniqueHostRequest request, TechniqueHostResponse.ResultCase result,
                                     FailureCode failure) {
            // 未认领不携带身体，失败响应则保留 Framework 结构化原因。
            var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
            if (result == TechniqueHostResponse.ResultCase.UNBOUND) {
                response.setUnbound(true);
            } else {
                RequestFailure rejected = RequestFailure.newBuilder().setCode(failure).build();
                response.setFailure(rejected);
            }
            return TechniqueProtocol.encodeHostResponse(response.build());
        }
    }
}
