package com.zerotheabsolute.realisticfirephysics.physics;

import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** The nearest cube rotation preserves every surface cell when timber returns to blocks. */
record TimberSurfaceRotation(Direction x, Direction y, Direction z) {
    static TimberSurfaceRotation closest(Quaterniondc rotation) {
        var rx = rotation.transform(new Vector3d(1, 0, 0));
        var ry = rotation.transform(new Vector3d(0, 1, 0));
        var rz = rotation.transform(new Vector3d(0, 0, 1));
        TimberSurfaceRotation best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Direction x : Direction.values())
            for (Direction y : Direction.values()) {
                if (x.getAxis() == y.getAxis()) continue;
                var a = x.getNormal();
                var b = y.getNormal();
                var z =
                        Direction.getNearest(
                                a.getY() * b.getZ() - a.getZ() * b.getY(),
                                a.getZ() * b.getX() - a.getX() * b.getZ(),
                                a.getX() * b.getY() - a.getY() * b.getX());
                double score = dot(rx, x) + dot(ry, y) + dot(rz, z);
                if (score > bestScore) {
                    bestScore = score;
                    best = new TimberSurfaceRotation(x, y, z);
                }
            }
        return best;
    }

    private static double dot(Vector3d vector, Direction direction) {
        var n = direction.getNormal();
        return vector.x * n.getX() + vector.y * n.getY() + vector.z * n.getZ();
    }

    Direction apply(Direction face) {
        var mapped =
                switch (face.getAxis()) {
                    case X -> x;
                    case Y -> y;
                    case Z -> z;
                };
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? mapped
                : mapped.getOpposite();
    }

    int[] ages(int[] source) {
        if (source.length == 0) return source;
        if (source.length != SurfaceCell.BLOCK_CELLS)
            throw new IllegalArgumentException("Invalid timber surface grid");
        int[] result = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            var cell = SurfaceCell.fromIndex(BlockPos.ZERO, i);
            var p = cell.center().subtract(.5, .5, .5);
            var point =
                    new Vec3(
                            x.getStepX() * p.x + y.getStepX() * p.y + z.getStepX() * p.z + .5,
                            x.getStepY() * p.x + y.getStepY() * p.y + z.getStepY() * p.z + .5,
                            x.getStepZ() * p.x + y.getStepZ() * p.y + z.getStepZ() * p.z + .5);
            result[SurfaceCell.at(BlockPos.ZERO, apply(cell.face()), point).index()] = source[i];
        }
        return result;
    }
}
