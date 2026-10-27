package com.zerotheabsolute.surfacefire.wind;

import java.util.Arrays;

/** Fixed storage with smooth interpolation in space and time; no per-sample allocation. */
public final class WindCache {
    public static final int CAPACITY = 8192;
    private static final double CELL_SIZE = 2;
    private static final double SAMPLES_PER_SECOND = 10;
    private final WindField field = new WindField();
    private final int[] xs = new int[CAPACITY], ys = new int[CAPACITY], zs = new int[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private final double[] samples = new double[CAPACITY * 6];
    private final double[] scratch = new double[3];

    public WindCache() {
        clear();
    }

    public void clear() {
        Arrays.fill(times, Long.MIN_VALUE);
    }

    public void sample(double seconds, double x, double y, double z, double[] out) {
        out[0] = out[1] = out[2] = 0;
        if (!Double.isFinite(seconds)
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) return;
        x /= CELL_SIZE;
        y /= CELL_SIZE;
        z /= CELL_SIZE;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double time = seconds * SAMPLES_PER_SECOND;
        long tick = (long) Math.floor(time);
        double blend = time - tick;
        for (int k = 0; k < 2; k++)
            for (int j = 0; j < 2; j++)
                for (int i = 0; i < 2; i++) {
                    int slot = prepare(ix + i, iy + j, iz + k, tick);
                    double weight =
                            (i == 0 ? 1 - fx : fx)
                                    * (j == 0 ? 1 - fy : fy)
                                    * (k == 0 ? 1 - fz : fz);
                    for (int axis = 0; axis < 3; axis++) {
                        double a = samples[slot + axis], b = samples[slot + axis + 3];
                        out[axis] += weight * (a + (b - a) * blend);
                    }
                }
    }

    private int prepare(int x, int y, int z, long tick) {
        int h = x * 0x1f1f1f1f ^ y * 0x6c8e9cf5 ^ z * 0x45d9f3b;
        int index = (h ^ (h >>> 16)) & (CAPACITY - 1), at = index * 6;
        boolean same = xs[index] == x && ys[index] == y && zs[index] == z;
        if (same && times[index] == tick) return at;
        if (same && times[index] == tick - 1) System.arraycopy(samples, at + 3, samples, at, 3);
        else {
            field.sample(
                    tick / SAMPLES_PER_SECOND,
                    x * CELL_SIZE,
                    y * CELL_SIZE,
                    z * CELL_SIZE,
                    scratch);
            System.arraycopy(scratch, 0, samples, at, 3);
        }
        field.sample(
                (tick + 1) / SAMPLES_PER_SECOND,
                x * CELL_SIZE,
                y * CELL_SIZE,
                z * CELL_SIZE,
                scratch);
        System.arraycopy(scratch, 0, samples, at + 3, 3);
        xs[index] = x;
        ys[index] = y;
        zs[index] = z;
        times[index] = tick;
        return at;
    }
}
