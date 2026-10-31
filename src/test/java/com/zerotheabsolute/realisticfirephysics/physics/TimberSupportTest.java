package com.zerotheabsolute.realisticfirephysics.physics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class TimberSupportTest {
    static final class World implements TimberSupport.Access {
        final Set<BlockPos> logs = new HashSet<>(),
                supports = new HashSet<>(),
                unloaded = new HashSet<>();

        public boolean loaded(BlockPos p) {
            return !unloaded.contains(p);
        }

        public boolean log(BlockPos p) {
            return logs.contains(p);
        }

        public boolean supports(BlockPos p) {
            return supports.contains(p);
        }
    }

    @Test
    void anotherRootHoldsTheEntireBridge() {
        var w = new World();
        for (int x = 0; x < 9; x++) w.logs.add(new BlockPos(x, 4, 0));
        w.supports.add(new BlockPos(8, 3, 0));
        assertEquals(
                TimberSupport.Result.SUPPORTED,
                TimberSupport.find(new BlockPos(0, 4, 0), w, 64).result());
        w.supports.clear();
        assertEquals(9, TimberSupport.find(new BlockPos(0, 4, 0), w, 64).logs().size());
        assertEquals(
                TimberSupport.Result.DETACHED,
                TimberSupport.find(new BlockPos(0, 4, 0), w, 64).result());
    }

    @Test
    void unknownChunkPreventsPartialDetachment() {
        var w = new World();
        w.logs.add(new BlockPos(15, 2, 0));
        w.unloaded.add(new BlockPos(16, 2, 0));
        assertEquals(
                TimberSupport.Result.UNLOADED,
                TimberSupport.find(new BlockPos(15, 2, 0), w, 64).result());
    }

    @Test
    void oversizedBuildIsNotPartiallyRemoved() {
        var w = new World();
        for (int x = 0; x < 100; x++) w.logs.add(new BlockPos(x, 2, 0));
        var result = TimberSupport.find(new BlockPos(0, 2, 0), w, 16);
        assertEquals(TimberSupport.Result.TOO_LARGE, result.result());
        assertEquals(16, result.visited());
    }

    @Test
    void cyclesAreVisitedOnce() {
        var w = new World();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) w.logs.add(new BlockPos(x, 2, z));
        var result = TimberSupport.find(new BlockPos(0, 2, 0), w, 16);
        assertEquals(TimberSupport.Result.DETACHED, result.result());
        assertEquals(16, result.visited());
    }
}
