package com.zerotheabsolute.realisticfirephysics.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;

import java.util.Collection;

/** Heat volumes merge spatially so dense fire does not produce one distortion pass per flame. */
final class HeatHazeSources {
    static final int CAPACITY = 256, LIFETIME_TICKS = 8;
    private static final double CELL_SIZE = 2;
    private static final int WICK_LIFETIME_TICKS = 3;
    private static final double NEAR_WICK_DISTANCE_SQUARED = .2 * .2;
    private static final double NEAR_SURFACE_DISTANCE_SQUARED = 1;
    private static final double NEAR_WICK_WIDTH_RATIO = .45, NEAR_WICK_HEIGHT_RATIO = .9;
    private final Long2ObjectOpenHashMap<Source> sources = new Long2ObjectOpenHashMap<>();

    static final class Source {
        final boolean wick;
        double x, z;
        double y, strength, width, height;
        long tick = Long.MIN_VALUE;

        Source(double x, double z, boolean wick) {
            this.x = x;
            this.z = z;
            this.wick = wick;
        }

        int lifetime() {
            return wick ? WICK_LIFETIME_TICKS : LIFETIME_TICKS;
        }

        double strength(double now) {
            return strength * Math.clamp((lifetime() - (now - tick)) / (wick ? 2.0 : 4.0), 0, 1);
        }

        boolean outsideNearLimit(double squaredDistance) {
            return squaredDistance
                    >= (wick ? NEAR_WICK_DISTANCE_SQUARED : NEAR_SURFACE_DISTANCE_SQUARED);
        }

        double visibleWidth(double distance) {
            return wick ? Math.min(width, distance * NEAR_WICK_WIDTH_RATIO) : width;
        }

        double visibleHeight(double distance) {
            return wick ? Math.min(height, distance * NEAR_WICK_HEIGHT_RATIO) : height;
        }
    }

    void offer(double x, double y, double z, double heat, double width, double height, long tick) {
        if (!(heat > .03)
                || !Double.isFinite(heat)
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) return;
        boolean wick = width < 1;
        double cellSize = wick ? .25 : CELL_SIZE;
        int bx = (int) Math.floor(x / cellSize), by = (int) Math.floor(y / cellSize);
        int bz = (int) Math.floor(z / cellSize);
        long key = BlockPos.asLong(bx, by, bz);
        if (wick) key ^= Long.MIN_VALUE;
        var source = sources.get(key);
        if (source == null) {
            if (sources.size() >= CAPACITY) return;
            source =
                    new Source(
                            wick ? x : (bx + .5) * CELL_SIZE,
                            wick ? z : (bz + .5) * CELL_SIZE,
                            wick);
            sources.put(key, source);
        }
        if (wick) {
            source.x = x;
            source.z = z;
        }
        if (source.tick != tick) {
            source.y = y;
            source.strength = heat;
            source.width = width;
            source.height = height;
        } else {
            source.y = Math.min(source.y, y);
            source.strength = Math.max(source.strength, heat);
            source.width = Math.max(source.width, width);
            source.height = Math.max(source.height, height);
        }
        source.tick = tick;
    }

    void expire(long tick) {
        var iterator = sources.values().iterator();
        while (iterator.hasNext()) {
            var source = iterator.next();
            if (tick - source.tick >= source.lifetime()) iterator.remove();
        }
    }

    Collection<Source> values() {
        return sources.values();
    }

    int size() {
        return sources.size();
    }

    void clear() {
        sources.clear();
    }
}
