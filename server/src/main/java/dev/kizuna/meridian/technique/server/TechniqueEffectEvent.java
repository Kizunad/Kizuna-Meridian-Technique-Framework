package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;

import java.util.Objects;
import java.util.UUID;

/** 交给附属模组执行的一次世界效果，携带同一时刻采集的持物事实。 */
public record TechniqueEffectEvent(UUID entityUuid, EffectIntent intent, HeldItems heldItems) {
    public TechniqueEffectEvent {
        // 处理器只能读取完整身份、意图和同次采集的持物事实。
        Objects.requireNonNull(entityUuid);
        Objects.requireNonNull(intent);
        Objects.requireNonNull(heldItems);
    }
}
