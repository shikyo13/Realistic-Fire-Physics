package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.vertex.VertexSorting;

import org.joml.Vector3f;

/**
 * Back-to-front quad order for flame meshes, identical to {@link
 * VertexSorting#DISTANCE_TO_ORIGIN} but computed with a stable radix sort.
 *
 * <p>Vanilla sorts by descending squared distance from the camera with a stable merge sort. The
 * distances are never negative, so their IEEE bits order the same way as the floats themselves.
 * Sorting {@code Integer.MAX_VALUE - bits} ascending with a stable radix sort therefore yields the
 * same permutation, ties included, in linear time. Large fires sort tens of thousands of quads per
 * frame.
 */
final class FlameQuadOrder implements VertexSorting {
    static final FlameQuadOrder INSTANCE = new FlameQuadOrder();
    private static final Vector3f ORIGIN = new Vector3f();
    private static final int RADIX_BITS = 8, BUCKETS = 1 << RADIX_BITS, PASSES = 4;
    private final int[] counts = new int[BUCKETS];
    private int[] keys = new int[0], scratchKeys = new int[0], scratchOrder = new int[0];

    private FlameQuadOrder() {}

    /** Uses this order only where it reproduces the active sorting exactly. */
    static VertexSorting replacing(VertexSorting active) {
        return active == VertexSorting.DISTANCE_TO_ORIGIN ? INSTANCE : active;
    }

    @Override
    public int[] sort(Vector3f[] centers) {
        int n = centers.length;
        var order = new int[n];
        if (n == 0) return order;
        if (keys.length < n) {
            keys = new int[n];
            scratchKeys = new int[n];
            scratchOrder = new int[n];
        }
        for (int i = 0; i < n; i++) {
            order[i] = i;
            // Same expression as VertexSorting.byDistance(origin): origin.distanceSquared(center).
            keys[i] = Integer.MAX_VALUE - Float.floatToRawIntBits(ORIGIN.distanceSquared(centers[i]));
        }
        // Each pass is a stable counting sort on one byte, least significant first.
        int[] fromKeys = keys, fromOrder = order, toKeys = scratchKeys, toOrder = scratchOrder;
        for (int pass = 0; pass < PASSES; pass++) {
            int shift = pass * RADIX_BITS;
            java.util.Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) counts[(fromKeys[i] >>> shift) & (BUCKETS - 1)]++;
            // A byte shared by every key leaves the order unchanged.
            if (counts[(fromKeys[0] >>> shift) & (BUCKETS - 1)] == n) continue;
            for (int b = 0, total = 0; b < BUCKETS; b++) {
                int count = counts[b];
                counts[b] = total;
                total += count;
            }
            for (int i = 0; i < n; i++) {
                int slot = counts[(fromKeys[i] >>> shift) & (BUCKETS - 1)]++;
                toKeys[slot] = fromKeys[i];
                toOrder[slot] = fromOrder[i];
            }
            int[] keysAfter = toKeys, orderAfter = toOrder;
            toKeys = fromKeys;
            toOrder = fromOrder;
            fromKeys = keysAfter;
            fromOrder = orderAfter;
        }
        if (fromOrder != order) System.arraycopy(fromOrder, 0, order, 0, n);
        return order;
    }
}
