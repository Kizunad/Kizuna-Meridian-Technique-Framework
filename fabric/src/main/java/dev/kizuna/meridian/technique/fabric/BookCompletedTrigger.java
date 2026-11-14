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
 * 进度触发器 {@code meridian_technique:book_completed}：一本书登记了卷轴的每一卷都学会时触发。
 *
 * <p>条件写 {@code "book": "<书 ID>"} 只认这本书，省略就是凑齐任意一本。看的是已学状态，不分卷轴还是命令学会的；
 * 已学列表变化和玩家进服时都核一遍，老档早已凑齐的也能补上。</p>
 */
public final class BookCompletedTrigger extends SimpleCriterionTrigger<BookCompletedTrigger.Instance> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_technique", "book_completed");
    static final BookCompletedTrigger TRIGGER = new BookCompletedTrigger();

    private BookCompletedTrigger() {
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

    void trigger(ServerPlayer player, String bookId) {
        // 原版只检查这名玩家还没完成的进度，重复核对不会重复授予。
        trigger(player, instance -> instance.matches(bookId));
    }

    /** 一条进度条件：可选的玩家谓词和书 ID。 */
    public record Instance(Optional<ContextAwarePredicate> player, Optional<String> book)
            implements SimpleCriterionTrigger.SimpleInstance {
        // 进度文件里的写法：player 和条件字段都可省。
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                Codec.STRING.optionalFieldOf("book").forGetter(Instance::book)).apply(instance, Instance::new));

        boolean matches(String bookId) {
            // 没写书 ID 时任意一本都算。
            return book.map(bookId::equals).orElse(true);
        }
    }
}
