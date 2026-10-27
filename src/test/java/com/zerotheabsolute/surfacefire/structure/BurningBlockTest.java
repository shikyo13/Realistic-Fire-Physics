package com.zerotheabsolute.surfacefire.structure;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BurningBlockTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void losingTheSupportingLogPreservesLeafBurnHistory() {
        var supported = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        var burn = new BurningBlock(BlockPos.ZERO, supported, StructureFuel.LEAVES);
        burn.ignite(0, 100);
        assertTrue(burn.matches(supported.setValue(LeavesBlock.DISTANCE, 7)));
        assertTrue(burn.burning(0, 110));
    }

    @Test
    void WaterloggingKeepsHistoryForQuenching() {
        var state = Blocks.OAK_LEAVES.defaultBlockState();
        var burn = new BurningBlock(BlockPos.ZERO, state, StructureFuel.LEAVES);
        assertTrue(burn.matches(state.setValue(LeavesBlock.WATERLOGGED, true)));
    }

    @Test
    void waterloggingStairsPreservesTheirScorchedSurfaces() {
        var state = Blocks.OAK_STAIRS.defaultBlockState();
        var burn = new BurningBlock(BlockPos.ZERO, state, StructureFuel.LIGHT);
        burn.ignite(1, 100);
        assertTrue(
                burn.matches(
                        state.setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.WATERLOGGED,
                                true)));
        assertFalse(burn.matches(Blocks.BIRCH_STAIRS.defaultBlockState()));
        assertEquals(100, burn.ignition[1]);
    }

    @Test
    void removingOrReplacingLeavesDoesNotInheritTheOldFire() {
        var burn =
                new BurningBlock(
                        BlockPos.ZERO, Blocks.OAK_LEAVES.defaultBlockState(), StructureFuel.LEAVES);
        assertFalse(burn.matches(Blocks.AIR.defaultBlockState()));
        assertFalse(burn.matches(Blocks.BIRCH_LEAVES.defaultBlockState()));
    }

    @Test
    void rotatingAReplacedLogStillInvalidatesItsOldSurfaceHistory() {
        var state =
                Blocks.OAK_LOG
                        .defaultBlockState()
                        .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        var burn = new BurningBlock(BlockPos.ZERO, state, StructureFuel.WOOD);
        assertFalse(burn.matches(state.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z)));
    }
}
