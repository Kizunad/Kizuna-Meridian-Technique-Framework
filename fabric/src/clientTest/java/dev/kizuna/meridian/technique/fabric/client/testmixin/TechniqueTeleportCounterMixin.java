package dev.kizuna.meridian.technique.fabric.client.testmixin;

import dev.kizuna.meridian.technique.fabric.client.TechniqueTeleportCounter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class TechniqueTeleportCounterMixin {
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;"
                    + "Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
    private void meridian$countTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        // 网络线程调度时 ensureRunningOnSameThread 会抛出并重入，
        // 只有主线程执行能越过该调用。
        TechniqueTeleportCounter.increment();
    }
}
