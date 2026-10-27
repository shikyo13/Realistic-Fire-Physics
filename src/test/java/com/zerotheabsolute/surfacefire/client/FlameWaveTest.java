package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FlameWaveTest {
    @Test
    void nearbyFlamesShareWavesButDistantPatchesDiffer() {
        var a = new FlameWave();
        var b = new FlameWave();
        var distant = new FlameWave();
        double nearDifference = 0, farDifference = 0;
        for (double t = 0; t < 10; t += .02) {
            a.update(t, 4, 1, 8);
            b.update(t, 4.125, 1, 8);
            distant.update(t, 12, 1, 8);
            nearDifference += Math.abs(a.height(1, 1) - b.height(1, 1));
            farDifference += Math.abs(a.height(1, 1) - distant.height(1, 1));
            assertTrue(a.height(1, 1) > .78 && a.height(1, 1) < 1.22);
        }
        assertTrue(farDifference > nearDifference * 8);
    }

    @Test
    void brightnessDoesNotFlashAndTexturePhaseIsSpatiallyContinuous() {
        var wave = new FlameWave();
        double previous = 0;
        for (int frame = 0; frame < 600; frame++) {
            wave.update(frame / 60.0, -18, 2, 5);
            double brightness = wave.brightness(.4f);
            if (frame > 0) assertTrue(Math.abs(brightness - previous) < .004);
            previous = brightness;
        }
        float a = FlameWave.texturePhase(-16, 1, 3);
        float b = FlameWave.texturePhase(-15.875, 1, 3);
        double delta = Math.abs(a - b);
        assertTrue(Math.min(delta, 1 - delta) < .005);
    }

    @Test
    void vegetationHasBroadValleysAndOccasionalCrestsWithoutHeightJumps() {
        var wave = new FlameWave();
        double low = Double.POSITIVE_INFINITY, high = 0, previous = 0;
        for (int frame = 0; frame < 1200; frame++) {
            wave.update(frame / 60.0, 4, 1, 8);
            double height = wave.vegetationHeight(.5f);
            low = Math.min(low, height);
            high = Math.max(high, height);
            if (frame > 0) assertTrue(Math.abs(height - previous) < .012);
            assertTrue(wave.vegetationWidth(.5f) >= .30);
            previous = height;
        }
        assertTrue(high > low * 2.5);
    }
}
