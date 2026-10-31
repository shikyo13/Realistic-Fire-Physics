package com.zerotheabsolute.realisticfirephysics.scorch;

import net.minecraft.core.BlockPos;

/** A regrowth front advances through cooled soil without changing the preserved burn footprint. */
public final class GrassRegrowth {
    public static final int LAYERS_PER_INTERVAL = 16;
    public static final int SYNC_STEPS = 256;
    public static final float SPREAD_FRACTION = .55f;
    public static final int SPREAD_LEVEL = Math.round(GrassGrowth.FULL * SPREAD_FRACTION);
    private static final int RATE_SCALE = 8;
    private static final float MIN_DURATION_SCALE = .5f, CELL_VARIATION = .16f;

    private GrassRegrowth() {}

    public static ChunkScars.Ground advance(
            ChunkScars.Ground g, long eligible, long rainClock, long duration, long position) {
        long active = (g.growing() | eligible) & g.cells();
        long elapsed = Math.max(0, rainClock - g.wetTicks());
        if (active == 0 || elapsed == 0) return g.rainAt(rainClock);
        long[] next = new long[GrassGrowth.WORD_COUNT];
        long completed = 0;
        double step = elapsed * (double) GrassGrowth.FULL * SPREAD_FRACTION / Math.max(1, duration);
        while (active != 0) {
            int cell = Long.numberOfTrailingZeros(active);
            int amount =
                    (int)
                            Math.min(
                                    GrassGrowth.FULL,
                                    g.growth().level(cell)
                                            + Math.max(
                                                    1,
                                                    Math.round(
                                                            step / durationScale(position, cell))));
            if (amount == GrassGrowth.FULL) completed |= 1L << cell;
            else GrassGrowth.put(next, cell, amount);
            active &= active - 1;
        }
        long remaining = g.cells() & ~completed;
        float exposure =
                g.plantExposure()
                        * Long.bitCount(remaining)
                        / Math.max(1, Long.bitCount(g.cells()));
        return new ChunkScars.Ground(
                remaining,
                exposure,
                g.cooledAt(),
                rainClock,
                g.grown() | completed,
                GrassGrowth.take(next));
    }

    /** Fixed soil-scale variation creates connected lobes without time-varying noise. */
    static float durationScale(long position, int cell) {
        int x = BlockPos.getX(position) * 8 + (cell & 7);
        int z = BlockPos.getZ(position) * 8 + (cell >>> 3);
        int ax = Math.floorDiv(x, RATE_SCALE), az = Math.floorDiv(z, RATE_SCALE);
        float u = Math.floorMod(x, RATE_SCALE) / (float) RATE_SCALE;
        float v = Math.floorMod(z, RATE_SCALE) / (float) RATE_SCALE;
        u = u * u * (3 - 2 * u);
        v = v * v * (3 - 2 * v);
        float north = mix(noise(ax, az), noise(ax + 1, az), u);
        float south = mix(noise(ax, az + 1), noise(ax + 1, az + 1), u);
        return MIN_DURATION_SCALE
                + mix(north, south, v)
                + CELL_VARIATION * (noise(x + 937, z - 431) - .5f);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float noise(int x, int z) {
        long value = x * 0x9e3779b97f4a7c15L ^ z * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return ((value ^ (value >>> 31)) & 65535) / 65535f;
    }

    public static boolean visuallyChanged(ChunkScars.Ground a, ChunkScars.Ground b) {
        return a.cells() != b.cells() || a.grown() != b.grown() || !a.growth().equals(b.growth());
    }
}
