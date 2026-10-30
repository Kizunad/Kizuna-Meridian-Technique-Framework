package dev.kizuna.meridian.technique.fabric.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 乏力回执调用原版跳跃，保留跳跃提升、冲刺与原版统计。 */
@Mixin(LivingEntity.class)
public interface TechniqueJumpInvoker {
    @Invoker("jump")
    void meridian$ordinaryJump();
}
