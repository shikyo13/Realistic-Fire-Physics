package com.zerotheabsolute.realisticfirephysics.wind;

import net.minecraft.core.Direction;

import java.util.Arrays;

/** Server-thread wind samples shared by all fuel cells in a block. */
public final class SpreadWind {
    private static final int CAPACITY = 2048;
    private static final int SAMPLE_TICKS = 2;
    private static final double TICKS_PER_SECOND = 20;
    private final WindCache wind = new WindCache();
    private final long[] positions = new long[CAPACITY], times = new long[CAPACITY];
    private final double[] xs = new double[CAPACITY], zs = new double[CAPACITY];
    private final double[] scratch = new double[3];

    public SpreadWind() {
        clear();
    }

    public int sample(long position, int x, int y, int z, long tick) {
        int slot = (int) (position ^ (position >>> 32)) & (CAPACITY - 1);
        long time = Math.floorDiv(tick, SAMPLE_TICKS);
        if (positions[slot] != position || times[slot] != time) {
            wind.sample(time * SAMPLE_TICKS / TICKS_PER_SECOND, x + .5, y + 1.2, z + .5, scratch);
            positions[slot] = position;
            times[slot] = time;
            xs[slot] = scratch[0];
            zs[slot] = scratch[2];
        }
        return slot;
    }

    public double x(int slot) {
        return xs[slot];
    }

    public double z(int slot) {
        return zs[slot];
    }

    public static double exposedWind(
            double velocity, int exposed, Direction positiveSource, Direction negativeSource) {
        Direction incoming = velocity >= 0 ? positiveSource : negativeSource;
        return (exposed & (1 << incoming.get3DDataValue())) != 0 ? velocity : 0;
    }

    public void clear() {
        Arrays.fill(times, Long.MIN_VALUE);
        wind.clear();
    }
}
