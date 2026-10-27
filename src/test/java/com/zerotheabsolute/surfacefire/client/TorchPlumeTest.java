package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TorchPlumeTest {
    @Test
    void foldsKeepTheRootAttachedAndTheRibbonOrdered() {
        var plume = new TorchPlume();
        for (int step = 0; step < 600; step++) {
            plume.update(step / 60.0, .31f, (step % 101) / 100f);
            assertEquals(0, plume.curl[0], 0.000001);
            assertEquals(0, plume.rise[0], 0.000001);
            assertEquals(0, plume.bend[0], 0.000001);
            assertEquals(1, plume.width[0]);
            for (int i = 1; i <= TorchPlume.SEGMENTS; i++) {
                assertTrue(plume.rise[i] > plume.rise[i - 1]);
                assertTrue(plume.width[i] >= 1 && plume.width[i] < 2);
                assertTrue(Math.abs(plume.curl[i]) < .3);
            }
        }
    }

    @Test
    void gentleGustsAreVisibleWithoutUnboundedSprintBending() {
        assertTrue(TorchPlume.response(.15f) > .25f);
        assertEquals(1, TorchPlume.response(1));
        assertEquals(1, TorchPlume.response(100));
        assertEquals(0, TorchPlume.response(Float.NaN));
    }

    @Test
    void airflowChangesAmplitudeWithoutJumpingAnimationPhase() {
        var a = new TorchPlume();
        var b = new TorchPlume();
        a.update(100000, .31f, .5f);
        b.update(100000, .31f, .5001f);
        for (int i = 0; i <= TorchPlume.SEGMENTS; i++)
            assertEquals(a.curl[i], b.curl[i], .0001);
        b.update(100000.1, .31f, .5f);
        assertTrue(Math.abs(a.curl[8] - b.curl[8]) > .01);
    }
}
