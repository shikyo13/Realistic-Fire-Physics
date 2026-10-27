package com.zerotheabsolute.surfacefire.structure;

import com.zerotheabsolute.surfacefire.FireSettings;
import com.zerotheabsolute.surfacefire.lighting.FireEmission;
import com.zerotheabsolute.surfacefire.lighting.LightCluster;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/** Shared authoritative surface history; clients derive smooth animation from the same clock. */
public final class BurningBlock {
    public static final long UNBURNED = -1;
    public final BlockPos pos;
    public final BlockState state;
    public final StructureFuel fuel;
    public final long[] ignition = new long[SurfaceCell.BLOCK_CELLS];
    public boolean quenched;
    public long outbreak;
    public long firstIgnition = Long.MAX_VALUE, lastIgnition = UNBURNED;
    public int count;
    private final long[] faceLast = new long[6];
    private final double[] faceU = new double[6], faceV = new double[6];
    private final int[] faceCount = new int[6];

    public BurningBlock(BlockPos pos, BlockState state, StructureFuel fuel) {
        this.pos = pos.immutable();
        this.state = state;
        this.fuel = fuel;
        Arrays.fill(ignition, UNBURNED);
        Arrays.fill(faceLast, UNBURNED);
    }

    public boolean ignite(int index, long now) {
        if (index < 0 || index >= ignition.length || ignition[index] != UNBURNED) return false;
        ignition[index] = now;
        firstIgnition = Math.min(firstIgnition, now);
        lastIgnition = Math.max(lastIgnition, now);
        count++;
        int face = index / SurfaceCell.FACE_CELLS;
        faceCount[face]++;
        faceU[face] += (index % SurfaceCell.WIDTH + .5) / SurfaceCell.WIDTH;
        faceV[face] +=
                (index % SurfaceCell.FACE_CELLS / SurfaceCell.WIDTH + .5) / SurfaceCell.WIDTH;
        faceLast[face] = Math.max(faceLast[face], now);
        return true;
    }

    public boolean burning(int index, long now) {
        return !quenched && ignition[index] != UNBURNED && now - ignition[index] < fuel.flameTicks;
    }

    /** Leaf support and waterlogging updates do not replace the scorched material. */
    public boolean matches(BlockState current) {
        if (!current.is(state.getBlock())) return false;
        if (fuel == StructureFuel.LEAVES) return true;
        var waterlogged =
                net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
        if (current.hasProperty(waterlogged))
            current = current.setValue(waterlogged, state.getValue(waterlogged));
        return current.equals(state);
    }

    public long coolAt() {
        return lastIgnition + fuel.flameTicks + FireSettings.SMOLDER_TICKS;
    }

    /** Six fractional sources follow the lit faces, including vertical trunks and undersides. */
    public void emitLight(long now, Long2ByteMap nodes) {
        emitLight(now, nodes, (x, y, z, px, py, pz) -> true);
    }

    public void emitLight(long now, Long2ByteMap nodes, LightCluster.Visibility visibility) {
        if (quenched || now >= coolAt()) return;
        for (Direction face : Direction.values()) {
            int f = face.get3DDataValue();
            if (faceCount[f] == 0) continue;
            long flameUntil = faceLast[f] + fuel.flameTicks;
            double strength =
                    FireEmission.strength(flameUntil, flameUntil + FireSettings.SMOLDER_TICKS, now);
            if (strength <= 0) continue;
            var cell = new SurfaceCell(pos, face, 0, 0);
            Vec3 center =
                    cell.point(faceU[f] / faceCount[f], faceV[f] / faceCount[f])
                            .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.2));
            double intensity = FireEmission.coverage(strength, faceCount[f], now <= flameUntil);
            new LightCluster.Source(center.x, center.y, center.z, intensity)
                    .emit(
                            (x, y, z, light) -> {
                                long key = BlockPos.asLong(x, y, z);
                                if (light > nodes.get(key)) nodes.put(key, (byte) light);
                            },
                            visibility);
        }
    }
}
