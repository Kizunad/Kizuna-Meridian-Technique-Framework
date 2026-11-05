package dev.kizuna.meridian.technique.fabric.mixin;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.technique.fabric.world.TechniqueTissueAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueTissueEffects;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TissueBenefitLeases;
import java.util.Base64;
import java.util.Collection;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class TechniqueTissueEffectMixin implements TechniqueTissueAccess {
    @Unique private static final String MERIDIAN_TISSUE_LEASES = "meridian_technique:tissue_benefits";
    @Unique private TechniqueTissueEffects meridian$tissues;

    @Override
    public TechniqueTissueEffects meridian$tissueEffects() {
        // 惰性创建避免构造实体时访问尚未初始化的世界。
        if (meridian$tissues == null) {
            meridian$tissues = new TechniqueTissueEffects((LivingEntity) (Object) this);
        }
        return meridian$tissues;
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void meridian$add(MobEffectInstance effect, Entity source, CallbackInfoReturnable<Boolean> callback) {
        // 原版命令和药水共用同一入口，身体拒绝时不先发布图标。
        TechniqueTissueEffects tissues = meridian$tissueEffects();
        if (tissues.handles(effect)) {
            callback.setReturnValue(tissues.add(effect));
        }
    }

    @Inject(method = "forceAddEffect", at = @At("HEAD"), cancellable = true)
    private void meridian$force(MobEffectInstance effect, Entity source, CallbackInfo callback) {
        // 强制替换仍必须经过能力事务，内部原版同步调用由控制器排除递归。
        TechniqueTissueEffects tissues = meridian$tissueEffects();
        if (tissues.handles(effect)) {
            tissues.apply(effect);
            callback.cancel();
        }
    }

    @Inject(method = "onEffectUpdated", at = @At("HEAD"))
    private void meridian$updated(MobEffectInstance effect, boolean reapply, Entity source, CallbackInfo callback) {
        // 隐藏等级恢复时采用原版实际实例；周期性的可见性更新不重启曲线。
        TechniqueTissueEffects tissues = meridian$tissueEffects();
        if (reapply && tissues.handles(effect)) {
            tissues.apply(effect);
        }
    }

    @Inject(method = "onEffectsRemoved", at = @At("HEAD"))
    private void meridian$removed(Collection<MobEffectInstance> effects, CallbackInfo callback) {
        // 到期、牛奶和命令清除都在本次身体后续事件前撤销自有修正。
        meridian$tissueEffects().removed(effects);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void meridian$reconcile(CallbackInfo callback) {
        // 仅加载或长时效果跨窗口触发事务，常规 tick 不重发曲线。
        if (meridian$tissues != null) {
            meridian$tissues.tick();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void meridian$unload(Entity.RemovalReason reason, CallbackInfo callback) {
        // 在实体从宿主目录撤销认领前冻结状态曲线，维度移动保留原版剩余时间。
        if (meridian$tissues != null) {
            meridian$tissues.suspend();
        }
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void meridian$death(DamageSource source, CallbackInfo callback) {
        // 先清理旧身体状态，复活实体不会继承旧组织归属。
        if (meridian$tissues != null) {
            LivingEntity actor = (LivingEntity) (Object) this;
            meridian$tissues.removed(actor.getActiveEffects());
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void meridian$save(ValueOutput output, CallbackInfo callback) {
        // 原版效果保存剩余刻数，额外字段只保存有界 Protobuf 相位与归属元数据。
        if (meridian$tissues != null) {
            TissueBenefitLeases saved = TissueBenefitLeases.newBuilder()
                    .addAllLeases(meridian$tissues.leases())
                    .build();
            output.putString(MERIDIAN_TISSUE_LEASES, Base64.getEncoder().encodeToString(saved.toByteArray()));
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void meridian$load(ValueInput input, CallbackInfo callback) {
        // 不在读档期间提交身体活动，身体认领完成后按原版剩余时间恢复。
        String encoded = input.getStringOr(MERIDIAN_TISSUE_LEASES, "");
        try {
            TissueBenefitLeases saved = TissueBenefitLeases.parseFrom(Base64.getDecoder().decode(encoded));
            meridian$tissueEffects().restore(saved.getLeasesList());
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("实体状态归属存档损坏", exception);
        }
    }
}
