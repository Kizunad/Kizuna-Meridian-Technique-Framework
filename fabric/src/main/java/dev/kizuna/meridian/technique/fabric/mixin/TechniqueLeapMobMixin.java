package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class TechniqueLeapMobMixin {
    @Inject(method = "serverAiStep", at = @At("TAIL"))
    private void meridian$stopDirectedNavigation(CallbackInfo callback) {
        Mob mob = (Mob) (Object) this;
        if (!directedActionsLocked(mob)) {
            return;
        }
        mob.getNavigation().stop();
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void meridian$preventOrdinaryAttack(ServerLevel level, Entity target,
                                                 CallbackInfoReturnable<Boolean> callback) {
        if (directedActionsLocked((Mob) (Object) this)) {
            callback.setReturnValue(false);
        }
    }

    private static boolean directedActionsLocked(Mob mob) {
        return ((TechniqueLeapAccess) mob).meridian$directedActionsLocked();
    }
}
