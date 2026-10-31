package com.zerotheabsolute.realisticfirephysics;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.realisticfirephysics.FireField.Site;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.util.Map;

class TorchLandingTest {
    private TorchLanding.Target find(Vec3 position, Map<BlockPos, TorchLanding.Fuel> fuel) {
        return TorchLanding.find(
                position,
                new AABB(
                        position.x - .125,
                        position.y,
                        position.z - .125,
                        position.x + .125,
                        position.y + .25,
                        position.z + .125),
                fuel::get);
    }

    @Test
    void centeredTorchUsesItsGroundSurface() {
        assertEquals(
                new TorchLanding.Target(new Site(4, 10, 4), TorchLanding.Fuel.GROUND),
                find(
                        new Vec3(.5, 11, .5),
                        Map.of(new BlockPos(0, 10, 0), TorchLanding.Fuel.GROUND)));
    }

    @Test
    void overhangingTorchFindsGrassUnderItsFootprint() {
        assertEquals(
                new TorchLanding.Target(new Site(7, 10, 4), TorchLanding.Fuel.GROUND),
                find(
                        new Vec3(1.05, 11, .5),
                        Map.of(new BlockPos(0, 10, 0), TorchLanding.Fuel.GROUND)));
    }

    @Test
    void overhangingTorchFindsLeavesAtNegativeCoordinates() {
        assertEquals(
                new TorchLanding.Target(new Site(-8, 10, -4), TorchLanding.Fuel.LEAVES),
                find(
                        new Vec3(-1.05, 11, -.5),
                        Map.of(new BlockPos(-1, 10, -1), TorchLanding.Fuel.LEAVES)));
    }

    @Test
    void centeredTorchCanSelectLeafFuel() {
        var target =
                find(
                        new Vec3(.5, 11, .5),
                        Map.of(new BlockPos(0, 10, 0), TorchLanding.Fuel.LEAVES));
        assertEquals(new TorchLanding.Target(new Site(4, 10, 4), TorchLanding.Fuel.LEAVES), target);
    }

    @Test
    void doesNotUseFuelOutsideTheFootprintOrBelowAnotherLevel() {
        var position = new Vec3(1.125, 11, .5);
        assertNull(find(position, Map.of(new BlockPos(0, 10, 0), TorchLanding.Fuel.GROUND)));
        assertNull(find(position, Map.of(new BlockPos(1, 9, 0), TorchLanding.Fuel.LEAVES)));
    }

    @Test
    void prefersFuelUnderTheCenterWhenMoreThanOneBlockIsTouched() {
        var fuels =
                Map.of(
                        new BlockPos(0, 10, 0),
                        TorchLanding.Fuel.LEAVES,
                        new BlockPos(1, 10, 0),
                        TorchLanding.Fuel.GROUND);
        assertEquals(TorchLanding.Fuel.GROUND, find(new Vec3(1.05, 11, .5), fuels).fuel());
    }
}
