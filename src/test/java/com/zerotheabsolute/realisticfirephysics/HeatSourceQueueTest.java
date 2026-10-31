package com.zerotheabsolute.realisticfirephysics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

class HeatSourceQueueTest {
    @Test
    void newSectionsAreServedBeforeADensePoolIsDrained() {
        var queue = new HeatSourceQueue();
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++) queue.add(new BlockPos(x, y, z));
        var fresh = new BlockPos(48, 1, 0);
        queue.add(fresh);
        assertEquals(4097, queue.size());
        queue.poll();
        assertEquals(fresh, queue.poll());
        assertEquals(4095, queue.size());
    }

    @Test
    void moreThanFourThousandSourcesDoNotDiscardNewIgnitions() {
        var queue = new HeatSourceQueue();
        var expected = new HashSet<BlockPos>();
        for (int x = -100; x <= 100; x++)
            for (int z = -20; z <= 20; z++) {
                var pos = new BlockPos(x, -31, z);
                expected.add(pos);
                queue.add(pos);
            }
        assertEquals(expected.size(), queue.size());
        BlockPos pos;
        while ((pos = queue.poll()) != null) assertTrue(expected.remove(pos));
        assertTrue(expected.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void repeatedMutablePositionsAreCopiedAndDeduplicated() {
        var queue = new HeatSourceQueue();
        var pos = new BlockPos.MutableBlockPos(-1, -64, -17);
        queue.add(pos);
        queue.add(pos);
        pos.set(18, 319, 16);
        queue.add(pos);
        assertEquals(2, queue.size());
        assertEquals(new BlockPos(-1, -64, -17), queue.poll());
        assertEquals(new BlockPos(18, 319, 16), queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void removingAChunkReleasesItsSourcesAtEveryHeightOnly() {
        var queue = new HeatSourceQueue();
        queue.add(new BlockPos(-1, -64, -1));
        queue.add(new BlockPos(-1, 319, -1));
        var survivor = new BlockPos(0, 32, -1);
        queue.add(survivor);
        queue.removeChunk(-1, -1, -4, 20);
        assertEquals(1, queue.size());
        assertEquals(survivor, queue.poll());
        queue.add(new BlockPos(-1, 0, -1));
        assertEquals(new BlockPos(-1, 0, -1), queue.poll());
    }

    @Test
    void removingAndRequeueingOnePositionDoesNotLoseItsNeighbors() {
        var queue = new HeatSourceQueue();
        var first = new BlockPos(0, 0, 0);
        var second = new BlockPos(1, 0, 0);
        queue.add(first);
        queue.add(second);
        queue.remove(first);
        queue.remove(first);
        assertEquals(second, queue.poll());
        queue.add(first);
        assertEquals(first, queue.poll());
        assertEquals(0, queue.size());
    }
}
