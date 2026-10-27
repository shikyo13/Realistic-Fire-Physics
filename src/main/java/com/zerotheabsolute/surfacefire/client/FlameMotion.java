package com.zerotheabsolute.surfacefire.client;

import net.minecraft.world.phys.Vec3;

/** Coherent gusts carry the plume; faster local flicker breaks up its burning edge. */
final class FlameMotion {
    private static final double WIND_BEND = .17;
    private static final double TIP_RATE = 18.5;
    private static final double SECONDARY_TIP_RATE = 27.3;
    private final double[] wind = new double[3];
    private double phase, secondary, speed;
    private Vec3 tilt = new Vec3(0, 1, 0);

    void update(double seconds, double x, double y, double z) {
        WorldAirflow.sample(seconds, x + .5, y + 1.2, z + .5, wind);
        speed = Math.sqrt(wind[0] * wind[0] + wind[2] * wind[2]);
        phase = seconds * TIP_RATE + x * 1.13 - z * .91 + y * .77;
        secondary = seconds * SECONDARY_TIP_RATE - x * .57 + z * 1.37;
        tilt = new Vec3(Math.tanh(wind[0] * WIND_BEND), 1, Math.tanh(wind[2] * WIND_BEND));
    }

    private double flicker(float seed) {
        return Math.sin(phase + seed * 17) * .72 + Math.sin(secondary + seed * 31) * .28;
    }

    double height(float seed, float heat) {
        return (.55 + .75 * seed)
                * (1 + (.07 + .10 * heat) * flicker(seed))
                / Math.sqrt(1 + speed * speed * .015);
    }

    float brightness(float seed) {
        return (float) Math.clamp(.73 + .20 * seed + .07 * flicker(seed), .5, 1);
    }

    Vec3 up() {
        return tilt;
    }
}
