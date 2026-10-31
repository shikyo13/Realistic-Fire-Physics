package com.zerotheabsolute.realisticfirephysics.client.mesh;

import java.util.function.IntBinaryOperator;

/** Conservative coverage for repeating textures, including the bilinear filter footprint. */
public final class TextureCoverage {
    private static final int FILTER_PADDING_PIXELS = 2;
    private final int cells;
    private final boolean[] covered;

    public TextureCoverage(int width, int height, int cells, IntBinaryOperator alpha) {
        if (width <= 0 || height <= 0 || cells <= 0)
            throw new IllegalArgumentException("Invalid texture dimensions");
        this.cells = cells;
        covered = new boolean[cells * cells];
        for (int z = 0; z < cells; z++)
            for (int x = 0; x < cells; x++) {
                int minX = x * width / cells - FILTER_PADDING_PIXELS;
                int minZ = z * height / cells - FILTER_PADDING_PIXELS;
                int maxX = (int) Math.ceil((x + 1.0) * width / cells) + FILTER_PADDING_PIXELS;
                int maxZ = (int) Math.ceil((z + 1.0) * height / cells) + FILTER_PADDING_PIXELS;
                search:
                for (int v = minZ; v <= maxZ; v++)
                    for (int u = minX; u <= maxX; u++) {
                        if (alpha.applyAsInt(Math.floorMod(u, width), Math.floorMod(v, height))
                                != 0) {
                            covered[z * cells + x] = true;
                            break search;
                        }
                    }
            }
    }

    public boolean contains(int cellX, int cellZ) {
        return covered[Math.floorMod(cellZ, cells) * cells + Math.floorMod(cellX, cells)];
    }
}
