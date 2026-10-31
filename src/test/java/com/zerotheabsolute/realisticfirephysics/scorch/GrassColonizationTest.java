package com.zerotheabsolute.realisticfirephysics.scorch;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

class GrassColonizationTest {
    @Test
    void scatteredSourcesAreSparseStableAndDoNotRepeatAtEachBlock() {
        var masks = new HashSet<Long>();
        for (int x = -8; x < 8; x++)
            for (int z = -8; z < 8; z++) {
                long pos = BlockPos.asLong(x, 64, z);
                long sites = GrassColonization.survivors(pos);
                assertTrue(Long.bitCount(sites) >= 1 && Long.bitCount(sites) <= 2);
                assertEquals(sites, GrassColonization.survivors(BlockPos.of(pos).asLong()));
                masks.add(sites);
            }
        assertTrue(masks.size() > 64, "Sources must vary across positive and negative coordinates");
    }

    @Test
    void livingEdgesAndInteriorSurvivorsBothStartGrowing() {
        var ground = new ChunkScars.Ground(-1L, 0, 0, 0);
        int interiorStarts = 0;
        for (int x = 0; x < 16; x++) {
            long pos = BlockPos.asLong(x, 64, 0);
            long sources = GrassColonization.sources(ground, 0, -1L, -1L, -1L, pos);
            long west = 0x0101010101010101L;
            assertEquals(west, sources & west);
            interiorStarts += Long.bitCount(sources & 0x007e7e7e7e7e7e00L);
            var next = GrassRegrowth.advance(ground, sources, 20, 100, pos);
            assertEquals(-1L, next.cells() | next.grown());
            assertEquals(sources, next.growing());
        }
        assertTrue(interiorStarts > 0, "Interior growth must not wait for the outside front");
    }

    @Test
    void noSourceCanRestoreAnUnburnedOrAlreadyRecoveredCell() {
        for (int cell = 0; cell < 64; cell++) {
            long burn = 1L << cell;
            var ground = new ChunkScars.Ground(burn, 1, 0, 0);
            long sources = GrassColonization.sources(ground, 0, 0, 0, 0, 89275);
            assertEquals(0, sources & ~burn);
        }
        var restored = new ChunkScars.Ground(0, 0, 0, 100, -1L, GrassGrowth.EMPTY);
        assertEquals(0, GrassColonization.sources(restored, 0, 0, 0, 0, 89275));
    }

    @Test
    void isolatedBurnEventuallyFillsFromSurvivorsWithoutAnArtificialBorder() {
        long position = BlockPos.asLong(-17, 73, -16);
        var ground = new ChunkScars.Ground(-1L, 64, 0, 0);
        long first = GrassColonization.sources(ground, -1L, -1L, -1L, -1L, position);
        assertTrue(Long.bitCount(first) <= 2, "An inert surround must not create a green ring");
        for (int tick = 5; tick <= 3000 && ground.cells() != 0; tick += 5) {
            long sources = GrassColonization.sources(ground, -1L, -1L, -1L, -1L, position);
            var next = GrassRegrowth.advance(ground, sources, tick, 100, position);
            for (int cell = 0; cell < 64; cell++)
                assertTrue(next.grass(cell) >= ground.grass(cell));
            assertEquals(
                    -1L, next.cells() | next.grown(), "Scorch stays beneath all partial growth");
            ground = next;
        }
        assertEquals(0, ground.cells());
        assertEquals(-1L, ground.grown());
    }
}
