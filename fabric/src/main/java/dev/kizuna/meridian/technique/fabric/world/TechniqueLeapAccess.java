package dev.kizuna.meridian.technique.fabric.world;

/** 短暂飞行状态绑定实体生命周期，不保存第二份身体或药水时钟。 */
public interface TechniqueLeapAccess {
    TechniqueLeapFlight meridian$getLeap();
    void meridian$setLeap(TechniqueLeapFlight flight);
    long meridian$getLeapReadyTick();
    void meridian$setLeapReadyTick(long tick);
    boolean meridian$getLeapPending();
    void meridian$markLeapPending();
    void meridian$clearLeapPending();
    boolean meridian$getWeakNotice();
    void meridian$markWeakNotice();
    void meridian$clearWeakNotice();
}
