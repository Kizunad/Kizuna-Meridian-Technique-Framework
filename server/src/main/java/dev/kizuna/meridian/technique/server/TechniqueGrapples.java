package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ArmRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Grapple;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.GrappleIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Throw;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ThrowIntent;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.ThrowLean;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.Unit;

/** 擒、摔两种附属效果的声明边界；这里只校验数值和缩放时长，抓人、抛人由附属处理器执行。 */
public final class TechniqueGrapples {
    private static final double MAX_MULTIPLIER = 10;
    private static final double MAX_PUSH = 10;
    private static final double MAX_APEX = 16;

    private TechniqueGrapples() {
        // 纯静态校验。
    }

    static boolean usesGrapple(TechniqueDefinition definition) {
        // 擒和摔按书级空手伤害结算，整式因此要求所属书声明拳掌规则。
        return definition.getEffectsList().stream()
                .anyMatch(effect -> effect.hasGrapple() || effect.hasThrow());
    }

    static void validate(Grapple grapple) {
        // 抓取距离有界，拉到身前的距离不超过抓取距离；声明了一靠时，它的时刻落在擒的时长里。
        TechniqueValues.id(grapple.getProcessorId(), "grapple.processor_id");
        double reach = TechniqueValues.positive(grapple.getMaximumDistance(), Unit.METER,
                TechniqueValues.MAX_DISTANCE, "grapple.maximum_distance");
        TechniqueValues.positive(grapple.getHoldDistance(), Unit.METER, reach, "grapple.hold_distance");
        positiveMillis(grapple.getDurationMillis(), "grapple.duration_millis");
        if (!grapple.hasLean()) {
            // 不声明一靠：摔不动的目标既不擒也不靠，这一式别的效果照常走（如缠腕只拧不擒）。
            return;
        }
        validateLean(grapple.getLean());
        if (grapple.getLean().getDelayMillis() > grapple.getDurationMillis()) {
            throw TechniqueErrors.invalid("grapple.lean.delay_millis", "一靠不能晚于擒的时长");
        }
    }

    static void validate(Throw toss) {
        // 飞行、落点、倒地都必须显式给出，倒地后的免摔时长可以为零。
        TechniqueValues.id(toss.getProcessorId(), "throw.processor_id");
        positiveMillis(toss.getFlightMillis(), "throw.flight_millis");
        TechniqueValues.positive(toss.getLandingDistance(), Unit.METER,
                TechniqueValues.MAX_DISTANCE, "throw.landing_distance");
        TechniqueValues.quantity(toss.getApexHeight(), Unit.METER, 0, MAX_APEX, "throw.apex_height");
        TechniqueValues.id(toss.getDamageTypeId(), "throw.damage_type_id");
        TechniqueValues.positive(toss.getMultiplier(), Unit.RATIO, MAX_MULTIPLIER, "throw.multiplier");
        positiveMillis(toss.getDownMillis(), "throw.down_millis");
        TechniqueValues.millis(toss.getDownImmunityMillis(), "throw.down_immunity_millis");
    }

    static void validateIntent(GrappleIntent intent) {
        // 意图里的声明和定义同一套边界，另要求带着书级拳掌规则。
        validate(intent.getGrapple());
        requireArmRules(intent.hasArmRules(), "grapple.arm_rules");
    }

    static void validateIntent(ThrowIntent intent) {
        // 同上。
        validate(intent.getThrow());
        requireArmRules(intent.hasArmRules(), "throw.arm_rules");
    }

    static void validateWithin(TechniqueEffect effect, long durationMillis) {
        // 擒的时长从生效时刻算起，不能超出整式。
        validate(effect.getGrapple());
        if (effect.getGrapple().getDurationMillis() > durationMillis - effect.getAtMillis()) {
            throw TechniqueErrors.invalid("grapple.duration_millis", "擒的时长超出施放时长");
        }
    }

    static GrappleIntent intent(Grapple grapple, ArmRules armRules) {
        // 世界意图带上起手时保存的书级拳掌规则，处理器按它算伤害。
        return GrappleIntent.newBuilder()
                .setGrapple(grapple)
                .setArmRules(armRules)
                .build();
    }

    static ThrowIntent intent(Throw toss, ArmRules armRules) {
        // 同上，摔的落地打击也按书级空手伤害。
        return ThrowIntent.newBuilder()
                .setThrow(toss)
                .setArmRules(armRules)
                .build();
    }

    public static Grapple scale(Grapple grapple, double tempo) {
        // 施放放慢时擒得更久，一靠也相应推迟。
        ThrowLean lean = grapple.getLean().toBuilder()
                .setDelayMillis(TechniqueTempo.scale(grapple.getLean().getDelayMillis(), tempo))
                .build();
        return grapple.toBuilder()
                .setDurationMillis(TechniqueTempo.scale(grapple.getDurationMillis(), tempo))
                .setLean(lean)
                .build();
    }

    public static Throw scale(Throw toss, double tempo) {
        // 只有飞行跟着施放速度；倒地和免摔属于被摔的人，不缩放。
        return toss.toBuilder()
                .setFlightMillis(TechniqueTempo.scale(toss.getFlightMillis(), tempo))
                .build();
    }

    private static void validateLean(ThrowLean lean) {
        // 一靠是一次打击：伤害类型、倍率、推开都要显式。
        TechniqueValues.millis(lean.getDelayMillis(), "grapple.lean.delay_millis");
        TechniqueValues.id(lean.getDamageTypeId(), "grapple.lean.damage_type_id");
        TechniqueValues.positive(lean.getMultiplier(), Unit.RATIO, MAX_MULTIPLIER, "grapple.lean.multiplier");
        TechniqueValues.quantity(lean.getPush(), Unit.RATIO, 0, MAX_PUSH, "grapple.lean.push");
    }

    private static void positiveMillis(long value, String path) {
        // 时长必须是正的毫秒数。
        TechniqueValues.millis(value, path);
        if (value <= 0) {
            throw TechniqueErrors.invalid(path, "时长必须大于零");
        }
    }

    private static void requireArmRules(boolean present, String path) {
        // 调度器从起手保存的书级规则填入；缺了说明意图不是本库发出的。
        if (!present) {
            throw TechniqueErrors.invalid(path, "缺少书级拳掌规则");
        }
    }
}
