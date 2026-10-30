package com.zerotheabsolute.surfacefire.client.mesh;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Random;

class UniformQuadGridTest {
    @Test
    void collapsesUniformFieldToOneQuad() {
        var grid = new UniformQuadGrid(8);
        for (int i = 0; i < 64; i++) grid.set(i, .45f, .45f, .45f, .45f);
        assertTrue(grid.next());
        assertEquals(0, grid.first());
        assertEquals(63, grid.last());
        assertEquals(.45f, grid.color());
        assertFalse(grid.next());
    }

    @Test
    void preservesGradientsHolesAndColorBoundaries() {
        var random = new Random(31026);
        for (int run = 0; run < 100; run++) {
            var grid = new UniformQuadGrid(8);
            var source = new float[64][];
            for (int i = 0; i < source.length; i++) {
                if (random.nextInt(5) == 0) continue;
                float a = random.nextInt(3) * .2f;
                float b = random.nextInt(6) == 0 ? a + .01f : a;
                source[i] = new float[] {a, b, a, a};
                grid.set(i, a, b, a, a);
            }
            var seen = new boolean[64];
            while (grid.next()) {
                int first = grid.first(), last = grid.last();
                for (int y = first / 8; y <= last / 8; y++)
                    for (int x = first % 8; x <= last % 8; x++) {
                        int index = y * 8 + x;
                        assertNotNull(source[index], "A merged quad crossed a missing cell");
                        assertFalse(seen[index], "Overlapping geometry");
                        seen[index] = true;
                        if (first != last)
                            for (float color : source[index]) assertEquals(grid.color(), color);
                    }
            }
            for (int i = 0; i < 64; i++) assertEquals(source[i] != null, seen[i]);
        }
    }

    @Test
    void clearDoesNotRetainThePreviousFrame() {
        var grid = new UniformQuadGrid(8);
        grid.set(63, 1, 1, 1, 1);
        grid.clear();
        assertFalse(grid.next());
        grid.set(0, 0, 0, 0, 0);
        assertTrue(grid.next());
        assertEquals(0, grid.last());
        assertFalse(grid.next());
    }
}
