package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BreakBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.WorldEvents;

import java.util.Comparator;
import java.util.HashSet;

/** 劈门共用几何和方块破坏入口，玩家检查世界修改权限，生物检查 mobGriefing。 */
final class TechniqueWorldDoors {
    private static final double HALF = 0.5;
    private static final int BODY_HEIGHT_BLOCKS = 2;

    private TechniqueWorldDoors() {
        // 不单独保存方块状态，世界始终是方块的权威来源。
    }

    static void execute(LivingEntity actor, BreakBlocks intent, TechniqueWorldEffects.Policy policy) {
        // 总开关与生物破坏规则先于任何方块副作用。
        ServerWorld world = (ServerWorld) actor.getWorld();
        if (policy == TechniqueWorldEffects.Policy.PLAYER_NO_BREAK
                || policy == TechniqueWorldEffects.Policy.MOB
                && !world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) {
            return;
        }
        double reach = intent.getForwardDistance().getValue();
        double halfWidth = intent.getWidth().getValue() * HALF;
        int radius = (int) Math.ceil(reach + halfWidth);
        var seen = new HashSet<BlockPos>();
        var positions = BlockPos.stream(actor.getBlockPos().add(-radius, 0, -radius),
                        actor.getBlockPos().add(radius, BODY_HEIGHT_BLOCKS, radius))
                .map(BlockPos::toImmutable)
                .filter(pos -> inside(actor, pos, reach, halfWidth))
                .sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(actor.getPos())))
                .toList();
        int broken = 0;
        for (BlockPos pos : positions) {
            BlockState state = world.getBlockState(pos);
            BlockPos base = base(pos, state);
            if (broken >= intent.getMaximumDoors()) {
                break;
            }
            if (matches(state, intent) && seen.add(base) && breakDoor(actor, base)) {
                broken++;
            }
        }
    }

    private static boolean inside(LivingEntity actor, BlockPos pos, double reach, double halfWidth) {
        // 门中心的前向投影与侧向投影定义矩形范围。
        Vec3d facing = Vec3d.fromPolar(0, actor.getYaw());
        Vec3d delta = Vec3d.ofCenter(pos).subtract(actor.getPos()).multiply(1, 0, 1);
        double forward = delta.dotProduct(facing);
        double lateral = Math.abs(delta.x * facing.z - delta.z * facing.x);
        return forward >= 0 && forward <= reach && lateral <= halfWidth;
    }

    private static boolean matches(BlockState state, BreakBlocks intent) {
        // 门的种类完全由数据包标签决定。
        return intent.getBlockTagsList().stream().anyMatch(id ->
                state.isIn(TagKey.of(RegistryKeys.BLOCK, new Identifier(id))));
    }

    private static BlockPos base(BlockPos pos, BlockState state) {
        // 双格门只从下半扇执行一次破坏，原版负责移除上半扇。
        if (state.getBlock() instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.down();
        }
        return pos;
    }

    private static boolean breakDoor(LivingEntity actor, BlockPos pos) {
        // 玩家在统一破坏入口前检查出生点等世界修改权限，耐久由功法末击统一结算。
        ServerWorld world = (ServerWorld) actor.getWorld();
        BlockState original = world.getBlockState(pos);
        if (actor instanceof ServerPlayerEntity player && !world.canPlayerModifyAt(player, pos)) {
            return false;
        }
        boolean broken = world.breakBlock(pos, true /* drop */, actor);
        if (broken) {
            world.syncWorldEvent(WorldEvents.ZOMBIE_BREAKS_WOODEN_DOOR, pos, 0);
            world.syncWorldEvent(WorldEvents.BLOCK_BROKEN, pos, Block.getRawIdFromState(original));
        }
        return broken;
    }
}
