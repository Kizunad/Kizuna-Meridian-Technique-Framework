package dev.kizuna.meridian.technique.fabric.mixin;

import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.fabric.world.TechniqueWeaponBinds;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
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
        if (packet.hasPosition() && meridian$outsideBind(packet)) {
            // 留在最近一次通过原版碰撞校验的位置，并同步客户端的预测坐标。
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            callback.cancel();
            return;
        }
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

    private boolean meridian$outsideBind(ServerboundMovePlayerPacket packet) {
        // 服务端移动包可在碰撞后写回客户端位置，须同时约束这条入口。
        if (!TechniqueWeaponBinds.participates(player)) {
            return false;
        }
        Vec3 origin = player.position();
        Vec3 step = new Vec3(packet.getX(origin.x), packet.getY(origin.y), packet.getZ(origin.z)).subtract(origin);
        return !TechniqueWeaponBinds.limitMovement(player, step).equals(step);
    }
}
