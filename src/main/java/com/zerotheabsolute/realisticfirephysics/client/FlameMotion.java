package com.zerotheabsolute.realisticfirephysics.client;

import net.minecraft.world.phys.Vec3;

/** Coherent gusts and travelling folds carry neighboring plumes together. */
final class FlameMotion {
    private static final double WIND_BEND = .17;
    private final double[] wind = new double[3];
    private final FlameWave wave = new FlameWave();
    private double speed, previousTime = Double.NaN, vigor;
    private Vec3 tilt = new Vec3(0, 1, 0);

    void update(double seconds, double x, double y, double z) {
        updateAt(seconds, x, y, z, x + .5, y + 1.2, z + .5);
    }

    void updateAt(double seconds, double x, double y, double z, double ax, double ay, double az) {
        WorldAirflow.sample(seconds, ax, ay, az, wind);
        speed = Math.sqrt(wind[0] * wind[0] + wind[2] * wind[2]);
        double density = FireEnvironment.ACTIVITY.intensity(x + .5, y + .5, z + .5);
        double dt = Double.isNaN(previousTime) ? 0 : Math.clamp(seconds - previousTime, 0, .25);
        vigor += (density - vigor) * Math.min(1, dt * 5);
        previousTime = seconds;
        wave.update(seconds, x, y, z);
        double bend = WIND_BEND * (1 + .8 * vigor);
        tilt = new Vec3(Math.tanh(wind[0] * bend), 1, Math.tanh(wind[2] * bend));
    }

    double height(float seed, float heat) {
        return (.62 + .58 * seed) * wave.height(heat, vigor) / Math.sqrt(1 + speed * speed * .015);
    }

    float brightness(float seed) {
        return wave.brightness(seed);
    }

    double vegetationHeight(float seed) {
        return wave.vegetationHeight(seed);
    }

    double vegetationWidth(float seed) {
        return wave.vegetationWidth(seed);
    }

    Vec3 up() {
        return tilt;
    }
}
