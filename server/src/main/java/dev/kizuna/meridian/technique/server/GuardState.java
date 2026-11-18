package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Guard;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;

import java.util.Objects;

/** 当前守状态的只读投影；不持久化，也不接受客户端上传。 */
public record GuardState(String bodyId, String castId, TechniqueReference technique, Guard guard) {
    public GuardState {
        // 查询结果保留精确身体与施放身份，供受击结算后定向收势。
        Objects.requireNonNull(bodyId);
        TechniqueValues.castId(castId);
        Objects.requireNonNull(technique);
        Objects.requireNonNull(guard);
    }
}
