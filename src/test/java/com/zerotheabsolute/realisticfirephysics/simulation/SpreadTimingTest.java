package com.zerotheabsolute.realisticfirephysics.simulation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpreadTimingTest {
    @Test
    void gustAcceleratesDownwindAndSlowsUpwindWithoutStoppingTheFront() {
        int calm = SpreadTiming.delay(12, 1, 0, 0, 1, 0, 0);
        assertTrue(SpreadTiming.delay(12, 1, 3, 0, 1, 0, 0) < calm);
        assertTrue(SpreadTiming.delay(12, 1, 3, 0, -1, 0, 0) > calm);
        assertEquals(calm, SpreadTiming.delay(12, 1, 3, 0, 0, 0, 1));
        assertTrue(SpreadTiming.delay(12, 1, 100, 0, -1, 0, 0) <= 22);
    }

    @Test
    void RotationAndWindReversalPreserveRelativePropagation() {
        assertEquals(
                SpreadTiming.delay(11, 1.3, 2, 1, 1, 0, 1),
                SpreadTiming.delay(11, 1.3, -1, 2, -1, 0, 1));
        assertEquals(
                SpreadTiming.delay(11, 1.3, 2, 1, 1, 0, 0),
                SpreadTiming.delay(11, 1.3, -2, -1, -1, 0, 0));
    }

    @Test
    void BuoyancyAcceleratesClimbingButAllowsDownwardSpread() {
        assertTrue(SpreadTiming.delay(12, 1, 3, 0, 0, 1, 0) < 12);
        assertEquals(15, SpreadTiming.delay(12, 1, 3, 0, 0, -1, 0));
    }

    @Test
    void ScheduledDirectionsSurviveLateTicksWithoutDuplication() {
        int packed = 0;
        int[] delays = {2, 9, 2, 6};
        for (int i = 0; i < 4; i++) packed = SpreadTiming.put(packed, i, delays[i]);
        int remaining = SpreadTiming.ALL_STEPS;
        assertEquals(2, SpreadTiming.next(packed, remaining));
        int ready = SpreadTiming.ready(packed, remaining, 5);
        assertEquals(5, ready);
        remaining &= ~ready;
        assertEquals(6, SpreadTiming.next(packed, remaining));
        assertEquals(remaining, SpreadTiming.ready(packed, remaining, 100));
        assertEquals(0, SpreadTiming.ready(packed, 0, 100));
    }

    @Test
    void MinimumAndMaximumDelaysRemainRepresentable() {
        assertEquals(1, SpreadTiming.delay(2, 4, 1000, 1000, 1, 0, 1));
        assertEquals(255, SpreadTiming.delay(1000, .1, -1000, 0, 1, 0, 0));
        int packed = SpreadTiming.put(0, 3, 255);
        assertEquals(255, SpreadTiming.next(packed, 8));
        assertEquals(0, SpreadTiming.ready(packed, 8, 254));
        assertEquals(8, SpreadTiming.ready(packed, 8, 255));
    }
}
