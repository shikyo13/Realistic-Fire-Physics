package com.zerotheabsolute.realisticfirephysics.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FireEmissionTest {
    @Test
    void coolingIsMonotonicAndEndsAtZero() {
        int previous = FireEmission.FLAME;
        for (long tick = 100; tick <= 501; tick++) {
            int light = FireEmission.strength(100, 500, tick);
            assertTrue(light >= 0 && light <= previous);
            previous = light;
        }
        assertEquals(FireEmission.SMOLDER, FireEmission.strength(100, 500, 140));
        assertEquals(0, FireEmission.strength(100, 500, 500));
    }

    @Test
    void sparseFlamesRemainBrightWithoutMakingEmbersAsBright() {
        assertEquals(14, FireEmission.coverage(15, 1, true));
        assertEquals(15, FireEmission.coverage(15, 16, true));
        assertTrue(FireEmission.coverage(8, 16, false) < 8);
        assertEquals(0, FireEmission.coverage(15, 0, true));
    }

    @Test
    void largeMergedSourcesHaveBoundedAdditionalReach() {
        assertEquals(FireEmission.MAXIMUM_SOURCE, FireEmission.coverage(15, 262144, true));
        assertEquals(0, FireEmission.coverage(0, 64, false));
    }
}
