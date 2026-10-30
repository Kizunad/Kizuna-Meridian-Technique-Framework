package dev.kizuna.meridian.technique.fabric.client.testmixin;

import dev.kizuna.meridian.technique.fabric.client.TechniqueTeleportCounter;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class TechniqueTeleportCounterMixin {
    @Inject(method = "onPlayerPositionLook", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;"
                    + "Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/thread/ThreadExecutor;)V"))
    private void meridian$countTeleport(PlayerPositionLookS2CPacket packet, CallbackInfo callback) {
        // 网络线程调度时 forceMainThread 会抛出并重入，只有主线程执行能越过该调用。
        TechniqueTeleportCounter.increment();
    }
}
