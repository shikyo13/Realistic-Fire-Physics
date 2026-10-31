package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HeatHazeSourcesTest {
    @Test
    void DenseFlamesMergeWithoutMultiplyingDistortion() {
        var sources = new HeatHazeSources();
        for (int i = 0; i < 256; i++) sources.offer(.2 + i % 8 / 8.0, 1, .2, .8, 2.5, 3, 0);
        assertEquals(1, sources.size());
        assertEquals(.8, sources.values().iterator().next().strength(0), 1e-8);
        assertEquals(1, sources.values().iterator().next().y, 0);
    }

    @Test
    void ExpiredHeatFadesAndReleasesItsSlot() {
        var sources = new HeatHazeSources();
        sources.offer(0, 1, 0, 1, 2.5, 3, 10);
        var source = sources.values().iterator().next();
        assertEquals(1, source.strength(14), 0);
        assertEquals(.5, source.strength(16), 0);
        sources.expire(18);
        assertEquals(0, sources.size());
    }

    @Test
    void WicksRemainAtTheirActualPositionAndColdSourcesDoNotOccupyCapacity() {
        var sources = new HeatHazeSources();
        sources.offer(1.1, 1.4, 2.2, .3, .35, .7, 10);
        var wick = sources.values().iterator().next();
        assertEquals(1.1, wick.x, 0);
        assertEquals(2.2, wick.z, 0);
        for (int i = 0; i < 1000; i++) sources.offer(i * 3, 1, 0, 0, 2.5, 3, 10);
        assertEquals(1, sources.size());
        for (int i = 0; i < 1000; i++) sources.offer(i * 3, 1, 0, 1, 2.5, 3, 10);
        assertEquals(HeatHazeSources.CAPACITY, sources.size());
    }
}
