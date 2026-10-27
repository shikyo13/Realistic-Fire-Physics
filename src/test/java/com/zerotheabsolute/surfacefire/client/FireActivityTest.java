package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FireActivityTest {
    @Test
    void nearbyFrontsShareOneEmitterAndConserveCombustion() {
        var field = new FireActivity();
        field.begin(0, 2, 0);
        field.add(-.5, 1, -.5, 2, 1);
        field.add(7.5, 1, .5, 3, 0);
        field.finish();
        assertEquals(5, field.emitters[0].energy);
        assertEquals(.4, field.emitters[0].wood, .0001);
        assertEquals(4.3, field.emitters[0].x, .0001);
        assertEquals(0, field.emitters[1].energy);
    }

    @Test
    void voiceCountStaysBoundedAcrossSeparatedFires() {
        var field = new FireActivity();
        field.begin(0, 2, 0);
        for (int x : new int[] {-30, 30})
            for (int z : new int[] {-30, 30}) field.add(x, 1, z, 20, .5f);
        field.finish();
        assertEquals(3, field.emitters.length);
        for (var source : field.emitters) assertEquals(20, source.energy);
    }

    @Test
    void coolingTeleportAndInvalidInputsLeaveNoStaleFire() {
        var field = new FireActivity();
        field.begin(0, 0, 0);
        field.add(0, 0, 0, 64, 1);
        field.finish();
        assertTrue(field.intensity(0, 0, 0) > .8);
        field.begin(30000000, 0, -30000000);
        field.add(0, 0, 0, 64, 1);
        field.add(Double.NaN, 0, 0, 1, 1);
        field.add(30000000, 0, -30000000, Float.POSITIVE_INFINITY, 1);
        field.finish();
        assertEquals(0, field.intensity(30000000, 0, -30000000));
        for (var source : field.emitters) assertEquals(0, source.energy);
        field.add(30000000, 0, -30000000, 2, 1);
        field.finish();
        assertEquals(2, field.emitters[0].energy);
        field.clear();
        assertEquals(0, field.intensity(30000000, 0, -30000000));
    }
}
