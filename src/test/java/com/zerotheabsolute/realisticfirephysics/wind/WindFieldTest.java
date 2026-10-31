package com.zerotheabsolute.realisticfirephysics.wind;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WindFieldTest {
    @Test
    void analyticalCurlConservesVolumeAwayFromBoundaries() {
        var field = new WindField();
        var a = new double[3];
        var b = new double[3];
        double epsilon = 1e-4;
        for (int i = 0; i < 80; i++) {
            double x = i * .317 - 14, y = i * .103 + .7, z = -i * .517;
            double divergence = 0;
            field.sample(17.3, x + epsilon, y, z, a);
            field.sample(17.3, x - epsilon, y, z, b);
            divergence += (a[0] - b[0]) / (2 * epsilon);
            field.sample(17.3, x, y + epsilon, z, a);
            field.sample(17.3, x, y - epsilon, z, b);
            divergence += (a[1] - b[1]) / (2 * epsilon);
            field.sample(17.3, x, y, z + epsilon, a);
            field.sample(17.3, x, y, z - epsilon, b);
            divergence += (a[2] - b[2]) / (2 * epsilon);
            assertEquals(0, divergence, 1e-5);
        }
    }

    @Test
    void gustsVaryLocallyAndDecayWithoutTemporalJumps() {
        var field = new WindField();
        var a = new double[3];
        var b = new double[3];
        double min = 100, max = 0, spatialDifference = 0;
        for (int i = 0; i < 6000; i++) {
            double t = i / 60.0;
            field.sample(t, 2.1, 1.2, 3.7, a);
            field.sample(t + 1.0 / 60, 2.1, 1.2, 3.7, b);
            for (int axis = 0; axis < 3; axis++) assertTrue(Math.abs(a[axis] - b[axis]) < .15);
            double speed = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            min = Math.min(min, speed);
            max = Math.max(max, speed);
            field.sample(t, 12.1, 1.2, 13.7, b);
            spatialDifference += Math.abs(a[0] - b[0]) + Math.abs(a[2] - b[2]);
        }
        assertTrue(max > min * 3);
        assertTrue(max < 9);
        assertTrue(spatialDifference / 6000 > .1);
    }

    @Test
    void cacheIsContinuousAcrossCellAndTimeBoundariesAndStableAfterEviction() {
        var cache = new WindCache();
        var a = new double[3];
        var b = new double[3];
        cache.sample(13.399999, 1.999999, 3.999999, -2.000001, a);
        cache.sample(13.400001, 2.000001, 4.000001, -1.999999, b);
        assertArrayEquals(a, b, 1e-4);
        cache.sample(19.2, -29_999_999, 62, 29_999_999, a);
        for (int i = 0; i < WindCache.CAPACITY * 2; i++) cache.sample(i, i * 3, i % 50, -i, b);
        cache.sample(19.2, -29_999_999, 62, 29_999_999, b);
        assertArrayEquals(a, b, 1e-12);
        cache.clear();
        cache.sample(19.2, -29_999_999, 62, 29_999_999, b);
        assertArrayEquals(a, b, 1e-12);
    }

    @Test
    void invalidCoordinatesCannotContaminateFollowingSamples() {
        var cache = new WindCache();
        var out = new double[3];
        cache.sample(Double.NaN, 0, 0, 0, out);
        assertArrayEquals(new double[3], out);
        cache.sample(100, 123, 45, 678, out);
        for (double value : out) assertTrue(Double.isFinite(value));
    }
}
