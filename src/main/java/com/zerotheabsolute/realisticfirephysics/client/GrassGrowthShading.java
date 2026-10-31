package com.zerotheabsolute.realisticfirephysics.client;

/** Stable material inputs shared by vanilla and shader-pack lighting. */
final class GrassGrowthShading {
    private static final float FRESH_RED = .14f, FRESH_GREEN = .10f, FRESH_BLUE = .02f;
    private static final float NORMAL_SCALE = .45f, MAX_NORMAL_SLOPE = .28f;

    private GrassGrowthShading() {}

    static float youth(float amount) {
        float t = Math.clamp(amount, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    static int red(int channel, float youth) {
        return channel(channel, youth, FRESH_RED);
    }

    static int green(int channel, float youth) {
        return channel(channel, youth, FRESH_GREEN);
    }

    static int blue(int channel, float youth) {
        return channel(channel, youth, FRESH_BLUE);
    }

    private static int channel(int value, float youth, float strength) {
        return Math.clamp(Math.round(value * (1 + youth * strength)), 0, 255);
    }

    static float slope(float[] texture, int x, int z, boolean horizontal) {
        if (texture == null) return 0;
        int width = GrassGrowthFront.RESOLUTION;
        int before = horizontal ? z * width + Math.max(0, x - 1) : Math.max(0, z - 1) * width + x;
        int after =
                horizontal
                        ? z * width + Math.min(width - 1, x + 1)
                        : Math.min(width - 1, z + 1) * width + x;
        return Math.clamp(
                (texture[after] - texture[before]) * NORMAL_SCALE,
                -MAX_NORMAL_SLOPE,
                MAX_NORMAL_SLOPE);
    }
}
