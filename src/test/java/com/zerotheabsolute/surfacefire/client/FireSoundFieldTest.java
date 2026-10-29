package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FireSoundFieldTest {
    @Test
    void firesOnOppositeSidesDoNotBecomeASoundInsideTheListener() {
        var field = new FireSoundField();
        field.begin(4, 1, 4);
        field.add(2, 1, 4, 10, 1);
        field.add(6, 1, 4, 10, 1);
        field.finish();
        assertEquals(10, field.emitters[0].energy);
        assertEquals(10, field.emitters[1].energy);
        assertEquals(2, field.emitters[0].x);
        assertEquals(6, field.emitters[1].x);
    }

    @Test
    void sourceStaysOnAnActualFlameInsteadOfTheEmptySpaceBetweenFires() {
        var field = new FireSoundField();
        field.begin(0, 1, 0);
        field.add(8, 1, 2, 3, 1);
        field.add(16, 1, 2, 2, 0);
        field.finish();
        assertEquals(8, field.emitters[0].x);
        assertEquals(5, field.emitters[0].energy);
        assertEquals(.6f, field.emitters[0].wood, .0001);
    }

    @Test
    void changingLoudnessDoesNotSwapSpatialVoices() {
        var field = new FireSoundField();
        field.begin(0, 0, 0);
        field.add(-20, 0, 0, 10, 0);
        field.add(20, 0, 0, 5, 0);
        field.finish();
        field.begin(0, 0, 0);
        field.add(-20, 0, 0, 3, 0);
        field.add(20, 0, 0, 20, 0);
        field.finish();
        assertEquals(-20, field.emitters[0].x);
        assertEquals(3, field.emitters[0].energy);
        assertEquals(20, field.emitters[1].x);
        assertEquals(20, field.emitters[1].energy);
    }

    @Test
    void outOfRangeFireCannotInflateAnAudibleGroupsVolume() {
        var field = new FireSoundField();
        field.begin(0, 0, 0);
        field.add(63, 0, 0, 1, 0);
        field.add(65, 0, 0, 100, 0);
        field.add(Double.NaN, 0, 0, 100, 0);
        field.finish();
        assertEquals(1, field.emitters[0].energy);
    }

    @Test
    void worldChangeAndCoolingRetireEveryVoice() {
        var field = new FireSoundField();
        field.begin(0, 0, 0);
        field.add(0, 0, 0, 10, 1);
        field.finish();
        field.begin(0, 0, 0);
        field.finish();
        for (var emitter : field.emitters) assertEquals(0, emitter.energy);
        field.add(0, 0, 0, 10, 1);
        field.finish();
        field.clear();
        for (var emitter : field.emitters) assertEquals(0, emitter.energy);
    }

    @Test
    void verticalSeparationAndRangeBoundariesRemainSpatial() {
        var field = new FireSoundField();
        field.begin(0, 0, 0);
        field.add(0, 64, 0, 10, 1);
        field.add(0, -64, 0, 10, 1);
        field.add(0, 0, 64, 10, 1);
        field.finish();
        for (var emitter : field.emitters) {
            assertEquals(10, emitter.energy);
            assertEquals(64 * 64, emitter.x * emitter.x + emitter.y * emitter.y + emitter.z * emitter.z);
        }
    }
}
