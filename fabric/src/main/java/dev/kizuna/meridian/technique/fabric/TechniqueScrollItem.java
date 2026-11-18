package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.KnownTechnique;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import dev.kizuna.meridian.technique.server.TechniqueScrollText;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/** 卷身份由注册物品固定，右键学习和消耗仅由服务器确认。 */
public final class TechniqueScrollItem extends Item {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll");
    private static final String WORKSPACE_KEY = "key.kizuna_inventory_ui.workspace";
    private static final String STANCE_KEY = "key.meridian_combat.stance";
    private final TechniqueScrollCatalog.Scroll scroll;

    TechniqueScrollItem(TechniqueScrollCatalog.Scroll scroll, ResourceKey<Item> key) {
        // 沿用原版默认叠放数量，单次学习只消耗一件；注册键决定语言键，与 1.20.1 的默认键相同。
        super(new Item.Properties().setId(key));
        this.scroll = scroll;
    }

    public TechniqueScrollCatalog.Scroll scroll() {
        // 客户端只获得不可变的展示和资源身份。
        return scroll;
    }

    @Override
    public Component getName(ItemStack stack) {
        // 书名与卷名组合展示，已带书名前缀的卷名不重复添加。
        return Component.translatableWithFallback(getDescriptionId(), scroll.displayName());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        // 每式一行写境界和真元费用，语言里有补充说明就接在后面；客户端不根据本地进度猜测能否学习。
        for (var technique : scroll.techniques()) {
            tooltip.accept(techniqueLine(technique));
        }
        // 整本书共通的规矩（拿什么兵器、磨什么）写在书名键加 .note 里，缺键时不显示。
        String bookNoteKey = TechniqueScrollText.key("book", scroll.volume().getBookId()) + ".note";
        if (Language.getInstance().has(bookNoteKey)) {
            tooltip.accept(Component.translatable(bookNoteKey));
        }
        // 只属于这一卷的话（比如这一卷从哪儿得来）写在卷名键加 .note 里，缺键时不显示。
        String volumeNoteKey = TechniqueScrollText.key("volume", scroll.volume().getId()) + ".note";
        if (Language.getInstance().has(volumeNoteKey)) {
            tooltip.accept(Component.translatable(volumeNoteKey));
        }
        List<String> prerequisites = scroll.prerequisites();
        if (!prerequisites.isEmpty()) {
            tooltip.accept(Component.literal("前置卷：" + String.join("、", prerequisites)));
        }
        tooltip.accept(Component.translatable("tooltip.meridian_technique.scroll.learn"));
    }

    /** 功法怎么放进槽位、怎么进架势；键名按玩家实际绑定的显示。阅读页末尾和学会第一卷时的聊天共用。 */
    public static Component slotHelp() {
        // 两个按键分别登记在库存界面框架和战斗界面附属里，这里只引用按键名。
        return Component.translatable("help.meridian_technique.slots",
                Component.keybind(WORKSPACE_KEY), Component.keybind(STANCE_KEY));
    }

    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        // 客户端只预测使用动画；身体写入、提示与物品消耗均在服务端执行。
        // 客户端 SUCCESS 照旧挥手，服务端 CONSUME 不挥手，卷轴原地减少。
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.FAIL;
        }
        Component rejection = learn(serverPlayer);
        if (rejection != null) {
            serverPlayer.sendOverlayMessage(rejection);
            return InteractionResult.FAIL;
        }
        if (!serverPlayer.getAbilities().instabuild) {
            stack.shrink(1);
        }
        Component message = Component.literal("学会《" + scroll.displayName() + "》：").append(techniqueNames());
        serverPlayer.sendOverlayMessage(message);
        if (knowsOnlyThisVolume(serverPlayer)) {
            serverPlayer.sendSystemMessage(slotHelp());
        }
        TechniqueScrollNetworking.learned(serverPlayer, scroll.volume().getId());
        VolumeLearnedTrigger.TRIGGER.trigger(serverPlayer, scroll.volume().getId());
        // 学会的进度奖励可能已把东西放进读空的这一格；原版把读空的卷轴当成手上的结果并清手，会连它一起清掉，
        // 所以报手上此刻的东西。
        return InteractionResult.CONSUME.heldItemTransformedTo(player.getItemInHand(hand));
    }

    private boolean knowsOnlyThisVolume(ServerPlayer player) {
        // 第一卷提示只看真实学习记录，天生会的式不算以前学过卷轴。
        Set<TechniqueReference> known = TechniqueFabric.service().observe(player.getUUID())
                .map(observation -> TechniqueState.read(observation.body())
                        .getKnownList().stream()
                        .map(KnownTechnique::getTechnique)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
        return known.equals(Set.copyOf(scroll.volume().getTechniquesList()));
    }

    private Component learn(ServerPlayer player) {
        // 同一服务端线程先检查重复，再调用命令共用的按卷学习入口。
        if (!player.isAlive() || player.isSpectator()) {
            return Component.literal("当前无法学习功法");
        }
        var entityId = player.getUUID();
        TechniqueVolume volume = scroll.volume();
        String volumeId = volume.getId();
        try {
            TechniqueService service = TechniqueFabric.service();
            Set<TechniqueReference> known = service.known(entityId).stream()
                    .map(TechniqueService.KnownEntry::technique)
                    .collect(Collectors.toSet());
            if (known.containsAll(volume.getTechniquesList())) {
                return Component.literal("你已经学会这一卷的全部功法");
            }
            service.learn(entityId, volumeId);
            return null;
        } catch (TechniqueService.PrerequisiteVolumeException exception) {
            return Component.literal(exception.getMessage());
        } catch (TechniqueService.NoHostException exception) {
            return Component.literal("你还没有可用的身体，无法学习功法");
        } catch (RuntimeException exception) {
            LOG.warn("学习卷轴 {} 失败", volumeId, exception);
            return Component.literal("暂时无法学会这卷功法，请稍后再试");
        }
    }

    private static Component techniqueLine(TechniqueScrollCatalog.NamedTechnique technique) {
        // 「金臂：游炁，金行真元 0.3」；补充说明键为式短名键加 .note，缺键时不显示，不影响其他书。
        String key = TechniqueScrollText.key("technique", technique.reference().getId());
        MutableComponent line = Component.translatableWithFallback(key, technique.fallbackName()).copy()
                .append("：" + TechniqueScrollText.summary(technique.definition()));
        String noteKey = key + ".note";
        if (Language.getInstance().has(noteKey)) {
            line.append("，").append(Component.translatable(noteKey));
        }
        return line;
    }

    private MutableComponent techniqueNames() {
        // 语言包可提供短名称，缺失条目时使用目录显示名。
        MutableComponent names = Component.empty();
        for (var technique : scroll.techniques()) {
            if (!names.getSiblings().isEmpty()) {
                names.append("、");
            }
            String key = "technique." + technique.reference().getId().replace(':', '.').replace('/', '.');
            names.append(Component.translatableWithFallback(key, technique.fallbackName()));
        }
        return names;
    }
}
