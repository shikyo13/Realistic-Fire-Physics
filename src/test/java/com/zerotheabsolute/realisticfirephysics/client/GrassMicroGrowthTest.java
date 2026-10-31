package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class GrassMicroGrowthTest {
    private static float[] variation() {
        var values = new float[256];
        for (int i = 0; i < values.length; i++) values[i] = ((i * 73 + 19) & 255) / 256f;
        return values;
    }

    @Test
    void bothColoniesCoverOnlyTheOriginalBurnAndRemainDeterministic() {
        long[] footprints = {0, -1L, 1L, Long.MIN_VALUE, 1L | 1L << 27 | Long.MIN_VALUE};
        for (var style :
                new GrassGrowthStyle[] {GrassGrowthStyle.TUFTS, GrassGrowthStyle.RUNNERS}) {
            for (long footprint : footprints) {
                var ranks =
                        GrassMicroGrowth.order(style, null, variation(), footprint, new byte[256]);
                int total = Long.bitCount(footprint) * 4;
                boolean[] seen = new boolean[total];
                for (int i = 0; i < ranks.length; i++) {
                    boolean covered = (footprint & 1L << ((i / 32) * 8 + i % 16 / 2)) != 0;
                    assertEquals(covered, ranks[i] >= 0);
                    if (!covered) continue;
                    assertTrue(ranks[i] < total);
                    assertFalse(seen[ranks[i]], "A texel must only be restored once");
                    seen[ranks[i]] = true;
                }
                assertArrayEquals(
                        ranks,
                        GrassMicroGrowth.order(style, null, variation(), footprint, new byte[256]));
            }
        }
    }

    @Test
    void nativeTextureAndColonyMechanismBothChangeIntermediateCoverage() {
        float[] horizontal = new float[256], vertical = new float[256];
        for (int i = 0; i < 256; i++) {
            horizontal[i] = i / 16 % 4 == 0 ? 0 : 1;
            vertical[i] = i % 4 == 0 ? 0 : 1;
        }
        var tufts =
                GrassMicroGrowth.order(
                        GrassGrowthStyle.TUFTS, horizontal, variation(), -1L, new byte[256]);
        var runners =
                GrassMicroGrowth.order(
                        GrassGrowthStyle.RUNNERS, horizontal, variation(), -1L, new byte[256]);
        var rotated =
                GrassMicroGrowth.order(
                        GrassGrowthStyle.TUFTS, vertical, variation(), -1L, new byte[256]);
        assertTrue(maskDifference(tufts, runners) > 24);
        assertTrue(maskDifference(tufts, rotated) > 24);
    }

    @Test
    void partialFootprintsCanFinishAndReburnWithoutResidualGeometry() {
        for (var style :
                new GrassGrowthStyle[] {GrassGrowthStyle.TUFTS, GrassGrowthStyle.RUNNERS}) {
            var front = new GrassGrowthFront(3000L << 38);
            float[] progress = new float[64];
            front.configure(style, variation(), 1L | Long.MIN_VALUE);
            Arrays.fill(progress, 1);
            front.update(progress);
            double[] area = {0};
            front.drawFixed((ax, az, bx, bz, cx, cz, dx, dz) -> area[0] += (cx - ax) * (cz - az));
            assertEquals(8.0 / 256, area[0], 1e-6);
            Arrays.fill(progress, 0);
            front.update(progress);
            assertFalse(front.hasMovingPixels());
            front.drawFixed((ax, az, bx, bz, cx, cz, dx, dz) -> fail("Reburned grass remains"));
        }
    }

    private static int maskDifference(int[] a, int[] b) {
        int difference = 0;
        for (int i = 0; i < a.length; i++) if ((a[i] < 96) != (b[i] < 96)) difference++;
        return difference;
    }
}
