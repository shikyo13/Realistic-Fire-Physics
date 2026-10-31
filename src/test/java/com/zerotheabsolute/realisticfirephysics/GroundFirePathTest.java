package com.zerotheabsolute.realisticfirephysics;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.realisticfirephysics.FireField.Site;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class GroundFirePathTest {
    @Test
    void frontClimbsAndDescendsOneBlockWithoutLeavingTheLattice() {
        var fuel = Set.of(new BlockPos(0, 0, 0), new BlockPos(1, 1, 0), new BlockPos(2, 0, 0));
        var current = new Site(7, 0, 3);
        current = GroundFirePath.next(current, current.offset(1, 0), fuel::contains);
        assertEquals(new Site(8, 1, 3), current);
        for (int x = 9; x <= 15; x++) {
            current = GroundFirePath.next(current, current.offset(1, 0), fuel::contains);
            assertEquals(new Site(x, 1, 3), current);
        }
        assertEquals(
                new Site(16, 0, 3),
                GroundFirePath.next(current, current.offset(1, 0), fuel::contains));
    }

    @Test
    void doesNotJumpTwoBlocksOrSpreadOntoUnavailableFuel() {
        var source = new Site(7, 0, 3);
        var target = source.offset(1, 0);
        assertNull(GroundFirePath.next(source, target, Set.of(new BlockPos(1, 2, 0))::contains));
        assertNull(GroundFirePath.next(source, target, Set.<BlockPos>of()::contains));
    }

    @Test
    void flatAndDiagonalSpreadRetainCornerProtection() {
        var source = new Site(7, 0, 7);
        var target = source.offset(1, 1);
        var all = Set.of(new BlockPos(1, 0, 0), new BlockPos(0, 0, 1), new BlockPos(1, 0, 1));
        assertEquals(target, GroundFirePath.next(source, target, all::contains));
        var cornerGap = Set.of(new BlockPos(1, 0, 0), new BlockPos(1, 0, 1));
        assertNull(GroundFirePath.next(source, target, cornerGap::contains));
        var raisedCorner = Set.of(new BlockPos(1, 1, 1));
        assertNull(GroundFirePath.next(source, target, raisedCorner::contains));
    }

    @Test
    void handlesNegativeBlockBoundaries() {
        var source = new Site(0, 5, -1);
        var target = source.offset(-1, 0);
        var fuel = Set.of(new BlockPos(-1, 4, -1));
        assertEquals(new Site(-1, 4, -1), GroundFirePath.next(source, target, fuel::contains));
    }

    @Test
    void staysOnSameLevelWhenTheSurfaceContinues() {
        var source = new Site(7, 0, 3);
        var target = source.offset(1, 0);
        var fuel = Set.of(new BlockPos(1, 0, 0), new BlockPos(1, -1, 0));
        assertEquals(target, GroundFirePath.next(source, target, fuel::contains));
    }

    @Test
    void avoidsVerticalFuelChecksInsideABlock() {
        var calls = new AtomicInteger();
        assertNull(
                GroundFirePath.next(
                        new Site(2, 0, 3),
                        new Site(3, 0, 3),
                        p -> {
                            calls.incrementAndGet();
                            return false;
                        }));
        assertEquals(1, calls.get());
    }
}
