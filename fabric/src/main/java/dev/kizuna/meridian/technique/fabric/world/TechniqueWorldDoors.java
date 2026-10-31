package dev.kizuna.meridian.technique.fabric.world;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.BreakBlocks;
import java.util.Comparator;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/** 劈门共用几何和方块破坏入口，玩家检查世界修改权限，生物检查 mobGriefing。 */
final class TechniqueWorldDoors {
    private static final double HALF = 0.5;
    private static final int BODY_HEIGHT_BLOCKS = 2;

    private TechniqueWorldDoors() {
        // 不单独保存方块状态，世界始终是方块的权威来源。
    }

    static void execute(LivingEntity actor, BreakBlocks intent, TechniqueWorldEffects.Policy policy) {
        // 总开关与生物破坏规则先于任何方块副作用。
        ServerLevel world = (ServerLevel) actor.level();
        if (policy == TechniqueWorldEffects.Policy.PLAYER_NO_BREAK
                || policy == TechniqueWorldEffects.Policy.MOB
                && !world.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }
        double reach = intent.getForwardDistance().getValue();
        double halfWidth = intent.getWidth().getValue() * HALF;
        int radius = (int) Math.ceil(reach + halfWidth);
        var seen = new HashSet<BlockPos>();
        var positions = BlockPos.betweenClosedStream(actor.blockPosition().offset(-radius, 0, -radius),
                        actor.blockPosition().offset(radius, BODY_HEIGHT_BLOCKS, radius))
                .map(BlockPos::immutable)
                .filter(pos -> inside(actor, pos, reach, halfWidth))
                .sorted(Comparator.comparingDouble(pos -> pos.distToCenterSqr(actor.position())))
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
        Vec3 facing = Vec3.directionFromRotation(0, actor.getYRot());
        Vec3 delta = Vec3.atCenterOf(pos).subtract(actor.position()).multiply(1, 0, 1);
        double forward = delta.dot(facing);
        double lateral = Math.abs(delta.x * facing.z - delta.z * facing.x);
        return forward >= 0 && forward <= reach && lateral <= halfWidth;
    }

    private static boolean matches(BlockState state, BreakBlocks intent) {
        // 门的种类完全由数据包标签决定。
        return intent.getBlockTagsList().stream().anyMatch(id ->
                state.is(TagKey.create(Registries.BLOCK, new ResourceLocation(id))));
    }

    private static BlockPos base(BlockPos pos, BlockState state) {
        // 双格门只从下半扇执行一次破坏，原版负责移除上半扇。
        if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    private static boolean breakDoor(LivingEntity actor, BlockPos pos) {
        // 玩家在统一破坏入口前检查出生点等世界修改权限，耐久由功法末击统一结算。
        ServerLevel world = (ServerLevel) actor.level();
        BlockState original = world.getBlockState(pos);
        if (actor instanceof ServerPlayer player && !world.mayInteract(player, pos)) {
            return false;
        }
        boolean broken = world.destroyBlock(pos, true /* drop */, actor);
        if (broken) {
            world.levelEvent(LevelEvent.SOUND_ZOMBIE_DOOR_CRASH, pos, 0);
            world.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(original));
        }
        return broken;
    }
}
