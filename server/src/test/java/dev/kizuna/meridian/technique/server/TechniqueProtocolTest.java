package dev.kizuna.meridian.technique.server;

import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import org.junit.Test;

import static dev.kizuna.meridian.technique.server.Fixtures.*;
import static org.junit.Assert.*;

public class TechniqueProtocolTest {
    private static final String ENTITY_UUID = "00000000-0000-0000-0000-000000000001";
    private static final int INSPECT_FIELD = 10;
    private static final int ACTIVITY_FIELD = 11;
    private static final int EFFECT_FIELD = 12;
    private static final int OVERSIZE_KNOWN_COUNT = 400;
    private static final int LONG_SOURCE_LENGTH = 200;

    @Test
    public void hostOperationsUseStableFieldNumbersAndRoundTrip() {
        // 宿主协议保留指定 oneof 编号，三种操作分别往返，当前不执行任何世界效果。
        assertEquals(INSPECT_FIELD, TechniqueHostRequest.getDescriptor().findFieldByName("inspect").getNumber());
        assertEquals(ACTIVITY_FIELD, TechniqueHostRequest.getDescriptor().findFieldByName("activity").getNumber());
        assertEquals(EFFECT_FIELD, TechniqueHostRequest.getDescriptor().findFieldByName("effect").getNumber());
        TechniqueHostRequest inspect = hostRequest().setInspect(true /* inspect */).build();
        assertEquals(inspect, TechniqueProtocol.decodeHost(TechniqueProtocol.encodeHost(inspect)));
        Any progress = TechniqueState.pack(TechniqueState.empty());
        BodyActivity activity = BodyActivity.newBuilder().putProgress(TechniqueState.KEY, progress).build();
        TechniqueHostRequest submit = hostRequest().setActivity(activity).build();
        assertEquals(submit, TechniqueProtocol.decodeHost(TechniqueProtocol.encodeHost(submit)));
        TechniqueHostRequest effect = hostRequest().setEffect(impulseIntent()).build();
        assertEquals(effect, TechniqueProtocol.decodeHost(TechniqueProtocol.encodeHost(effect)));
        PresentationIntent presentation = PresentationIntent.newBuilder()
                .setId("sample:display")
                .setAction(PresentationAction.STOP)
                .build();
        EffectIntent display = EffectIntent.newBuilder()
                .setCastId("display")
                .setPresentation(presentation)
                .build();
        TechniqueHostRequest displayRequest = hostRequest().setEffect(display).build();
        assertEquals(displayRequest, TechniqueProtocol.decodeHost(TechniqueProtocol.encodeHost(displayRequest)));
    }

