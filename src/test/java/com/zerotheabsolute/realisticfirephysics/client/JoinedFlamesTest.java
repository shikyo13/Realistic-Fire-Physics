package com.zerotheabsolute.realisticfirephysics.client;

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

    @Test
    void verticalPlumesCombineAcrossBlocksAndStayBounded() {
        var fire = new JoinedFlames();
        for (int i = 0; i < 5; i++)
            fire.add(0, .75 + i * .5, 0, .5, .8, new Vec3(0, 1, 0), .4f, 1, true);
        assertEquals(2, fire.mergeVertical());
        assertEquals(2, fire.mergeVertical());
    }

    @Test
    void verticalJoinDoesNotCrossMissingFuel() {
        var fire = new JoinedFlames();
        fire.add(0, 1, 0, .5, .8, new Vec3(0, 1, 0), .4f, 1, true);
        fire.add(0, 2, 0, .5, .8, new Vec3(0, 1, 0), .4f, 1, true);
        assertEquals(2, fire.mergeVertical());
    }

    @Test
    void groundFlamesDoNotBecomeVerticalColumns() {
        var fire = new JoinedFlames();
        add(fire, 0, 1, 0);
        fire.add(0, 1.5, 0, .5, .8, new Vec3(0, 1, 0), .4f, 1, true);
        assertEquals(2, fire.mergeVertical());
    }

    @Test
    void denseBucketIndexLinksExactlyLikeTheMapLookup() {
        var random = new java.util.Random(20261117);
        for (int trial = 0; trial < 40; trial++) {
            var dense = new JoinedFlames();
            var map = new JoinedFlames();
            map.denseIndexEnabled = false;
            // Clustered fronts and columns around negative and positive bucket boundaries.
            double originX = random.nextInt(9) - 4, originZ = random.nextInt(9) - 4;
            int count = 50 + random.nextInt(400);
            for (int i = 0; i < count; i++) {
                double x = originX + random.nextDouble() * 6 - 3;
                double y = 1 + random.nextInt(4) * .5 + random.nextDouble() * .4;
                double z = originZ + random.nextDouble() * 6 - 3;
                double width = .2 + random.nextDouble() * .4;
                double height = .1 + random.nextDouble();
                var up = new Vec3(random.nextDouble() * .2 - .1, 1, random.nextDouble() * .2 - .1);
                float seed = random.nextFloat(), heat = .02f + random.nextFloat();
                boolean vertical = random.nextInt(4) == 0;
                dense.add(x, y, z, width, height, up, seed, heat, vertical);
                map.add(x, y, z, width, height, up, seed, heat, vertical);
            }
            dense.link();
            map.link();
            for (int i = 0; i < count; i++) {
                assertEquals(map.merged(i), dense.merged(i), "merged " + trial + "/" + i);
                assertEquals(map.firstLink(i), dense.firstLink(i), "first " + trial + "/" + i);
                assertEquals(map.secondLink(i), dense.secondLink(i), "second " + trial + "/" + i);
            }
        }
    }
}
