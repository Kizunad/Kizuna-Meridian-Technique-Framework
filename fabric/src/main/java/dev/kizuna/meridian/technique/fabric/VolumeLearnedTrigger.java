package dev.kizuna.meridian.technique.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 进度触发器 {@code meridian_technique:volume_learned}：玩家用卷轴学会一卷时触发。
 *
 * <p>条件写 {@code "volume": "<卷 ID>"} 只认这一卷，省略就是学会任意一卷。管理命令直接加的卷不触发。</p>
 */
public final class VolumeLearnedTrigger extends SimpleCriterionTrigger<VolumeLearnedTrigger.Instance> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_technique", "volume_learned");
    static final VolumeLearnedTrigger TRIGGER = new VolumeLearnedTrigger();

    private VolumeLearnedTrigger() {
        // 全局只有一个实例，由注册表持有。
    }

    static void register() {
        // 触发器类型在注册表冻结前登记，进度文件按 ID 引用。
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, ID, TRIGGER);
    }

    @Override
    public Codec<Instance> codec() {
        // 进度文件里的条件格式。
        return Instance.CODEC;
    }

    void trigger(ServerPlayer player, String volumeId) {
        // 卷轴学成那一刻调用；原版只检查这名玩家还没完成的进度。
        trigger(player, instance -> instance.matches(volumeId));
    }

    /** 一条进度条件：可选的玩家谓词和卷 ID。 */
    public record Instance(Optional<ContextAwarePredicate> player, Optional<String> volume)
            implements SimpleCriterionTrigger.SimpleInstance {
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                Codec.STRING.optionalFieldOf("volume").forGetter(Instance::volume)).apply(instance, Instance::new));

        boolean matches(String volumeId) {
            // 没写卷 ID 时任意一卷都算。
            return volume.map(volumeId::equals).orElse(true);
        }
    }
}