    @Test
    public void hostRejectsMissingOperationInvalidUuidAndMalformedPayload() {
        // 缺操作、假查询、错误实体身份和超大输入均不能进入将来的宿主分发。
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.encodeHost(hostRequest().build()));
        TechniqueHostRequest falseInspect = hostRequest().setInspect(false /* inspect */).build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.encodeHost(falseInspect));
        TechniqueHostRequest invalidUuid = hostRequest().setEntityUuid("1-1-1-1-1")
                .setInspect(true /* inspect */)
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.encodeHost(invalidUuid));
        byte[] excessive = new byte[TechniqueProtocol.MAX_PAYLOAD_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.decodeHost(excessive));
        byte[] valid = TechniqueProtocol.encodeHost(hostRequest().setEffect(impulseIntent()).build());
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.decodeHost(truncated));
        EffectIntent noEffect = EffectIntent.newBuilder().setCastId("empty").build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(noEffect));
        BodyActivity removal = BodyActivity.newBuilder().addRemoveModifiers("sample:modifier").build();
        TechniqueHostRequest removalRequest = hostRequest().setActivity(removal).build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.encodeHost(removalRequest));
    }

    @Test
    public void horizontalDirectionAcceptsAbsentZeroAndFiniteValuesButRejectsNonfinite() {
        // 方向是纯输入数据，零向量与缺省合法，任一非有限分量都必须拒绝。
        CastRequest absent = request(body(Shape.TENDRIL), "direction");
        assertEquals(absent, TechniqueProtocol.decodeCast(TechniqueProtocol.encodeCast(absent)));
        HorizontalDirection zero = HorizontalDirection.getDefaultInstance();
        CastRequest zeroRequest = absent.toBuilder().setDirection(zero).build();
        assertEquals(zeroRequest, TechniqueProtocol.decodeCast(TechniqueProtocol.encodeCast(zeroRequest)));
        HorizontalDirection finite = HorizontalDirection.newBuilder()
                .setX(Double.MAX_VALUE)
                .setZ(-Double.MAX_VALUE)
                .build();
        CastRequest directed = absent.toBuilder().setDirection(finite).build();
        assertEquals(directed, TechniqueProtocol.decodeCast(TechniqueProtocol.encodeCast(directed)));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            HorizontalDirection x = HorizontalDirection.newBuilder().setX(invalid).build();
            HorizontalDirection z = HorizontalDirection.newBuilder().setZ(invalid).build();
            for (HorizontalDirection direction : java.util.List.of(x, z)) {
                CastRequest request = absent.toBuilder().setDirection(direction).build();
                assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.encodeCast(request));
                EffectIntent effect = impulseIntent().toBuilder().setDirection(direction).build();
                assertThrows(IllegalArgumentException.class, () -> TechniqueProtocol.validateEffect(effect));
            }
        }
    }

    @Test
    public void corruptStateAndOversizeAnyAreRejectedWithoutOverwritingBody() {
        // 类型错误不能当成未学会，错误版本也不能静默清空原存档。
        TechniqueDefinition definition = definition();
        BodySnapshot before = body(Shape.TENDRIL);
        Any wrongType = Any.pack(BodyProtobuf.toMessage(before).getSettledAt());
        var wrong = BodyProtobuf.toMessage(before).toBuilder().putAddonProgress(TechniqueState.KEY, wrongType).build();
        BodySnapshot corrupted = BodyProtobuf.fromMessage(wrong);
        assertThrows(IllegalArgumentException.class, () -> TechniqueState.read(corrupted));
        var host = new MemoryHost(corrupted);
        CastOutcome failure = new TechniqueCasting(registry(definition)).cast(host, request(before, "corrupt"));
        assertEquals(TechniqueFailure.INVALID_STATE, failure.getFailure());
        assertFalse(failure.getReason().isBlank());
        assertEquals(corrupted, host.current());
        TechniqueProgress future = TechniqueState.empty().toBuilder()
                .setSchemaVersion(TechniqueValues.SCHEMA_VERSION + 1)
                .build();
        assertThrows(IllegalArgumentException.class, () -> TechniqueState.decode(future.toByteArray()));
        var oversized = TechniqueState.empty().toBuilder();
        KnownTechnique original = progress(definition).getKnown(0);
        for (int index = 0; index < OVERSIZE_KNOWN_COUNT; index++) {
            TechniqueReference reference = original.getTechnique().toBuilder().setVersion(index + 1).build();
            KnownTechnique known = original.toBuilder()
                    .setTechnique(reference)
                    .setSource("sample:" + "x".repeat(LONG_SOURCE_LENGTH))
                    .build();
            oversized.addKnown(known);
        }
        assertThrows(IllegalArgumentException.class, () -> TechniqueState.pack(oversized.build()));
    }

    @Test
    public void generatedDescriptorContainsImportedProtocolAndNoFormalContent() throws Exception {
        // 描述集可供其他语言生成同一协议，发布资源中不夹带测试功法。
        String path = "/META-INF/kizuna_meridian/protocol/technique-v1.desc";
        try (var stream = TechniqueCatalog.class.getResourceAsStream(path)) {
            assertNotNull(stream);
            FileDescriptorSet descriptors = FileDescriptorSet.parseFrom(stream);
            assertTrue(descriptors.getFileList().stream()
                    .anyMatch(file -> file.getName().endsWith("technique.proto")));
            assertTrue(descriptors.getFileList().stream()
                    .anyMatch(file -> file.getName().equals("kizuna/meridian/v1/activity.proto")));
        }
        assertNull(TechniqueCatalog.class.getResource("/techniques.textproto"));
    }

    private static TechniqueHostRequest.Builder hostRequest() {
        // UUID 用于未来实体路由，和 Framework 的身体 ID 不混为一谈。
        return TechniqueHostRequest.newBuilder()
                .setEntityUuid(ENTITY_UUID)
                .setRequestId("host_request");
    }

    private static EffectIntent impulseIntent() {
        // INPUT 在此只校验声明，实际速度换算和方向归一化留给宿主效果阶段。
        Quantity speed = quantity(1, Unit.METERS_PER_SECOND);
        Impulse impulse = Impulse.newBuilder()
                .setDirection(ImpulseDirection.INPUT)
                .setSpeed(speed)
                .build();
        return EffectIntent.newBuilder()
                .setCastId("impulse")
                .setImpulse(impulse)
                .build();
    }
}
