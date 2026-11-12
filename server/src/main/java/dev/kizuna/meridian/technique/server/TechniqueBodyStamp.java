package dev.kizuna.meridian.technique.server;

/**
 * 宿主不结清、不构造身体就能报出的两个数。
 * revision 是宿主里已提交的身体版本；clockMillis 是此刻 inspect 结清以后 settled_at 会是多少毫秒。
 */
public record TechniqueBodyStamp(long revision, long clockMillis) {
    public TechniqueBodyStamp {
        // 两个数都来自身体协议，不能为负。
        if (revision < 0 || clockMillis < 0) {
            throw new IllegalArgumentException("身体版本和时钟不能为负");
        }
    }
}
