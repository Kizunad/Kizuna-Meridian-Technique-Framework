package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueInfusionStatuses;
import dev.kizuna.meridian.technique.fabric.world.TechniqueBreathingStatuses;
import java.util.Collection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 附行图标和身体上的附行一起起落：外面给的图标不收，图标被清掉就结束附行，装载后对一次。 */
@Mixin(LivingEntity.class)
public abstract class TechniqueInfusionStatusMixin {
    /** 装载后身体宿主可能晚几 tick 才认领实体，对图标最多等这么多 tick。 */
    @Unique private static final int MERIDIAN_RECONCILE_TICKS = 100;
    @Unique private int meridian$infusionReconcileTicks;

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void meridian$rejectInfusionStatus(MobEffectInstance effect, Entity source,
                                               CallbackInfoReturnable<Boolean> callback) {
        // 指令和药水直接给附行图标：身体上没有对应的附行，不收。
        LivingEntity entity = (LivingEntity) (Object) this;
        if (TechniqueInfusionStatuses.rejects(entity, effect) || TechniqueBreathingStatuses.rejects(entity, effect)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "forceAddEffect", at = @At("HEAD"), cancellable = true)
    private void meridian$rejectForcedInfusionStatus(MobEffectInstance effect, Entity source, CallbackInfo callback) {
        // 强制替换走的是另一个入口，同样不收。
        LivingEntity entity = (LivingEntity) (Object) this;
        if (TechniqueInfusionStatuses.rejects(entity, effect) || TechniqueBreathingStatuses.rejects(entity, effect)) {
            callback.cancel();
        }
    }

    @Inject(method = "onEffectsRemoved", at = @At("HEAD"))
    private void meridian$infusionStatusRemoved(Collection<MobEffectInstance> effects, CallbackInfo callback) {
        // 牛奶、指令、图腾清掉图标时，附行跟着结束。
        LivingEntity entity = (LivingEntity) (Object) this;
        TechniqueBreathingStatuses.removed(entity, effects);
        TechniqueInfusionStatuses.removed(entity, effects);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void meridian$armInfusionReconcile(ValueInput input, CallbackInfo callback) {
        // 读档时不碰身体；等实体开始 tick、身体宿主认领以后再对。
        meridian$infusionReconcileTicks = MERIDIAN_RECONCILE_TICKS;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void meridian$reconcileInfusionStatus(CallbackInfo callback) {
        // 平时这里只比一个整数。装载后的头一 tick 看要不要对：玩家要对（附行可能还在、图标没了），
        // 别的实体只在带着图标时才对；不用对的当场清零。
        if (meridian$infusionReconcileTicks == 0) {
            return;
        }
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.level().isClientSide()) {
            meridian$infusionReconcileTicks = 0;
            return;
        }
        boolean wanted = entity instanceof ServerPlayer || TechniqueInfusionStatuses.shown(entity);
        if (!wanted || TechniqueInfusionStatuses.reconcile(entity)) {
            meridian$infusionReconcileTicks = 0;
        } else {
            meridian$infusionReconcileTicks--;
        }
    }
}
