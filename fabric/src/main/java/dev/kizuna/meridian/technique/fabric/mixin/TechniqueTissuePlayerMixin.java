package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueTissueEffects;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponBinds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class TechniqueTissuePlayerMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void meridian$death(DamageSource source, CallbackInfo callback) {
        // ServerPlayer 的死亡覆盖不调用 LivingEntity.die，须在此清理旧身体归属。
        ServerPlayer player = (ServerPlayer) (Object) this;
        TechniqueWeaponBinds.clear(player);
        TechniqueTissueEffects.of(player).removed(player.getActiveEffects());
    }
}
