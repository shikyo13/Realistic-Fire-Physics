package com.zerotheabsolute.realisticfirephysics.scorch;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

class ScorchPersistenceTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void init() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
    }

    @Test
    void sparseFacesPartialGrassAndRainProgressSurviveSaving() {
        var c = new ChunkScars();
        c.epoch = 5;
        c.rainClock = 2560;
        c.ground.put(42, new ChunkScars.Ground(Long.MIN_VALUE | 3, 1.25f, 400, 2900, 4, 2, .375f));
        c.structures.put(
                43,
                new ChunkScars.Structure(
                        Blocks.OAK_LEAVES.defaultBlockState(),
                        new long[] {0, Long.MIN_VALUE, 0, 3, 0, 0},
                        true,
                        450));
        var restored = new ChunkScars();
        restored.deserializeNBT(registries, c.serializeNBT(registries));
        assertEquals(c.ground, restored.ground);
        assertEquals(2560, restored.rainClock);
        assertEquals(5, restored.epoch);
        var s = restored.structures.get(43);
        assertTrue(s.quenched());
        assertTrue(s.contains(127));
        assertTrue(s.contains(192));
        assertFalse(s.contains(0));
        assertTrue(s.state().is(Blocks.OAK_LEAVES));
    }

    @Test
    void anEpochResetRemovesBothKindsOfScar() {
        var c = new ChunkScars();
        c.ground.put(42, new ChunkScars.Ground(-1, 64, 10, 0));
        c.structures.put(
                43,
                new ChunkScars.Structure(
                        Blocks.OAK_LOG.defaultBlockState(), new long[6], false, 10));
        c.clear(8);
        assertTrue(c.empty());
        assertEquals(8, c.epoch);
    }

    @Test
    void independentCellProgressSurvivesSavingAndOldUniformProgressMigrates() {
        var c = new ChunkScars();
        var g = new ChunkScars.Ground(-1L, 4, 0, 0);
        c.ground.put(42, GrassRegrowth.advance(g, -1L, 20, 100, 42));
        var tag = c.serializeNBT(registries);
        var restored = new ChunkScars();
        restored.deserializeNBT(registries, tag);
        assertEquals(c.ground, restored.ground);
        assertNotEquals(restored.ground.get(42).grass(0), restored.ground.get(42).grass(7));
        var ground = tag.getList("Ground", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        ground.remove("GrowthCells");
        ground.putLong("Growing", 3);
        ground.putFloat("Growth", .375f);
        restored.deserializeNBT(registries, tag);
        assertEquals(.375f, restored.ground.get(42).grass(0), 2e-5);
        assertEquals(.375f, restored.ground.get(42).grass(1), 2e-5);
        assertEquals(0, restored.ground.get(42).grass(2));
    }

    @Test
    void coldTimberCellsDoNotMakeFreshFireInstantlyConsumeTheLog() {
        var b =
                new com.zerotheabsolute.realisticfirephysics.structure.BurningBlock(
                        net.minecraft.core.BlockPos.ZERO,
                        Blocks.OAK_LOG.defaultBlockState(),
                        com.zerotheabsolute.realisticfirephysics.structure.StructureFuel.WOOD);
        b.ignite(0, com.zerotheabsolute.realisticfirephysics.structure.BurningBlock.COLD);
        b.ignite(1, 1000);
        assertEquals(1000, b.firstIgnition);
        assertFalse(b.burning(0, 1000));
        assertTrue(b.burning(1, 1000));
    }

    @Test
    void extinguishEpochSurvivesRestartWithoutClearingScars() {
        var archive = new ScorchArchive();
        archive.extinguish();
        archive.extinguish();
        var tag = archive.save(new net.minecraft.nbt.CompoundTag(), registries);
        assertEquals(0, tag.getLong("Epoch"));
        assertEquals(2, ScorchArchive.load(tag, registries).heatEpoch());
    }
}
