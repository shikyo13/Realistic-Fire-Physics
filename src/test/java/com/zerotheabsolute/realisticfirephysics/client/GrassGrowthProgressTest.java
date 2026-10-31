package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.realisticfirephysics.scorch.ChunkScars;

import org.junit.jupiter.api.Test;

class GrassGrowthProgressTest {
    private static ChunkScars.Ground ground(float progress) {
        return new ChunkScars.Ground(-1L, 0, 0, 0, 0, 1, progress);
    }

    @Test
    void startingANeighborDoesNotResetAnAlreadyMovingCell() {
        var p = new GrassGrowthProgress(ground(.2f), 0);
        p.receive(ground(.3f), 10);
        float before = p.values(15)[0];
        p.receive(new ChunkScars.Ground(-1L, 0, 0, 0, 0, 3, .4f), 15);
        assertEquals(before, p.values(15)[0], 2e-5);
        assertEquals(0, p.values(15)[1], 2e-5);
        assertTrue(p.values(16)[1] > 0);
    }

    @Test
    void samplesMoveContinuouslyThenStopWithoutExtrapolating() {
        var p = new GrassGrowthProgress(ground(.2f), 0);
        p.receive(ground(.3f), 10);
        assertEquals(.2f, p.values(10)[0], 2e-5);
        assertEquals(.25f, p.values(15)[0], 2e-5);
        assertEquals(.3f, p.values(20)[0], 2e-5);
        assertEquals(.3f, p.values(500)[0], 2e-5);
    }

    @Test
    void newSampleDoesNotJumpEvenWhenPreviousInterpolationIsIncomplete() {
        var p = new GrassGrowthProgress(ground(.2f), 0);
        p.receive(ground(.3f), 10);
        float before = p.values(15.5)[0];
        p.receive(ground(.35f), 15.5);
        assertEquals(before, p.values(15.5)[0], 2e-5);
        assertTrue(p.values(15.6)[0] > before);
    }

    @Test
    void reburningClearsGrowthAndRejoiningKeepsTheSavedProgress() {
        var p = new GrassGrowthProgress(ground(.7f), 200);
        assertEquals(.7f, p.values(201)[0], 2e-5);
        p.receive(ground(0), 205);
        assertEquals(0, p.values(205)[0]);
        assertEquals(0, p.values(500)[0]);
    }
}
