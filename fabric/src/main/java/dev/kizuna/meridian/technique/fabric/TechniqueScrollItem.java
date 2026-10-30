package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import dev.kizuna.meridian.technique.server.TechniqueService;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 卷身份由注册物品固定，右键学习和消耗仅由服务器确认。 */
public final class TechniqueScrollItem extends Item {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll");
    private final TechniqueScrollCatalog.Scroll scroll;

    TechniqueScrollItem(TechniqueScrollCatalog.Scroll scroll) {
        // 沿用原版默认叠放数量，单次学习只消耗一件。
        super(new Item.Settings());
        this.scroll = scroll;
    }

    public TechniqueScrollCatalog.Scroll scroll() {
        // 客户端只获得不可变的展示和资源身份。
        return scroll;
    }

    @Override
    public Text getName(ItemStack stack) {
        // 书名与卷名组合展示，已带书名前缀的卷名不重复添加。
        return Text.translatableWithFallback(getTranslationKey(), scroll.displayName());
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        // 提示列出全部式和前置卷，客户端不根据本地进度猜测能否学习。
        tooltip.add(Text.literal("功法：").append(techniqueNames()));
        List<String> prerequisites = scroll.prerequisites();
        if (!prerequisites.isEmpty()) {
            tooltip.add(Text.literal("前置卷：" + String.join("、", prerequisites)));
        }
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        // 客户端只预测使用动画；身体写入、提示与物品消耗均在服务端执行。
        ItemStack stack = player.getStackInHand(hand);
        if (world.isClient) {
            return TypedActionResult.success(stack);
        }
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return TypedActionResult.fail(stack);
        }
        Text rejection = learn(serverPlayer);
        if (rejection != null) {
            serverPlayer.sendMessage(rejection, true /* overlay */);
            return TypedActionResult.fail(stack);
        }
        if (!serverPlayer.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        Text message = Text.literal("学会《" + scroll.displayName() + "》：").append(techniqueNames());
        serverPlayer.sendMessage(message, true /* overlay */);
        TechniqueScrollNetworking.learned(serverPlayer, scroll.volume().getId());
        return TypedActionResult.consume(stack);
    }

    private Text learn(ServerPlayerEntity player) {
        // 同一服务端线程先检查重复，再调用命令共用的按卷学习入口。
        if (!player.isAlive() || player.isSpectator()) {
            return Text.literal("当前无法学习功法");
        }
        var entityId = player.getUuid();
        TechniqueVolume volume = scroll.volume();
        String volumeId = volume.getId();
        try {
            TechniqueService service = TechniqueFabric.service();
            Set<TechniqueReference> known = service.known(entityId).stream()
                    .map(TechniqueService.KnownEntry::technique)
                    .collect(Collectors.toSet());
            if (known.containsAll(volume.getTechniquesList())) {
                return Text.literal("你已经学会这一卷的全部功法");
            }
            service.learn(entityId, volumeId);
            return null;
        } catch (TechniqueService.PrerequisiteVolumeException exception) {
            return Text.literal(exception.getMessage());
        } catch (TechniqueService.NoHostException exception) {
            return Text.literal("你还没有可用的身体，无法学习功法");
        } catch (RuntimeException exception) {
            LOG.warn("学习卷轴 {} 失败", volumeId, exception);
            return Text.literal("暂时无法学会这卷功法，请稍后再试");
        }
    }

    private MutableText techniqueNames() {
        // 语言包可提供短名称，缺失条目时使用目录显示名。
        MutableText names = Text.empty();
        for (var technique : scroll.techniques()) {
            if (!names.getSiblings().isEmpty()) {
                names.append("、");
            }
            String key = "technique." + technique.reference().getId().replace(':', '.').replace('/', '.');
            names.append(Text.translatableWithFallback(key, technique.fallbackName()));
        }
        return names;
    }
}
