package dev.kizuna.meridian.technique.fabric.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 原版拷贝构造器不复制隐藏实例，状态预检副本必须保留其完整降级链。 */
@Mixin(MobEffectInstance.class)
public interface TechniqueEffectAccess {
    @Accessor("hiddenEffect")
    MobEffectInstance meridian$hiddenEffect();

    @Accessor("hiddenEffect")
    void meridian$setHiddenEffect(MobEffectInstance effect);
}
