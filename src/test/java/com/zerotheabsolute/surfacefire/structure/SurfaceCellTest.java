package com.zerotheabsolute.surfacefire.structure;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

import java.util.Set;

class SurfaceCellTest {
    private SurfaceCell.SurfaceAccess terrain(BlockPos... blocks) {
        var occupied = Set.of(blocks);
        return new SurfaceCell.SurfaceAccess() {
            public boolean burnable(BlockPos p) {
                return occupied.contains(p);
            }

            public boolean exposed(BlockPos p, Direction face) {
                return !occupied.contains(p.relative(face));
            }
        };
    }

    @Test
    void allFaceCellsRoundTripWithoutCoordinateAliasing() {
        var block = new BlockPos(-30_000_000, -63, 30_000_000);
        for (int i = 0; i < SurfaceCell.BLOCK_CELLS; i++) {
            var cell = SurfaceCell.fromIndex(block, i);
            assertEquals(i, cell.index());
            assertEquals(cell, SurfaceCell.at(block, cell.face(), cell.center()));
        }
    }

    @Test
    void frontCrossesAdjacentLogsOnTheSameFace() {
        var a = new SurfaceCell(BlockPos.ZERO, Direction.UP, 7, 3);
        assertEquals(
                new SurfaceCell(new BlockPos(1, 0, 0), Direction.UP, 0, 3),
                a.next(1, 0, terrain(BlockPos.ZERO, new BlockPos(1, 0, 0))));
    }

    @Test
    void frontWrapsAroundAnExposedOuterCorner() {
        var a = new SurfaceCell(BlockPos.ZERO, Direction.UP, 7, 3);
        assertEquals(
                new SurfaceCell(BlockPos.ZERO, Direction.EAST, 3, 7),
                a.next(1, 0, terrain(BlockPos.ZERO)));
    }

    @Test
    void frontClimbsAnInsideCorner() {
        var a = new SurfaceCell(BlockPos.ZERO, Direction.UP, 7, 3);
        var raised = new BlockPos(1, 1, 0);
        assertEquals(
                new SurfaceCell(raised, Direction.WEST, 3, 0),
                a.next(1, 0, terrain(BlockPos.ZERO, new BlockPos(1, 0, 0), raised)));
    }

    @Test
    void frontDoesNotCrossANonflammableObstruction() {
        var a = new SurfaceCell(BlockPos.ZERO, Direction.UP, 7, 3);
        var blocked =
                new SurfaceCell.SurfaceAccess() {
                    public boolean burnable(BlockPos p) {
                        return p.equals(BlockPos.ZERO);
                    }

                    public boolean exposed(BlockPos p, Direction f) {
                        return f != Direction.EAST;
                    }
                };
        assertNull(a.next(1, 0, blocked));
    }
}
