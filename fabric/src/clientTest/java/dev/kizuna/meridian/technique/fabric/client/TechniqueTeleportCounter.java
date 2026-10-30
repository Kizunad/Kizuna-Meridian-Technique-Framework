package dev.kizuna.meridian.technique.fabric.client;

import java.util.concurrent.atomic.AtomicInteger;

/** 统计本地玩家在主线程实际处理的服务端传送包。 */
public final class TechniqueTeleportCounter {
    private static final AtomicInteger count = new AtomicInteger();

    private TechniqueTeleportCounter() {
        // 只供验收入口读取累计值。
    }

    public static void increment() {
        // 由客户端网络处理器的 Mixin 在切回主线程后调用。
        count.incrementAndGet();
    }

    public static int count() {
        // 验收按前后差值判断，不重置全局计数。
        return count.get();
    }
}
