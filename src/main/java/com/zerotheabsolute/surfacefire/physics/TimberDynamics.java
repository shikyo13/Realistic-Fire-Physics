package com.zerotheabsolute.surfacefire.physics;

import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Fixed-step rigid-body motion. Velocities use blocks and radians per Minecraft tick. */
public final class TimberDynamics {
    private static final double TICKS_PER_SECOND = 20;
    public static final double GRAVITY = 9.81 / (TICKS_PER_SECOND * TICKS_PER_SECOND);
    public static final double MAX_SPEED = .8;
    private static final double MAX_ANGULAR_SPEED = .065;
    private static final double RESTITUTION = .08;
    private static final double FRICTION = .58;
    private static final double CONTACT_SLOP = .0002;
    public Vec3 position = Vec3.ZERO;
    public Vec3 velocity = Vec3.ZERO;
    public Vec3 angularVelocity = Vec3.ZERO;
    public final Quaterniond rotation = new Quaterniond();
    private final Quaterniond stepRotation = new Quaterniond();
    private final Vector3d scratch = new Vector3d();
    private double inverseInertia = 1;

    public void inertia(double meanRadiusSquared) {
        inverseInertia = 1 / Math.max(.2, meanRadiusSquared * 2 / 3 + 1.0 / 6);
    }

    public Vec3 rotate(Vec3 local) {
        rotation.transform(scratch.set(local.x, local.y, local.z));
        return new Vec3(scratch.x, scratch.y, scratch.z);
    }

    public void integrate(double dt) {
        velocity = limited(velocity.add(0, -GRAVITY * dt, 0), MAX_SPEED);
        position = position.add(velocity.scale(dt));
        angularVelocity = limited(angularVelocity, MAX_ANGULAR_SPEED);
        double speed = angularVelocity.length();
        if (speed > 1e-9) {
            stepRotation.rotationAxis(
                    speed * dt,
                    angularVelocity.x / speed,
                    angularVelocity.y / speed,
                    angularVelocity.z / speed);
            rotation.premul(stepRotation).normalize();
        }
    }

    public void resolve(TimberCollision.Contact contact) {
        Vec3 n = contact.normal();
        position = position.add(n.scale(contact.depth() + CONTACT_SLOP));
        Vec3 r = contact.point().subtract(position);
        Vec3 at = velocity.add(angularVelocity.cross(r));
        double speed = at.dot(n);
        if (speed >= 0) return;
        double impulse = -(1 + RESTITUTION) * speed / (1 + r.cross(n).lengthSqr() * inverseInertia);
        apply(r, n.scale(impulse));
        Vec3 tangent = at.subtract(n.scale(speed));
        double slip = tangent.length();
        if (slip > 1e-7) {
            tangent = tangent.scale(1 / slip);
            double friction =
                    Math.min(
                            impulse * FRICTION,
                            slip / (1 + r.cross(tangent).lengthSqr() * inverseInertia));
            apply(r, tangent.scale(-friction));
        }
    }

    private void apply(Vec3 offset, Vec3 impulse) {
        velocity = velocity.add(impulse);
        angularVelocity = angularVelocity.add(offset.cross(impulse).scale(inverseInertia));
    }

    public boolean resting() {
        return velocity.lengthSqr() < .0004 && angularVelocity.lengthSqr() < .000025;
    }

    private static Vec3 limited(Vec3 value, double max) {
        double length = value.length();
        return length > max ? value.scale(max / length) : value;
    }
}
