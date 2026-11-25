package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponSplash;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.LivingEntity;

/** 泼洒沿宿主选敌和做功边界执行，由伤害宿主结算部位与组织能力。 */
public final class TechniqueWeaponSplash {
    public interface Released {
        void released(LivingEntity actor, TechniqueReference technique);
    }

    /** 热兵释放成功时通知一次，包括挥空。 */
    public static final Event<Released> RELEASED = EventFactory.createArrayBacked(Released.class,
            listeners -> (actor, technique) -> {
                for (var listener : listeners) {
                    listener.released(actor, technique);
                }
            });

    public interface Hit {
        boolean hit(LivingEntity target, DamageSource source, double dose, WeaponSplash splash);
    }

    public static final Event<Hit> HIT = EventFactory.createArrayBacked(Hit.class, callbacks ->
            (target, source, dose, splash) -> {
                if (callbacks.length != 1) {
                    throw new IllegalStateException("泼洒须由唯一的局部伤害宿主处理");
                }
                return callbacks[0].hit(target, source, dose, splash);
            });

    private static final int FAN_RAYS = 7;
    private static final int DROPS_PER_RAY = 4;
    private static final float DROP_SIZE = 0.7F;
    private static final double HALF = 0.5;
    private static final double HAND_HEIGHT = 0.7;
    private static final double ARC_HEIGHT = 0.35;

    static void show(LivingEntity actor, WeaponStrike strike) {
        // 颗粒从当前面向铺到扇区外沿，颜色沿用沉积物，落点和伤害仍由服务端选取。
        var level = (ServerLevel) actor.level();
        var dust = new DustParticleOptions(strike.getSplash().getCoating().getColorRgb(), DROP_SIZE);
        double angle = strike.getAngle().getValue();
        double distance = strike.getDistance().getValue();
        double facing = actor.getYRot() + strike.getFacingOffset().getValue();
        double handY = actor.getY(HAND_HEIGHT);
        for (int ray = 0; ray < FAN_RAYS; ray++) {
            double offset = angle * ((double) ray / (FAN_RAYS - 1) - HALF);
            var direction = Vec3.directionFromRotation(0, (float) (facing + offset));
            for (int step = 1; step <= DROPS_PER_RAY; step++) {
                double fraction = (double) step / DROPS_PER_RAY;
                var point = actor.position().add(direction.scale(distance * fraction));
                double y = handY + ARC_HEIGHT * Math.sin(Math.PI * fraction);
                level.sendParticles(dust, point.x, y, point.z, 1, 0, 0, 0, 0);
            }
        }
    }

    private TechniqueWeaponSplash() {
        // 事件不保存施放者或目标。
    }
}
