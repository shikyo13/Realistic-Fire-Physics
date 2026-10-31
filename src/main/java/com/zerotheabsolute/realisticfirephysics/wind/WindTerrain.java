package com.zerotheabsolute.realisticfirephysics.wind;

import com.zerotheabsolute.realisticfirephysics.wind.flow.ObstacleFlow;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Only loaded terrain is observed; leaves remain porous and sealed full blocks stop airflow. */
public final class WindTerrain implements ObstacleFlow.Terrain {
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private Level level;
    private final long[] columns = new long[256];
    private final int[] heights = new int[256];

    public void advance(TerrainWind wind, Level level) {
        this.level = level;
        java.util.Arrays.fill(columns, Long.MIN_VALUE);
        try {
            wind.advance(level.getGameTime(), this);
        } finally {
            this.level = null;
        }
    }

    @Override
    public boolean solid(int x, int y, int z) {
        position.set(x, y, z);
        if (y < level.getMinBuildHeight()) return true;
        if (y >= level.getMaxBuildHeight()) return false;
        if (!level.hasChunkAt(position)) return true;
        var state = level.getBlockState(position);
        if (state.is(net.minecraft.tags.BlockTags.LEAVES)) return false;
        return !state.getFluidState().isEmpty() || state.isCollisionShapeFullBlock(level, position);
    }

    @Override
    public boolean outdoors(int x, int y, int z) {
        position.set(x, y, z);
        if (!level.hasChunkAt(position)) return false;
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        int slot = (x & 15) | ((z & 15) << 4);
        if (columns[slot] != key) {
            heights[slot] =
                    level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types
                                    .MOTION_BLOCKING_NO_LEAVES,
                            x,
                            z);
            columns[slot] = key;
        }
        return y >= heights[slot];
    }
}
