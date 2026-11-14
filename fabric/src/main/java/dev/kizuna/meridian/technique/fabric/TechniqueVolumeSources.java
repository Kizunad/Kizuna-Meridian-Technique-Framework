package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSources;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 功法谱来源暗示的服务端登记处：各附属登记「谁身上有哪一卷」，进服时整份发给客户端。
 *
 * <p>只用来在功法谱里拼展示句子，不参与学卷、掉落或任何判定。附属在初始化期 {@link #register}；
 * 来源在运行中变了（例如重载配置）就调 {@link #refresh} 让在线玩家换成新的一份。
 * 功法谱只列有卷轴的卷，没有卷轴的卷照样核对，但不发给客户端。</p>
 */
public final class TechniqueVolumeSources {
    private static final int MAX_PACKET_BYTES = 256 * 1024;
    private static final int MAX_SOURCES = 4096;
    public static final ProtobufChannel CHANNEL = new ProtobufChannel(
            Identifier.fromNamespaceAndPath("meridian_technique", "volume_sources"), MAX_PACKET_BYTES);
    private static final Map<Identifier, Provider> PROVIDERS = new LinkedHashMap<>();

    private TechniqueVolumeSources() {
        // 只有静态登记处。
    }

    @FunctionalInterface
    public interface Provider {
        /** 这家附属在当前服务器上登记的来源；在服务端主线程调用。 */
        List<VolumeSource> sources(MinecraftServer server);
    }

    public static synchronized void register(Identifier id, Provider provider) {
        // 初始化期登记，同一身份只能登记一次。
        if (PROVIDERS.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException("功法谱来源已登记：" + id);
        }
    }

    public static void refresh(MinecraftServer server) {
        // 来源变了以后在线玩家各发一份新的。
        var sources = collect(server);
        server.getPlayerList().getPlayers().forEach(player -> send(player, sources));
    }

    static void initialize() {
        // 通道两端都要登记；功法服务建好后立刻 collect 一遍，配置写错的卷在开服时就报出来。
        CHANNEL.registerClientbound();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> join(handler.player, server));
    }

    private static void join(ServerPlayer player, MinecraftServer server) {
        // 没有功法目录的服务器没有卷可暗示，不发。
        if (TechniqueFabric.running()) {
            send(player, collect(server));
        }
    }

    static synchronized VolumeSources collect(MinecraftServer server) {
        // 按登记顺序汇总，每条都核对。
        var builder = VolumeSources.newBuilder();
        PROVIDERS.forEach((id, provider) -> provider.sources(server).forEach(source -> {
            validate(id, source);
            if (hasScroll(source)) {
                builder.addSources(source);
            }
        }));
        if (builder.getSourcesCount() > MAX_SOURCES) {
            throw new IllegalArgumentException("功法谱来源超过 " + MAX_SOURCES + " 条");
        }
        return builder.build();
    }

    static void validate(Identifier provider, VolumeSource source) {
        // 卷必须在功法目录里；生物来源要写实体、境界和 (0, 1] 的占比，仇家来源要写出身语言键。
        if (TechniqueFabric.service().registry().findVolume(source.getVolumeId()).isEmpty()) {
            throw invalid(provider, "卷不在功法目录里 " + source.getVolumeId());
        }
        switch (source.getKindCase()) {
            case KNOWER -> {
                var knower = source.getKnower();
                if (Identifier.tryParse(knower.getEntityType()) == null || knower.getRealm() == Realm.REALM_UNSPECIFIED
                        || !(knower.getShare() > 0 && knower.getShare() <= 1)) {
                    throw invalid(provider, "生物来源不完整 " + source.getVolumeId());
                }
            }
            case FOE -> {
                if (source.getFoe().getOriginKey().isBlank()) {
                    throw invalid(provider, "仇家来源没写出身 " + source.getVolumeId());
                }
            }
            case KIND_NOT_SET -> throw invalid(provider, "来源没写类别 " + source.getVolumeId());
        }
    }

    private static boolean hasScroll(VolumeSource source) {
        // 目录里的卷 ID 都合法；只有初始化期生成了卷轴的卷才会出现在功法谱里。
        Identifier volume = Identifier.parse(source.getVolumeId());
        return TechniqueScrollItems.scrolls().containsKey(volume.withSuffix("_scroll"));
    }

    private static IllegalArgumentException invalid(Identifier provider, String detail) {
        // 报错带上登记方，方便找到写错的配置。
        return new IllegalArgumentException("功法谱来源 " + provider + "：" + detail);
    }

    private static void send(ServerPlayer player, VolumeSources sources) {
        // 没装客户端功法库的连接收不到，也不需要。
        if (ServerPlayNetworking.canSend(player, CHANNEL.type())) {
            ServerPlayNetworking.send(player, CHANNEL.payload(sources.toByteArray()));
        }
    }
}
