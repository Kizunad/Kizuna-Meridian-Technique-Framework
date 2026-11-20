package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.MaintainedBreathingMessages.MaintainedBreathing;
import dev.kizuna.meridian.technique.server.TechniqueBreathingPractice;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** 运功只由身体发动；原版清除运功图标时通知身体收功。 */
public final class TechniqueBreathingStatuses {
    public static final Event<Removed> REMOVED = EventFactory.createArrayBacked(Removed.class,
            listeners -> (player, effectId) -> {
                for (Removed listener : listeners) {
                    listener.removed(player, effectId);
                }
            });
    private static final Map<String, Holder<MobEffect>> EFFECTS = new HashMap<>();
    private static ServerPlayer updating;

    private TechniqueBreathingStatuses() {
        // 原版注册表拥有定义，身体拥有维持时钟和费用。
    }

    public static void register(MaintainedBreathing definition) {
        // 不同版本的同一式可以共用同色图标。
        TechniqueBreathingPractice.validate(definition);
        String id = definition.getEffectId();
        var existing = EFFECTS.get(id);
        if (existing != null) {
            if (existing.value().getColor() != definition.getColorRgb()) {
                throw new IllegalArgumentException("运功图标颜色不一致：" + id);
            }
            return;
        }
        var effect = new TechniqueQuietEffect(MobEffectCategory.BENEFICIAL, definition.getColorRgb());
        EFFECTS.put(id, Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, Identifier.parse(id), effect));
    }

    public static void show(ServerPlayer player, String id) {
        // 授予只在本实体、当前同步调用内开放，跨玩家的授效仍会被拒。
        Holder<MobEffect> target = EFFECTS.get(id);
        if (target == null) {
            throw new IllegalArgumentException("未注册的运功图标：" + id);
        }
        var previous = updating;
        updating = player;
        try {
            player.addEffect(new MobEffectInstance(target, MobEffectInstance.INFINITE_DURATION, 0,
                    true /* ambient */, false /* showParticles */, true /* showIcon */));
        } finally {
            updating = previous;
        }
    }

    public static void clear(ServerPlayer player) {
        // 读档时也清除旧的孤立图标；主动撤效不再反向收第二次费用。
        var previous = updating;
        updating = player;
        try {
            EFFECTS.values().forEach(player::removeEffect);
        } finally {
            updating = previous;
        }
    }

    public static void end(ServerPlayer player, String techniqueId, String castId) {
        // 旧按键只能清它看到的那次运功，不能拿上次的身份摘掉后来重新起的效果。
        TechniqueFabric.service().statusHost(player.getUUID())
                .flatMap(host -> TechniqueBreathingPractice.active(host.inspect().body()))
                .filter(active -> active.getCastId().equals(castId) && active.getTechniqueId().equals(techniqueId))
                .ifPresent(active -> player.removeEffect(EFFECTS.get(active.getDefinition().getEffectId())));
    }

    public static boolean rejects(LivingEntity entity, MobEffectInstance incoming) {
        // 身体以外的指令、药剂和强制授效均不能启动运功。
        return !entity.level().isClientSide() && entity != updating && EFFECTS.containsValue(incoming.getEffect());
    }

    public static void removed(LivingEntity entity, Collection<MobEffectInstance> removed) {
        // 牛奶、图腾和指令都走原版的批量移除回调。
        if (!(entity instanceof ServerPlayer player) || player == updating) {
            return;
        }
        for (MobEffectInstance instance : removed) {
            for (var entry : EFFECTS.entrySet()) {
                if (instance.is(entry.getValue())) {
                    REMOVED.invoker().removed(player, entry.getKey());
                }
            }
        }
    }

    @FunctionalInterface
    public interface Removed {
        void removed(ServerPlayer player, String effectId);
    }
}
