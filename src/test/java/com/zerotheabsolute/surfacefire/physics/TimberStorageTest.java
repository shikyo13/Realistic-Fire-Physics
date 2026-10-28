package com.zerotheabsolute.surfacefire.physics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

class TimberStorageTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
    }

    @Test
    void sparseBurnAgesAndMaterialSurviveRoundTrip() {
        var ages = new int[384];
        Arrays.fill(ages, -1);
        ages[0] = 0;
        ages[80] = 120;
        ages[383] = 65534;
        var part =
                new TimberBlocks(
                        new BlockPos(-2, 7, 1), Blocks.BIRCH_LOG.defaultBlockState(), ages, true);
        var result = TimberStorage.read(TimberStorage.write(List.of(part)), registries).getFirst();
        assertEquals(part.offset(), result.offset());
        assertEquals(part.state(), result.state());
        assertTrue(result.quenched());
        for (int i = 0; i < 384; i++) assertEquals(ages[i], result.ageAt(i));
        assertFalse(result.burning(100));
        assertFalse(result.consumed(1000));
    }

    @Test
    void unburnedWoodStoresNoCellArray() {
        var part =
                new TimberBlocks(
                        BlockPos.ZERO, Blocks.OAK_LOG.defaultBlockState(), new int[0], false);
        var result = TimberStorage.read(TimberStorage.write(List.of(part)), registries).getFirst();
        assertEquals(0, result.cellCount());
        assertFalse(result.burning(0));
        assertFalse(result.consumed(1000));
    }

    @Test
    void burnSnapshotCannotChangeBehindCachedSummaries() {
        var ages = new int[384];
        Arrays.fill(ages, 20);
        var part = new TimberBlocks(BlockPos.ZERO, Blocks.OAK_LOG.defaultBlockState(), ages, false);
        ages[0] = -1;
        assertEquals(20, part.ageAt(0));
        assertTrue(part.burning(100));
        assertFalse(part.burning(240));
        assertTrue(part.consumed(400));
    }

    @Test
    void compactSpawnPreservesGeometryHeatAndOutbreak() {
        var ages = new int[384];
        Arrays.fill(ages, -1);
        ages[0] = 0;
        ages[80] = 120;
        ages[383] = 65534;
        var part =
                new TimberBlocks(
                        new BlockPos(-2, 7, 1),
                        Blocks.BIRCH_LOG.defaultBlockState(),
                        ages,
                        true,
                        91);
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            TimberSpawnData.write(buffer, List.of(part));
            var result = TimberSpawnData.read(buffer).getFirst();
            assertEquals(part.offset(), result.offset());
            assertEquals(part.state(), result.state());
            assertTrue(result.quenched());
            assertEquals(91, result.outbreak());
            for (int c = 0; c < ages.length; c++) assertEquals(ages[c], result.ageAt(c));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void compactSpawnRejectsOversizedGeometryBeforeAllocation() {
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buffer.writeByte(1);
            buffer.writeVarInt(TimberStorage.MAX_PARTS + 1);
            assertThrows(IllegalArgumentException.class, () -> TimberSpawnData.read(buffer));
        } finally {
            buffer.release();
        }
    }
}
