package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TorchAirflowTest {
    @Test
    void jumpingAndFallingBendTheFlameOppositeVerticalMotion() {
        var response = new TorchAirflow.Response();
        for (int i = 0; i < 20; i++) response.tick(0, .28f, 0);
        assertTrue(response.sample(1).y() < -.99f);
        for (int i = 0; i < 20; i++) response.tick(0, -.28f, 0);
        assertTrue(response.sample(1).y() > .99f);
    }

    @Test
    void flameTrailsMovementAndReversesWhenWalkingBackwards() {
        var r = new TorchAirflow.Response();
        for (int i = 0; i < 20; i++) r.tick(0, -.28f);
        assertTrue(r.sample(1).z() > .99f);
        for (int i = 0; i < 20; i++) r.tick(0, .28f);
        assertTrue(r.sample(1).z() < -.99f);
    }

    @Test
    void stoppingReturnsToStillFlameWithoutSnapping() {
        var r = new TorchAirflow.Response();
        r.tick(.28f, 0);
        float moving = r.sample(1).strength();
        r.tick(0, 0);
        assertTrue(r.sample(1).strength() < moving);
        assertTrue(r.sample(1).strength() > 0);
        for (int i = 0; i < 30; i++) r.tick(0, 0);
        assertTrue(r.sample(1).strength() < .001f);
    }

    @Test
    void interpolationAndDiagonalSpeedStayBoundedAndRootStaysAttached() {
        var r = new TorchAirflow.Response();
        for (int i = 0; i < 20; i++) r.tick(.8f, .8f);
        var bend = r.sample(1);
        assertTrue(bend.strength() <= 1);
        assertEquals(0, bend.curvature(0));
        assertTrue(bend.curvature(.5f) < bend.curvature(1));
        r.tick(0, 0);
        assertEquals(bend.x(), r.sample(0).x(), .00001f);
    }

    @Test
    void TeleportsAndInvalidPositionsResetTheResponse() {
        var r = new TorchAirflow.Response();
        r.tick(.28f, 0);
        r.tick(30, 0);
        assertEquals(0, r.sample(1).strength());
        r.tick(Float.NaN, 0);
        assertEquals(0, r.sample(1).strength());
    }
}
