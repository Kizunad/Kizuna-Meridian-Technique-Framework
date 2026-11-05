package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StatusBenefit;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.server.TechniqueBenefits;
import dev.kizuna.meridian.technique.server.TechniqueCatalogSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/** 初始化目录驱动原版效果注册，内容包只提供数据、语言与贴图。 */
public final class TechniqueStatusBenefits {
    private static final long MILLIS_PER_TICK = 50;
    private static final Map<String, Registered> benefits = new LinkedHashMap<>();

    public record Registered(StatusBenefit benefit, TechniqueDefinition technique, Holder<MobEffect> effect) { }

    private TechniqueStatusBenefits() {
        // 注册表在两个逻辑端以同一目录固定，不运行时制造新身份。
    }

    public static void register(List<TechniqueCatalogSource> sources) {
        // 同一效果只能有一份权威配置，重复身份明确报错。
        for (var source : sources) {
            for (var technique : source.catalog().getDefinitionsList()) {
                for (var effect : technique.getEffectsList()) {
                    if (effect.hasStatusBenefit()) {
                        register(technique, effect.getStatusBenefit());
                    }
                }
            }
        }
    }

    private static void register(TechniqueDefinition technique, StatusBenefit benefit) {
        // 先验证身份和范围，再注册为可被原版命令补全的有益状态；26.2 的效果实例按注册项引用效果。
        TechniqueBenefits.validate(benefit);
        if (benefits.containsKey(benefit.getId())) {
            throw new IllegalArgumentException("状态增益身份重复：" + benefit.getId());
        }
        Holder<MobEffect> effect = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
                Identifier.parse(benefit.getId()), new QuietBenefit(benefit.getColorRgb()));
        benefits.put(benefit.getId(), new Registered(benefit, technique, effect));
    }

    public static Registered get(String id) {
        // 缺少初始化声明属于接线错误，不能静默跳过。
        Registered registered = benefits.get(id);
        if (registered == null) {
            throw new IllegalArgumentException("未注册的状态增益：" + id);
        }
        return registered;
    }

    public static Optional<Registered> active(LivingEntity actor) {
        // 旧宿主把此入口用于跃步与 AI，保留只查询跃步能力的兼容语义。
        return activeLeap(actor);
    }

    public static Optional<Registered> activeLeap(LivingEntity actor) {
        // 跃步只读取有跃步能力的状态，其他增益的注册或添加顺序不影响选择。
        return benefits.values().stream()
                .filter(value -> value.benefit().hasLeap() && actor.hasEffect(value.effect())).findFirst();
    }

    public static Optional<Registered> tissue(MobEffectInstance instance) {
        // 能力接线按目录描述查找，不识别内容包的具体状态 ID。
        return benefits.values().stream()
                .filter(value -> value.benefit().hasTissue() && instance.is(value.effect())).findFirst();
    }

    public static List<Registered> tissues() {
        // 重载只核对组织状态，不改变既有轻身的生命周期。
        return benefits.values().stream().filter(value -> value.benefit().hasTissue()).toList();
    }

    public static boolean quiet(MobEffect effect) {
        // 命令给予同种效果也保持无粒子。
        return effect instanceof QuietBenefit;
    }

    public static void apply(LivingEntity actor, StatusBenefit benefit) {
        // 直接替换同身份状态，使重复施放只刷新持续时间且不保留高等级。
        Registered registered = get(benefit.getId());
        if (!registered.benefit().equals(benefit)) {
            throw new IllegalArgumentException("状态增益与初始化配置不一致");
        }
        int ticks = Math.toIntExact((benefit.getDurationMillis() + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK);
        MobEffectInstance instance = new MobEffectInstance(registered.effect(), ticks, 0,
                true /* ambient */, false /* showParticles */, true /* showIcon */);
        if (benefit.hasTissue()) {
            TechniqueTissueEffects.of(actor).apply(instance);
        } else {
            actor.removeEffect(registered.effect());
            actor.addEffect(instance);
        }
    }

    private static final class QuietBenefit extends MobEffect {
        private QuietBenefit(int color) {
            // 图标和颜色来自内容，行为由共享跃步宿主执行。
            super(MobEffectCategory.BENEFICIAL, color);
        }
    }
}
