package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.scorch.GrassRegrowth;

import java.util.Arrays;

/** Fixed native texels fill continuously over stationary scorch, then merge into larger quads. */
final class GrassGrowthFront {
    static final int RESOLUTION = 16;
    private static final float PIXEL_FILL_SHARE = .045f;
    private static final float FILL_INTERVAL =
            PIXEL_FILL_SHARE * GrassRegrowth.LAYERS_PER_INTERVAL * GrassRegrowth.SPREAD_FRACTION;
    private final float[] starts = new float[RESOLUTION * RESOLUTION];
    private final byte[] directions = new byte[starts.length];
    private final int[] rows = new int[RESOLUTION];
    private final int[] mergedRows = new int[RESOLUTION];
    private final int[] moving = new int[starts.length];
    private int movingCount;
    private boolean movingChanged;
    private final float[] progress = new float[64];
    private final float[] amounts = new float[starts.length];

    @FunctionalInterface
    interface Surface {
        void quad(float ax, float az, float bx, float bz, float cx, float cz, float dx, float dz);
    }

    GrassGrowthFront(long positionSeed) {
        Arrays.fill(progress, Float.NaN);
        for (int i = 0; i < starts.length; i++) {
            long value = positionSeed ^ (i * 0x9e3779b97f4a7c15L);
            value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
            value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
            value ^= value >>> 31;
            starts[i] = ((value & 65535) + .5f) / 65536f * (1 - FILL_INTERVAL);
            directions[i] = (byte) ((value >>> 20) & 3);
        }
    }

    void draw(float[] progress, Surface sink) {
        update(progress);
        drawMoving(sink);
        drawFixed(sink);
    }

    /** Returns true only when the completed-pixel mesh has changed. */
    boolean update(float[] progress) {
        movingChanged = false;
        if (Arrays.equals(this.progress, progress)) return false;
        System.arraycopy(progress, 0, this.progress, 0, this.progress.length);
        movingCount = 0;
        boolean changed = false;
        for (int z = 0; z < RESOLUTION; z++) {
            int full = 0;
            for (int x = 0; x < RESOLUTION; x++) {
                float t = progress[(z / 2) * 8 + x / 2];
                int pixel = z * RESOLUTION + x;
                float amount = Math.clamp((t - starts[pixel]) / FILL_INTERVAL, 0, 1);
                float previous = amounts[pixel];
                if (amount != previous
                        && ((previous > 0 && previous < 1) || (amount > 0 && amount < 1)))
                    movingChanged = true;
                amounts[pixel] = amount;
                if (amount >= 1) full |= 1 << x;
                else if (amount > 0) moving[movingCount++] = pixel;
            }
            changed |= rows[z] != full;
            rows[z] = full;
        }
        return changed;
    }

    boolean hasMovingPixels() {
        return movingCount > 0;
    }

    boolean movingChanged() {
        return movingChanged;
    }

    void drawMoving(Surface sink) {
        for (int i = 0; i < movingCount; i++) {
            int pixel = moving[i], x = pixel % RESOLUTION, z = pixel / RESOLUTION;
            float amount = amounts[pixel];
            // Only the growing edge moves; its texture remains anchored to the terrain.
            amount = amount * amount * (3 - 2 * amount);
            float x0 = x / (float) RESOLUTION, x1 = (x + 1f) / RESOLUTION;
            float z0 = z / (float) RESOLUTION, z1 = (z + 1f) / RESOLUTION;
            float length = amount / RESOLUTION;
            switch (directions[pixel]) {
                case 0 -> x1 = x0 + length;
                case 1 -> x0 = x1 - length;
                case 2 -> z1 = z0 + length;
                default -> z0 = z1 - length;
            }
            sink.quad(x0, z0, x0, z1, x1, z1, x1, z0);
        }
    }

    void drawFixed(Surface sink) {
        System.arraycopy(rows, 0, mergedRows, 0, RESOLUTION);
        for (int z = 0; z < RESOLUTION; z++)
            for (int x = 0; x < RESOLUTION; x++) {
                if ((mergedRows[z] & 1 << x) == 0) continue;
                int end = x + 1;
                while (end < RESOLUTION && (mergedRows[z] & 1 << end) != 0) end++;
                int bits = ((1 << (end - x)) - 1) << x, bottom = z + 1;
                while (bottom < RESOLUTION && (mergedRows[bottom] & bits) == bits) {
                    mergedRows[bottom] &= ~bits;
                    bottom++;
                }
                float x0 = x / (float) RESOLUTION, x1 = end / (float) RESOLUTION;
                float z0 = z / (float) RESOLUTION, z1 = bottom / (float) RESOLUTION;
                sink.quad(x0, z0, x0, z1, x1, z1, x1, z0);
                x = end - 1;
            }
    }
}
