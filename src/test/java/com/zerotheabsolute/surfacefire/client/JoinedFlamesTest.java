package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class JoinedFlamesTest {
    private void add(JoinedFlames fire, double x, double y, double z) {
        fire.add(x, y, z, .5, 1, new Vec3(0, 1, 0), .4f, 1);
    }

    @Test
    void joinsAcrossBlockAndBucketBoundaries() {
        var fire = new JoinedFlames();
        add(fire, .7, 1, .2);
        add(fire, 1.2, 1, .2);
        add(fire, 1.7, 1, .2);
        fire.link();
        assertTrue(fire.connected(1, 0));
        assertTrue(fire.connected(1, 2));
    }

    @Test
    void wrapsAnOutsideCorner() {
        var fire = new JoinedFlames();
        add(fire, .5, 1, 1.06);
        add(fire, 1.06, 1, .8);
        add(fire, 1.06, 1, .3);
        fire.link();
        assertTrue(fire.connected(1, 0));
        assertTrue(fire.connected(1, 2));
    }

    @Test
    void doesNotBridgeSeparateFiresOrFloors() {
        var fire = new JoinedFlames();
        add(fire, 0, 1, 0);
        add(fire, 2, 1, 0);
        add(fire, .2, 3, 0);
        fire.link();
        assertFalse(fire.connected(0, 1));
        assertFalse(fire.connected(0, 2));
    }

    @Test
    void reusedFrameDoesNotKeepExtinguishedNeighbors() {
        var fire = new JoinedFlames();
        add(fire, 0, 1, 0);
        add(fire, .5, 1, 0);
        fire.link();
        assertTrue(fire.connected(0, 1));
        fire.clear();
        add(fire, 0, 1, 0);
        add(fire, 5, 1, 0);
        fire.link();
        assertFalse(fire.connected(0, 1));
    }

    @Test
    void tightTriangleCannotBecomeAClosedRibbon() {
        var fire = new JoinedFlames();
        add(fire, 0, 1, 0);
        add(fire, .5, 1, 0);
        add(fire, .25, 1, .4);
        fire.link();
        for (int i = 0; i < 3; i++) {
            assertFalse(fire.connected(i, (i + 1) % 3) && fire.connected(i, (i + 2) % 3));
        }
    }

    @Test
    void dyingFlamesDoNotStretchIntoGlowingOutlines() {
        var fire = new JoinedFlames();
        add(fire, 0, 1, 0);
        fire.add(.5, 1, 0, .4, .12, new Vec3(0, 1, 0), .4f, .6f);
        fire.link();
        assertFalse(fire.connected(0, 1));
        assertFalse(fire.connected(1, 0));
    }
}
