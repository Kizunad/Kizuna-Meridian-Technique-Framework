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
 * 进度触发器 {@code meridian_technique:codex_completed}：本机登记了卷轴的每一卷都学会时触发。
 *
 * <p>分母是当前装上的全部功法包，新装一个包以后要再学齐才算；核对时机同 {@link BookCompletedTrigger}。</p>
 */
public final class CodexCompletedTrigger extends SimpleCriterionTrigger<CodexCompletedTrigger.Instance> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_technique", "codex_completed");
    static final CodexCompletedTrigger TRIGGER = new CodexCompletedTrigger();

    private CodexCompletedTrigger() {
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

    void trigger(ServerPlayer player) {
        // 没有额外条件，凑齐即满足。
        trigger(player, instance -> true);
    }

    /** 一条进度条件：只有可选的玩家谓词。 */
    public record Instance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
        // 进度文件里的写法：player 和条件字段都可省。
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player))
                .apply(instance, Instance::new));
    }
}
