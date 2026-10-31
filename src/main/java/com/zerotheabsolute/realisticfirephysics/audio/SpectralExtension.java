package com.zerotheabsolute.realisticfirephysics.audio;

import java.util.Arrays;

/**
 * Streaming form of the synchronized spectral extension in Chadwick and James (2011), section 4.
 */
public final class SpectralExtension {
    public static final int SAMPLE_RATE = 48_000;
    public static final int HOP = 512;
    private static final int SIZE = 2048;
    private static final double CUTOFF_HZ = 180, BLEND_START_HZ = 165, BLEND_END_HZ = 195;
    private static final double FIT_WIDTH_HZ = 10;
    private static final double HIGH_PASS_HZ = 30;
    private static final Radix2Fft FFT = new Radix2Fft(SIZE);
    private static final double HIGH_PASS_MEMORY =
            Math.exp(-2 * Math.PI * HIGH_PASS_HZ / SAMPLE_RATE);
    private final double[] pressure = new double[SIZE], noise = new double[SIZE];
    private final double[] pr = new double[SIZE], pi = new double[SIZE];
    private final double[] nr = new double[SIZE], ni = new double[SIZE];
    private final double[] accumulated = new double[SIZE];
    private final double[] window = new double[SIZE],
            low = new double[SIZE],
            fit = new double[SIZE];
    private final PowerLawNoise generator;
    private double previousInput, previousFiltered;

    public SpectralExtension(long seed) {
        generator = new PowerLawNoise(SAMPLE_RATE, 3, CUTOFF_HZ, seed);
        for (int k = 0; k < SIZE; k++) {
            window[k] = Math.sin(Math.PI * (k + .5) / SIZE);
            double frequency = (double) Math.min(k, SIZE - k) * SAMPLE_RATE / SIZE;
            low[k] = Math.clamp((BLEND_END_HZ - frequency) / (BLEND_END_HZ - BLEND_START_HZ), 0, 1);
            double distance = (frequency - CUTOFF_HZ) / FIT_WIDTH_HZ;
            fit[k] = Math.exp(-.5 * distance * distance);
        }
    }

    /** Consumes and produces one hop. Buffer contents are copied; no references escape. */
    public void process(float[] input, float[] output) {
        if (input.length != HOP || output.length != HOP)
            throw new IllegalArgumentException("Spectral buffers must contain one hop");
        System.arraycopy(pressure, HOP, pressure, 0, SIZE - HOP);
        System.arraycopy(noise, HOP, noise, 0, SIZE - HOP);
        for (int i = 0; i < HOP; i++) {
            double value = Float.isFinite(input[i]) ? input[i] : 0;
            double filtered = HIGH_PASS_MEMORY * (previousFiltered + value - previousInput);
            previousInput = value;
            previousFiltered = filtered;
            pressure[SIZE - HOP + i] = filtered;
            noise[SIZE - HOP + i] = generator.next();
        }
        Arrays.fill(pi, 0);
        Arrays.fill(ni, 0);
        for (int i = 0; i < SIZE; i++) {
            pr[i] = pressure[i] * window[i];
            nr[i] = Math.abs(pr[i]) * noise[i];
        }
        FFT.transform(pr, pi, false);
        FFT.transform(nr, ni, false);
        double a = 0, b = 0, c = 0;
        for (int k = 0; k < SIZE; k++) {
            double signalPower = pr[k] * pr[k] + pi[k] * pi[k];
            double lo = low[k], hi = 1 - lo;
            a += fit[k] * hi * hi * (nr[k] * nr[k] + ni[k] * ni[k]);
            b += fit[k] * 2 * lo * hi * (pr[k] * nr[k] + pi[k] * ni[k]);
            c += fit[k] * (lo * lo - 1) * signalPower;
        }
        double beta = positiveRoot(a, b, c);
        for (int k = 0; k < SIZE; k++) {
            pr[k] = low[k] * pr[k] + beta * (1 - low[k]) * nr[k];
            pi[k] = low[k] * pi[k] + beta * (1 - low[k]) * ni[k];
        }
        FFT.transform(pr, pi, true);
        // Four overlapping square-root Hann windows have a squared sum of two.
        for (int i = 0; i < SIZE; i++) accumulated[i] += pr[i] * window[i] * .5;
        for (int i = 0; i < HOP; i++) output[i] = (float) accumulated[i];
        System.arraycopy(accumulated, HOP, accumulated, 0, SIZE - HOP);
        Arrays.fill(accumulated, SIZE - HOP, SIZE, 0);
    }

    static double positiveRoot(double a, double b, double c) {
        if (a < 1e-24 || c >= 0) return 0;
        double root = Math.sqrt(Math.max(0, b * b - 4 * a * c));
        return b >= 0 ? -2 * c / (b + root) : (-b + root) / (2 * a);
    }
}
