package com.zerotheabsolute.realisticfirephysics.simulation;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;

/** Sleeping chunks retain their work without consuming the active spread budget. */
public final class ChunkFireQueue<T> {
    private final ToLongFunction<T> chunkKey, dueTime;
    private final Long2ObjectOpenHashMap<Bucket> buckets = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Bucket> ready =
            new PriorityQueue<>(Comparator.comparingLong(bucket -> bucket.due));
    private LongFunction<BurnClock> clocks;
    private int size;

    private final class Bucket {
        final long chunk;
        final PriorityQueue<T> work = new PriorityQueue<>(Comparator.comparingLong(dueTime));
        long due;
        boolean queued;

        Bucket(long chunk) {
            this.chunk = chunk;
        }
    }

    public ChunkFireQueue(ToLongFunction<T> chunkKey, ToLongFunction<T> dueTime) {
        this.chunkKey = chunkKey;
        this.dueTime = dueTime;
    }

    public void add(T item) {
        long key = chunkKey.applyAsLong(item);
        var bucket = buckets.computeIfAbsent(key, Bucket::new);
        bucket.work.add(item);
        size++;
        if (clocks != null) refresh(bucket);
    }

    public void prepare(LongFunction<BurnClock> clocks) {
        this.clocks = clocks;
        ready.clear();
        for (var bucket : buckets.values()) {
            bucket.queued = false;
            refresh(bucket);
        }
    }

    private void refresh(Bucket bucket) {
        var clock = clocks.apply(bucket.chunk);
        if (clock != null && clock.paused()) return;
        long due = dueTime.applyAsLong(bucket.work.peek()) + (clock == null ? 0 : clock.offset());
        if (bucket.queued && due == bucket.due) return;
        if (bucket.queued) ready.remove(bucket);
        bucket.due = due;
        bucket.queued = true;
        ready.add(bucket);
    }

    public T poll(long worldTime) {
        if (ready.isEmpty() || ready.peek().due > worldTime) return null;
        var bucket = ready.remove();
        bucket.queued = false;
        var item = bucket.work.remove();
        size--;
        if (bucket.work.isEmpty()) buckets.remove(bucket.chunk);
        else refresh(bucket);
        return item;
    }

    public int size() {
        return size;
    }

    public void clear() {
        buckets.clear();
        ready.clear();
        size = 0;
    }
}
