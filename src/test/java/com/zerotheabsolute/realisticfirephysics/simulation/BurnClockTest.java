package com.zerotheabsolute.realisticfirephysics.simulation;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.Test;

class BurnClockTest {
    @Test
    void coolingRetiresPausedWorkAndHistoryRemovalReleasesClock() {
        var clocks = new FireClocks();
        var pos = new net.minecraft.core.BlockPos(0, 0, 0);
        clocks.retain(pos);
        clocks.heated(pos);
        clocks.track(pos, 300, 1);
        clocks.clock(0).update(100, false);
        clocks.cooled(pos);
        assertFalse(clocks.clock(0).paused());
        clocks.release(pos);
        assertNull(clocks.clock(0));
        assertEquals(0, clocks.save(new CompoundTag(), null).getList("Chunks", 10).size());
    }

    @Test
    void inactiveTimeNeverAdvancesFuelAndRepeatedTransitionsStayContinuous() {
        var clock = new BurnClock(0, -1);
        assertEquals(120, clock.time(120));
        assertTrue(clock.update(120, false));
        assertEquals(120, clock.time(10000));
        assertFalse(clock.update(10000, false));
        assertTrue(clock.update(10000, true));
        assertEquals(120, clock.time(10000));
        assertEquals(140.5, clock.time(10020.5));
        clock.update(10020, false);
        clock.update(20000, true);
        assertEquals(140, clock.time(20000));
    }

    @Test
    void savedActiveChunkWaitsForTickEligibilityAfterWorldReload() {
        var entry = new CompoundTag();
        entry.putLong("Chunk", 23);
        entry.putLong("Offset", 500);
        entry.putLong("PausedAt", -1);
        entry.putLong("LastWorld", 1000);
        entry.putLong("Until", 700);
        entry.putLongArray("Owners", new long[] {3});
        var list = new ListTag();
        list.add(entry);
        var saved = new CompoundTag();
        saved.put("Chunks", list);
        var clocks = FireClocks.load(saved, null);
        assertEquals(500, clocks.time(23, 100000));
        var roundTrip = FireClocks.load(clocks.save(new CompoundTag(), null), null);
        assertEquals(500, roundTrip.time(23, 200000));
        roundTrip.clock(23).update(200000, true);
        assertEquals(501, roundTrip.time(23, 200001));
    }
}
