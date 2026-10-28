package com.zerotheabsolute.surfacefire.structure;

import com.zerotheabsolute.surfacefire.FuelRules;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Combustion rates are independent of rendering and of any moving-body implementation. */
public enum StructureFuel {
    WOOD(240, 400, 3, 6),
    LEAVES(65, 130, 2, 4),
    LIGHT(95, 180, 2, 5);
    public final int flameTicks, consumeTicks, minimumStepTicks, stepVariation;

    StructureFuel(int flameTicks, int consumeTicks, int minimumStepTicks, int stepVariation) {
        this.flameTicks = flameTicks;
        this.consumeTicks = consumeTicks;
        this.minimumStepTicks = minimumStepTicks;
        this.stepVariation = stepVariation;
    }

    public static StructureFuel at(Level level, BlockPos pos) {
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || state.isAir() || FuelRules.vegetation(state))
            return null;
        boolean flammable = false;
        for (Direction face : Direction.values())
            if (state.isFlammable(level, pos, face)) {
                flammable = true;
                break;
            }
        if (!flammable) return null;
        if (state.is(BlockTags.LEAVES)) return LEAVES;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) return WOOD;
        return LIGHT;
    }
}
