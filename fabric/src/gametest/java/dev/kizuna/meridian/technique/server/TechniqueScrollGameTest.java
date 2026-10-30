package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

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

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void registeredScrollKeepsReadableNamesAndCatalogDetails(TestContext context) {
        // 正式注册卷轴携带完整定义，客户端读数不再依赖 ID 或单独硬编码数值。
        var item = TechniqueScrollItems.scrolls().get(new Identifier(BASIC + "_scroll"));
        var scroll = item.scroll();
        context.assertTrue(!scroll.displayName().contains(":"), "卷标题暴露资源 ID");
        for (var technique : scroll.techniques()) {
            context.assertTrue(!technique.fallbackName().contains(":"), "式名回退暴露资源 ID");
            context.assertTrue(technique.definition().getId().equals(technique.reference().getId()),
                    "阅读数据丢失正式目录定义");
            context.assertTrue(!TechniqueScrollText.details(technique.definition()).isEmpty(), "阅读数值为空");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void learnCommandCompletesBooksVolumesAndTechniquesAndExecutesBook(TestContext context) {
        // 真实命令树补全的书、卷和式都来自目录，书级选项可直接执行。
        Prepared prepared = prepare(context, GameMode.CREATIVE);
        var player = prepared.player();
        var source = player.getCommandSource().withLevel(LEARN_PERMISSION_LEVEL);
        var host = prepared.host();
        var commands = player.getServer().getCommandManager();
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
            context.assertTrue(commands.executeWithPrefix(source,
                    "meridian_technique learn @s sample_scrolls:book") == 1, "补全的书 ID 不能执行");
            context.assertTrue(TechniqueFabric.service().known(player.getUuid()).size() == BOTH_VOLUMES_KNOWN,
                    "整书学习遗漏书内功法");
            context.assertTrue(host.current().revision() == revision + 1, "整书没有一次提交");
        } finally {
            release(player);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void survivalLearnsAndConsumesOnceThenRejectsDuplicate(TestContext context) {
        // 成功提交知识后才消耗一个，重复使用不改变身体版本或物品数量。
        Prepared prepared = prepare(context, GameMode.SURVIVAL);
        ServerPlayerEntity player = prepared.player();
        ItemStack stack = scroll(BASIC);
        try {
            context.assertTrue(use(player, stack) == ActionResult.CONSUME, "生存玩家未成功使用卷轴");
            context.assertTrue(stack.getCount() == INITIAL_COUNT - 1, "生存学习没有只消耗一个卷轴");
            context.assertTrue(TechniqueFabric.service().known(player.getUuid()).size() == 1,
                    "卷轴未写入可信已学记录");
            long revision = prepared.host().current().revision();
            context.assertTrue(use(player, stack) == ActionResult.FAIL, "重复学习没有拒绝");
            context.assertTrue(stack.getCount() == INITIAL_COUNT - 1, "重复学习消耗了卷轴");
            context.assertTrue(prepared.host().current().revision() == revision, "重复学习写入了身体");
        } finally {
            release(player);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void missingPrerequisiteRejectsWithoutConsumingOrWriting(TestContext context) {
        // 前置卷不足必须在身体活动提交和物品消耗之前拒绝。
        Prepared prepared = prepare(context, GameMode.SURVIVAL);
        ServerPlayerEntity player = prepared.player();
        ItemStack stack = scroll(ADVANCED);
        long revision = prepared.host().current().revision();
        try {
            context.assertTrue(use(player, stack) == ActionResult.FAIL, "缺少前置卷仍成功使用");
            context.assertTrue(stack.getCount() == INITIAL_COUNT, "前置拒绝消耗了卷轴");
            context.assertTrue(TechniqueFabric.service().known(player.getUuid()).isEmpty(), "前置拒绝写入了知识");
            context.assertTrue(prepared.host().current().revision() == revision, "前置拒绝改写了身体");
        } finally {
            release(player);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void creativeLearnsBothVolumesWithoutConsuming(TestContext context) {
        // 创造玩家仍须逐卷满足前置，成功学习后保持两卷物品数量。
        Prepared prepared = prepare(context, GameMode.CREATIVE);
        ServerPlayerEntity player = prepared.player();
        ItemStack basic = scroll(BASIC);
        ItemStack advanced = scroll(ADVANCED);
        try {
            context.assertTrue(use(player, basic) == ActionResult.CONSUME, "创造玩家未学会基础卷");
            context.assertTrue(use(player, advanced) == ActionResult.CONSUME, "满足前置后未学会进阶卷");
            context.assertTrue(basic.getCount() == INITIAL_COUNT && advanced.getCount() == INITIAL_COUNT,
                    "创造学习消耗了卷轴");
            context.assertTrue(TechniqueFabric.service().known(player.getUuid()).size() == BOTH_VOLUMES_KNOWN,
                    "创造学习没有保留两卷的全部式");
        } finally {
            release(player);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void unboundPlayerRejectsWithoutConsuming(TestContext context) {
        // 没有宿主认领的玩家使用卷轴时不创建身体或消耗物品。
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.changeGameMode(GameMode.SURVIVAL);
        ItemStack stack = scroll(BASIC);
        try {
            context.assertTrue(use(player, stack) == ActionResult.FAIL, "无身体玩家仍成功使用");
            context.assertTrue(stack.getCount() == INITIAL_COUNT, "无身体拒绝消耗了卷轴");
        } finally {
            release(player);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void creativeGroupContainsEveryEarlyVolumeInBookOrder(TestContext context) {
        // 核对真实注册表、创造展示列表和搜索列表，晚到入口不增加物品。
        ItemGroup group = TechniqueScrollItems.group();
        var world = context.getWorld();
        var display = new ItemGroup.DisplayContext(world.getEnabledFeatures(), true /* hasPermissions */,
                world.getRegistryManager());
        group.updateEntries(display);
        List<String> expected = List.of("sample_manifest:volume_scroll", BASIC + "_scroll", ADVANCED + "_scroll");
        List<String> registered = TechniqueScrollItems.scrolls().keySet().stream().map(Identifier::toString).toList();
        List<String> visible = group.getDisplayStacks().stream()
                .map(stack -> Registries.ITEM.getId(stack.getItem()).toString())
                .toList();
        List<String> searchable = group.getSearchTabStacks().stream()
                .map(stack -> Registries.ITEM.getId(stack.getItem()).toString())
                .toList();
        context.assertTrue(registered.equals(expected), "初始化卷轴注册顺序或范围不正确");
        context.assertTrue(visible.equals(expected) && searchable.equals(expected), "功法创造栏遗漏或打乱了卷轴");
        context.assertTrue(Registries.ITEM.getId(group.getIcon().getItem()).toString().equals(expected.get(0)),
                "功法栏图标不是第一卷");
        context.assertTrue(TechniqueFabric.service().registry().findVolume(HeldTechniqueFixtures.UPPER).isPresent(),
                "晚到目录不应从服务端注册表消失");
        context.complete();
    }

    private static Prepared prepare(TestContext context, GameMode mode) {
        // 使用有网络处理器的服务端测试玩家，身体由内存宿主单独认领。
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.changeGameMode(mode);
        MemoryHost host = new MemoryHost(withProgress(body(Shape.TENDRIL), TechniqueState.empty()));
        TechniqueGameTestHost.bind(player.getUuid(), host);
        return new Prepared(player, host);
    }

    private static ItemStack scroll(String volumeId) {
        // 从 Minecraft 正式物品注册表取物品，不能在测试中另造卷轴实现。
        var item = TechniqueScrollItems.scrolls().get(new Identifier(volumeId + "_scroll"));
        if (item == null) {
            throw new AssertionError("清单卷轴未注册：" + volumeId);
        }
        return new ItemStack(item, INITIAL_COUNT);
    }

    private static ActionResult use(ServerPlayerEntity player, ItemStack stack) {
        // 与服务端右键包共用物品交互入口，涵盖原版创造模式数量恢复规则。
        player.setStackInHand(Hand.MAIN_HAND, stack);
        return player.interactionManager.interactItem(player, player.getWorld(), stack, Hand.MAIN_HAND);
    }

    private static void release(ServerPlayerEntity player) {
        // 移除测试玩家并释放宿主，避免同一服务器中的后续测试受到影响。
        player.getServer().getPlayerManager().remove(player);
        TechniqueGameTestHost.unbind(player.getUuid());
    }

    private record Prepared(ServerPlayerEntity player, MemoryHost host) { }
}
