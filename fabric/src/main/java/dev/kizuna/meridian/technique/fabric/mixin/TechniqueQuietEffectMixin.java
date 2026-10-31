package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEffectInstance.class)
public abstract class TechniqueQuietEffectMixin {
    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
    private void meridian$quiet(CallbackInfoReturnable<Boolean> callback) {
        // 原版命令与保存读取也遵守无粒子，不改变其他药水显示。
        var effect = ((MobEffectInstance) (Object) this).getEffect();
        if (TechniqueStatusBenefits.quiet(effect)) {
            callback.setReturnValue(false /* showParticles */);
        }
    }

    @Inject(method = "isAmbient", at = @At("HEAD"), cancellable = true)
    private void meridian$ambient(CallbackInfoReturnable<Boolean> callback) {
        // 同一内容效果的显示属性不由命令的可选参数改变。
        var effect = ((MobEffectInstance) (Object) this).getEffect();
        if (TechniqueStatusBenefits.quiet(effect)) {
            callback.setReturnValue(true /* ambient */);
        }
    }

    @Inject(method = "showIcon", at = @At("HEAD"), cancellable = true)
    private void meridian$icon(CallbackInfoReturnable<Boolean> callback) {
        // 隐藏粒子不连带隐藏物品栏与 HUD 图标。
        var effect = ((MobEffectInstance) (Object) this).getEffect();
        if (TechniqueStatusBenefits.quiet(effect)) {
            callback.setReturnValue(true /* showIcon */);
        }
    }
}
