package dev.kizuna.meridian.technique.fabric;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * 功法谱：拿书人自己学会了哪些卷的图鉴。
 *
 * <p>物品不记任何进度，谁拿着翻开看到的都是自己的；右键只在本机发 {@link #OPEN}，书页由界面附属登记。</p>
 */
public final class TechniqueCodex {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_technique", "technique_codex");
    public static final Event<OpenCallback> OPEN = EventFactory.createArrayBacked(OpenCallback.class,
            callbacks -> () -> {
                for (OpenCallback callback : callbacks) {
                    callback.open();
                }
            });
    private static Item item;

    private TechniqueCodex() {
        // 物品身份在注册表冻结前固定。
    }

    static void register() {
        // 和卷轴物品同一时机登记，功法创造栏把它排在最前。
        if (BuiltInRegistries.ITEM.containsKey(ID)) {
            throw new IllegalArgumentException("功法谱物品身份已存在：" + ID);
        }
        item = Registry.register(BuiltInRegistries.ITEM, ID, new CodexItem(ResourceKey.create(Registries.ITEM, ID)));
    }

    public static Item item() {
        // 创造栏、配方测试和界面截图读取同一件物品。
        return item;
    }

    @FunctionalInterface
    public interface OpenCallback {
        /** 本机玩家右键功法谱；只在客户端主线程调用。 */
        void open();
    }

    /** 功法谱物品本身：一本一叠，右键翻开，不消耗。 */
    static final class CodexItem extends Item {
        CodexItem(ResourceKey<Item> key) {
            // 一人一本就够，不叠放。
            super(new Item.Properties().setId(key).stacksTo(1));
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            // 只在本机翻开；服务端不改物品，也不记谁看过。
            if (level.isClientSide()) {
                OPEN.invoker().open();
            }
            return InteractionResult.SUCCESS;
        }
    }
}
