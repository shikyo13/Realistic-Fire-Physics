package com.zerotheabsolute.surfacefire;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import org.junit.jupiter.api.Test;

class FireHistoryPersistenceTest {
    @Test
    void historicalScarsAreNotTruncatedAtAnOutbreaksLimit() {
        var entries = new ListTag();
        for (int i = 0; i <= FireSettings.MAX_SUPPORTED_SAMPLES; i++) {
            var sample = new CompoundTag();
            sample.putInt("X", i);
            sample.putInt("Y", 0);
            sample.putInt("Z", 0);
            sample.putLong("Ignition", 100);
            sample.putInt("Fuel", 1);
            sample.putBoolean("Quenched", true);
            sample.putLong("Outbreak", 5);
            entries.add(sample);
        }
        var saved = new CompoundTag();
        saved.put("Samples", entries);
        var field = FireField.load(saved, null);
        assertEquals(FireSettings.MAX_SUPPORTED_SAMPLES + 1, field.size());
        var roundTrip = field.save(new CompoundTag(), null).getList("Samples", Tag.TAG_COMPOUND);
        assertEquals(entries.size(), roundTrip.size());
        assertEquals(5, roundTrip.getCompound(roundTrip.size() - 1).getLong("Outbreak"));
    }
}
