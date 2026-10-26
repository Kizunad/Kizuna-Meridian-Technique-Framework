package dev.kizuna.meridian.technique.server;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.MeridianMessages;
import dev.kizuna.meridian.server.BodyActivityExecution;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;

import java.io.IOException;
import java.util.UUID;

/** 有界协议解析与宿主消息校验；不发现宿主、不执行世界效果。 */
public final class TechniqueProtocol {
    public static final int MAX_PAYLOAD_BYTES = MeridianProtocol.MAX_PAYLOAD_BYTES;
    private static final int MAX_RECURSION = 32;

    private TechniqueProtocol() {
        // 对外只交换 Protobuf，不引入第二种序列化格式。
    }

    public static TechniqueCatalog decodeCatalog(byte[] payload) {
        // 完整语义校验需要已登记附属，随后必须调用 TechniqueRegistry.load。
        return parse(payload, TechniqueCatalog.parser());
    }

    public static byte[] encodeCast(CastRequest request) {
        // 本地构造的请求也不能省略身份或夹带非法方向。
        validateCast(request);
        return request.toByteArray();
    }

    public static CastRequest decodeCast(byte[] payload) {
        // 解析限制先于字段校验，避免超大字节进入业务层。
        CastRequest request = parse(payload, CastRequest.parser());
        validateCast(request);
        return request;
    }

    public static void validateCast(CastRequest request) {
        // 数值来源仅限目录；请求可以指定的只有身份、目标与水平输入方向。
        TechniqueValues.knownFields(request);
        validateReference(request.getTechnique());
        TechniqueValues.castId(request.getCastId());
        if (request.getBodyId().isBlank() || request.getBodyId().length() > TechniqueValues.MAX_TEXT
                || request.getTargetId().length() > TechniqueValues.MAX_TEXT) {
            throw TechniqueErrors.invalid("cast", "身体身份缺失或目标标识过长");
        }
        validateDirection(request.getDirection());
    }

    static void validateReference(TechniqueReference reference) {
        // 进度和请求使用完全相同的精确版本约束。
        TechniqueValues.id(reference.getId(), "technique.id");
        if (reference.getVersion() <= 0) {
            throw TechniqueErrors.invalid("technique.version", "版本必须大于 0");
        }
    }

    public static byte[] encodeHost(TechniqueHostRequest request) {
        // 可信入口也要限制允许操作及其结构，不能把 parseFrom 当成有效性证明。
        validateHost(request);
        return request.toByteArray();
    }

    public static TechniqueHostRequest decodeHost(byte[] payload) {
        // 先限制载荷，再校验允许操作，避免非法消息进入宿主分发。
        TechniqueHostRequest request = parse(payload, TechniqueHostRequest.parser());
        validateHost(request);
        return request;
    }

    public static void validateHost(TechniqueHostRequest request) {
        // UUID 使用标准完整文本；空操作与 inspect=false 都不能被解释为查询。
        TechniqueValues.knownFields(request);
        String entityUuid = request.getEntityUuid();
        try {
            if (!UUID.fromString(entityUuid).toString().equals(entityUuid)) {
                throw TechniqueErrors.invalid("entity_uuid", "必须是标准小写 UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw TechniqueErrors.invalid("entity_uuid", "必须是标准小写 UUID");
        }
        String requestId = request.getRequestId();
        if (requestId.isBlank() || requestId.length() > TechniqueValues.MAX_TEXT
                || request.getSerializedSize() > MAX_PAYLOAD_BYTES) {
            throw TechniqueErrors.invalid("request_id", "请求标识缺失、过长或消息超限");
        }
        switch (request.getOperationCase()) {
            case INSPECT -> {
                if (!request.getInspect()) {
                    throw TechniqueErrors.invalid("inspect", "查询操作必须为 true");
                }
            }
            case ACTIVITY -> validateActivity(request);
            case EFFECT -> validateEffect(request.getEffect());
            default -> throw TechniqueErrors.invalid("operation", "缺少或未知宿主操作");
        }
    }

    private static void validateActivity(TechniqueHostRequest request) {
        // 这里只验证活动结构；真实身体、revision 和守恒由宿主提交时复核。
        if (request.getActivity().getRemoveModifiersCount() != 0) {
            throw TechniqueErrors.invalid("activity.remove_modifiers", "本库的修正必须跑完整条曲线，不能提前移除");
        }
        BodyActivityExecution.validate(request.getActivity());
        MeridianMessages.BodyCommand command = MeridianMessages.BodyCommand.newBuilder()
                .setBodyId(request.getEntityUuid())
                .setExpectedRevision(0)
                .setExecuteActivity(request.getActivity())
                .build();
        MeridianMessages.RequestEnvelope envelope = MeridianMessages.RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(request.getRequestId())
                .setBodyCommand(command)
                .build();
        // Framework 编码器校验活动的嵌套必填项与数值，失败抛 IllegalArgumentException。
        MeridianProtocol.encodeRequest(envelope);
    }

    public static void validateEffect(EffectIntent intent) {
        // 世界效果只声明冲量或表现，身体能力修正必须使用权威活动。
        TechniqueValues.knownFields(intent);
        TechniqueValues.castId(intent.getCastId());
        validateDirection(intent.getDirection());
        if (intent.getTargetId().length() > TechniqueValues.MAX_TEXT) {
            throw TechniqueErrors.invalid("target_id", "目标身份过长");
        }
        switch (intent.getOperationCase()) {
            case IMPULSE -> TechniqueDefinitions.validateImpulse(intent.getImpulse());
            case PRESENTATION -> {
                PresentationIntent presentation = intent.getPresentation();
                TechniqueValues.id(presentation.getId(), "presentation.id");
                if (presentation.getActionValue() <= 0
                        || presentation.getAction() == PresentationAction.UNRECOGNIZED) {
                    throw TechniqueErrors.invalid("presentation.action", "未知表现操作");
                }
            }
            default -> throw TechniqueErrors.invalid("effect", "缺少或未知世界效果");
        }
    }

    private static void validateDirection(HorizontalDirection direction) {
        // 不在这里归一化，保留原始请求用于幂等比较；0 与缺省均表示朝向。
        if (!Double.isFinite(direction.getX()) || !Double.isFinite(direction.getZ())) {
            throw TechniqueErrors.invalid("direction", "水平 x、z 必须是有限数");
        }
    }

    static <T extends Message> T parse(byte[] payload, Parser<T> parser) {
        // 分配消息前限制整个输入，嵌套深度与未知字段同样在边界拒绝。
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw TechniqueErrors.invalid("payload", "字节载荷为空或超限");
        }
        CodedInputStream input = CodedInputStream.newInstance(payload);
        input.setSizeLimit(MAX_PAYLOAD_BYTES);
        input.setRecursionLimit(MAX_RECURSION);
        try {
            T message = parser.parseFrom(input);
            if (!input.isAtEnd()) {
                throw TechniqueErrors.invalid("payload", "存在未消费字节");
            }
            TechniqueValues.knownFields(message);
            return message;
        } catch (IOException exception) {
            throw TechniqueErrors.invalid("payload", "Protobuf 格式非法或载荷截断");
        }
    }
}
