package com.zerotheabsolute.surfacefire.structure;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Stream;

class StructurePersistenceTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
    }

    private static CompoundTag block(int x) {
        var tag = new CompoundTag();
        tag.putLong("Pos", new BlockPos(x, 10, 0).asLong());
        tag.put("State", NbtUtils.writeBlockState(Blocks.OAK_LEAVES.defaultBlockState()));
        tag.putInt("Fuel", StructureFuel.LEAVES.ordinal());
        var times = new long[SurfaceCell.BLOCK_CELLS];
        Arrays.fill(times, BurningBlock.UNBURNED);
        times[135] = 100;
        tag.putLongArray("Ignition", times);
        tag.putBoolean("Quenched", true);
        return tag;
    }

    private static CompoundTag roundTrip(ListTag blocks, long totalIgnited) {
        var tag = new CompoundTag();
        tag.put("Blocks", blocks);
        tag.putLong("IgnitedCells", totalIgnited);
        return StructureField.load(tag, registries).save(new CompoundTag(), registries);
    }

    @Test
    void sparseBurnsCountOccupiedCellsAndPreserveAllThreeThousandBlocks() {
        var blocks = new ListTag();
        for (int i = 0; i < 3000; i++) blocks.add(block(i));
        var saved = roundTrip(blocks, 8000);
        assertEquals(3000, saved.getList("Blocks", Tag.TAG_COMPOUND).size());
        assertEquals(8000, saved.getLong("IgnitedCells"));
        for (var value : saved.getList("Blocks", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) value;
            assertTrue(entry.getBoolean("Quenched"));
            assertEquals(100, entry.getLongArray("Ignition")[135]);
        }
    }

    @Test
    void invalidArrayDoesNotDiscardLaterValidRecords() {
        var malformed = block(0);
        malformed.putLongArray("Ignition", new long[3]);
        var blocks = new ListTag();
        blocks.add(malformed);
        blocks.add(block(1));
        assertEquals(1, roundTrip(blocks, 1).getList("Blocks", Tag.TAG_COMPOUND).size());
    }

    @Test
    void retainedCellsCannotUndercountTheHistoricalTotal() {
        var blocks = new ListTag();
        blocks.add(block(0));
        blocks.add(block(1));
        assertEquals(2, roundTrip(blocks, -1).getLong("IgnitedCells"));
    }

    @Test
    void coldHistoryCanExceedTheActiveBlockLimit() {
        var blocks = new ListTag();
        int count = com.zerotheabsolute.surfacefire.FireSettings.MAX_TRACKED_STRUCTURE_BLOCKS + 1;
        for (int i = 0; i < count; i++) blocks.add(block(i));
        assertEquals(count, roundTrip(blocks, count).getList("Blocks", Tag.TAG_COMPOUND).size());
    }

    @Test
    void legacyGrassSurfacesDoNotRestoreDuplicateVegetationFire() {
        var grass = block(0);
        grass.put("State", NbtUtils.writeBlockState(Blocks.SHORT_GRASS.defaultBlockState()));
        var blocks = new ListTag();
        blocks.add(grass);
        blocks.add(block(1));
        assertEquals(1, roundTrip(blocks, 2).getList("Blocks", Tag.TAG_COMPOUND).size());
    }
}
