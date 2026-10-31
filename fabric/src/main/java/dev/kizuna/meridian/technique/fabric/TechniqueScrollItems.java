package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.technique.server.TechniqueScrollCatalog;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 初始化期登记每卷一个物品和独立的功法创造栏，两端使用同一份清单。 */
public final class TechniqueScrollItems {
    public static final Identifier GROUP_ID = Identifier.fromNamespaceAndPath("meridian_technique", "techniques");
    private static Map<Identifier, TechniqueScrollItem> scrolls = Map.of();
    private static CreativeModeTab group;

    private TechniqueScrollItems() {
        // 物品身份在注册表冻结前固定，不随服务器切换或资源重载增删。
    }

    static void register(TechniqueScrollCatalog catalog) {
        // 所有冲突预检完成后才写入 Minecraft 物品注册表；26.2 的物品构造时就要带上注册键。
        var candidates = new LinkedHashMap<Identifier, TechniqueScrollItem>();
        for (var scroll : catalog.registrationOrder()) {
            Identifier id = Identifier.parse(scroll.itemId());
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                throw new IllegalArgumentException("卷轴物品身份已存在：" + id);
            }
            candidates.put(id, new TechniqueScrollItem(scroll, ResourceKey.create(Registries.ITEM, id)));
        }
        if (BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(GROUP_ID)) {
            throw new IllegalArgumentException("功法创造栏身份已存在：" + GROUP_ID);
        }
        candidates.forEach((id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item));
        scrolls = Collections.unmodifiableMap(candidates);
        List<TechniqueScrollItem> ordered = catalog.creativeOrder().stream()
                .map(scroll -> scrolls.get(Identifier.parse(scroll.itemId())))
                .toList();
        registerGroup(ordered);
    }

    private static void registerGroup(List<TechniqueScrollItem> ordered) {
        // 图标使用第一卷；空目录仍登记固定栏身份并显示原版空栏规则。
        CreativeModeTab created = FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.meridian_technique.techniques"))
                .icon(() -> icon(ordered))
                .displayItems((context, entries) -> ordered.forEach(entries::accept))
                .build();
        group = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, GROUP_ID, created);
    }

    private static ItemStack icon(List<TechniqueScrollItem> ordered) {
        // 未安装内容时使用原版纸张，不向框架注入具体功法。
        if (ordered.isEmpty()) {
            return new ItemStack(Items.PAPER);
        }
        return new ItemStack(ordered.get(0));
    }

    public static Map<Identifier, TechniqueScrollItem> scrolls() {
        // 模型加载和客户端展示复用初始化期不可变身份集合。
        return scrolls;
    }

    public static CreativeModeTab group() {
        // 整合测试与宿主读取同一栏，不构造第二份展示列表。
        return group;
    }
}
