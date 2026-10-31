package com.zerotheabsolute.realisticfirephysics.client;

import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/** Reuses centers only while an immediate flame batch is sorted and uploaded synchronously. */
public final class FlameSortScratch {
    private static final int MAX_CENTERS = 65536;
    private static final ArrayList<Vector3f> CENTERS = new ArrayList<>();
    private static volatile Thread owner;
    private static int used;

    private FlameSortScratch() {}

    static void begin() {
        if (owner != null) throw new IllegalStateException("Flame sorting is already active");
        used = 0;
        owner = Thread.currentThread();
    }

    static void end() {
        owner = null;
    }

    static void clear() {
        CENTERS.clear();
        CENTERS.trimToSize();
        used = 0;
    }

    public static Vector3f[] centers(ByteBuffer vertices, int vertexCount, int stride, int offset) {
        if (owner != Thread.currentThread()) return null;
        if (offset < 0 || stride < offset + 3 * Float.BYTES) return null;
        int base = vertices.position();
        var result = new Vector3f[vertexCount / 4];
        for (int quad = 0; quad < result.length; quad++) {
            // Shader formats can have a byte stride that is not a multiple of four.
            int a = base + quad * 4 * stride + offset;
            int b = a + 2 * stride;
            result[quad] =
                    center(
                            (vertices.getFloat(a) + vertices.getFloat(b)) * .5f,
                            (vertices.getFloat(a + 4) + vertices.getFloat(b + 4)) * .5f,
                            (vertices.getFloat(a + 8) + vertices.getFloat(b + 8)) * .5f);
        }
        return result;
    }

    static Vector3f center(float x, float y, float z) {
        // Chunk sort states may outlive their upload; those must retain independent centers.
        if (owner != Thread.currentThread() || used >= MAX_CENTERS) return new Vector3f(x, y, z);
        if (used == CENTERS.size()) CENTERS.add(new Vector3f());
        return CENTERS.get(used++).set(x, y, z);
    }
}
