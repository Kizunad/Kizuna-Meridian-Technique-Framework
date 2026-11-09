package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;

import java.util.Objects;
import java.util.UUID;

/** 一次施放最终结束的通知，载荷可由附属模组按 cast_id 做清理。 */
public record TechniqueCastEnded(UUID entityUuid, String castId, TechniqueCastEndReason reason,
                                 CastOutcome outcome) {
    public TechniqueCastEnded {
        // 结束事件的身份和结果必须完整，附属据此释放一次施放占用的资源。
        Objects.requireNonNull(entityUuid);
        Objects.requireNonNull(castId);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(outcome);
    }
}
