package com.zerotheabsolute.surfacefire.structure;

import com.zerotheabsolute.surfacefire.FireField;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Vanilla ignition sources feed the surface simulation instead of a second spread algorithm. */
public final class VanillaFireConversion {
    private VanillaFireConversion() {}

    public static boolean convert(ServerLevel level, BlockPos fire) {
        if (!level.getBlockState(fire).is(Blocks.FIRE)) return false;
        boolean managed = false;
        var field = StructureField.get(level);
        for (Direction face : StructureTerrain.FACES) {
            var neighbor = fire.relative(face);
            if (StructureFuel.at(level, neighbor) == null) continue;
            managed = true;
            field.igniteFrom(level, neighbor, Vec3.atCenterOf(fire));
        }
        var ground = fire.below();
        // Fire occupies the air over grass while this hook runs.
        if (level.getBlockState(ground).is(Blocks.GRASS_BLOCK)
                || level.getBlockState(ground).is(Blocks.MOSS_BLOCK)
                || level.getBlockState(ground).is(Blocks.PODZOL)
                || level.getBlockState(ground).is(Blocks.MYCELIUM)) managed = true;
        if (!managed) return false;
        level.removeBlock(fire, false);
        if (FireField.fuel(level, ground) > 0)
            FireField.get(level)
                    .ignite(
                            level,
                            new FireField.Site(
                                    ground.getX() * 8 + 4, ground.getY(), ground.getZ() * 8 + 4));
        return true;
    }
}
