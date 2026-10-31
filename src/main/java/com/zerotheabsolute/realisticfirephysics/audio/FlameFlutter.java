package com.zerotheabsolute.realisticfirephysics.audio;

import java.util.SplittableRandom;

/** Low-rate entrainment envelope: relative airflow modulates the burning sound as a whole. */
final class FlameFlutter {
    private static final int CONTROL_RATE = 240;
    private static final int SAMPLES_PER_CONTROL = FireSynthesizer.SAMPLE_RATE / CONTROL_RATE;
    private static final double FLOW_RESPONSE = 1 - Math.exp(-1.0 / (CONTROL_RATE * .16));
    private static final double WANDER_RESPONSE = 1 - Math.exp(-1.0 / (CONTROL_RATE * .3));
    private static final double BASE_FLUTTER_HZ = 5.5, FLOW_FLUTTER_HZ = 4.5;
    private static final double SECONDARY_FLUTTER_HZ = 3.7;
    private static final double MEAN_FLARE = .22, FLUTTER_DEPTH = .48, SECONDARY_DEPTH = .18;
    private static final double TWO_PI = 2 * Math.PI;
    private final SplittableRandom random;
    private double airflow, phase, secondary, wander, gain = 1, increment;
    private int remaining;

    FlameFlutter(long seed) {
        random = new SplittableRandom(seed);
    }

    double next(float targetAirflow) {
        if (remaining == 0) {
            airflow += (targetAirflow - airflow) * FLOW_RESPONSE;
            double strength = CombustionAirflow.activity(airflow);
            wander += (random.nextDouble(-1, 1) - wander) * WANDER_RESPONSE;
            phase =
                    (phase
                                    + TWO_PI
                                            * (BASE_FLUTTER_HZ
                                                    + FLOW_FLUTTER_HZ * strength
                                                    + wander)
                                            / CONTROL_RATE)
                            % TWO_PI;
            secondary = (secondary + TWO_PI * SECONDARY_FLUTTER_HZ / CONTROL_RATE) % TWO_PI;
            double target =
                    1
                            + strength
                                    * (MEAN_FLARE
                                            + FLUTTER_DEPTH * Math.sin(phase)
                                            + SECONDARY_DEPTH * Math.sin(secondary));
            increment = (target - gain) / SAMPLES_PER_CONTROL;
            remaining = SAMPLES_PER_CONTROL;
        }
        remaining--;
        gain += increment;
        return gain;
    }
}
