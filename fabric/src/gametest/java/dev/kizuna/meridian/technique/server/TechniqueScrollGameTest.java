package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;

/** 真实服务端玩家通过原版物品交互入口学习清单卷轴。 */
public final class TechniqueScrollGameTest implements FabricGameTest {
    private static final String BASIC = "sample_scrolls:basic";
    private static final String ADVANCED = "sample_scrolls:advanced";
    private static final int INITIAL_COUNT = 2;
    private static final int BOTH_VOLUMES_KNOWN = 2;
    private static final int LEARN_PERMISSION_LEVEL = 2;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void registeredScrollKeepsReadableNamesAndCatalogDetails(GameTestHelper context) {
        // 正式注册卷轴携带完整定义，客户端读数不再依赖 ID 或单独硬编码数值。
        var item = TechniqueScrollItems.scrolls().get(new ResourceLocation(BASIC + "_scroll"));
        var scroll = item.scroll();
        context.assertTrue(!scroll.displayName().contains(":"), "卷标题暴露资源 ID");
        for (var technique : scroll.techniques()) {
            context.assertTrue(!technique.fallbackName().contains(":"), "式名回退暴露资源 ID");
            context.assertTrue(technique.definition().getId().equals(technique.reference().getId()),
                    "阅读数据丢失正式目录定义");
            context.assertTrue(!TechniqueScrollText.details(technique.definition()).isEmpty(), "阅读数值为空");
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void learnCommandCompletesBooksVolumesAndTechniquesAndExecutesBook(GameTestHelper context) {
        // 真实命令树补全的书、卷和式都来自目录，书级选项可直接执行。
        Prepared prepared = prepare(context, GameType.CREATIVE);
        var player = prepared.player();
        var source = player.createCommandSourceStack().withPermission(LEARN_PERMISSION_LEVEL);
        var host = prepared.host();
        var commands = player.getServer().getCommands();
        var dispatcher = commands.getDispatcher();
        String prefix = "meridian_technique learn @s sample_scrolls:";
        try {
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(prefix, source)).join()
                    .getList()
                    .stream()
                    .map(suggestion -> suggestion.getText())
                    .collect(Collectors.toSet());
            context.assertTrue(suggestions.equals(Set.of("sample_scrolls:book", BASIC, ADVANCED,
                    "sample_scrolls:first", "sample_scrolls:second")), "学习命令缺少书、卷或式补全");
            long revision = host.current().revision();
            context.assertTrue(commands.performPrefixedCommand(source,
                    "meridian_technique learn @s sample_scrolls:book") == 1, "补全的书 ID 不能执行");
            context.assertTrue(TechniqueFabric.service().known(player.getUUID()).size() == BOTH_VOLUMES_KNOWN,
                    "整书学习遗漏书内功法");
            context.assertTrue(host.current().revision() == revision + 1, "整书没有一次提交");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void survivalLearnsAndConsumesOnceThenRejectsDuplicate(GameTestHelper context) {
        // 成功提交知识后才消耗一个，重复使用不改变身体版本或物品数量。
        Prepared prepared = prepare(context, GameType.SURVIVAL);
        ServerPlayer player = prepared.player();
        ItemStack stack = scroll(BASIC);
        try {
            context.assertTrue(use(player, stack) == InteractionResult.CONSUME, "生存玩家未成功使用卷轴");
            context.assertTrue(stack.getCount() == INITIAL_COUNT - 1, "生存学习没有只消耗一个卷轴");
            context.assertTrue(TechniqueFabric.service().known(player.getUUID()).size() == 1,
                    "卷轴未写入可信已学记录");
            long revision = prepared.host().current().revision();
            context.assertTrue(use(player, stack) == InteractionResult.FAIL, "重复学习没有拒绝");
            context.assertTrue(stack.getCount() == INITIAL_COUNT - 1, "重复学习消耗了卷轴");
            context.assertTrue(prepared.host().current().revision() == revision, "重复学习写入了身体");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void missingPrerequisiteRejectsWithoutConsumingOrWriting(GameTestHelper context) {
        // 前置卷不足必须在身体活动提交和物品消耗之前拒绝。
        Prepared prepared = prepare(context, GameType.SURVIVAL);
        ServerPlayer player = prepared.player();
        ItemStack stack = scroll(ADVANCED);
        long revision = prepared.host().current().revision();
        try {
            context.assertTrue(use(player, stack) == InteractionResult.FAIL, "缺少前置卷仍成功使用");
            context.assertTrue(stack.getCount() == INITIAL_COUNT, "前置拒绝消耗了卷轴");
            context.assertTrue(TechniqueFabric.service().known(player.getUUID()).isEmpty(), "前置拒绝写入了知识");
            context.assertTrue(prepared.host().current().revision() == revision, "前置拒绝改写了身体");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void creativeLearnsBothVolumesWithoutConsuming(GameTestHelper context) {
        // 创造玩家仍须逐卷满足前置，成功学习后保持两卷物品数量。
        Prepared prepared = prepare(context, GameType.CREATIVE);
        ServerPlayer player = prepared.player();
        ItemStack basic = scroll(BASIC);
        ItemStack advanced = scroll(ADVANCED);
        try {
            context.assertTrue(use(player, basic) == InteractionResult.CONSUME, "创造玩家未学会基础卷");
            context.assertTrue(use(player, advanced) == InteractionResult.CONSUME, "满足前置后未学会进阶卷");
            context.assertTrue(basic.getCount() == INITIAL_COUNT && advanced.getCount() == INITIAL_COUNT,
                    "创造学习消耗了卷轴");
            context.assertTrue(TechniqueFabric.service().known(player.getUUID()).size() == BOTH_VOLUMES_KNOWN,
                    "创造学习没有保留两卷的全部式");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void unboundPlayerRejectsWithoutConsuming(GameTestHelper context) {
        // 没有宿主认领的玩家使用卷轴时不创建身体或消耗物品。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        ItemStack stack = scroll(BASIC);
        try {
            context.assertTrue(use(player, stack) == InteractionResult.FAIL, "无身体玩家仍成功使用");
            context.assertTrue(stack.getCount() == INITIAL_COUNT, "无身体拒绝消耗了卷轴");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void creativeGroupContainsEveryEarlyVolumeInBookOrder(GameTestHelper context) {
        // 核对真实注册表、创造展示列表和搜索列表，晚到入口不增加物品。
        CreativeModeTab group = TechniqueScrollItems.group();
        var world = context.getLevel();
        var display = new CreativeModeTab.ItemDisplayParameters(world.enabledFeatures(), true /* hasPermissions */,
                world.registryAccess());
        group.buildContents(display);
        List<String> expected = List.of("sample_manifest:volume_scroll", BASIC + "_scroll", ADVANCED + "_scroll");
        List<String> registered = TechniqueScrollItems.scrolls().keySet().stream().map(ResourceLocation::toString).toList();
        List<String> visible = group.getDisplayItems().stream()
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .toList();
        List<String> searchable = group.getSearchTabDisplayItems().stream()
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .toList();
        context.assertTrue(registered.equals(expected), "初始化卷轴注册顺序或范围不正确");
        context.assertTrue(visible.equals(expected) && searchable.equals(expected), "功法创造栏遗漏或打乱了卷轴");
        context.assertTrue(BuiltInRegistries.ITEM.getKey(group.getIconItem().getItem()).toString().equals(expected.get(0)),
                "功法栏图标不是第一卷");
        context.assertTrue(TechniqueFabric.service().registry().findVolume(HeldTechniqueFixtures.UPPER).isPresent(),
                "晚到目录不应从服务端注册表消失");
        context.succeed();
    }

    private static Prepared prepare(GameTestHelper context, GameType mode) {
        // 使用有网络处理器的服务端测试玩家，身体由内存宿主单独认领。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.setGameMode(mode);
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), TechniqueState.empty()));
        TechniqueGameTestHost.bind(player.getUUID(), host);
        return new Prepared(player, host);
    }

    private static ItemStack scroll(String volumeId) {
        // 从 Minecraft 正式物品注册表取物品，不能在测试中另造卷轴实现。
        var item = TechniqueScrollItems.scrolls().get(new ResourceLocation(volumeId + "_scroll"));
        if (item == null) {
            throw new AssertionError("清单卷轴未注册：" + volumeId);
        }
        return new ItemStack(item, INITIAL_COUNT);
    }

    private static InteractionResult use(ServerPlayer player, ItemStack stack) {
        // 与服务端右键包共用物品交互入口，涵盖原版创造模式数量恢复规则。
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
    }

    private static void release(ServerPlayer player) {
        // 移除测试玩家并释放宿主，避免同一服务器中的后续测试受到影响。
        player.getServer().getPlayerList().remove(player);
        TechniqueGameTestHost.unbind(player.getUUID());
    }

    private record Prepared(ServerPlayer player, MemoryHost host) { }
}
