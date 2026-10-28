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
        float a = FlameWave.texturePhase(-16, 1, 3, .4f);
        float b = FlameWave.texturePhase(-15.875, 1, 3, .4f);
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
            assertTrue(wave.vegetationWidth(.5f) >= .25);
            previous = height;
        }
        assertTrue(high > low * 2.5);
    }

    @Test
    void localTextureDetailVariesWithoutChangingSharedMotion() {
        float a = FlameWave.texturePhase(4, 1, 8, .1f);
        float b = FlameWave.texturePhase(4.125, 1, 8, .9f);
        double delta = Math.abs(a - b);
        assertTrue(Math.min(delta, 1 - delta) > .3);
        assertEquals(a, FlameWave.texturePhase(4, 1, 8, .1f));
    }

    @Test
    void vegetationSettlesContinuouslyBehindTheFront() {
        double previous = 1;
        for (int step = 0; step <= 200; step++) {
            double height = FlameWave.vegetationEnvelope(step / 200.0, .5f);
            assertTrue(height <= previous && height >= .18);
            assertTrue(previous - height < .014);
            previous = height;
        }
        assertEquals(1, FlameWave.vegetationEnvelope(.25, .5f));
        assertEquals(.18, FlameWave.vegetationEnvelope(1, .5f), .00001);
    }

    @Test
    void bandThicknessVariesSmoothlyAroundItsSmallAverageIncrease() {
        double sum = 0, minimum = 2, maximum = 0;
        for (int x = -100; x <= 100; x++)
            for (int z = -100; z <= 100; z++) {
                double value = FlameWave.bandThickness(x, z);
                sum += value;
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
                assertTrue(Math.abs(value - FlameWave.bandThickness(x + .125, z)) < .01);
            }
        assertEquals(1.05, sum / (201 * 201), .002);
        assertTrue(minimum >= .97 && maximum <= 1.13 && maximum - minimum > .12);
    }
}
