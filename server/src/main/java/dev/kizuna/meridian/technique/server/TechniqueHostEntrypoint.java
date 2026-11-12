package dev.kizuna.meridian.technique.server;

import java.util.Optional;
import java.util.UUID;

/** Fabric 可信宿主的进程内字节交换入口；实体归属和世界副作用由宿主负责。 */
@FunctionalInterface
public interface TechniqueHostEntrypoint {
    String ENTRYPOINT_ID = "meridian_technique_host";

    /** 交换一份完整的 TechniqueHostRequest 或 TechniqueHostResponse 字节载荷。 */
    byte[] exchange(byte[] request);

    /**
     * 可选的轻量读数：该实体身体已提交的版本，以及此刻被 inspect 时回执里 settled_at 会是多少毫秒。
     * 只回这两个数，不结清时间，也不构造身体，供僵直到期、施放有没有到下一个边界这类判断使用。
     * 答不出同样的数就返回空，调用方退回 inspect：宿主不认领该实体、身体尚未绑定、
     * 身体已死（时钟不再前进），或者宿主没有实现这个方法。
     */
    default Optional<TechniqueBodyStamp> bodyStamp(UUID entityUuid) {
        // 默认不提供，旧宿主的行为不变。
        return Optional.empty();
    }
}
