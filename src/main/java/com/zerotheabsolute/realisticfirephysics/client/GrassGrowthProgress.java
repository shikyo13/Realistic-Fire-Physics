package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.scorch.ChunkScars;
import com.zerotheabsolute.realisticfirephysics.scorch.GrassGrowth;

/** Interpolate received progress without predicting growth while rain or simulation is paused. */
final class GrassGrowthProgress {
    private static final double MAX_INTERVAL = 20;
    private final float[] from = new float[GrassGrowth.CELL_COUNT];
    private final float[] target = new float[from.length];
    private final float[] sampled = new float[from.length];
    private double started, duration = 1;

    GrassGrowthProgress(ChunkScars.Ground initial, double now) {
        for (int i = 0; i < from.length; i++) from[i] = target[i] = initial.grass(i);
        started = now;
    }

    void receive(ChunkScars.Ground next, double now) {
        float fraction = fraction(now);
        for (int i = 0; i < from.length; i++) {
            float progress = next.grass(i);
            from[i] = progress < target[i] ? progress : from[i] + (target[i] - from[i]) * fraction;
            target[i] = progress;
        }
        duration = Math.clamp(now - started, 1, MAX_INTERVAL);
        started = now;
    }

    float[] values(double now) {
        float fraction = fraction(now);
        for (int i = 0; i < from.length; i++)
            sampled[i] = from[i] + (target[i] - from[i]) * fraction;
        return sampled;
    }

    private float fraction(double now) {
        return (float) Math.clamp((now - started) / duration, 0, 1);
    }
}
