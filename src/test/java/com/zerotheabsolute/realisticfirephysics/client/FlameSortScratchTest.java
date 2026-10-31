package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class FlameSortScratchTest {
    @AfterEach
    void release() {
        FlameSortScratch.end();
        FlameSortScratch.clear();
    }

    @Test
    void consecutiveUploadsReuseCentersWithoutSharingWithinTheBatch() {
        FlameSortScratch.begin();
        var first = FlameSortScratch.center(1, 2, 3);
        var second = FlameSortScratch.center(4, 5, 6);
        assertNotSame(first, second);
        assertEquals(new Vector3f(1, 2, 3), first);
        FlameSortScratch.end();
        FlameSortScratch.begin();
        var reused = FlameSortScratch.center(-7, 8, -9);
        assertSame(first, reused);
        assertEquals(new Vector3f(-7, 8, -9), reused);
    }

    @Test
    void ordinaryAndWorkerSortsKeepIndependentCenters() throws Exception {
        var ordinary = FlameSortScratch.center(1, 2, 3);
        FlameSortScratch.begin();
        var flame = FlameSortScratch.center(4, 5, 6);
        var worker = new Vector3f[1];
        var thread = new Thread(() -> worker[0] = FlameSortScratch.center(7, 8, 9));
        thread.start();
        thread.join();
        FlameSortScratch.end();
        FlameSortScratch.begin();
        FlameSortScratch.center(0, 0, 0);
        assertNotSame(ordinary, flame);
        assertEquals(new Vector3f(1, 2, 3), ordinary);
        assertEquals(new Vector3f(7, 8, 9), worker[0]);
    }

    @Test
    void cleanupReleasesCachedCenters() {
        FlameSortScratch.begin();
        var previous = FlameSortScratch.center(1, 2, 3);
        FlameSortScratch.end();
        FlameSortScratch.clear();
        FlameSortScratch.begin();
        assertNotSame(previous, FlameSortScratch.center(1, 2, 3));
    }

    @Test
    void centersPreserveOppositeCornerMathAndVertexLayout() {
        var data = ByteBuffer.allocate(8 * 32).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 8; i++) {
            data.putFloat(i * 32 + 4, i - 4);
            data.putFloat(i * 32 + 8, i * 2);
            data.putFloat(i * 32 + 12, -i);
        }
        assertNull(FlameSortScratch.centers(data, 8, 32, 4));
        FlameSortScratch.begin();
        var centers = FlameSortScratch.centers(data, 8, 32, 4);
        assertArrayEquals(
                new Vector3f[] {new Vector3f(-3, 2, -1), new Vector3f(1, 10, -5)}, centers);
    }

    @Test
    void shaderLayoutsSupportUnalignedStrideAndBufferPosition() {
        int stride = 54;
        int offset = 2;
        int base = 7;
        var data = ByteBuffer.allocate(base + 8 * stride).order(ByteOrder.nativeOrder());
        data.position(base);
        for (int i = 0; i < 8; i++) {
            int p = base + i * stride + offset;
            data.putFloat(p, i - 4);
            data.putFloat(p + 4, i * 2);
            data.putFloat(p + 8, -i);
        }
        FlameSortScratch.begin();
        assertArrayEquals(
                new Vector3f[] {new Vector3f(-3, 2, -1), new Vector3f(1, 10, -5)},
                FlameSortScratch.centers(data, 8, stride, offset));
    }

    @Test
    void unknownLayoutsUseTheOriginalSorter() {
        FlameSortScratch.begin();
        assertNull(FlameSortScratch.centers(ByteBuffer.allocate(32), 4, 8, -1));
        assertNull(FlameSortScratch.centers(ByteBuffer.allocate(32), 4, 8, 0));
    }
}
