package com.zerotheabsolute.surfacefire.structure;

import com.zerotheabsolute.surfacefire.FireSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** One fuel cell on a block face. Adjacency follows flat faces and convex/concave corners. */
public record SurfaceCell(BlockPos block, Direction face, int u, int v) {
    public static final int WIDTH = FireSettings.CELLS_PER_BLOCK;
    public static final int FACE_CELLS = WIDTH * WIDTH;
    public static final int BLOCK_CELLS = FACE_CELLS * 6;
    private static final double HALF_CELL = .5 / WIDTH;

    public SurfaceCell {
        block = block.immutable();
        if (u < 0 || u >= WIDTH || v < 0 || v >= WIDTH)
            throw new IllegalArgumentException("Surface cell outside face");
    }

    public int index() {
        return face.get3DDataValue() * FACE_CELLS + v * WIDTH + u;
    }

    public static SurfaceCell fromIndex(BlockPos block, int index) {
        if (index < 0 || index >= BLOCK_CELLS)
            throw new IllegalArgumentException("Invalid surface index");
        return new SurfaceCell(
                block,
                Direction.from3DDataValue(index / FACE_CELLS),
                index % WIDTH,
                index % FACE_CELLS / WIDTH);
    }

    public Vec3 center() {
        return point((u + .5) / WIDTH, (v + .5) / WIDTH);
    }

    public Vec3 point(double faceU, double faceV) {
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        return switch (face.getAxis()) {
            case X -> new Vec3(block.getX() + plane, block.getY() + faceV, block.getZ() + faceU);
            case Y -> new Vec3(block.getX() + faceU, block.getY() + plane, block.getZ() + faceV);
            case Z -> new Vec3(block.getX() + faceU, block.getY() + faceV, block.getZ() + plane);
        };
    }

    public static SurfaceCell at(BlockPos block, Direction face, Vec3 point) {
        double localU =
                face.getAxis() == Direction.Axis.X
                        ? point.z - block.getZ()
                        : point.x - block.getX();
        double localV =
                face.getAxis() == Direction.Axis.Y
                        ? point.z - block.getZ()
                        : point.y - block.getY();
        return new SurfaceCell(block, face, cell(localU), cell(localV));
    }

    private static int cell(double value) {
        return Math.clamp((int) Math.floor(value * WIDTH), 0, WIDTH - 1);
    }

    public SurfaceCell next(int du, int dv, SurfaceAccess terrain) {
        if (Math.abs(du) + Math.abs(dv) != 1)
            throw new IllegalArgumentException("Expected one surface step");
        int nextU = u + du, nextV = v + dv;
        if (nextU >= 0 && nextU < WIDTH && nextV >= 0 && nextV < WIDTH)
            return new SurfaceCell(block, face, nextU, nextV);
        Direction.Axis axis =
                du != 0
                        ? (face.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X)
                        : (face.getAxis() == Direction.Axis.Y
                                ? Direction.Axis.Z
                                : Direction.Axis.Y);
        Direction tangent =
                Direction.fromAxisAndDirection(
                        axis,
                        du + dv > 0
                                ? Direction.AxisDirection.POSITIVE
                                : Direction.AxisDirection.NEGATIVE);
        Vec3 edge = center().add(Vec3.atLowerCornerOf(tangent.getNormal()).scale(HALF_CELL));
        var adjacent = block.relative(tangent);
        var raised = adjacent.relative(face);
        if (terrain.burnable(raised) && terrain.exposed(raised, tangent.getOpposite()))
            return at(
                    raised,
                    tangent.getOpposite(),
                    edge.add(Vec3.atLowerCornerOf(face.getNormal()).scale(HALF_CELL)));
        if (terrain.burnable(adjacent) && terrain.exposed(adjacent, face))
            return at(
                    adjacent,
                    face,
                    edge.add(Vec3.atLowerCornerOf(tangent.getNormal()).scale(HALF_CELL)));
        if (terrain.exposed(block, tangent))
            return at(
                    block,
                    tangent,
                    edge.subtract(Vec3.atLowerCornerOf(face.getNormal()).scale(HALF_CELL)));
        return null;
    }

    public interface SurfaceAccess {
        boolean burnable(BlockPos block);

        boolean exposed(BlockPos block, Direction face);
    }
}
