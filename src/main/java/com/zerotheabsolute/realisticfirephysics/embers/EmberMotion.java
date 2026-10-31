package com.zerotheabsolute.realisticfirephysics.embers;

/** Tick-sized transport of a cooling firebrand, in blocks and ticks. */
public final class EmberMotion {
    public static final int MAX_AGE = 200;
    private static final double WIND_RESPONSE = .075;
    private static final double GRAVITY = .003;
    private static final double TERMINAL_FALL_SPEED = -.12;
    private static final int LIFT_TICKS = 24;
    public double x, y, z;

    public void advance(int age, double windX, double windZ) {
        x += (windX / 20 - x) * WIND_RESPONSE;
        z += (windZ / 20 - z) * WIND_RESPONSE;
        y = Math.max(TERMINAL_FALL_SPEED, y - GRAVITY * Math.min(1, age / (double) LIFT_TICKS));
    }

    public static float heat(int age) {
        return (float) Math.clamp(1 - age / (double) MAX_AGE, 0, 1);
    }
}
