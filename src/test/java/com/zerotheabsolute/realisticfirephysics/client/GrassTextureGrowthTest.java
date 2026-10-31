package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class GrassTextureGrowthTest {
    private static float[] variation() {
        var values = new float[256];
        for (int i = 0; i < values.length; i++) values[i] = ((i * 73 + 19) & 255) / 256f;
        return values;
    }

    @Test
    void fullTextureGrowsFromTwoRootsWithConnectedSuccessors() {
        byte[] directions = new byte[256];
        int[] ranks = GrassTextureGrowth.order(null, variation(), -1L, directions);
        boolean[] seen = new boolean[256];
        int roots = 0;
        for (int i = 0; i < ranks.length; i++) {
            assertTrue(ranks[i] >= 0 && ranks[i] < 256);
            assertFalse(seen[ranks[i]]);
            seen[ranks[i]] = true;
            int parent =
                    switch (directions[i]) {
                        case 0 -> i % 16 > 0 ? i - 1 : -1;
                        case 1 -> i % 16 < 15 ? i + 1 : -1;
                        case 2 -> i >= 16 ? i - 16 : -1;
                        default -> i < 240 ? i + 16 : -1;
                    };
            if (parent < 0 || ranks[parent] >= ranks[i]) roots++;
        }
        assertTrue(roots <= 2, "Every other pixel must extend an existing grass patch");
        assertArrayEquals(ranks, GrassTextureGrowth.order(null, variation(), -1L, new byte[256]));
    }

    @Test
    void textureDetailsChangePathsWithoutLosingDisconnectedBurnCoverage() {
        float[] vertical = new float[256], horizontal = new float[256];
        for (int i = 0; i < 256; i++) {
            vertical[i] = i % 4 == 0 ? 0 : 1;
            horizontal[i] = i / 16 % 4 == 0 ? 0 : 1;
        }
        var a = GrassTextureGrowth.order(vertical, variation(), -1L, new byte[256]);
        var b = GrassTextureGrowth.order(horizontal, variation(), -1L, new byte[256]);
        int differences = 0;
        for (int i = 0; i < 256; i++) if (a[i] != b[i]) differences++;
        assertTrue(differences > 128, "Texture direction must materially change the growth paths");
        long footprint = 1L | 1L << 27 | Long.MIN_VALUE;
        int[] sparse = GrassTextureGrowth.order(vertical, variation(), footprint, new byte[256]);
        assertEquals(12, Arrays.stream(sparse).filter(rank -> rank >= 0).count());
        for (int i = 0; i < 256; i++)
            assertEquals((footprint & (1L << ((i / 32) * 8 + i % 16 / 2))) != 0, sparse[i] >= 0);
    }

    @Test
    void connectedAppearanceChangesCellSilhouettesAndKeepsTheBurnFootprint() {
        var front = new GrassGrowthFront(3000L << 38);
        float[] progress = new float[64];
        Arrays.fill(progress, 0, 32, 1);
        front.update(progress);
        var scattered = coverage(front);
        front.configure(GrassGrowthStyle.TEXTURE, variation());
        front.update(progress);
        var connected = coverage(front);
        int changedPixels = 0;
        double area = 0;
        for (int i = 0; i < 256; i++) {
            if (Math.abs(scattered[i] - connected[i]) > .1) changedPixels++;
            area += connected[i];
        }
        assertTrue(
                changedPixels > 40,
                "A half-grown block must not retain the cell-shaped silhouette");
        assertEquals(128, area, 1.1);
        assertFalse(front.update(progress));
        assertFalse(front.movingChanged());
        front.configure(GrassGrowthStyle.TEXTURE, variation(), 1L << 27);
        Arrays.fill(progress, 1);
        front.update(progress);
        assertEquals(4, Arrays.stream(coverage(front)).sum(), 1e-5);
        Arrays.fill(progress, 0);
        front.update(progress);
        assertEquals(0, Arrays.stream(coverage(front)).sum(), 1e-5);
    }

    private static double[] coverage(GrassGrowthFront front) {
        double[] pixels = new double[256];
        GrassGrowthFront.Surface collect =
                (ax, az, bx, bz, cx, cz, dx, dz) -> {
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            double width =
                                    Math.max(0, Math.min(cx * 16, x + 1) - Math.max(ax * 16, x));
                            double depth =
                                    Math.max(0, Math.min(cz * 16, z + 1) - Math.max(az * 16, z));
                            pixels[z * 16 + x] += width * depth;
                        }
                };
        front.drawFixed(collect);
        front.drawMoving(collect);
        return pixels;
    }
}
