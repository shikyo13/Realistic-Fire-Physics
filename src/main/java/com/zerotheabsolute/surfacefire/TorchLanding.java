package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.FireField.Site;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

/** Finds fuel touching a settled torch's footprint, including overhanging edges. */
final class TorchLanding {
    private static final double FOOT_CONTACT_EPSILON = .02;

    enum Fuel { GROUND, LEAVES }

    record Target(Site site, Fuel fuel) {}

    private TorchLanding() {}

    static Target find(Vec3 position, AABB bounds, Function<BlockPos, Fuel> fuelAt) {
        int y = (int) Math.floor(position.y - FOOT_CONTACT_EPSILON);
        int centerX = (int) Math.floor(position.x);
        int centerZ = (int) Math.floor(position.z);
        var center = new BlockPos(centerX, y, centerZ);
        var fuel = fuelAt.apply(center);
        if (fuel != null) return target(position, center, fuel);

        int minX = (int) Math.floor(bounds.minX);
        int maxX = (int) Math.floor(Math.nextDown(bounds.maxX));
        int minZ = (int) Math.floor(bounds.minZ);
        int maxZ = (int) Math.floor(Math.nextDown(bounds.maxZ));
        Target closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++) {
                if (x == centerX && z == centerZ) continue;
                var block = new BlockPos(x, y, z);
                fuel = fuelAt.apply(block);
                if (fuel == null) continue;
                double dx = Math.max(0, Math.max(x - position.x, position.x - (x + 1)));
                double dz = Math.max(0, Math.max(z - position.z, position.z - (z + 1)));
                double distance = dx * dx + dz * dz;
                if (distance < closestDistance) {
                    closest = target(position, block, fuel);
                    closestDistance = distance;
                }
            }
        return closest;
    }

    private static Target target(Vec3 position, BlockPos block, Fuel fuel) {
        int cells = FireSettings.CELLS_PER_BLOCK;
        int x =
                Math.clamp(
                        (int) Math.floor(position.x * cells),
                        block.getX() * cells,
                        block.getX() * cells + cells - 1);
        int z =
                Math.clamp(
                        (int) Math.floor(position.z * cells),
                        block.getZ() * cells,
                        block.getZ() * cells + cells - 1);
        return new Target(new Site(x, block.getY(), z), fuel);
    }
}
