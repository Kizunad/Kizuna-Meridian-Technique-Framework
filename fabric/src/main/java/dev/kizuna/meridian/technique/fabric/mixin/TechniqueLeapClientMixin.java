package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.client.TechniqueLeapClient;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class TechniqueLeapClientMixin {
    @Shadow private int jumpingCooldown;

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void meridian$landingDelay(CallbackInfo callback) {
        // 轻身间隔由服务端 FINISH 回执的可起跳时刻决定，不叠加原版十 tick 的按键冷却；间隔内 WAIT 不发包。
        if ((Object) this instanceof ClientPlayerEntity player && !player.isSneaking()
                && TechniqueStatusBenefits.active(player).isPresent()) {
            jumpingCooldown = 0;
        }
    }

    @Inject(method = "jump", at = @At("HEAD"), cancellable = true)
    private void meridian$jump(CallbackInfo callback) {
        // 只接管本地玩家按跳跃键产生的请求，其他实体走权威 AI。
        if ((Object) this instanceof ClientPlayerEntity player && TechniqueLeapClient.jump(player)) {
            callback.cancel();
        }
    }
}
