package com.zerotheabsolute.realisticfirephysics.physics;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Separating-axis contact between a rotating unit log and a terrain collision box. */
public final class TimberCollision {
    private static final double HALF = .499;
    private static final double AXIS_EPSILON = 1e-8;
    private final Vec3[] axes = new Vec3[3];
    private final Vector3d scratch = new Vector3d();
    private Vec3 normal;
    private double depth;

    public record Contact(Vec3 normal, double depth, Vec3 point) {}

    public void orient(Quaterniond rotation) {
        for (int i = 0; i < 3; i++) {
            scratch.set(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0);
            rotation.transform(scratch);
            axes[i] = new Vec3(scratch.x, scratch.y, scratch.z);
        }
    }

    public AABB bounds(Vec3 center) {
        double x = HALF * (Math.abs(axes[0].x) + Math.abs(axes[1].x) + Math.abs(axes[2].x));
        double y = HALF * (Math.abs(axes[0].y) + Math.abs(axes[1].y) + Math.abs(axes[2].y));
        double z = HALF * (Math.abs(axes[0].z) + Math.abs(axes[1].z) + Math.abs(axes[2].z));
        return new AABB(
                center.x - x, center.y - y, center.z - z, center.x + x, center.y + y, center.z + z);
    }

    public Contact contact(Vec3 center, AABB terrain) {
        Vec3 delta = center.subtract(terrain.getCenter());
        Vec3 half =
                new Vec3(terrain.getXsize() * .5, terrain.getYsize() * .5, terrain.getZsize() * .5);
        depth = Double.POSITIVE_INFINITY;
        Vec3[] world = WORLD_AXES;
        for (Vec3 a : world) if (!separated(a, delta, half)) return null;
        for (Vec3 a : axes) {
            if (!separated(a, delta, half)) return null;
            for (Vec3 b : world) if (!separated(a.cross(b), delta, half)) return null;
        }
        // The support point averages an edge/face when its axis is perpendicular to the normal.
        Vec3 point = center;
        for (Vec3 axis : axes) {
            double dot = axis.dot(normal);
            if (Math.abs(dot) > .02) point = point.subtract(axis.scale(Math.copySign(HALF, dot)));
        }
        return new Contact(normal, depth, point);
    }

    private boolean separated(Vec3 axis, Vec3 delta, Vec3 half) {
        double length = axis.length();
        if (length < AXIS_EPSILON) return true;
        axis = axis.scale(1 / length);
        double radius =
                HALF
                        * (Math.abs(axis.dot(axes[0]))
                                + Math.abs(axis.dot(axes[1]))
                                + Math.abs(axis.dot(axes[2])));
        radius += Math.abs(axis.x) * half.x + Math.abs(axis.y) * half.y + Math.abs(axis.z) * half.z;
        double overlap = radius - Math.abs(delta.dot(axis));
        if (overlap <= 0) return false;
        if (overlap < depth) {
            depth = overlap;
            normal = delta.dot(axis) < 0 ? axis.scale(-1) : axis;
        }
        return true;
    }

    private static final Vec3[] WORLD_AXES = {
        new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)
    };
}
