package com.zerotheabsolute.realisticfirephysics.audio;

import java.util.function.DoubleSupplier;

/** Analytic derivative of Mitchell-Netravali reconstructed heat release (B = C = 1/3). */
public final class FluxPressure {
    private final DoubleSupplier flux;
    private final double stepPerSample;
    private final double simulationRate;
    private double a, b, c, d, phase;
    private boolean initialized;

    public FluxPressure(DoubleSupplier flux, double simulationRate, int sampleRate) {
        if (!(simulationRate > 0) || sampleRate < simulationRate)
            throw new IllegalArgumentException("Audio rate must cover the simulation rate");
        this.flux = flux;
        this.simulationRate = simulationRate;
        stepPerSample = simulationRate / sampleRate;
    }

    public double next() {
        if (!initialized) {
            a = b = c = d = finite(flux.getAsDouble());
            initialized = true;
        }
        while (phase >= 1) {
            phase -= 1;
            a = b;
            b = c;
            c = d;
            d = finite(flux.getAsDouble());
        }
        double pressure =
                a * derivative(phase + 1)
                        + b * derivative(phase)
                        + c * derivative(phase - 1)
                        + d * derivative(phase - 2);
        phase += stepPerSample;
        return pressure * simulationRate;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    private static double derivative(double x) {
        double t = Math.abs(x);
        double derivative =
                t < 1 ? (21 * t * t - 24 * t) / 6 : t < 2 ? (-7 * t * t + 24 * t - 20) / 6 : 0;
        return x < 0 ? -derivative : derivative;
    }
}
