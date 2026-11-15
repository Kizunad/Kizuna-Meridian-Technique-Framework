package dev.kizuna.meridian.technique.fabric.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.technique.fabric.world.TechniqueHitFeel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HitFeel;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HitTier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 打中时的粒子：有血肉的溅血，骨头的出骨屑，铁的出火星，木头的出木屑；量随打得多重。
 * 目标是什么料按实体类型标签认（meridian_technique:feel/bone、metal、wood），都不在的算血肉。
 * 配置关了血以后，血肉换成普通的冲击粒子。
 */
public final class TechniqueHitFeelClient {
    private enum Material { FLESH, BONE, METAL, WOOD }

    private static final TagKey<EntityType<?>> BONE = tag("bone");
    private static final TagKey<EntityType<?>> METAL = tag("metal");
    private static final TagKey<EntityType<?>> WOOD = tag("wood");
    /** 四档各出多少颗：擦到、轻、重、打死。 */
    private static final int[] COUNTS = {3, 7, 14, 24};
    /** 一份里亮色的那种占几分之一（血里的鲜红、铁屑里的火星）。 */
    private static final int ACCENT_EVERY = 3;
    private static final double SPREAD = 0.15;
    private static final double FORWARD_SPEED = 0.12;
    private static final double FORWARD_JITTER = 0.2;
    private static final double SIDE_JITTER = 0.14;
    private static final double UP_SPEED = 0.05;
    private static final double UP_JITTER = 0.2;
    private static TechniqueFeelSettings settings;

    private TechniqueHitFeelClient() {
        // 收到就演，不保存状态。
    }

    public static void initialize() {
        // 进世界时读一次配置；收包回调已在客户端主线程上。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> settings = TechniqueFeelSettings.load());
        ClientPlayNetworking.registerGlobalReceiver(TechniqueHitFeel.CHANNEL.type(), (payload, context) -> {
            try {
                show(HitFeel.parseFrom(payload.bytes()));
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalArgumentException("打击提示消息非法", invalid);
            }
        });
    }

    private static void show(HitFeel hit) {
        ClientLevel level = Minecraft.getInstance().level;
        boolean enabled = settings != null && settings.hitParticles();
        if (level == null || !enabled || hit.getTier() == HitTier.HIT_TIER_UNSPECIFIED) {
            return;
        }
        Entity target = level.getEntity(hit.getTargetEntityId());
        if (target == null) {
            return;
        }
        Material material = material(target);
        ParticleOptions chunk = chunk(material);
        ParticleOptions accent = accent(material);
        RandomSource random = level.getRandom();
        int count = COUNTS[hit.getTier().getNumber() - 1];
        for (int index = 0; index < count; index++) {
            // 顺着打过来的方向往外溅，带一点向上和向两边的散开。
            double forward = FORWARD_SPEED + random.nextDouble() * FORWARD_JITTER;
            double side = (random.nextDouble() * 2 - 1) * SIDE_JITTER;
            level.addParticle(index % ACCENT_EVERY == 0 ? accent : chunk,
                    hit.getX() + (random.nextDouble() * 2 - 1) * SPREAD,
                    hit.getY() + (random.nextDouble() * 2 - 1) * SPREAD,
                    hit.getZ() + (random.nextDouble() * 2 - 1) * SPREAD,
                    hit.getDirectionX() * forward - hit.getDirectionZ() * side,
                    UP_SPEED + random.nextDouble() * UP_JITTER,
                    hit.getDirectionZ() * forward + hit.getDirectionX() * side);
        }
    }

    private static Material material(Entity target) {
        // 26.2 的实体类型没有直接查标签的方法，经注册表里的持有者查。
        var type = target.getType().builtInRegistryHolder();
        if (type.is(BONE)) {
            return Material.BONE;
        }
        if (type.is(METAL)) {
            return Material.METAL;
        }
        return type.is(WOOD) ? Material.WOOD : Material.FLESH;
    }

    private static ParticleOptions chunk(Material material) {
        // 碎块用原版的方块碎屑粒子：会飞出去、会落地，颜色取对应方块的贴图。
        return switch (material) {
            case FLESH -> settings.blood() ? block(Blocks.NETHER_WART_BLOCK) : ParticleTypes.CRIT;
            case BONE -> block(Blocks.BONE_BLOCK);
            case METAL -> block(Blocks.IRON_BLOCK);
            case WOOD -> block(Blocks.OAK_PLANKS);
        };
    }

    private static ParticleOptions accent(Material material) {
        return switch (material) {
            // 关了血：暴击星点里夹几团白烟，看得出打中了，没有红的。
            case FLESH -> settings.blood() ? block(Blocks.REDSTONE_BLOCK) : ParticleTypes.POOF;
            case BONE -> ParticleTypes.WHITE_ASH;
            case METAL -> ParticleTypes.ELECTRIC_SPARK;
            case WOOD -> block(Blocks.STRIPPED_OAK_LOG);
        };
    }

    private static ParticleOptions block(Block block) {
        return new BlockParticleOption(ParticleTypes.BLOCK, block.defaultBlockState());
    }

    private static TagKey<EntityType<?>> tag(String name) {
        Identifier id = Identifier.fromNamespaceAndPath("meridian_technique", "feel/" + name);
        return TagKey.create(Registries.ENTITY_TYPE, id);
    }
}
