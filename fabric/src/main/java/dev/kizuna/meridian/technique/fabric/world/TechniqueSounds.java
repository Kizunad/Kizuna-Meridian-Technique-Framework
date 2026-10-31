package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueSoundCues;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** 目录声音的世界播放：施放者声音经服务监听分发，目标声音与起跳声由打击、跃步就地调用。 */
public final class TechniqueSounds {
    private TechniqueSounds() {
        // 取值规则只在 TechniqueSoundCues；这里只把声音放进世界。
    }

    public static Consumer<TechniqueService.SoundDue> listener(MinecraftServer server) {
        // 每个服务器实例一个监听器，事件只在服务端主线程发布。
        return event -> playAtCaster(server, event);
    }

    public static void play(Entity anchor, SoundCue cue) {
        // 在锚点实体脚下播放；附近玩家连同施放者本人都收到声音包，客户端不预测，所以不会双响。
        if (!(anchor.level() instanceof ServerLevel world)) {
            return;
        }
        float volume = (float) TechniqueSoundCues.volume(cue);
        float pitch = (float) TechniqueSoundCues.playedPitch(cue, world.getRandom().nextDouble());
        long seed = world.getRandom().nextLong();
        world.playSeededSound(null /* except */, anchor.getX(), anchor.getY(), anchor.getZ(), event(cue),
                SoundSource.PLAYERS, volume, pitch, seed);
    }

    public static void playAll(Entity anchor, List<SoundCue> cues) {
        // 同一锚点的多条声音按目录顺序各播一次。
        for (SoundCue cue : cues) {
            play(anchor, cue);
        }
    }

    private static void playAtCaster(MinecraftServer server, TechniqueService.SoundDue event) {
        // 施放者已不在任何已加载世界时没有位置可播放。
        LivingEntity caster = TechniqueStaggers.find(server, event.entityUuid());
        if (caster != null) {
            play(caster, event.cue());
        }
    }

    private static Holder<SoundEvent> event(SoundCue cue) {
        // 已注册的按注册表编号下发；未注册的随包内联 ID，客户端有该资源就能播放。
        ResourceLocation id = new ResourceLocation(cue.getSoundId());
        ResourceKey<SoundEvent> key = ResourceKey.create(Registries.SOUND_EVENT, id);
        Optional<Holder.Reference<SoundEvent>> registered = BuiltInRegistries.SOUND_EVENT.getHolder(key);
        if (registered.isPresent()) {
            return registered.get();
        }
        return Holder.direct(SoundEvent.createVariableRangeEvent(id));
    }
}
