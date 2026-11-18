package dev.kizuna.meridian.technique.server;

import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import static dev.kizuna.meridian.technique.server.Fixtures.Shape;
import static dev.kizuna.meridian.technique.server.Fixtures.body;
import static dev.kizuna.meridian.technique.server.Fixtures.withProgress;
import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/**
 * 进度触发器：{@code volume_learned} 用卷轴学会才触发，命令直接加卷不触发；
 * {@code book_completed}、{@code codex_completed} 按已学全量核对，怎么学会的都算；
 * 读掉手上最后一卷时，学会它的进度奖励留在读空的那只手里。
 */
public final class TechniqueAdvancementGameTest {
    private static final String BASIC = "sample_scrolls:basic";
    private static final Identifier BASIC_LEARNED = Identifier.parse("meridian_technique_test:learned_basic");
    private static final Identifier ADVANCED_LEARNED = Identifier.parse("meridian_technique_test:learned_advanced");
    private static final Identifier ANY_LEARNED = Identifier.parse("meridian_technique_test:learned_any");
    private static final Identifier BOOK_COMPLETED = Identifier.parse("meridian_technique_test:completed_sample_book");
    private static final Identifier CODEX_COMPLETED = Identifier.parse("meridian_technique_test:completed_codex");
    private static final String MANIFEST = "sample_manifest:volume";
    private static final Identifier MANIFEST_GIFT = Identifier.parse("meridian_technique_test:learned_manifest_gift");

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void scrollLearningCompletesMatchingAdvancements(GameTestHelper context) {
        // 学之前三条都没完成；用卷轴学会基础卷后，「基础卷」和「任意一卷」完成，「进阶卷」仍未完成。
        ServerPlayer player = prepare(context);
        try {
            context.assertFalse(done(player, BASIC_LEARNED), "没学就完成了基础卷进度");
            context.assertFalse(done(player, ANY_LEARNED), "没学就完成了任意一卷进度");
            ItemStack stack = new ItemStack(TechniqueScrollItems.scrolls().get(Identifier.parse(BASIC + "_scroll")));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
            context.assertTrue(TechniqueFabric.service().known(player.getUUID()).size()
                    == 1 + GuardTechniqueFixtures.INNATE_COUNT, "卷轴没有学会");
            context.assertTrue(done(player, BASIC_LEARNED), "用卷轴学会基础卷后进度没完成");
            context.assertTrue(done(player, ANY_LEARNED), "用卷轴学会一卷后任意一卷进度没完成");
            context.assertFalse(done(player, ADVANCED_LEARNED), "学会基础卷却完成了进阶卷进度");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void rewardForLastScrollStaysInTheEmptiedHand(GameTestHelper context) {
        // 生存玩家读掉手上唯一一卷：测试进度送的纸落进读空的主手，原版按读空的卷轴清手时不能把它清掉。
        ServerPlayer player = prepare(context);
        try {
            ItemStack stack = new ItemStack(TechniqueScrollItems.scrolls().get(Identifier.parse(MANIFEST + "_scroll")));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
            context.assertTrue(done(player, MANIFEST_GIFT), "用卷轴学会示例卷后送纸的进度没完成");
            context.assertTrue(player.getMainHandItem().is(Items.PAPER),
                    "读空的主手里不是进度送的纸：" + player.getMainHandItem());
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void commandLearningLeavesAdvancementsOpen(GameTestHelper context) {
        // 管理命令直接写入知识，不算用卷轴学会。
        ServerPlayer player = prepare(context);
        var source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        try {
            context.assertTrue(GameTestCommands.run(source, "meridian technique learn @s " + BASIC) == 1,
                    "学习命令失败");
            context.assertTrue(TechniqueFabric.service().known(player.getUUID()).size()
                    == 1 + GuardTechniqueFixtures.INNATE_COUNT, "命令没有学会");
            context.assertFalse(done(player, ANY_LEARNED), "命令加卷触发了卷轴进度");
        } finally {
            release(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void completingBooksCompletesBookThenCodexAdvancements(GameTestHelper context) {
        // 学了半本不算；示例书两卷学齐算凑齐这本；另一本也学齐才算全谱。命令学会的同样算数。
        ServerPlayer player = prepare(context);
        var source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        try {
            learn(context, source, BASIC);
            context.assertFalse(done(player, BOOK_COMPLETED), "只学一卷就算凑齐了这本");
            learn(context, source, "sample_scrolls:advanced");
            context.assertTrue(done(player, BOOK_COMPLETED), "两卷学齐没算凑齐这本");
            context.assertFalse(done(player, CODEX_COMPLETED), "还有一本没学就算全谱");
            learn(context, source, MANIFEST);
            context.assertTrue(done(player, CODEX_COMPLETED), "所有书都学齐没算全谱");
        } finally {
            release(player);
        }
        context.succeed();
    }

    private static void learn(GameTestHelper context, CommandSourceStack source, String volume) {
        // 走管理命令加卷，和老档、装新包前学会的情形一样不经卷轴。
        context.assertTrue(GameTestCommands.run(source, "meridian technique learn @s " + volume) == 1,
                "学习命令失败：" + volume);
    }

    private static ServerPlayer prepare(GameTestHelper context) {
        // 生存测试玩家，身体由内存宿主单独认领。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        TechniqueGameTestHost.bind(player.getUUID(), new MemoryHost(withProgress(body(Shape.TENDRIL),
                TechniqueState.empty())));
        return player;
    }

    private static boolean done(ServerPlayer player, Identifier id) {
        // 测试数据包里的进度必须已加载，缺了直接报错而不是当作未完成。
        var advancement = player.level().getServer().getAdvancements().get(id);
        if (advancement == null) {
            throw new AssertionError("测试进度没有加载：" + id);
        }
        return player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static void release(ServerPlayer player) {
        // 移除测试玩家并释放宿主，避免同一服务器中的后续测试受到影响。
        player.level().getServer().getPlayerList().remove(player);
        TechniqueGameTestHost.unbind(player.getUUID());
    }
}
