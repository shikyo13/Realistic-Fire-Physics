package com.zerotheabsolute.realisticfirephysics.scorch;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

class GrassRegrowthTest {
    @Test
    void partialGrowthRetainsTheUnderlyingBurnMask() {
        var start = new ChunkScars.Ground(-1L, 64, 0, 0);
        var young = GrassRegrowth.advance(start, 1L, 20, 100, 0);
        assertEquals(-1L, young.cells());
        assertTrue(young.grass(0) > 0 && young.grass(0) < 1);
        assertEquals(0, young.grass(1));
        var mature = GrassRegrowth.advance(young, 1L, 1000, 100, 0);
        assertEquals(-2L, mature.cells());
        assertEquals(1L, mature.grown());
        assertEquals(1, mature.grass(0));
        assertSame(GrassGrowth.EMPTY, mature.growth());
    }

    @Test
    void ineligibleGroundDoesNotAccumulateRecovery() {
        var start = new ChunkScars.Ground(-1L, 64, 0, 0);
        var blocked = GrassRegrowth.advance(start, 0, 10000, 100, 0);
        assertEquals(-1L, blocked.cells());
        assertEquals(0, blocked.grown());
        assertEquals(0, blocked.growing());
        var resumed = GrassRegrowth.advance(blocked, 1L, 10020, 100, 0);
        var uninterrupted = GrassRegrowth.advance(start, 1L, 20, 100, 0);
        assertEquals(uninterrupted.growth(), resumed.growth());
    }

    @Test
    void restartingFireReplacesRecoveryOnlyWhereItBurns() {
        var recovered = new ChunkScars.Ground(12, 2, 0, 100, 3, 4, .4f);
        var reburned = recovered.merge(new ChunkScars.Ground(1, 1, 200, 100));
        assertEquals(13, reburned.cells());
        assertEquals(2, reburned.grown());
        assertEquals(.4f, reburned.grass(2), .001f);
        assertEquals(0, reburned.grass(0));
    }

    @Test
    void aConnectedFrontOverlapsAndVariesWithoutSeedingInteriorBlockSeams() {
        var patch = new ChunkScars.Ground[4][4];
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) patch[x][z] = new ChunkScars.Ground(-1L, 0, 0, 0);
        boolean overlapping = false, varied = false;
        int remaining = 1024;
        for (int step = 1; step <= 600 && remaining > 0; step++) {
            var next = new ChunkScars.Ground[4][4];
            remaining = 0;
            for (int x = 0; x < 4; x++)
                for (int z = 0; z < 4; z++) {
                    var g = patch[x][z];
                    long edge =
                            ScorchBits.recoveryEdge(
                                    blocked(g),
                                    x == 0 ? 0 : blocked(patch[x - 1][z]),
                                    x == 3 ? 0 : blocked(patch[x + 1][z]),
                                    z == 0 ? 0 : blocked(patch[x][z - 1]),
                                    z == 3 ? 0 : blocked(patch[x][z + 1]));
                    next[x][z] =
                            GrassRegrowth.advance(
                                    g, edge, step * 5L, 100, BlockPos.asLong(x, 0, z));
                    remaining += Long.bitCount(next[x][z].cells());
                    assertEquals(-1L, next[x][z].cells() | next[x][z].grown());
                    for (int cell = 0; cell < 64; cell++)
                        assertTrue(next[x][z].grass(cell) >= g.grass(cell));
                }
            patch = next;
            var corner = patch[0][0];
            overlapping |= corner.grass(9) > 0 && corner.grass(1) > 0 && corner.grass(1) < 1;
            varied |= Math.abs(corner.grass(0) - corner.grass(7)) > .1f;
            assertConnected(patch);
        }
        assertEquals(0, remaining, "Recovery must eventually reach the entire interior");
        assertTrue(overlapping, "Neighbors must start before an entire band finishes");
        assertTrue(varied, "The perimeter must not advance at one synchronized rate");
    }

    private static long blocked(ChunkScars.Ground g) {
        return g.cells() & ~g.growth().spreading();
    }

    private static void assertConnected(ChunkScars.Ground[][] patch) {
        boolean[] green = new boolean[1024], seen = new boolean[1024];
        var queue = new ArrayDeque<Integer>();
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++) {
                int i = z * 32 + x;
                green[i] = patch[x / 8][z / 8].grass((z & 7) * 8 + (x & 7)) > 0;
                if (green[i] && (x == 0 || x == 31 || z == 0 || z == 31)) {
                    seen[i] = true;
                    queue.add(i);
                }
            }
        while (!queue.isEmpty()) {
            int i = queue.removeFirst(), x = i % 32, z = i / 32;
            int[] neighbors = {
                x > 0 ? i - 1 : -1, x < 31 ? i + 1 : -1, z > 0 ? i - 32 : -1, z < 31 ? i + 32 : -1
            };
            for (int n : neighbors)
                if (n >= 0 && green[n] && !seen[n]) {
                    seen[n] = true;
                    queue.add(n);
                }
        }
        assertArrayEquals(green, seen, "Every growing cell must connect back to a patch edge");
    }
}
