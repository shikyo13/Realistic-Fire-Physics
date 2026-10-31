package com.zerotheabsolute.realisticfirephysics.client;

import java.util.Arrays;

/** Ordered connected texels follow low-cost paths through the native grass texture. */
final class GrassTextureGrowth {
    private static final int WIDTH = GrassGrowthFront.RESOLUTION;
    private static final float BASE_COST = .2f, DETAIL_COST = .8f;
    private static final int ROOT_COUNT = 2;
    private final float[] costs = new float[WIDTH * WIDTH];
    private final int[] heap = new int[costs.length], slots = new int[costs.length];
    private int size;

    static int[] order(float[] texture, float[] variation, long footprint, byte[] directions) {
        var paths = new GrassTextureGrowth();
        Arrays.fill(paths.costs, Float.POSITIVE_INFINITY);
        Arrays.fill(paths.slots, -1);
        int[] ranks = new int[WIDTH * WIDTH];
        Arrays.fill(ranks, -1);
        for (int root = 0; root < ROOT_COUNT; root++) {
            int next = paths.seed(variation, footprint, ranks);
            if (next < 0) break;
            paths.offer(next, 0);
        }
        int rank = 0;
        while (true) {
            if (paths.size == 0) {
                int next = paths.seed(variation, footprint, ranks);
                if (next < 0) break;
                paths.offer(next, 0);
            }
            int pixel = paths.pop();
            ranks[pixel] = rank++;
            int x = pixel % WIDTH, z = pixel / WIDTH;
            if (x > 0)
                paths.visit(
                        pixel,
                        pixel - 1,
                        (byte) 1,
                        texture,
                        variation,
                        footprint,
                        ranks,
                        directions);
            if (x + 1 < WIDTH)
                paths.visit(
                        pixel,
                        pixel + 1,
                        (byte) 0,
                        texture,
                        variation,
                        footprint,
                        ranks,
                        directions);
            if (z > 0)
                paths.visit(
                        pixel,
                        pixel - WIDTH,
                        (byte) 3,
                        texture,
                        variation,
                        footprint,
                        ranks,
                        directions);
            if (z + 1 < WIDTH)
                paths.visit(
                        pixel,
                        pixel + WIDTH,
                        (byte) 2,
                        texture,
                        variation,
                        footprint,
                        ranks,
                        directions);
        }
        return ranks;
    }

    private int seed(float[] variation, long footprint, int[] ranks) {
        int result = -1;
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < ranks.length; i++) {
            if (ranks[i] >= 0 || slots[i] >= 0 || !covered(footprint, i)) continue;
            if (variation[i] < best) {
                best = variation[i];
                result = i;
            }
        }
        return result;
    }

    private void visit(
            int from,
            int to,
            byte direction,
            float[] texture,
            float[] variation,
            long footprint,
            int[] ranks,
            byte[] directions) {
        if (ranks[to] >= 0 || !covered(footprint, to)) return;
        float detail = texture == null ? .5f : Math.clamp(texture[to], 0, 1);
        float next = costs[from] + BASE_COST + DETAIL_COST * detail + variation[to] * .02f;
        if (next < costs[to]) {
            directions[to] = direction;
            offer(to, next);
        }
    }

    private static boolean covered(long footprint, int pixel) {
        return (footprint & (1L << ((pixel / WIDTH / 2) * 8 + pixel % WIDTH / 2))) != 0;
    }

    private void offer(int pixel, float cost) {
        costs[pixel] = cost;
        int slot = slots[pixel];
        if (slot < 0) slot = size++;
        while (slot > 0) {
            int parent = (slot - 1) >>> 1;
            if (!earlier(pixel, heap[parent])) break;
            heap[slot] = heap[parent];
            slots[heap[slot]] = slot;
            slot = parent;
        }
        heap[slot] = pixel;
        slots[pixel] = slot;
    }

    private int pop() {
        int first = heap[0], last = heap[--size];
        slots[first] = -1;
        if (size == 0) return first;
        int slot = 0;
        while (slot * 2 + 1 < size) {
            int child = slot * 2 + 1;
            if (child + 1 < size && earlier(heap[child + 1], heap[child])) child++;
            if (!earlier(heap[child], last)) break;
            heap[slot] = heap[child];
            slots[heap[slot]] = slot;
            slot = child;
        }
        heap[slot] = last;
        slots[last] = slot;
        return first;
    }

    private boolean earlier(int a, int b) {
        return costs[a] < costs[b] || costs[a] == costs[b] && a < b;
    }
}
