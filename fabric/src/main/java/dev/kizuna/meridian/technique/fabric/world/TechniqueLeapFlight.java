package dev.kizuna.meridian.technique.fabric.world;

import net.minecraft.world.phys.Vec3;

/** 一次起跳的轨迹和落地边界，只活到落地、卸载或死亡。 */
public final class TechniqueLeapFlight {
    public final TechniqueStatusBenefits.Registered registered;
    public final Vec3 origin;
    public final Vec3 launch;
    public final String castId;
    public double landingY;
    public Vec3 velocity;
    public Vec3 end;
    public int ticks;

    public TechniqueLeapFlight(TechniqueStatusBenefits.Registered registered, double landingY, Vec3 origin,
                               Vec3 launch, String castId) {
        // 落点高度只豁免本次设计落差，更深处的跌落仍保留伤害；起点与初速度供两端回执比对。
        this.registered = registered;
        this.landingY = landingY;
        this.origin = origin;
        this.launch = launch;
        this.velocity = launch;
        this.castId = castId;
    }
}
