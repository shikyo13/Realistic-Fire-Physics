package com.zerotheabsolute.surfacefire.physics;

import com.zerotheabsolute.surfacefire.structure.StructureField;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;

/** Sleeping bodies return to terrain; landing never overwrites an occupied block. */
final class TimberLanding {
    private TimberLanding() {}

    static void settle(FallingTimber entity, ServerLevel level) {
        var occupied = new HashSet<BlockPos>();
        var parts = entity.parts();
        // Logs reserve landing cells before foliage.
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                if ((pass == 1) != part.leaf() || entity.consumed(part)) continue;
                var center = entity.centerOf(i);
                var position = destination(level, center, occupied);
                if (position == null) {
                    if (!part.leaf()
                            && level.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS))
                        Block.popResource(
                                level,
                                BlockPos.containing(center),
                                new ItemStack(part.state().getBlock()));
                    continue;
                }
                BlockState state = part.state();
                if (state.hasProperty(BlockStateProperties.AXIS)) {
                    var axis = state.getValue(BlockStateProperties.AXIS);
                    Vec3 direction =
                            entity.motion.rotate(
                                    new Vec3(
                                            axis == Direction.Axis.X ? 1 : 0,
                                            axis == Direction.Axis.Y ? 1 : 0,
                                            axis == Direction.Axis.Z ? 1 : 0));
                    state =
                            state.setValue(
                                    BlockStateProperties.AXIS,
                                    Direction.getNearest(direction.x, direction.y, direction.z)
                                            .getAxis());
                }
                if (state.hasProperty(BlockStateProperties.WATERLOGGED))
                    state =
                            state.setValue(
                                    BlockStateProperties.WATERLOGGED,
                                    level.getFluidState(position)
                                            .is(net.minecraft.tags.FluidTags.WATER));
                if (level.setBlock(position, state, Block.UPDATE_ALL)) {
                    occupied.add(position);
                    if (!entity.burnMarksCleared())
                        StructureField.get(level)
                                .restoreMoving(
                                        level,
                                        position,
                                        state,
                                        part,
                                        entity.age(),
                                        entity.quenched());
                }
            }
        // Deliver the restored flame clocks before removing the moving representation.
        StructureField.get(level).flushChanges(level);
        entity.discard();
    }

    private static BlockPos destination(ServerLevel level, Vec3 point, HashSet<BlockPos> occupied) {
        var base = BlockPos.containing(point);
        BlockPos best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int y = 0; y <= 1; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    var pos = base.offset(x, y, z);
                    if (occupied.contains(pos)
                            || !level.isInWorldBounds(pos)
                            || !level.hasChunkAt(pos)) continue;
                    var state = level.getBlockState(pos);
                    if (!state.canBeReplaced() || state.hasBlockEntity()) continue;
                    double next = pos.distToCenterSqr(point);
                    if (next < distance) {
                        best = pos;
                        distance = next;
                    }
                }
        return best;
    }
}
