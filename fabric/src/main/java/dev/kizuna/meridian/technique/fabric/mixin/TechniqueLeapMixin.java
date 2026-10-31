package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapFlight;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class TechniqueLeapMixin implements TechniqueLeapAccess {
    @Unique private TechniqueLeapFlight meridian$leap;
    @Unique private long meridian$leapReadyTick;
    @Unique private boolean meridian$leapPending;
    @Unique private boolean meridian$weakNotice;

    @Override
    public TechniqueLeapFlight meridian$getLeap() {
        // 轨迹随实体释放。
        return meridian$leap;
    }

    @Override
    public void meridian$setLeap(TechniqueLeapFlight flight) {
        // 两端各自保存当前预测或权威轨迹。
        meridian$leap = flight;
    }

    @Override
    public long meridian$getLeapReadyTick() {
        // 世界时间达到该值前不能再次起跳。
        return meridian$leapReadyTick;
    }

    @Override
    public void meridian$setLeapReadyTick(long tick) {
        // 服务端落地时写入，本地玩家只采用服务端回执中的同一刻度。
        meridian$leapReadyTick = tick;
    }

    @Override
    public boolean meridian$getLeapPending() {
        // 早于可起跳时刻到达的请求由服务端挂起。
        return meridian$leapPending;
    }

    @Override
    public void meridian$markLeapPending() {
        // 挂起期间冻结请求时的位置与视线。
        meridian$leapPending = true;
    }

    @Override
    public void meridian$clearLeapPending() {
        // 到达可起跳时刻后按冻结状态重新决策。
        meridian$leapPending = false;
    }

    @Override
    public boolean meridian$getWeakNotice() {
        // 乏力连按只显示一次。
        return meridian$weakNotice;
    }

    @Override
    public void meridian$markWeakNotice() {
        // 记录已经显示的提示。
        meridian$weakNotice = true;
    }

    @Override
    public void meridian$clearWeakNotice() {
        // 成功跃步后允许下一次提示。
        meridian$weakNotice = false;
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void meridian$travel(Vec3 input, CallbackInfo callback) {
        // 服务端玩家由统一 tick 推进，其他实体与本地预测沿原版 travel 时序推进。
        LivingEntity actor = (LivingEntity) (Object) this;
        if (actor instanceof ServerPlayer && meridian$leap != null) {
            callback.cancel();
        } else if (TechniqueLeaps.travel(actor)) {
            callback.cancel();
        }
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void meridian$mobLeap(CallbackInfo callback) {
        // 生物在原版移动阶段读取目标，同样使用服务端落点求解器。
        if ((Object) this instanceof Mob mob) {
            TechniqueLeaps.tickMob(mob);
        }
    }

    @ModifyVariable(method = "causeFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double meridian$fallDistance(double distance) {
        // 仅减去本次跃步本应覆盖的下降，不授予持续的摔落免疫。
        // 26.2 的摔落距离是第一个 double 参数；按 float 取会改到第二个参数 damageModifier。
        return TechniqueLeaps.fallDistance((LivingEntity) (Object) this, distance);
    }
}
