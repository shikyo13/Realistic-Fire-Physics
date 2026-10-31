package com.zerotheabsolute.realisticfirephysics.physics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

class TimberCanopyTest {
    private static final class World implements TimberCanopy.Access {
        final Set<BlockPos> logs = new HashSet<>(),
                leaves = new HashSet<>(),
                inactive = new HashSet<>();
        final HashMap<BlockPos, Integer> probes = new HashMap<>();

        public TimberCanopy.Probe inspect(BlockPos position) {
            probes.merge(position, 1, Integer::sum);
            if (inactive.contains(position)) return null;
            int distance = logs.stream().mapToInt(p -> p.distManhattan(position)).min().orElse(7);
            return new TimberCanopy.Probe(
                    leaves.contains(position), logs.contains(position), Math.min(7, distance));
        }
    }

    @Test
    void entireIsolatedCrownIncludesOuterLeafLayer() {
        var world = new World();
        for (int y = 2; y <= 18; y++) world.logs.add(new BlockPos(0, y, 0));
        for (int x = -3; x <= 3; x++)
            for (int y = 16; y <= 19; y++)
                for (int z = -3; z <= 3; z++) world.leaves.add(new BlockPos(x, y, z));
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) world.leaves.add(new BlockPos(x, 20, z));
        world.leaves.removeAll(world.logs);
        var canopy =
                TimberCanopy.find(world.logs, world, TimberStorage.MAX_PARTS - world.logs.size());
        assertEquals(TimberCanopy.Result.COMPLETE, canopy.result());
        assertEquals(world.leaves, canopy.leaves());
        assertTrue(canopy.leaves().size() > 192);
        assertEquals(world.probes.size(), canopy.probes());
        assertTrue(world.probes.values().stream().allMatch(n -> n == 1));
    }

    @Test
    void sharedLeavesStayWithSupportedNeighborBeyondSearchBoundary() {
        var world = new World();
        var falling = new BlockPos(0, 6, 0);
        world.logs.add(falling);
        world.logs.add(new BlockPos(10, 6, 0));
        for (int x = 1; x < 10; x++) world.leaves.add(new BlockPos(x, 6, 0));
        var canopy = TimberCanopy.find(Set.of(falling), world, 100);
        assertEquals(TimberCanopy.Result.COMPLETE, canopy.result());
        assertEquals(
                Set.of(new BlockPos(1, 6, 0), new BlockPos(2, 6, 0), new BlockPos(3, 6, 0)),
                canopy.leaves());
    }

    @Test
    void aNeighboringLogProtectsLeavesThroughoutSharedCanopy() {
        var world = new World();
        var falling = new BlockPos(0, 6, 0);
        world.logs.add(falling);
        world.logs.add(new BlockPos(6, 6, 0));
        for (int x = 1; x < 6; x++) world.leaves.add(new BlockPos(x, 6, 0));
        var canopy = TimberCanopy.find(Set.of(falling), world, 100);
        assertEquals(TimberCanopy.Result.COMPLETE, canopy.result());
        assertTrue(canopy.leaves().isEmpty());
    }

    @Test
    void incompleteTerrainDefersWholeCanopy() {
        var world = new World();
        var falling = new BlockPos(15, 8, 0);
        world.logs.add(falling);
        world.inactive.add(falling.east());
        var canopy = TimberCanopy.find(world.logs, world, 100);
        assertEquals(TimberCanopy.Result.INACTIVE, canopy.result());
        assertTrue(canopy.leaves().isEmpty());
    }

    @Test
    void oversizedCanopyNeverSilentlyTruncates() {
        var world = new World();
        world.logs.add(BlockPos.ZERO);
        for (int y = 1; y <= 7; y++) world.leaves.add(new BlockPos(0, y, 0));
        var canopy = TimberCanopy.find(world.logs, world, 6);
        assertEquals(TimberCanopy.Result.TOO_LARGE, canopy.result());
        assertTrue(canopy.leaves().isEmpty());
    }
}
