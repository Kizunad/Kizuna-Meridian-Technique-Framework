package dev.kizuna.meridian.technique.fabric;

import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeFoe;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeKnower;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.VolumeSource;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;

import static dev.kizuna.meridian.technique.server.TechniqueGameTestTicks.DEFAULT_TIMEOUT;

/** 功法谱物品和来源登记处：翻书不耗书，书加线合出功法谱，写错的来源登记被拦下。 */
public final class TechniqueCodexGameTest {
    private static final Identifier PROVIDER = Identifier.fromNamespaceAndPath("meridian_technique_test", "sources");
    private static final String BASIC = "sample_scrolls:basic";
    /** 测试服务器上晚到目录的卷：在功法目录里，但没有卷轴物品。 */
    private static final String WITHOUT_SCROLL = "sample:upper";
    private static final double HALF = 0.5;

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void usingCodexKeepsTheBookInHand(GameTestHelper context) {
        // 服务端照原版右键入口走一遍：书还在手里，一本也没少。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        ItemStack stack = new ItemStack(TechniqueCodex.item());
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            player.gameMode.useItem(player, player.level(), stack, InteractionHand.MAIN_HAND);
            ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
            context.assertTrue(held.is(TechniqueCodex.item()) && held.getCount() == 1, "翻功法谱把书用掉了");
            context.assertTrue(held.getMaxStackSize() == 1, "功法谱能叠放");
        } finally {
            player.level().getServer().getPlayerList().remove(player);
        }
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void bookAndStringCraftOneCodex(GameTestHelper context) {
        // 走服务器配方表：书加线合出一本功法谱。
        var level = context.getLevel();
        List<ItemStack> ingredients = List.of(new ItemStack(Items.BOOK), new ItemStack(Items.STRING));
        CraftingInput input = CraftingInput.of(ingredients.size(), 1, ingredients);
        Optional<RecipeHolder<CraftingRecipe>> found = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        context.assertTrue(found.isPresent(), "书加线合不出东西");
        ItemStack result = found.orElseThrow().value().assemble(input);
        context.assertTrue(result.is(TechniqueCodex.item()) && result.getCount() == 1, "书加线合出来的不是功法谱：" + result);
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void sourceCheckRejectsIncompleteEntries(GameTestHelper context) {
        // 完整的生物来源和仇家来源放行；目录外的卷、缺境界、占比越界、没写出身、没写类别都拦下。
        TechniqueVolumeSources.validate(PROVIDER, knower(BASIC, Realm.TRUE_QI, HALF));
        TechniqueVolumeSources.validate(PROVIDER, foe(BASIC, "origin.test.foe"));
        assertRejected(context, knower("sample_scrolls:missing", Realm.TRUE_QI, HALF), "目录外的卷");
        assertRejected(context, knower(BASIC, Realm.REALM_UNSPECIFIED, HALF), "缺境界");
        assertRejected(context, knower(BASIC, Realm.TRUE_QI, 0), "占比为零");
        assertRejected(context, knower(BASIC, Realm.TRUE_QI, 1 + HALF), "占比大于一");
        assertRejected(context, foe(BASIC, " "), "没写出身");
        assertRejected(context, VolumeSource.newBuilder().setVolumeId(BASIC).build(), "没写类别");
        context.succeed();
    }

    @GameTest(maxTicks = DEFAULT_TIMEOUT)
    public void registeredSourcesReachTheCollectedList(GameTestHelper context) {
        // 登记过的来源出现在汇总里，没卷轴的卷核对通过但不发；同一身份再登记一次报错。
        var source = knower(BASIC, Realm.WANDERING_QI, HALF);
        var scrollless = knower(WITHOUT_SCROLL, Realm.WANDERING_QI, HALF);
        TechniqueVolumeSources.register(PROVIDER, server -> List.of(source, scrollless));
        try {
            TechniqueVolumeSources.register(PROVIDER, server -> List.of());
            context.fail("同一身份登记了两次");
        } catch (IllegalArgumentException expected) {
            // 第二次登记被拒，第一次登记的仍然有效。
        }
        var collected = TechniqueVolumeSources.collect(context.getLevel().getServer());
        context.assertTrue(collected.getSourcesList().contains(source), "登记的来源没进汇总");
        context.assertFalse(collected.getSourcesList().contains(scrollless), "没卷轴的卷也发给了客户端");
        context.succeed();
    }

    private static void assertRejected(GameTestHelper context, VolumeSource source, String label) {
        // 核对时抛出且报错带上登记方。
        try {
            TechniqueVolumeSources.validate(PROVIDER, source);
        } catch (IllegalArgumentException expected) {
            context.assertTrue(expected.getMessage().contains(PROVIDER.toString()), "报错没写登记方：" + label);
            return;
        }
        context.fail("来源核对放过了" + label);
    }

    private static VolumeSource knower(String volume, Realm realm, double share) {
        // 某种生物到某境界时会这一卷的来源。
        var knower = VolumeKnower.newBuilder().setEntityType("minecraft:pillager").setRealm(realm).setShare(share);
        return VolumeSource.newBuilder().setVolumeId(volume).setKnower(knower).build();
    }

    private static VolumeSource foe(String volume, String originKey) {
        // 某个出身的仇家身上带着这一卷的来源。
        return VolumeSource.newBuilder().setVolumeId(volume).setFoe(VolumeFoe.newBuilder().setOriginKey(originKey))
                .build();
    }
}
