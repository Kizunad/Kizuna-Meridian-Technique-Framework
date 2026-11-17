package dev.kizuna.meridian.technique.fabric.world;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 不出粒子的状态效果：图标照常显示，身上不冒粒子，用 {@code /effect give} 给出来的也一样。
 *
 * <p>功法目录里的状态增益、附行图标，和伤害附属的灼伤、寒侵都继承它；
 * {@code TechniqueQuietEffectMixin} 按这个类型把效果实例的「显示粒子」固定成否。</p>
 */
public class TechniqueQuietEffect extends MobEffect {
    public TechniqueQuietEffect(MobEffectCategory category, int color) {
        // 图标和颜色来自内容，行为由各自的宿主执行。
        super(category, color);
    }
}
