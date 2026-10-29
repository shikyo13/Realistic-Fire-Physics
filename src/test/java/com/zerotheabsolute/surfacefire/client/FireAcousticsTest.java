package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FireAcousticsTest {
    @Test
    void fireFadesWithDistanceWhileLargeFiresCarryFarther() {
        for (float energy : new float[] {.02f, 1, 16, 1000}) {
            float previous = 1;
            for (double distance = 0; distance <= 64; distance += .25) {
                float gain = FireAcoustics.distanceGain(distance, energy);
                assertTrue(gain > 0 && gain <= previous);
                previous = gain;
            }
        }
        assertEquals(1, FireAcoustics.distanceGain(0, 1));
        assertTrue(FireAcoustics.distanceGain(16, 1) < .3f);
        assertTrue(FireAcoustics.distanceGain(16, 100) > FireAcoustics.distanceGain(16, 1));
        assertEquals(0, FireAcoustics.distanceGain(Double.NaN, 1));
        assertEquals(0, FireAcoustics.distanceGain(1, Float.POSITIVE_INFINITY));
    }

    @Test
    void thrownTorchRushIsBoundedAndFadesDuringACliffFall() {
        assertEquals(1, TorchAcoustics.motionGain(0));
        assertTrue(TorchAcoustics.motionGain(6) > 1.9);
        assertEquals(TorchAcoustics.motionGain(8), TorchAcoustics.motionGain(100));
        float nearbyRush = TorchAcoustics.motionGain(6) * TorchAcoustics.distanceGain(1);
        float fallingFarAway = TorchAcoustics.motionGain(30) * TorchAcoustics.distanceGain(24);
        assertTrue(fallingFarAway < nearbyRush * .15f);
        assertTrue(TorchAcoustics.distanceGain(16) < .17f);
        assertEquals(0, TorchAcoustics.distanceGain(Double.NaN));
        assertEquals(1, TorchAcoustics.motionGain(Float.NaN));
    }

    @Test
    void heldTorchFollowsTheHandAndYawInsteadOfTheCamera() {
        double[] right = new double[3], left = new double[3], turned = new double[3];
        TorchAcoustics.heldPosition(0, 2, 0, 0, 0, true, right);
        TorchAcoustics.heldPosition(0, 2, 0, 0, 0, false, left);
        TorchAcoustics.heldPosition(0, 2, 0, 180, 0, true, turned);
        assertTrue(right[0] < 0 && left[0] > 0);
        assertEquals(right[1], left[1]);
        assertEquals(right[2], left[2]);
        assertEquals(-right[0], turned[0], .00001);
        assertEquals(-right[2], turned[2], .00001);
        assertEquals(1, TorchAcoustics.distanceGain(.6));
        assertTrue(TorchAcoustics.distanceGain(4) < .65f);
    }
}
