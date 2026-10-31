package com.zerotheabsolute.realisticfirephysics.audio;

import java.util.SplittableRandom;

/**
 * Reduced fuel-mixing reactors supplying the heat-release term of the combustion sound equation.
 */
public final class CombustionFlux {
    public static final int STEPS_PER_SECOND = 360;
    private static final int REACTORS = 12;
    private static final double STEP = 1.0 / STEPS_PER_SECOND;
    private static final double CALM_MIXING_RATE = 36;
    private static final double TURBULENT_MIXING_RATE = 240;
    private static final double CALM_MIXING_RESPONSE = 45;
    private static final double TURBULENT_MIXING_RESPONSE = 95;
    private static final double CALM_FLUCTUATION = .18;
    private static final double FLOW_FLUCTUATION = .65;
    private static final double CONTROL_RESPONSE = 1 - Math.exp(-STEP * 25);
    private static final double THERMAL_RESPONSE = 1 - Math.exp(-STEP * 14);
    private static final double BASE_REACTION_RATE = 28;
    private final double[] fuel = new double[REACTORS];
    private final double[] mixing = new double[REACTORS];
    private final double[] mixingTarget = new double[REACTORS];
    private final double[] temperature = new double[REACTORS];
    private final SplittableRandom random;
    private CombustionState target = CombustionState.SILENT;
    private double heat, supply, moisture, airflow, mixingClock;

    public CombustionFlux(long seed) {
        random = new SplittableRandom(seed);
    }

    public void update(CombustionState state) {
        target = state;
    }

    public double next() {
        heat += (target.heat() - heat) * CONTROL_RESPONSE;
        supply += (target.fuel() - supply) * CONTROL_RESPONSE;
        moisture += (target.moisture() - moisture) * CONTROL_RESPONSE;
        airflow += (target.airflow() - airflow) * CONTROL_RESPONSE;
        double flux = 0;
        double ventilation = 1 + .13 * airflow;
        double activity = CombustionAirflow.activity(airflow);
        double fluctuation = CALM_FLUCTUATION + FLOW_FLUCTUATION * activity;
        double response =
                1
                        - Math.exp(
                                -STEP
                                        * (CALM_MIXING_RESPONSE
                                                + (TURBULENT_MIXING_RESPONSE - CALM_MIXING_RESPONSE)
                                                        * activity));
        mixingClock +=
                STEP * (CALM_MIXING_RATE + (TURBULENT_MIXING_RATE - CALM_MIXING_RATE) * activity);
        boolean freshMixing = mixingClock >= 1;
        if (freshMixing) mixingClock -= Math.floor(mixingClock);
        for (int i = 0; i < REACTORS; i++) {
            if (freshMixing) mixingTarget[i] = random.nextDouble(-1, 1);
            mixing[i] += (mixingTarget[i] - mixing[i]) * response;
            double oxygen = Math.max(.05, ventilation + fluctuation * mixing[i]);
            double thermalTarget = heat * (1 - .7 * moisture);
            temperature[i] += (thermalTarget - temperature[i]) * THERMAL_RESPONSE;
            double reaction = BASE_REACTION_RATE * oxygen * Math.max(0, temperature[i] - .12);
            double injected = supply * heat * STEP * (1 + .18 * mixing[i]);
            fuel[i] += injected;
            double consumed = fuel[i] * (1 - Math.exp(-reaction * STEP));
            fuel[i] -= consumed;
            flux += consumed / STEP;
        }
        return flux / REACTORS;
    }
}
