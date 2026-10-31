package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class GrassGrowthFrontTest {
    private static double area(GrassGrowthFront front, float progress, long grown, long growing) {
        double[] area = {0};
        front.draw(
                values(progress, grown, growing),
                (ax, az, bx, bz, cx, cz, dx, dz) -> {
                    area[0] +=
                            Math.abs(
                                            ax * bz - bx * az + bx * cz - cx * bz + cx * dz
                                                    - dx * cz + dx * az - ax * dz)
                                    * .5;
                });
        return area[0];
    }

    private static float[] values(float progress, long grown, long growing) {
        float[] values = new float[64];
        for (int i = 0; i < values.length; i++)
            values[i] = (grown & (1L << i)) != 0 ? 1 : (growing & (1L << i)) != 0 ? progress : 0;
        return values;
    }

    @Test
    void frontCoverageAdvancesMonotonicallyAtSubpixelIntervals() {
        var front = new GrassGrowthFront(3000L << 38);
        double previous = 0;
        int moving = 0;
        for (int i = 0; i <= 2000; i++) {
            double current = area(front, i / 2000f, 0, -1L);
            assertTrue(current + 1e-6 >= previous);
            assertTrue(current - previous < .01, "A front step must not pop whole texture groups");
            if (current - previous > 1e-7) moving++;
            previous = current;
        }
        assertTrue(moving > 300, "The front must move between native pixel thresholds");
        assertEquals(1, previous, 1e-6);
    }

    @Test
    void growingTexelsKeepRectangularSilhouettesAndStayOnTheirTextureGrid() {
        int[] partial = {0};
        new GrassGrowthFront(3000L << 38)
                .draw(
                        values(.47f, 0, -1L),
                        (ax, az, bx, bz, cx, cz, dx, dz) -> {
                            assertEquals(ax, bx);
                            assertEquals(cx, dx);
                            assertEquals(az, dz);
                            assertEquals(bz, cz);
                            float[] sides = {ax, az, cx, cz};
                            int unaligned = 0;
                            for (float side : sides)
                                if (Math.abs(side * 16 - Math.round(side * 16)) > 1e-5) unaligned++;
                            assertTrue(
                                    unaligned <= 1,
                                    "Only one edge of an individual texel may advance");
                            if (unaligned == 1) {
                                partial[0]++;
                                assertTrue(cx - ax <= 1f / 16 + 1e-6);
                                assertTrue(cz - az <= 1f / 16 + 1e-6);
                            }
                        });
        assertTrue(partial[0] > 0, "Growth must include moving subpixel edges");
    }

    @Test
    void regrowthNeverCoversCellsThatHaveNotBeenReleasedForGrowth() {
        var front = new GrassGrowthFront(3000L << 38);
        assertEquals(0, area(front, 1, 0, 0));
        assertEquals(1.0 / 64, area(front, 1, 0, 1L << 27), 1e-6);
        assertEquals(1.0 / 64, area(front, 0, 1L << 27, 0), 1e-6);
    }

    @Test
    void completedPatchMergesIntoASingleSurface() {
        int[] quads = {0};
        new GrassGrowthFront(3000L << 38)
                .draw(values(1, 0, -1L), (ax, az, bx, bz, cx, cz, dx, dz) -> quads[0]++);
        assertEquals(1, quads[0]);
    }

    @Test
    void retainedPixelsOnlyInvalidateWhenTheirGeometryChanges() {
        var front = new GrassGrowthFront(3000L << 38);
        List<Float> previous = List.of();
        int retained = 0;
        for (int step = 0; step <= 2000; step++) {
            float progress = step / 2000f;
            boolean changed = front.update(values(progress, 0, -1L));
            var current = new ArrayList<Float>();
            front.drawFixed(
                    (ax, az, bx, bz, cx, cz, dx, dz) ->
                            current.addAll(List.of(ax, az, bx, bz, cx, cz, dx, dz)));
            assertEquals(!previous.equals(current), changed);
            assertFalse(
                    front.update(values(progress, 0, -1L)),
                    "Stationary growth must reuse its mesh");
            if (!changed) retained++;
            previous = current;
        }
        assertTrue(retained > 1000, "Subpixel motion should leave most completed meshes unchanged");
        assertTrue(front.update(values(0, 0, 0)), "Reburning must remove cached recovered pixels");
        assertFalse(front.hasMovingPixels());
    }

    @Test
    void partialPixelCacheTracksExactGeometryThroughGrowthAndReburning() {
        var front = new GrassGrowthFront(3000L << 38);
        List<Float> previous = List.of();
        for (int step = 0; step <= 1000; step++) {
            float progress = step <= 500 ? step / 500f : (1000 - step) / 500f;
            float[] cells = values(progress, 0, -1L);
            front.update(cells);
            List<Float> current = movingVertices(front);
            assertEquals(!previous.equals(current), front.movingChanged());
            front.update(cells.clone());
            assertFalse(front.movingChanged(), "Equal coverage must retain its partial mesh");
            assertEquals(current, movingVertices(front));
            previous = current;
        }
        assertFalse(front.hasMovingPixels(), "Reburning must remove partial geometry too");
    }

    @Test
    void idleAndCompletedCellsDoNotInvalidateAnUnrelatedPartialPixelCache() {
        var front = new GrassGrowthFront(3000L << 38);
        float[] cells = values(.38f, 0, 1L << 27);
        front.update(cells);
        assertTrue(front.hasMovingPixels());
        List<Float> before = movingVertices(front);
        cells[0] = 1;
        assertTrue(front.update(cells), "The completed mesh changes independently");
        assertFalse(front.movingChanged());
        assertEquals(before, movingVertices(front));
    }

    private static List<Float> movingVertices(GrassGrowthFront front) {
        var vertices = new ArrayList<Float>();
        front.drawMoving(
                (ax, az, bx, bz, cx, cz, dx, dz) ->
                        vertices.addAll(List.of(ax, az, bx, bz, cx, cz, dx, dz)));
        return vertices;
    }

    @Test
    void eachPatternPreservesCoverageAndCanReturnToTheOriginalGeometry() {
        var front = new GrassGrowthFront(3000L << 38);
        float[] texture = new float[256];
        for (int i = 0; i < texture.length; i++) texture[i] = (i % 13) / 12f;
        front.update(values(.43f, 0, -1L));
        var original = movingVertices(front);
        var appearances = new java.util.HashSet<List<Float>>();
        for (var style : GrassGrowthStyle.values()) {
            front.configure(style, texture);
            double previous = 0;
            for (int i = 0; i <= 1000; i++) {
                double current = area(front, i / 1000f, 0, -1L);
                assertTrue(current >= previous - 1e-6);
                assertTrue(current - previous < .02);
                previous = current;
            }
            assertEquals(1, previous, 1e-6);
            front.update(values(.43f, 0, -1L));
            appearances.add(movingVertices(front));
            assertFalse(front.update(values(.43f, 0, -1L)));
            assertFalse(front.movingChanged());
            assertEquals(0, area(front, 1, 0, 0));
        }
        assertEquals(GrassGrowthStyle.values().length, appearances.size());
        front.configure(GrassGrowthStyle.SCATTERED, null);
        front.update(values(.43f, 0, -1L));
        assertEquals(original, movingVertices(front));
    }
}
