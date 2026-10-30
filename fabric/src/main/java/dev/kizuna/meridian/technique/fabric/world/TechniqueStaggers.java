package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StaggerStatus;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 服务端僵直判定的世界表现：临时移动减速，以及给本人和观察者的剩余时长副本。 */
public final class TechniqueStaggers {
    public static final Identifier CHANNEL = new Identifier("meridian_technique", "stagger");
    public static final int MAX_PACKET_BYTES = 64;
    private static final String MODIFIER_NAME = "meridian_technique:stagger";
    private static final UUID MODIFIER_ID = UUID.nameUUIDFromBytes(MODIFIER_NAME.getBytes(StandardCharsets.UTF_8));

    private TechniqueStaggers() {
        // 判定只在 TechniqueService；这里只做表现与同步。
    }

    public static Consumer<TechniqueService.StaggerChanged> listener(MinecraftServer server) {
        // 每个服务器实例一个监听器，事件只在服务端主线程发布。
        return event -> apply(server, event);
    }

    private static void apply(MinecraftServer server, TechniqueService.StaggerChanged event) {
        // 实体已不在任何已加载世界时没有表现可撤销，临时修饰也不会写入存档。
        LivingEntity entity = find(server, event.entityUuid());
        if (entity == null) {
            return;
        }
        slow(entity, event);
        var message = StaggerStatus.newBuilder()
                .setEntityId(entity.getId())
                .setRemainingMillis(event.remainingMillis())
                .build();
        var recipients = new HashSet<>(PlayerLookup.tracking(entity));
        if (entity instanceof ServerPlayerEntity player) {
            recipients.add(player);
        }
        for (ServerPlayerEntity player : recipients) {
            sendTo(player, message);
        }
    }

    static LivingEntity find(MinecraftServer server, UUID entityUuid) {
        // 服务只记实体 UUID，逐个世界查找当前实例；施放者声音也按同一方式找锚点。
        for (ServerWorld world : server.getWorlds()) {
            if (world.getEntity(entityUuid) instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private static void slow(LivingEntity entity, TechniqueService.StaggerChanged event) {
        // 固定身份的乘法修饰：新僵直替换旧修饰，结束时移除；与施放限速各自独立。
        EntityAttributeInstance speed = entity.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(MODIFIER_ID);
        if (event.remainingMillis() > 0) {
            var modifier = new EntityAttributeModifier(MODIFIER_ID, MODIFIER_NAME,
                    event.movementMultiplier() - 1, EntityAttributeModifier.Operation.MULTIPLY_TOTAL);
            speed.addTemporaryModifier(modifier);
        }
        if (entity instanceof ServerPlayerEntity player) {
            // 本人立即收到属性；旁观者仍由原版实体跟踪器消费属性脏标记。
            player.networkHandler.sendPacket(new EntityAttributesS2CPacket(player.getId(), List.of(speed)));
        }
    }

    private static void sendTo(ServerPlayerEntity player, StaggerStatus message) {
        // 消息只有两个有界字段，缓冲区不跨接收者复用读指针。
        var buffer = PacketByteBufs.create();
        buffer.writeByteArray(message.toByteArray());
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }
}
