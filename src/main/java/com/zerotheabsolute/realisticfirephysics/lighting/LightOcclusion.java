package com.zerotheabsolute.realisticfirephysics.lighting;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Conservative voxel traversal for the extra samples of a bright, extended source. */
public final class LightOcclusion implements LightCluster.Visibility {
    private static final int OPAQUE = 15, MAX_STEPS = 24;
    private static final int MAX_CACHED_BLOCKS = 16384;
    private final Long2ByteOpenHashMap opacity = new Long2ByteOpenHashMap();
    private final BlockTest blocks;

    public LightOcclusion(Level level) {
        var cursor = new BlockPos.MutableBlockPos();
        blocks =
                (x, y, z) -> {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor)) return true;
                    var state = level.getBlockState(cursor);
                    return state.getLightBlock(level, cursor) >= OPAQUE
                            || !state.getFluidState().isEmpty();
                };
    }

    LightOcclusion(BlockTest blocks) {
        this.blocks = blocks;
    }

    /** Reuse storage, but never reuse visibility after terrain or fluids may have changed. */
    public void reset() {
        opacity.clear();
    }

    private boolean blocked(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte cached = opacity.get(key);
        if (cached != 0) return cached == 2;
        boolean blocked = blocks.blocked(x, y, z);
        if (opacity.size() < MAX_CACHED_BLOCKS) opacity.put(key, (byte) (blocked ? 2 : 1));
        return blocked;
    }

    @Override
    public boolean between(double x, double y, double z, int tx, int ty, int tz) {
        int px = (int) Math.floor(x), py = (int) Math.floor(y), pz = (int) Math.floor(z);
        double dx = tx + .5 - x, dy = ty + .5 - y, dz = tz + .5 - z;
        int sx = Double.compare(dx, 0), sy = Double.compare(dy, 0), sz = Double.compare(dz, 0);
        double stepX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double stepY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double stepZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double nextX = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? px + 1 : px) - x) / dx;
        double nextY = dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? py + 1 : py) - y) / dy;
        double nextZ = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? pz + 1 : pz) - z) / dz;
        for (int i = 0; i < MAX_STEPS; i++) {
            if (blocked(px, py, pz)) return false;
            if (px == tx && py == ty && pz == tz) return true;
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (nextX <= next) {
                px += sx;
                nextX += stepX;
            }
            if (nextY <= next) {
                py += sy;
                nextY += stepY;
            }
            if (nextZ <= next) {
                pz += sz;
                nextZ += stepZ;
            }
        }
        return false;
    }

    @FunctionalInterface
    interface BlockTest {
        boolean blocked(int x, int y, int z);
    }
}
