package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

class ScorchMaskTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exposingTheOtherSideOfBurnedFoliageDoesNotRevealFreshGreenLeaves() {
        var cover = new float[SurfaceCell.BLOCK_CELLS];
        Arrays.fill(
                cover,
                Direction.NORTH.get3DDataValue() * 64,
                Direction.NORTH.get3DDataValue() * 64 + 64,
                1);
        var leaf = new ScorchMask(Blocks.OAK_LEAVES, cover, null, true);
        assertEquals(1, leaf.charAt(Direction.SOUTH, .5, .5, 1));
        assertEquals(1, leaf.charAt(Direction.EAST, 1, .5, .5));
        var wood = new ScorchMask(Blocks.OAK_LOG, cover, null, false);
        assertEquals(0, wood.charAt(Direction.SOUTH, .5, .5, 1));
        assertEquals(1, wood.charAt(Direction.NORTH, .5, .5, 0));
    }

    @Test
    void workersKeepAnImmutableSnapshotAndLeafTopologyStaysStable() {
        var cover = new float[SurfaceCell.BLOCK_CELLS];
        cover[0] = .5f;
        var partial = new ScorchMask(Blocks.OAK_LEAVES, cover, null, true);
        Arrays.fill(cover, 1);
        var full = new ScorchMask(Blocks.OAK_LEAVES, cover, null, true);
        assertFalse(partial.sameCoverage(full));
        for (var face : Direction.values())
            assertEquals(partial.subdivisions(face), full.subdivisions(face));
        assertEquals(0, partial.charAt(Direction.SOUTH, 1, 1, 1));
        assertFalse(partial.matches(Blocks.BIRCH_LEAVES.defaultBlockState()));
    }

    @Test
    void groundCoveragePreservesAnEdgeWithoutColoringTheSidesOrReplacementBlocks() {
        var cover = new float[81];
        for (int z = 0; z < 9; z++) for (int x = 0; x < 9; x++) cover[z * 9 + x] = x / 8f;
        var mask = new ScorchMask(null, cover, new float[81], false);
        assertEquals(.25f, mask.charAt(Direction.UP, .25, 1, .5));
        assertEquals(0, mask.charAt(Direction.SOUTH, .25, 1, .5));
        assertTrue(mask.matches(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertFalse(mask.matches(Blocks.STONE.defaultBlockState()));
        assertEquals(8, mask.subdivisions(Direction.UP));
        assertEquals(0, mask.subdivisions(Direction.SOUTH));
    }
}
