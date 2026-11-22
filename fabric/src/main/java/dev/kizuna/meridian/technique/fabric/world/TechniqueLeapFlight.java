package dev.kizuna.meridian.technique.fabric.world;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.LivingEntity;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import java.util.function.Consumer;

/** 一次起跳的轨迹和落地边界，只活到落地、卸载或死亡。 */
public final class TechniqueLeapFlight {
    public enum WallCollisionPolicy { CONTINUE, FINISH }

    public record Start(LeapProfile profile, String effectId, double landingY, Vec3 origin, Vec3 launch,
                        String castId, WallCollisionPolicy wallCollisionPolicy,
                        Consumer<TechniqueLeaps.LeapResult> completion, Consumer<LivingEntity> contact) { }

    public final LeapProfile profile;
    public final String effectId;
    public final Vec3 origin;
    public final Vec3 launch;
    public final String castId;
    public final WallCollisionPolicy wallCollisionPolicy;
    public final Consumer<TechniqueLeaps.LeapResult> completion;
    public final Consumer<LivingEntity> contact;
    public double landingY;
    public Vec3 velocity;
    public Vec3 end;
    public int ticks;
    public boolean contacted;
    public LivingEntity preLandingContact;

    public TechniqueLeapFlight(Start start) {
        // 落点高度只豁免本次设计落差，更深处的跌落仍保留伤害；起点与初速度供两端回执比对。
        this.profile = start.profile();
        this.effectId = start.effectId();
        this.landingY = start.landingY();
        this.origin = start.origin();
        this.launch = start.launch();
        this.velocity = start.launch();
        this.castId = start.castId();
        this.wallCollisionPolicy = start.wallCollisionPolicy();
        this.completion = start.completion();
        this.contact = start.contact();
    }
}
