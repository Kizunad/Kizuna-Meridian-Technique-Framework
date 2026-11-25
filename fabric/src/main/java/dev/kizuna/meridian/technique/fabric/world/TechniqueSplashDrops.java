package dev.kizuna.meridian.technique.fabric.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponSplash;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/** 一次泼洒在每个命中者脚下各留一份到期回落，跨维度共用主世界时钟。 */
public final class TechniqueSplashDrops extends SavedData {
    private record Drop(UUID target, GlobalPos lastPosition, long due, ItemStack stack) {
        private static final Codec<Drop> CODEC = RecordCodecBuilder.create(builder -> builder.group(
                UUIDUtil.CODEC.fieldOf("target").forGetter(Drop::target),
                GlobalPos.CODEC.fieldOf("position").forGetter(Drop::lastPosition),
                Codec.LONG.fieldOf("due").forGetter(Drop::due),
                ItemStack.CODEC.fieldOf("stack").forGetter(Drop::stack)
        ).apply(builder, Drop::new));
    }

    private static final long MILLIS_PER_TICK = 50;
    private static final Codec<TechniqueSplashDrops> CODEC = Drop.CODEC.listOf()
            .xmap(TechniqueSplashDrops::new, saved -> saved.drops);
    private static final SavedDataType<TechniqueSplashDrops> TYPE = new SavedDataType<>(
            Identifier.parse("meridian_technique:splash_drops"), () -> new TechniqueSplashDrops(List.of()),
            CODEC, null);
    private final List<Drop> drops;

    private TechniqueSplashDrops(List<Drop> saved) {
        // 读回未到期或等待区块加载的回落。
        drops = new ArrayList<>(saved);
    }

    static void schedule(LivingEntity target, WeaponSplash splash, int count) {
        // 回收额在出手时已确定，效果刷新不能改变旧份额或旧时刻。
        var level = (ServerLevel) target.level();
        var overworld = level.getServer().overworld();
        var saved = overworld.getDataStorage().computeIfAbsent(TYPE);
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(splash.getDropItem()));
        long duration = Math.ceilDiv(splash.getCoating().getDurationMillis(), MILLIS_PER_TICK);
        long due = overworld.getGameTime() + duration;
        var position = GlobalPos.of(level.dimension(), target.blockPosition());
        saved.drops.add(new Drop(target.getUUID(), position, due, new ItemStack(item, count)));
        saved.setDirty();
    }

    public static void tick(MinecraftServer server) {
        // 区块卸载期间不强载；恢复后只兑现一次。死亡或下线保留最后所在格。
        var overworld = server.overworld();
        var saved = overworld.getDataStorage().get(TYPE);
        if (saved == null || saved.drops.isEmpty()) {
            return;
        }
        long now = overworld.getGameTime();
        var entries = saved.drops.listIterator();
        while (entries.hasNext()) {
            var drop = entries.next();
            LivingEntity target = TechniqueStaggers.find(server, drop.target());
            GlobalPos position = drop.lastPosition();
            if (target != null) {
                position = GlobalPos.of(target.level().dimension(), target.blockPosition());
            }
            var level = server.getLevel(position.dimension());
            if (now >= drop.due() && level != null && level.hasChunkAt(position.pos())) {
                Vec3 feet = Vec3.atBottomCenterOf(position.pos());
                if (target != null) {
                    feet = target.position();
                }
                var item = new ItemEntity(level, feet.x, feet.y, feet.z, drop.stack());
                item.setDefaultPickUpDelay();
                if (level.addFreshEntity(item)) {
                    entries.remove();
                    saved.setDirty();
                }
            } else if (!position.equals(drop.lastPosition())) {
                entries.set(new Drop(drop.target(), position, drop.due(), drop.stack()));
                saved.setDirty();
            }
        }
    }
}
