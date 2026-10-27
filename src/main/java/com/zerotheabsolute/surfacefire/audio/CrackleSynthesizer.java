package com.zerotheabsolute.surfacefire.audio;

import java.util.SplittableRandom;

/** Bounded, clustered fuel-fracture transients; no recorded samples or continuous wind bed. */
final class CrackleSynthesizer {
    private static final int VOICES = 8;
    private static final int RATE = SpectralExtension.SAMPLE_RATE;
    private final double[] envelope = new double[VOICES], decay = new double[VOICES];
    private final double[] previousNoise = new double[VOICES];
    private final SplittableRandom random;
    private int untilEvent;
    private double burst;

    CrackleSynthesizer(long seed) {
        random = new SplittableRandom(seed);
    }

    double next(CombustionState state) {
        double burning = state.heat() * state.fuel() * state.wood();
        if (--untilEvent <= 0) {
            if (burning > .01) {
                int slot = 0;
                for (int i = 1; i < VOICES; i++) if (envelope[i] < envelope[slot]) slot = i;
                envelope[slot] = burning * random.nextDouble(.035, .14);
                decay[slot] = Math.exp(-1 / (RATE * random.nextDouble(.002, .012)));
                previousNoise[slot] = 0;
                burst = random.nextDouble() < .28 ? random.nextDouble(8, 28) : burst * .35;
                double eventsPerSecond = burning * (1.5 + 3 * state.moisture() + burst);
                untilEvent =
                        Math.max(
                                1,
                                (int)
                                        (-Math.log(1 - random.nextDouble())
                                                * RATE
                                                / eventsPerSecond));
            } else untilEvent = RATE / 40;
        }
        double value = 0;
        for (int i = 0; i < VOICES; i++) {
            if (envelope[i] < 1e-7) continue;
            double noise = random.nextDouble(-1, 1);
            value += envelope[i] * (.65 * noise + .35 * previousNoise[i]);
            previousNoise[i] = noise;
            envelope[i] *= decay[i];
        }
        return value;
    }
}
