package com.zerotheabsolute.surfacefire.client;

import it.unimi.dsi.fastutil.HashCommon;

import net.minecraft.client.renderer.block.model.BakedQuad;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** Shares identical immutable sub-quads across burn masks and terrain rebuilds. */
final class ScorchCellMeshes {
    private static final int STRIDE = 8, COLOR = 3;
    private final AtomicReferenceArray<Entry> cells;

    private record Entry(
            BakedQuad source, int width, int cell, int a, int b, int c, int d, BakedQuad mesh) {}

    ScorchCellMeshes(int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("Cell cache capacity must be a power of two");
        cells = new AtomicReferenceArray<>(capacity);
    }

    BakedQuad get(BakedQuad source, int[] grid, int width, int x, int y) {
        int a = (y * width + x) * STRIDE;
        int b = a + STRIDE;
        int d = a + width * STRIDE;
        int c = d + STRIDE;
        int cell = y * width + x;
        int ca = grid[a + COLOR], cb = grid[b + COLOR];
        int cc = grid[c + COLOR], cd = grid[d + COLOR];
        int hash = System.identityHashCode(source);
        hash = 31 * hash + width;
        hash = 31 * hash + cell;
        hash = 31 * hash + ca;
        hash = 31 * hash + cb;
        hash = 31 * hash + cc;
        hash = 31 * hash + cd;
        int index = HashCommon.mix(hash) & (cells.length() - 1);
        var cached = cells.get(index);
        if (cached != null
                && cached.source == source
                && cached.width == width
                && cached.cell == cell
                && cached.a == ca
                && cached.b == cb
                && cached.c == cc
                && cached.d == cd) return cached.mesh;
        var vertices = new int[4 * STRIDE];
        System.arraycopy(grid, a, vertices, 0, STRIDE);
        System.arraycopy(grid, b, vertices, STRIDE, STRIDE);
        System.arraycopy(grid, c, vertices, 2 * STRIDE, STRIDE);
        System.arraycopy(grid, d, vertices, 3 * STRIDE, STRIDE);
        var result =
                new BakedQuad(
                        vertices,
                        -1,
                        source.getDirection(),
                        source.getSprite(),
                        source.isShade(),
                        source.hasAmbientOcclusion());
        // A collision merely rebuilds an identical mesh. Hits allocate no keys or recency nodes;
        // immutable entries can be read safely by concurrent terrain workers.
        cells.set(index, new Entry(source, width, cell, ca, cb, cc, cd, result));
        return result;
    }

    void clear() {
        for (int i = 0; i < cells.length(); i++) cells.set(i, null);
    }
}
