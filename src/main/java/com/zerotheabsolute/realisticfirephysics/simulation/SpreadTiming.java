package com.zerotheabsolute.realisticfirephysics.simulation;

/** Bounded directional transport; fuel lifetime remains independent of propagation speed. */
public final class SpreadTiming {
    private static final double DOWNWIND_GAIN = 1.25;
    private static final double UPWIND_LOSS = .45;
    private static final double UPWARD_GAIN = .8;
    private static final double DOWNWARD_LOSS = .2;
    public static final int ALL_STEPS = 15;

    private SpreadTiming() {}

    public static int delay(
            double baseTicks,
            double speed,
            double windX,
            double windZ,
            double dx,
            double dy,
            double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return Math.clamp((int) Math.round(baseTicks / speed), 1, 255);
        double along = (windX * dx + windZ * dz) / length;
        double response = along / (1 + Math.abs(along));
        double rate = 1 + response * (response >= 0 ? DOWNWIND_GAIN : UPWIND_LOSS);
        rate *= 1 + dy / length * (dy >= 0 ? UPWARD_GAIN : DOWNWARD_LOSS);
        return Math.clamp((int) Math.round(baseTicks / (speed * rate)), 1, 255);
    }

    public static int put(int packed, int step, int delay) {
        return packed | (Math.clamp(delay, 1, 255) << (step * 8));
    }

    public static int next(int packed, int remaining) {
        int delay = 256;
        for (int step = 0; step < 4; step++)
            if ((remaining & (1 << step)) != 0)
                delay = Math.min(delay, (packed >>> (step * 8)) & 255);
        return delay;
    }

    public static int ready(int packed, int remaining, long elapsed) {
        int result = 0;
        for (int step = 0; step < 4; step++)
            if ((remaining & (1 << step)) != 0 && elapsed >= ((packed >>> (step * 8)) & 255))
                result |= 1 << step;
        return result;
    }
}
