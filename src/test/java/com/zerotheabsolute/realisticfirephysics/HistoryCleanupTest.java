package com.zerotheabsolute.realisticfirephysics;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class HistoryCleanupTest {
    @Test
    void disabledRetentionKeepsColdScarsAndCapacityCannotRemoveHeat() {
        assertFalse(HistoryCleanup.eligible(100000, 100, 0, false));
        assertFalse(HistoryCleanup.eligible(99, 100, 1, true));
        assertFalse(HistoryCleanup.eligible(119, 100, 1, false));
        assertTrue(HistoryCleanup.eligible(120, 100, 1, false));
        assertTrue(HistoryCleanup.eligible(100, 100, 0, true));
    }

    @Test
    void largeRetentionDoesNotOverflowAndCleanupIsBoundedAndFair() {
        assertFalse(HistoryCleanup.eligible(1_000_000, 100, 31_536_000, false));
        var cleanup = new HistoryCleanup<Integer>();
        for (int i = 0; i < 5; i++) cleanup.added(i);
        var visited = new ArrayList<Integer>();
        cleanup.sweep(
                2,
                i -> {
                    visited.add(i);
                    return true;
                });
        assertEquals(java.util.List.of(0, 1), visited);
        cleanup.sweep(
                2,
                i -> {
                    visited.add(i);
                    return true;
                });
        assertEquals(java.util.List.of(0, 1, 2, 3), visited);
    }

    @Test
    void replacingTheSameSurfaceDoesNotAccumulateCleanupEntries() {
        var cleanup = new HistoryCleanup<Integer>();
        for (int i = 0; i < 1000; i++) {
            cleanup.added(3);
            cleanup.removed(3);
        }
        cleanup.added(3);
        cleanup.added(3);
        var visited = new ArrayList<Integer>();
        cleanup.sweep(
                1000,
                i -> {
                    visited.add(i);
                    return true;
                });
        assertEquals(java.util.List.of(3), visited);
    }
}
