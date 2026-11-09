package dev.kizuna.meridian.technique.server;

/** 一次已开始施放离开活动状态的原因，供附属模组释放世界接管。 */
public enum TechniqueCastEndReason {
    COMPLETED,
    FAILED,
    INTERRUPTED,
    STAGGERED,
    DIED,
    UNLOADED,
    DISCONNECTED
}
