package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueBinding;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;

/** AI 使用与起手一致的只读费用预检；实际施放仍执行原子验证。 */
public final class TechniqueAvailability {
    private TechniqueAvailability() {
        // 不持有跨身体版本的预检缓存。
    }

    public static boolean affordable(BodySnapshot body, TechniqueDefinition definition, TechniqueBinding binding) {
        // 调用方先完成身体绑定，再以同一快照检查全部费用。
        return TechniqueActivities.affordable(body, definition, binding);
    }
}
