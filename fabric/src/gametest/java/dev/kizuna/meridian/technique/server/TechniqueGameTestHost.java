package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static dev.kizuna.meridian.technique.server.Fixtures.*;

/** 只在 GameTest 中提供目录和按真实实体 UUID 认领的内存身体宿主。 */
public final class TechniqueGameTestHost implements TechniqueHostEntrypoint, TechniqueRegistryEntrypoint {
    private static final String PRESENTATION_ID = "sample:stance";
    private static final Map<UUID, MemoryHost> HOSTS = new HashMap<>();

    @Override
    public TechniqueRegistry registry() {
        // 测试内容通过与生产相同的目录 entrypoint 装配，不提供全局安装旁路。
        return Fixtures.registry(definitionWithEffects());
    }

    static void bind(UUID entityUuid, MemoryHost host) {
        // 身体只属于指定的真实测试实体，其他实体必须得到未认领响应。
        HOSTS.put(entityUuid, host);
    }

    static void unbind(UUID entityUuid) {
        // 测试结束后释放宿主引用，不影响已经写入内存存储的断言。
        HOSTS.remove(entityUuid);
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 所有操作经过真实字节契约，身体活动继续由 Framework 原子处理。
        TechniqueHostRequest request = TechniqueProtocol.decodeHost(payload);
        MemoryHost host = HOSTS.get(UUID.fromString(request.getEntityUuid()));
        if (host == null) {
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
    }

    private static byte[] route(MemoryHost host, TechniqueHostRequest request) {
        // 按操作类型分别确认身体快照、原子活动和世界效果。
        var response = TechniqueHostResponse.newBuilder().setRequestId(request.getRequestId());
        switch (request.getOperationCase()) {
            case INSPECT -> response.setBody(BodyProtobuf.toMessage(host.inspect()));
            case ACTIVITY -> {
                TechniqueHost.Commit commit = host.submit(new TechniqueHost.Submission(request.getRequestId(),
                        request.getExpectedRevision(), request.getActivity()));
                TechniqueActivityCommit activity = TechniqueActivityCommit.newBuilder()
                        .setRevision(commit.revision())
                        .setActivity(commit.activity())
                        .build();
                response.setActivity(activity);
            }
            case EFFECT -> {
                host.submitEffect(new TechniqueHost.EffectSubmission(request.getRequestId(), request.getEffect()));
                response.setEffectAccepted(true);
            }
            default -> throw new IllegalArgumentException("缺少宿主操作");
        }
        return TechniqueProtocol.encodeHostResponse(response.build());
    }

    private static TechniqueDefinition definitionWithEffects() {
        // 持续表现和冲量都只记录意图，不向正式资源注入任何功法。
        PresentationEffect presentation = PresentationEffect.newBuilder()
                .setId(PRESENTATION_ID)
                .setDuration(PresentationDuration.UNTIL_RECOVERY_END)
                .build();
        TechniqueEffect start = TechniqueEffect.newBuilder()
                .setAtMillis(0)
                .setPresentation(presentation)
                .build();
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(quantity(1, Unit.METERS_PER_SECOND))
                .build();
        TechniqueEffect movement = TechniqueEffect.newBuilder()
                .setAtMillis(WARMUP)
                .setImpulse(impulse)
                .build();
        return definition().toBuilder()
                .addEffects(start)
                .addEffects(movement)
                .build();
    }
}
