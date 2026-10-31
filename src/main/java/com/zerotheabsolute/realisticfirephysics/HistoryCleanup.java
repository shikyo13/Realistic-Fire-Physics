package com.zerotheabsolute.realisticfirephysics;

import java.util.LinkedHashSet;
import java.util.function.Predicate;

/** Incremental optional retention work; active or warm records are never eligible. */
public final class HistoryCleanup<K> {
    private final LinkedHashSet<K> queue = new LinkedHashSet<>();

    public void added(K key) {
        queue.add(key);
    }

    public void removed(K key) {
        queue.remove(key);
    }

    public void clear() {
        queue.clear();
    }

    public void sweep(int budget, Predicate<K> retain) {
        int examined = Math.min(budget, queue.size());
        for (int i = 0; i < examined; i++) {
            K key = queue.removeFirst();
            if (retain.test(key)) queue.add(key);
        }
    }

    public static boolean eligible(
            long now, long coldAt, int retentionSeconds, boolean overCapacity) {
        return now >= coldAt
                && (overCapacity || retentionSeconds > 0 && now - coldAt >= retentionSeconds * 20L);
    }
}
