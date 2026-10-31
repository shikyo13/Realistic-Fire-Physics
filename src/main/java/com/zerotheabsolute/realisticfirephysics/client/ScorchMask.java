package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.FuelRules;
import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/** Immutable surface coverage shared by terrain workers, including foliage's interior. */
final class ScorchMask {
    private static final Direction[] FACES = Direction.values();
    private static final int WIDTH = SurfaceCell.WIDTH;
    private final Block block;
    private final float[] coverage;
    private final float[] drying;
    private final int[] faceSubdivisions = new int[FACES.length];
    private int charredFaces;
    final boolean foliage;

    ScorchMask(Block block, float[] coverage, float[] drying, boolean foliage) {
        this.block = block;
        this.coverage = coverage.clone();
        this.drying = drying == null ? null : drying.clone();
        this.foliage = foliage;
        for (var face : FACES) {
            faceSubdivisions[face.get3DDataValue()] = calculateSubdivisions(face);
            if (calculateUniformChar(face)) charredFaces |= 1 << face.get3DDataValue();
        }
    }

    boolean matches(BlockState state) {
        return block == null ? FuelRules.ground(state) : state.is(block);
    }

    boolean ground() {
        return block == null;
    }

    boolean sameCoverage(ScorchMask other) {
        return other != null
                && block == other.block
                && foliage == other.foliage
                && Arrays.equals(coverage, other.coverage)
                && Arrays.equals(drying, other.drying);
    }

    float charAt(Direction face, double x, double y, double z) {
        if (block == null) return face == Direction.UP ? corner(coverage, x, z) : 0;
        if (!foliage) return surface(face, x, y, z);
        // Cutout leaves represent foliage throughout a block, not a solid six-sided shell.
        float amount = 0;
        for (var direction : FACES) amount = Math.max(amount, surface(direction, x, y, z));
        return amount;
    }

    float dryAt(Direction face, double x, double z) {
        return drying != null && face == Direction.UP ? corner(drying, x, z) : 0;
    }

    int subdivisions(Direction face) {
        return faceSubdivisions[face.get3DDataValue()];
    }

    private int calculateSubdivisions(Direction face) {
        if (foliage) return WIDTH;
        int offset = block == null ? 0 : face.get3DDataValue() * SurfaceCell.FACE_CELLS;
        int length = block == null ? coverage.length : SurfaceCell.FACE_CELLS;
        if (block == null && face != Direction.UP) return 0;
        float min = 1, max = 0, dryMin = 1, dryMax = 0;
        for (int i = 0; i < length; i++) {
            min = Math.min(min, coverage[offset + i]);
            max = Math.max(max, coverage[offset + i]);
            if (drying != null) {
                dryMin = Math.min(dryMin, drying[i]);
                dryMax = Math.max(dryMax, drying[i]);
            }
        }
        if (max == 0 && (drying == null || dryMax == 0)) return 0;
        return min == max && (drying == null || dryMin == dryMax) ? 1 : WIDTH;
    }

    boolean uniformlyCharred(Direction face) {
        return (charredFaces & (1 << face.get3DDataValue())) != 0;
    }

    private boolean calculateUniformChar(Direction face) {
        if (block == null && face != Direction.UP) return false;
        int first = block == null || foliage ? 0 : face.get3DDataValue();
        int last = foliage ? FACES.length : first + 1;
        int length = block == null ? coverage.length : SurfaceCell.FACE_CELLS;
        for (int f = first; f < last; f++) {
            boolean full = true;
            for (int i = 0; i < length; i++)
                if (coverage[f * length + i] < 1) {
                    full = false;
                    break;
                }
            if (full) return true;
        }
        return false;
    }

    private float surface(Direction face, double x, double y, double z) {
        double u = face.getAxis() == Direction.Axis.X ? z : x;
        double v = face.getAxis() == Direction.Axis.Y ? z : y;
        return sample(
                coverage,
                face.get3DDataValue() * SurfaceCell.FACE_CELLS,
                WIDTH,
                Math.clamp(u * WIDTH - .5, 0, WIDTH - 1),
                Math.clamp(v * WIDTH - .5, 0, WIDTH - 1));
    }

    private static float corner(float[] values, double x, double z) {
        return sample(
                values,
                0,
                WIDTH + 1,
                Math.clamp(x * WIDTH, 0, WIDTH),
                Math.clamp(z * WIDTH, 0, WIDTH));
    }

    private static float sample(float[] values, int offset, int width, double x, double y) {
        int x0 = (int) x,
                y0 = (int) y,
                x1 = Math.min(x0 + 1, width - 1),
                y1 = Math.min(y0 + 1, width - 1);
        float a = values[offset + y0 * width + x0], b = values[offset + y0 * width + x1];
        float c = values[offset + y1 * width + x0], d = values[offset + y1 * width + x1];
        return (float)
                ((a + (b - a) * (x - x0)) * (1 - (y - y0)) + (c + (d - c) * (x - x0)) * (y - y0));
    }
}
