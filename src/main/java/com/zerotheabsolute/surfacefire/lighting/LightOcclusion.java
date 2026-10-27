package com.zerotheabsolute.surfacefire.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Conservative voxel traversal for the extra samples of a bright, extended source. */
public final class LightOcclusion implements LightCluster.Visibility {
    private static final int OPAQUE = 15, MAX_STEPS = 24;
    private final Level level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LightOcclusion(Level level) {
        this.level = level;
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
            cursor.set(px, py, pz);
            if (!level.hasChunkAt(cursor)) return false;
            var state = level.getBlockState(cursor);
            if (state.getLightBlock(level, cursor) >= OPAQUE || !state.getFluidState().isEmpty())
                return false;
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
}
