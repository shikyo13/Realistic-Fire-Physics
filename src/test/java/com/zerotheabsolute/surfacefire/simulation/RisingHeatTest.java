package com.zerotheabsolute.surfacefire.simulation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class RisingHeatTest {
    private record Cell(int x, int y, int z) {}

    private static final class World implements RisingHeat.Access {
        final Map<Cell, Integer> terrain = new HashMap<>();
        final Set<Cell> ignited = new HashSet<>();
        int probes;

        public int inspect(int x, int y, int z) {
            probes++;
            return terrain.getOrDefault(new Cell(x, y, z), RisingHeat.OPEN);
        }

        public void ignite(int x, int y, int z, double fx, double fy, double fz) {
            ignited.add(new Cell(x, y, z));
        }
    }

    @Test
    void RisingFlamesReachOverheadFuelAcrossAnAirGap() {
        var w = new World();
        var crown = new Cell(0, 4, 0);
        w.terrain.put(crown, RisingHeat.FUEL);
        RisingHeat.trace(.5, 1.05, .5, 0, 0, 5, w);
        assertTrue(w.ignited.contains(crown));
    }

    @Test
    void SolidRoofOrWaterBlocksTheColumn() {
        var w = new World();
        w.terrain.put(new Cell(0, 2, 0), RisingHeat.BLOCKED);
        w.terrain.put(new Cell(0, 4, 0), RisingHeat.FUEL);
        RisingHeat.trace(.5, 1.05, .5, 0, 0, 5, w);
        assertTrue(w.ignited.isEmpty());
    }

    @Test
    void FlameBesideATrunkHeatsItsHigherExposedFaces() {
        var w = new World();
        for (int y = 1; y <= 6; y++) w.terrain.put(new Cell(0, y, 0), RisingHeat.FUEL);
        RisingHeat.trace(1.08, 1.5, .5, 0, 0, 5, w);
        assertTrue(w.ignited.contains(new Cell(0, 5, 0)));
        assertFalse(w.ignited.contains(new Cell(0, 7, 0)));
    }

    @Test
    void GustDeflectsHeatTowardDownwindFuel() {
        var calm = new World();
        var gust = new World();
        var fuel = new Cell(2, 5, 0);
        calm.terrain.put(fuel, RisingHeat.FUEL);
        gust.terrain.put(fuel, RisingHeat.FUEL);
        RisingHeat.trace(.5, 1.05, .5, 0, 0, 5, calm);
        RisingHeat.trace(.5, 1.05, .5, 3, 0, 5, gust);
        assertFalse(calm.ignited.contains(fuel));
        assertTrue(gust.ignited.contains(fuel));
    }

    @Test
    void FirstFuelInterceptsColumnAndProbeCountIsBounded() {
        var w = new World();
        w.terrain.put(new Cell(0, 2, 0), RisingHeat.FUEL);
        w.terrain.put(new Cell(0, 3, 0), RisingHeat.FUEL);
        RisingHeat.trace(.5, 1.05, .5, 0, 0, 100, w);
        assertEquals(Set.of(new Cell(0, 2, 0)), w.ignited);
        var empty = new World();
        int work = RisingHeat.trace(-3.6, -12.7, 4.2, 50, -50, 1000, empty);
        assertEquals(work, empty.probes);
        assertTrue(work <= RisingHeat.MAX_PROBES);
    }
}
