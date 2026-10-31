package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StaggerStatus;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 服务端僵直判定的世界表现：临时移动减速，以及给本人和观察者的剩余时长副本。 */
public final class TechniqueStaggers {
    private static final int MAX_PACKET_BYTES = 64;
    public static final ProtobufChannel CHANNEL =
            new ProtobufChannel(Identifier.fromNamespaceAndPath("meridian_technique", "stagger"), MAX_PACKET_BYTES);
    private static final Identifier MODIFIER_ID = Identifier.fromNamespaceAndPath("meridian_technique", "stagger");

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
        if (entity instanceof ServerPlayer player) {
            recipients.add(player);
        }
        for (ServerPlayer player : recipients) {
            sendTo(player, message);
        }
    }

    static LivingEntity find(MinecraftServer server, UUID entityUuid) {
        // 服务只记实体 UUID，逐个世界查找当前实例；施放者声音也按同一方式找锚点。
        for (ServerLevel world : server.getAllLevels()) {
            if (world.getEntity(entityUuid) instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private static void slow(LivingEntity entity, TechniqueService.StaggerChanged event) {
        // 固定身份的乘法修饰：新僵直替换旧修饰，结束时移除；与施放限速各自独立。
        AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(MODIFIER_ID);
        if (event.remainingMillis() > 0) {
            // 26.2 的修饰按 ID 识别，不再有 UUID 和名字；ID 沿用原来的名字。
            var modifier = new AttributeModifier(MODIFIER_ID,
                    event.movementMultiplier() - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            speed.addTransientModifier(modifier);
        }
        if (entity instanceof ServerPlayer player) {
            // 本人立即收到属性；旁观者仍由原版实体跟踪器消费属性脏标记。
            player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), List.of(speed)));
        }
    }

    private static void sendTo(ServerPlayer player, StaggerStatus message) {
        // 消息只有两个有界字段，每个接收者一份新载荷。
        ServerPlayNetworking.send(player, CHANNEL.payload(message.toByteArray()));
    }
}
