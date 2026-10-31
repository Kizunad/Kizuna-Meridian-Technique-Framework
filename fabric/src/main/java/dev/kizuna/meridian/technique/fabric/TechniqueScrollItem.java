package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueVolume;
import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import dev.kizuna.meridian.technique.server.TechniqueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** 卷身份由注册物品固定，右键学习和消耗仅由服务器确认。 */
public final class TechniqueScrollItem extends Item {
    private static final Logger LOG = LoggerFactory.getLogger("meridian_technique/scroll");
    private final TechniqueScrollCatalog.Scroll scroll;

    TechniqueScrollItem(TechniqueScrollCatalog.Scroll scroll) {
        // 沿用原版默认叠放数量，单次学习只消耗一件。
        super(new Item.Properties());
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
    public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag context) {
        // 提示列出全部式和前置卷，客户端不根据本地进度猜测能否学习。
        tooltip.add(Component.literal("功法：").append(techniqueNames()));
        List<String> prerequisites = scroll.prerequisites();
        if (!prerequisites.isEmpty()) {
            tooltip.add(Component.literal("前置卷：" + String.join("、", prerequisites)));
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        // 客户端只预测使用动画；身体写入、提示与物品消耗均在服务端执行。
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.fail(stack);
        }
        Component rejection = learn(serverPlayer);
        if (rejection != null) {
            serverPlayer.displayClientMessage(rejection, true /* overlay */);
            return InteractionResultHolder.fail(stack);
        }
        if (!serverPlayer.getAbilities().instabuild) {
            stack.shrink(1);
        }
        Component message = Component.literal("学会《" + scroll.displayName() + "》：").append(techniqueNames());
        serverPlayer.displayClientMessage(message, true /* overlay */);
        TechniqueScrollNetworking.learned(serverPlayer, scroll.volume().getId());
        return InteractionResultHolder.consume(stack);
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
