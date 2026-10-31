package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class TechniqueLeapPlayerMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void meridian$authoritativeLeap(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        // 飞行中客户端只能转头，不能用位置包覆盖服务端求出的轨迹或提前落地；挂起的请求连视线也冻结。
        // 26.2 的 ServerPlayer.level() 直接是服务端世界，服务器也从世界取。
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.level());
        var access = (TechniqueLeapAccess) player;
        if (access.meridian$getLeapPending()) {
            callback.cancel();
        } else if (access.meridian$getLeap() != null) {
            if (player.level().getServer().isSameThread()) {
                float yaw = packet.getYRot(player.getYRot());
                float pitch = packet.getXRot(player.getXRot());
                if (Float.isFinite(yaw) && Float.isFinite(pitch)) {
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                }
            }
            callback.cancel();
        }
    }
}
