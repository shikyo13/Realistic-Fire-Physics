package com.zerotheabsolute.surfacefire.audio;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CombustionAudioTest {
    @Test
    void constantFluxIsSilentAndSinusoidalPressureHasTheCorrectDerivative() {
        var constant = new FluxPressure(() -> 4, 360, 48000);
        for (int i = 0; i < 48000; i++) assertEquals(0, constant.next(), 1e-10);
        int[] step = {0};
        var periodic =
                new FluxPressure(() -> Math.sin(2 * Math.PI * 8 * step[0]++ / 360), 360, 48000);
        double energy = 0;
        for (int i = 0; i < 96000; i++) {
            double value = periodic.next();
            if (i >= 48000) energy += value * value;
        }
        assertEquals(2 * Math.PI * 8 / Math.sqrt(2), Math.sqrt(energy / 48000), .25);
    }

    @Test
    void transformRoundTripPreservesIndependentWaveform() {
        int size = 2048;
        var fft = new Radix2Fft(size);
        double[] real = new double[size], imaginary = new double[size];
        for (int i = 0; i < size; i++) real[i] = Math.sin(i * .13) + (i % 17 == 0 ? .2 : 0);
        double[] expected = real.clone();
        fft.transform(real, imaginary, false);
        fft.transform(real, imaginary, true);
        assertArrayEquals(expected, real, 1e-12);
        for (double value : imaginary) assertEquals(0, value, 1e-12);
    }

    @Test
    void crossoverFitConservesTargetEnergyAndHandlesSilence() {
        double beta = SpectralExtension.positiveRoot(3, -.7, -2);
        assertTrue(beta > 0);
        assertEquals(0, 3 * beta * beta - .7 * beta - 2, 1e-12);
        assertEquals(0, SpectralExtension.positiveRoot(0, 0, 0));
        assertEquals(0, SpectralExtension.positiveRoot(1e-30, 0, -1));
    }

    @Test
    void spectralExtensionCannotMakeSoundFromSilence() {
        var extension = new SpectralExtension(7);
        float[] input = new float[SpectralExtension.HOP], output = input.clone();
        for (int block = 0; block < 100; block++) {
            extension.process(input, output);
            for (float sample : output) assertEquals(0, sample);
        }
    }

    @Test
    void pcmOutputIsIndependentOfReadChunkBoundaries() {
        var first = new FireSynthesizer(13);
        var second = new FireSynthesizer(13);
        var state = new CombustionState(1, 1, .2f, 4, .6f);
        first.update(state);
        second.update(state);
        float[] whole = new float[96000], split = whole.clone();
        first.render(whole, 0, whole.length);
        for (int start = 0; start < split.length; start += 137)
            second.render(split, start, Math.min(137, split.length - start));
        assertArrayEquals(whole, split);
        for (float sample : whole) assertTrue(Float.isFinite(sample) && Math.abs(sample) < 1);
    }

    @Test
    void airflowCannotCreateSoundWithoutCombustionAndQuenchingFadesToSilence() {
        var synth = new FireSynthesizer(77);
        float[] samples = new float[48000];
        synth.update(new CombustionState(0, 0, 0, 12, 1));
        synth.render(samples, 0, samples.length);
        assertEquals(0, energy(samples));
        synth.update(new CombustionState(1, 1, 0, 0, 1));
        synth.render(samples, 0, samples.length);
        assertTrue(energy(samples) > 1e-6);
        synth.update(CombustionState.SILENT);
        for (int second = 0; second < 3; second++) synth.render(samples, 0, samples.length);
        assertTrue(energy(samples) < 1e-10);
    }

    @Test
    void invalidControlsAreBoundedAtThePublicationBoundary() {
        var state = new CombustionState(Float.NaN, Float.POSITIVE_INFINITY, -2, 500, 2);
        assertEquals(new CombustionState(0, 0, 0, 12, 1), state);
    }

    @Test
    void motionCreatesCoherentFlutterAndStoppingSettlesWithoutClicks() {
        var flutter = new FlameFlutter(17);
        for (int i = 0; i < 48000; i++) assertEquals(1, flutter.next(0));
        double min = 10, max = 0, previous = 1;
        for (int i = 0; i < 48000 * 3; i++) {
            double gain = flutter.next(6);
            assertTrue(Math.abs(gain - previous) < .002, "Discontinuous gain causes clicks");
            if (i > 48000) {
                min = Math.min(min, gain);
                max = Math.max(max, gain);
            }
            previous = gain;
        }
        assertTrue(max - min > .8, "Motion needs audible envelope contrast");
        for (int i = 0; i < 48000 * 3; i++) previous = flutter.next(0);
        assertEquals(1, previous, 1e-6);
    }

    private static double energy(float[] values) {
        double total = 0;
        for (float value : values) total += value * value;
        return total / values.length;
    }
}
