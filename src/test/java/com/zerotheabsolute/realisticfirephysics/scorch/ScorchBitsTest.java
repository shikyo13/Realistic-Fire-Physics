package com.zerotheabsolute.realisticfirephysics.scorch;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScorchBitsTest {
    @Test
    void fullyBurnedNeighborsDoNotSeedInteriorRecovery() {
        assertEquals(0, ScorchBits.recoveryEdge(-1L, -1L, -1L, -1L, -1L));
    }

    @Test
    void grassAcrossAChunkEdgeSeedsOnlyTheTouchingColumn() {
        assertEquals(0x0101010101010101L, ScorchBits.recoveryEdge(-1L, 0, -1L, -1L, -1L));
        assertEquals(0x8080808080808080L, ScorchBits.recoveryEdge(-1L, -1L, 0, -1L, -1L));
        assertEquals(255L, ScorchBits.recoveryEdge(-1L, -1L, -1L, 0, -1L));
        assertEquals(255L << 56, ScorchBits.recoveryEdge(-1L, -1L, -1L, -1L, 0));
    }

    @Test
    void recoveryAdvancesFromExistingGrassWithoutWrappingRows() {
        long green = 1L << 7;
        assertEquals((1L << 6) | (1L << 15), ScorchBits.recoveryEdge(~green, -1L, -1L, -1L, -1L));
    }

    @Test
    void negativeWorldCoordinatesKeepLocalCellIndex() {
        assertEquals(63, ScorchBits.index(-1, -1));
        assertEquals(0, ScorchBits.index(-8, -8));
    }

    @Test
    void repeatedMigrationDoesNotDoublePlantDamage() {
        var g = new ChunkScars.Ground(0xffff, 7.25f, 20, 100);
        assertEquals(7.25f, g.merge(g).plantExposure());
        assertEquals(8.25f, g.merge(new ChunkScars.Ground(1L << 20, 1, 30, 120)).plantExposure());
    }
}
