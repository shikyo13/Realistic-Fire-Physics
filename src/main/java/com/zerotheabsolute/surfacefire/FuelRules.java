package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.torch.TorchBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** World-state fuel queries, called only on the world's owning thread. */
public final class FuelRules {
    private FuelRules() {}

    public static boolean vegetation(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN)
                || s.is(Blocks.DEAD_BUSH);
    }

    public static int fuel(Level level, BlockPos p) {
        if (!level.hasChunkAt(p)) return 0;
        var ground = level.getBlockState(p);
        var above = level.getBlockState(p.above());
        if (!above.getFluidState().isEmpty()
                || !(above.isAir() || vegetation(above) || TorchBlocks.isTorch(above))) return 0;
        if (above.is(Blocks.SNOW)) return 0;
        return ground(ground) ? (vegetation(above) ? 2 : 1) : 0;
    }

    public static boolean ground(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM);
    }
}
