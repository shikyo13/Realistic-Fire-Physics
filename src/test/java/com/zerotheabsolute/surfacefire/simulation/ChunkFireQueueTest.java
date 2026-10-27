package com.zerotheabsolute.surfacefire.simulation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChunkFireQueueTest {
    record Work(long chunk, long due, int id) {}

    @Test
    void pausedBacklogDoesNotBlockOtherChunksOrBurstWhenResumed() {
        var paused = new BurnClock(0, 10);
        var running = new BurnClock(0, -1);
        var queue = new ChunkFireQueue<Work>(Work::chunk, Work::due);
        queue.add(new Work(1, 15, 1));
        queue.add(new Work(2, 20, 2));
        queue.prepare(key -> key == 1 ? paused : running);
        assertEquals(2, queue.poll(1000).id());
        assertNull(queue.poll(1000));
        assertEquals(1, queue.size());
        paused.update(1000, true);
        queue.prepare(key -> key == 1 ? paused : running);
        assertNull(queue.poll(1004));
        assertEquals(1, queue.poll(1005).id());
        assertNull(queue.poll(1005));
        assertEquals(0, queue.size());
    }

    @Test
    void workScheduledDuringTraversalRetainsOrderAndClearRemovesSleepers() {
        var clock = new BurnClock(0, -1);
        var queue = new ChunkFireQueue<Work>(Work::chunk, Work::due);
        queue.prepare(key -> clock);
        queue.add(new Work(1, 30, 3));
        queue.add(new Work(1, 20, 2));
        queue.add(new Work(2, 10, 1));
        assertEquals(1, queue.poll(100).id());
        assertEquals(2, queue.poll(100).id());
        queue.add(new Work(2, 25, 4));
        assertEquals(4, queue.poll(100).id());
        clock.update(100, false);
        queue.prepare(key -> clock);
        assertNull(queue.poll(1000));
        queue.clear();
        assertEquals(0, queue.size());
    }
}
