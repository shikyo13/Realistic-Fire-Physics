package com.zerotheabsolute.surfacefire.client.mesh;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TextureCoverageTest {
    @Test
    void transparentTextureCanSkipEveryCell() {
        var mask = new TextureCoverage(64, 64, 8, (x, z) -> 0);
        for (int z = -8; z < 16; z++)
            for (int x = -8; x < 16; x++) assertFalse(mask.contains(x, z));
    }

    @Test
    void filteringAcrossRepeatEdgesKeepsBothSides() {
        var mask = new TextureCoverage(64, 64, 8, (x, z) -> x == 0 && z == 0 ? 1 : 0);
        assertTrue(mask.contains(0, 0));
        assertTrue(mask.contains(-1, 0));
        assertTrue(mask.contains(0, -1));
        assertTrue(mask.contains(-1, -1));
        assertFalse(mask.contains(3, 3));
    }

    @Test
    void evenFaintInteriorPixelsArePreservedAtNegativeCoordinates() {
        var mask = new TextureCoverage(64, 64, 8, (x, z) -> x == 28 && z == 36 ? 1 : 0);
        assertTrue(mask.contains(3, 4));
        assertTrue(mask.contains(3 - 8, 4 - 8));
        assertFalse(mask.contains(0, 0));
    }
}
