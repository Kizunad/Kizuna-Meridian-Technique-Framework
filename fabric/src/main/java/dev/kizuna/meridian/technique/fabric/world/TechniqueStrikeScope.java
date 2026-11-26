package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.EffectIntent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;

/** 兵器打击逐个伤到目标期间可读的施放身份；伤害附属与内容模组据此认出这一击属于哪次施放。 */
public final class TechniqueStrikeScope {
    private static final ScopedValue<Strike> current = ScopedValue.newInstance();

    private TechniqueStrikeScope() {
        // 身份只随当前调用栈传递，不保存到实体或身体。
    }

    /**
     * 一次兵器打击的固定事实。
     *
     * @param effect  调度器发出的打击意图，带施放号、精确式、选定手和权威生效时刻
     * @param weapon  出击当刻选定手上的物品副本
     * @param targets 本击实际去打的目标，按结算先后排列；空挥时为空
     * @param source  本击的伤害来源，用来和附伤、反伤区分
     */
    public record Strike(EffectIntent effect, ItemStack weapon, List<String> targets, DamageSource source) {
        public Strike {
            Objects.requireNonNull(effect);
            Objects.requireNonNull(weapon);
            targets = List.copyOf(targets);
            Objects.requireNonNull(source);
        }

        @Override
        public ItemStack weapon() {
            return weapon.copy();
        }
    }

    static void run(Strike strike, Runnable hits) {
        // 只覆盖本击的原版伤害调用，返回后立即失效。
        ScopedValue.where(current, strike).run(hits);
    }

    public static Optional<Strike> current(DamageSource source) {
        // 附伤和反伤使用另一个来源对象，不继承主击的施放身份。
        if (current.isBound() && current.get().source() == source) {
            return Optional.of(current.get());
        }
        return Optional.empty();
    }
}
