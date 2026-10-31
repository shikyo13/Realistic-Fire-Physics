package com.zerotheabsolute.realisticfirephysics.structure;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/** A tick-local terrain cache: cell propagation never repeats a block's fuel/weather query. */
final class StructureTerrain implements SurfaceCell.SurfaceAccess {
    static final Direction[] FACES = Direction.values();

    record Probe(BlockState state, StructureFuel fuel, int exposed, boolean wet) {}

    private final Long2ObjectOpenHashMap<Probe> cache = new Long2ObjectOpenHashMap<>();
    private final ServerLevel level;
    private long clock = Long.MIN_VALUE;

    StructureTerrain(ServerLevel level) {
        this.level = level;
    }

    Probe probe(BlockPos pos) {
        if (clock != level.getGameTime()) {
            clock = level.getGameTime();
            cache.clear();
        }
        return cache.computeIfAbsent(pos.asLong(), key -> inspect(pos));
    }

    void invalidate(BlockPos pos) {
        cache.remove(pos.asLong());
        for (Direction d : FACES) cache.remove(pos.relative(d).asLong());
    }

    private Probe inspect(BlockPos pos) {
        if (!level.hasChunkAt(pos)) return new Probe(null, null, 0, true);
        var state = level.getBlockState(pos);
        var fuel = StructureFuel.at(level, pos);
        int exposed = 0;
        boolean wet = state.getFluidState().is(FluidTags.WATER) || level.isRainingAt(pos.above());
        for (Direction face : FACES) {
            var neighbor = pos.relative(face);
            if (!level.hasChunkAt(neighbor)) continue;
            var next = level.getBlockState(neighbor);
            wet |= next.getFluidState().is(FluidTags.WATER);
            if (!next.isSolidRender(level, neighbor)) exposed |= 1 << face.get3DDataValue();
        }
        return new Probe(state, fuel, exposed, wet);
    }

    public boolean burnable(BlockPos pos) {
        return probe(pos).fuel != null;
    }

    public boolean exposed(BlockPos pos, Direction face) {
        return (probe(pos).exposed & (1 << face.get3DDataValue())) != 0;
    }
}
