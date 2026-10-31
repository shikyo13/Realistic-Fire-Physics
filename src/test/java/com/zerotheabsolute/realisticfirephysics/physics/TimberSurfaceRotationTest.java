package com.zerotheabsolute.realisticfirephysics.physics;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

class TimberSurfaceRotationTest {
    @Test
    void identityPreservesEveryBurnClock() {
        int[] ages = new int[SurfaceCell.BLOCK_CELLS];
        Arrays.setAll(ages, i -> i);
        assertArrayEquals(ages, TimberSurfaceRotation.closest(new Quaterniond()).ages(ages));
    }

    @Test
    void fallenSideCarriesItsBurningCellsToTheTopFace() {
        int[] ages = new int[SurfaceCell.BLOCK_CELLS];
        Arrays.fill(ages, -1);
        var cell = new SurfaceCell(BlockPos.ZERO, Direction.EAST, 2, 5);
        ages[cell.index()] = 73;
        var rotation =
                TimberSurfaceRotation.closest(new Quaterniond().rotateZ((float) Math.PI / 2));
        int[] result = rotation.ages(ages);
        assertEquals(Direction.UP, rotation.apply(Direction.EAST));
        for (int i = 0; i < result.length; i++)
            if (result[i] >= 0) {
                assertEquals(Direction.UP, SurfaceCell.fromIndex(BlockPos.ZERO, i).face());
                assertEquals(73, result[i]);
            }
        assertEquals(1, Arrays.stream(result).filter(age -> age >= 0).count());
    }

    @Test
    void obliqueLandingNeverDuplicatesOrDropsSurfaceCells() {
        int[] ages = new int[SurfaceCell.BLOCK_CELLS];
        Arrays.setAll(ages, i -> i);
        for (int i = 0; i < 24; i++) {
            var rotation =
                    TimberSurfaceRotation.closest(
                            new Quaterniond().rotateXYZ(i * .31f, i * .47f, i * .13f));
            int[] mapped = rotation.ages(ages);
            Arrays.sort(mapped);
            assertArrayEquals(ages, mapped);
        }
    }
}
