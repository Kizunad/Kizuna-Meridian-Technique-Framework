package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class TechniqueLeapPlayerMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void meridian$authoritativeLeap(PlayerMoveC2SPacket packet, CallbackInfo callback) {
        // 飞行中客户端只能转头，不能用位置包覆盖服务端求出的轨迹或提前落地；挂起的请求连视线也冻结。
        NetworkThreadUtils.forceMainThread(packet, (ServerPlayNetworkHandler) (Object) this, player.getServerWorld());
        var access = (TechniqueLeapAccess) player;
        if (access.meridian$getLeapPending()) {
            callback.cancel();
        } else if (access.meridian$getLeap() != null) {
            if (player.getServer().isOnThread()) {
                float yaw = packet.getYaw(player.getYaw());
                float pitch = packet.getPitch(player.getPitch());
                if (Float.isFinite(yaw) && Float.isFinite(pitch)) {
                    player.setYaw(yaw);
                    player.setPitch(pitch);
                }
            }
            callback.cancel();
        }
    }
}
