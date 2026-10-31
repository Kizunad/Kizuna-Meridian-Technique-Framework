package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HandSide;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItem;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemCategory;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItemRules;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.HeldItems;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/** 玩家和生物共用的服务端持物事实与数据包规则匹配。 */
public final class TechniqueWorldItems {
    private TechniqueWorldItems() {
        // 工具只读取实体当前持物，不保存身体或背包状态。
    }

    public static HeldItems inspect(LivingEntity entity) {
        // 空手也提供消息，主手侧决定 Damage 的实际出力手臂。
        HeldItem main = item(entity.getMainHandItem());
        HeldItem off = item(entity.getOffhandItem());
        HandSide side = HandSide.RIGHT;
        if (entity.getMainArm() == HumanoidArm.LEFT) {
            side = HandSide.LEFT;
        }
        return HeldItems.newBuilder()
                .setMainHand(main)
                .setOffHand(off)
                .setMainHandSide(side)
                .build();
    }

    private static HeldItem item(ItemStack stack) {
        // 排序标签使重试时的事实具有稳定顺序；26.2 的物品标签从物品注册项读取。
        if (stack.isEmpty()) {
            return HeldItem.getDefaultInstance();
        }
        return HeldItem.newBuilder()
                .setItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .addAllTags(stack.typeHolder().tags().map(tag -> tag.location().toString()).sorted().toList())
                .setDamageable(stack.getMaxDamage() > 0)
                .setContainsItems(containsItems(stack))
                .build();
    }

    public static boolean usable(ItemStack stack) {
        // 装物容器不能在中途切换后被当成一次性武器消耗。
        return !stack.isEmpty() && !containsItems(stack);
    }

    private static boolean containsItems(ItemStack stack) {
        // 潜影盒和收纳袋在 26.2 分别使用容器组件与收纳袋组件；空库存仍允许参与持物选择。
        ItemContainerContents container = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        BundleContents bundle = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        return container.nonEmptyItems().iterator().hasNext() || !bundle.isEmpty();
    }

    public static HeldItemCategory category(HeldItemRules rules, ItemStack stack) {
        // 有序规则的末项覆盖未分类物品。
        return rules.getCategoriesList().stream()
                .filter(category -> matches(category.getItemTagsList(), stack))
                .findFirst().orElseThrow();
    }

    public static double durabilityMultiplier(HeldItemRules rules, ItemStack stack) {
        // 数据包声明首次命中的耐久倍率，不按物品硬编码。
        return rules.getDurabilityList().stream()
                .filter(rule -> matches(rule.getItemTagsList(), stack))
                .findFirst().orElseThrow().getMultiplier().getValue();
    }

    private static boolean matches(List<String> tags, ItemStack stack) {
        // 同一项中的多个标签使用任一命中语义。
        return tags.isEmpty() || tags.stream().anyMatch(id ->
                stack.is(TagKey.create(Registries.ITEM, Identifier.parse(id))));
    }
}
