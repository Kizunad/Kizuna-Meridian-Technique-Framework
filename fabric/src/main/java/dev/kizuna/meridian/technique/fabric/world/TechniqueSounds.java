package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.SoundCue;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueSoundCues;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

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
        if (!(anchor.getWorld() instanceof ServerWorld world)) {
            return;
        }
        float volume = (float) TechniqueSoundCues.volume(cue);
        float pitch = (float) TechniqueSoundCues.playedPitch(cue, world.getRandom().nextDouble());
        long seed = world.getRandom().nextLong();
        world.playSound(null /* except */, anchor.getX(), anchor.getY(), anchor.getZ(), event(cue),
                SoundCategory.PLAYERS, volume, pitch, seed);
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

    private static RegistryEntry<SoundEvent> event(SoundCue cue) {
        // 已注册的按注册表编号下发；未注册的随包内联 ID，客户端有该资源就能播放。
        Identifier id = new Identifier(cue.getSoundId());
        RegistryKey<SoundEvent> key = RegistryKey.of(RegistryKeys.SOUND_EVENT, id);
        Optional<RegistryEntry.Reference<SoundEvent>> registered = Registries.SOUND_EVENT.getEntry(key);
        if (registered.isPresent()) {
            return registered.get();
        }
        return RegistryEntry.of(SoundEvent.of(id));
    }
}
