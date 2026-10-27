package com.zerotheabsolute.surfacefire.audio;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Continuous, non-looping noise with a prescribed power spectrum and conjugate symmetry. */
public final class PowerLawNoise {
    private static final int SIZE = 4096, HOP = SIZE / 2;
    private static final Radix2Fft FFT = new Radix2Fft(SIZE);
    private final double[] real = new double[SIZE], imaginary = new double[SIZE];
    private final double[] output = new double[HOP], tail = new double[HOP];
    private final double[] amplitude = new double[SIZE / 2], window = new double[SIZE];
    private final SplittableRandom random;
    private final double normalization;
    private int cursor = HOP;

    public PowerLawNoise(int sampleRate, double exponent, double cutoff, long seed) {
        random = new SplittableRandom(seed);
        double energy = 0;
        for (int k = 1; k < SIZE / 2; k++) {
            double frequency = (double) k * sampleRate / SIZE;
            double high = Math.clamp((frequency - cutoff * .9) / (cutoff * .2), 0, 1);
            amplitude[k] = high * Math.pow(frequency / cutoff, -exponent / 2);
            energy += 2 * amplitude[k] * amplitude[k];
        }
        normalization = SIZE / Math.sqrt(energy);
        for (int i = 0; i < SIZE; i++) window[i] = Math.sin(Math.PI * (i + .5) / SIZE);
    }

    public double next() {
        if (cursor == HOP) refill();
        return output[cursor++];
    }

    private void refill() {
        Arrays.fill(real, 0);
        Arrays.fill(imaginary, 0);
        for (int k = 1; k < SIZE / 2; k++) {
            double phase = random.nextDouble(2 * Math.PI);
            real[k] = real[SIZE - k] = amplitude[k] * Math.cos(phase);
            imaginary[k] = amplitude[k] * Math.sin(phase);
            imaginary[SIZE - k] = -imaginary[k];
        }
        FFT.transform(real, imaginary, true);
        for (int i = 0; i < HOP; i++) {
            output[i] = tail[i] + real[i] * window[i] * normalization;
            tail[i] = real[i + HOP] * window[i + HOP] * normalization;
        }
        cursor = 0;
    }
}
