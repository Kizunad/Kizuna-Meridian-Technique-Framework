package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponBinds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class TechniqueBindMovementMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 meridian$limitBoundMovement(Vec3 movement) {
        // 在原版方块碰撞前限制双方的步幅，载具本身仍可带着乘客脱离。
        if ((Object) this instanceof LivingEntity living) {
            return TechniqueWeaponBinds.limitMovement(living, movement);
        }
        return movement;
    }
}
