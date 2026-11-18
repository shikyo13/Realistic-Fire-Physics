package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.VertexSorting;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class FlameQuadOrderTest {
    @Test
    void reproducesVanillaDistanceOrderIncludingTies() {
        var random = new java.util.Random(91);
        for (int trial = 0; trial < 60; trial++) {
            int n = trial == 0 ? 0 : trial == 1 ? 1 : random.nextInt(6000);
            var centers = new Vector3f[n];
            for (int i = 0; i < n; i++) {
                // Coarse coordinates force many exactly equal distances, including mirrored quads.
                if (random.nextInt(3) == 0 && i > 0) centers[i] = new Vector3f(centers[random.nextInt(i)]).mul(-1, 1, 1);
                else
                    centers[i] =
                            new Vector3f(
                                    (random.nextInt(64) - 32) * .25f,
                                    (random.nextInt(32) - 8) * .125f,
                                    random.nextFloat() * 90 - 45);
            }
            assertArrayEquals(
                    VertexSorting.DISTANCE_TO_ORIGIN.sort(centers),
                    FlameQuadOrder.INSTANCE.sort(centers),
                    "trial " + trial);
        }
    }

    @Test
    void leavesOtherSortingsUnchanged() {
        assertSame(FlameQuadOrder.INSTANCE, FlameQuadOrder.replacing(VertexSorting.DISTANCE_TO_ORIGIN));
        assertSame(VertexSorting.ORTHOGRAPHIC_Z, FlameQuadOrder.replacing(VertexSorting.ORTHOGRAPHIC_Z));
    }
}
