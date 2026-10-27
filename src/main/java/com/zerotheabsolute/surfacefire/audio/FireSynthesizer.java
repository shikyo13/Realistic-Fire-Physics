package com.zerotheabsolute.surfacefire.audio;

/**
 * Stateful mono PCM synthesis. One audio worker owns each instance; all work buffers are reused.
 */
public final class FireSynthesizer {
    public static final int SAMPLE_RATE = SpectralExtension.SAMPLE_RATE;
    private static final double PRESSURE_GAIN = .055;
    private static final double OUTPUT_HEADROOM = .85;
    private static final double CRACKLE_GAIN = .9;
    private static final double SOFTENING_MIX = .12;
    private static final double SOFTENING_RESPONSE =
            1 - Math.exp(-2 * Math.PI * 3500 / SAMPLE_RATE);
    private final CombustionFlux flux;
    private final FluxPressure pressure;
    private final SpectralExtension extension;
    private final CrackleSynthesizer crackle;
    private final FlameFlutter flutter;
    private double softened;
    private final float[] low = new float[SpectralExtension.HOP];
    private final float[] extended = new float[SpectralExtension.HOP];
    private CombustionState state = CombustionState.SILENT;
    private int cursor = SpectralExtension.HOP;

    public FireSynthesizer(long seed) {
        flux = new CombustionFlux(seed);
        pressure = new FluxPressure(flux::next, CombustionFlux.STEPS_PER_SECOND, SAMPLE_RATE);
        extension = new SpectralExtension(seed ^ 0x9e3779b97f4a7c15L);
        crackle = new CrackleSynthesizer(seed ^ 0xc6bc279692b5cc83L);
        flutter = new FlameFlutter(seed ^ 0x165667b19e3779f9L);
    }

    public void update(CombustionState state) {
        this.state = state;
        flux.update(state);
    }

    public void render(float[] output, int offset, int frames) {
        java.util.Objects.checkFromIndexSize(offset, frames, output.length);
        for (int i = offset; i < offset + frames; i++) {
            if (cursor == extended.length) {
                for (int j = 0; j < low.length; j++) low[j] = (float) pressure.next();
                extension.process(low, extended);
                cursor = 0;
            }
            double value = extended[cursor++] * PRESSURE_GAIN + crackle.next(state) * CRACKLE_GAIN;
            softened += (value - softened) * SOFTENING_RESPONSE;
            value += SOFTENING_MIX * (softened - value);
            value *= flutter.next(state.airflow());
            output[i] = (float) (OUTPUT_HEADROOM * value / (1 + Math.abs(value)));
        }
    }
}
