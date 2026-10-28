package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

class ScorchCellMeshesTest {
    private static BakedQuad source() {
        return new BakedQuad(new int[32], 0, Direction.UP, null, true, true);
    }

    private static int[] grid() {
        var grid = new int[9 * 8];
        for (int i = 0; i < grid.length; i++) grid[i] = i;
        return grid;
    }

    @Test
    void cachedCellsPreserveEveryVertexAttributeAndWinding() {
        var cache = new ScorchCellMeshes(16);
        var source = source();
        var grid = grid();
        var first = cache.get(source, grid, 3, 0, 0);
        var expected = new int[32];
        int[] corners = {0, 1, 4, 3};
        for (int i = 0; i < 4; i++) System.arraycopy(grid, corners[i] * 8, expected, i * 8, 8);
        assertArrayEquals(expected, first.getVertices());
        assertEquals(-1, first.getTintIndex());
        assertEquals(source.getDirection(), first.getDirection());
        assertEquals(source.isShade(), first.isShade());
        assertEquals(source.hasAmbientOcclusion(), first.hasAmbientOcclusion());
        assertSame(first, cache.get(source, grid.clone(), 3, 0, 0));
        assertNotSame(first, cache.get(source, grid, 3, 1, 0));
        assertNotSame(first, cache.get(source(), grid, 3, 0, 0));
    }

    @Test
    void changingOneCornerNeverMutatesAnOlderMesh() {
        var cache = new ScorchCellMeshes(16);
        var source = source();
        var grid = grid();
        var old = cache.get(source, grid, 3, 0, 0);
        var unchanged = cache.get(source, grid, 3, 1, 1);
        var original = old.getVertices().clone();
        grid[3] = 0xff334455;
        var updated = cache.get(source, grid, 3, 0, 0);
        assertNotSame(old, updated);
        assertEquals(grid[3], updated.getVertices()[3]);
        assertArrayEquals(original, old.getVertices());
        assertArrayEquals(unchanged.getVertices(), cache.get(source, grid, 3, 1, 1).getVertices());
    }

    @Test
    void evictionAndReloadReleaseOldGeometry() {
        var cache = new ScorchCellMeshes(1);
        var source = source();
        var grid = grid();
        var first = cache.get(source, grid, 3, 0, 0);
        cache.get(source, grid, 3, 1, 1);
        var recreated = cache.get(source, grid, 3, 0, 0);
        assertNotSame(first, recreated);
        assertArrayEquals(first.getVertices(), recreated.getVertices());
        cache.clear();
        assertNotSame(recreated, cache.get(source, grid, 3, 0, 0));
    }

    @Test
    void concurrentWorkersAndCollisionsNeverShareTheWrongColors() throws Exception {
        var cache = new ScorchCellMeshes(1);
        var source = source();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                int color = 0xff334455 + worker;
                jobs.add(
                        () -> {
                            var grid = grid();
                            grid[3] = color;
                            for (int i = 0; i < 100; i++) {
                                var mesh = cache.get(source, grid, 3, 0, 0);
                                assertEquals(color, mesh.getVertices()[3]);
                                assertEquals(grid[8 + 3], mesh.getVertices()[8 + 3]);
                            }
                            return null;
                        });
            }
            for (var result : workers.invokeAll(jobs)) result.get();
        }
    }
}
