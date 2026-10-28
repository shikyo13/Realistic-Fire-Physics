package com.zerotheabsolute.surfacefire.lighting;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class LightOcclusionTest {
    @Test
    void overlappingRaysReuseVoxelsWithinOneLightingUpdate() {
        var reads = new AtomicInteger();
        var visibility =
                new LightOcclusion(
                        (x, y, z) -> {
                            reads.incrementAndGet();
                            return false;
                        });
        assertTrue(visibility.between(.5, .5, .5, 4, 0, 0));
        assertEquals(5, reads.get());
        assertTrue(visibility.between(1.5, .5, .5, 4, 0, 0));
        assertEquals(5, reads.get());
    }

    @Test
    void nextUpdateObservesRemovedOrAddedObstacles() {
        var obstruction = new AtomicBoolean(true);
        var visibility = new LightOcclusion((x, y, z) -> x == 2 && obstruction.get());
        assertFalse(visibility.between(.5, .5, .5, 4, 0, 0));
        obstruction.set(false);
        visibility.reset();
        assertTrue(visibility.between(.5, .5, .5, 4, 0, 0));
        obstruction.set(true);
        visibility.reset();
        assertFalse(visibility.between(.5, .5, .5, 4, 0, 0));
    }

    @Test
    void diagonalAndNegativeCoordinateRaysRemainBlocked() {
        var visibility = new LightOcclusion((x, y, z) -> x == -2 && y == -2 && z == -2);
        assertFalse(visibility.between(-.5, -.5, -.5, -4, -4, -4));
        assertTrue(visibility.between(-.5, -.5, -.5, 4, 4, 4));
    }
}
