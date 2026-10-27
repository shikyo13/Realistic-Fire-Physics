package com.zerotheabsolute.surfacefire.audio;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FireSoundProfileTest {
    @Test
    void defaultTorchSignalIsUnchanged() {
        var original = new FireSynthesizer(31);
        var explicit = new FireSynthesizer(31, FireSoundProfile.TORCH);
        var controls = new CombustionState(1, 1, 0, 0, .55f);
        original.update(controls);
        explicit.update(controls);
        float[] a = new float[48000], b = new float[48000];
        original.render(a, 0, a.length);
        explicit.render(b, 0, b.length);
        assertArrayEquals(a, b);
    }

    @Test
    void wildfireHasMoreBodyAndRetainsOutputHeadroom() {
        var torch = new FireSynthesizer(31);
        var forest = new FireSynthesizer(31, FireSoundProfile.WILDFIRE);
        var controls = new CombustionState(1, 1, 0, 5, .8f);
        torch.update(controls);
        forest.update(controls);
        float[] a = new float[96000], b = new float[96000];
        torch.render(a, 0, a.length);
        forest.render(b, 0, b.length);
        double quiet = 0, loud = 0;
        for (int i = 24000; i < a.length; i++) {
            quiet += a[i] * a[i];
            loud += b[i] * b[i];
            assertTrue(Float.isFinite(b[i]) && Math.abs(b[i]) < .85);
        }
        assertTrue(loud > quiet * 3);
    }
}
