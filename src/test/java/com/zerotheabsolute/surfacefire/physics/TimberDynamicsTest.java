package com.zerotheabsolute.surfacefire.physics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class TimberDynamicsTest {
    @Test
    void rotatedLogOnlyHitsItsActualOrientedShape() {
        var shape = new TimberCollision();
        shape.orient(new Quaterniond().rotateZ(Math.PI / 4));
        assertNull(shape.contact(Vec3.ZERO, new AABB(.6, .6, -.1, .7, .7, .1)));
        assertNotNull(shape.contact(Vec3.ZERO, new AABB(-1, -1, -1, 1, -.5, 1)));
    }

    @Test
    void contactNormalPointsOutOfTheGround() {
        var shape = new TimberCollision();
        shape.orient(new Quaterniond());
        var hit = shape.contact(new Vec3(0, .4, 0), new AABB(-4, -1, -4, 4, 0, 4));
        assertNotNull(hit);
        assertEquals(1, hit.normal().y, 1e-8);
        assertEquals(.099, hit.depth(), .001);
    }

    @Test
    void freeFlightHasGravityWithoutInventedTorque() {
        var body = new TimberDynamics();
        body.position = new Vec3(0, 10, 0);
        for (int i = 0; i < 60; i++) body.integrate(1.0 / 6);
        assertTrue(body.position.y < 8.1);
        assertTrue(body.velocity.y < -.39);
        assertEquals(0, body.angularVelocity.length(), 1e-8);
    }

    @Test
    void offCenterImpactCreatesRotation() {
        var body = new TimberDynamics();
        body.inertia(5);
        body.velocity = new Vec3(0, -.5, 0);
        body.resolve(new TimberCollision.Contact(new Vec3(0, 1, 0), .01, new Vec3(1, -2, 0)));
        assertTrue(body.angularVelocity.z > 0);
        assertTrue(body.velocity.y > -.5);
    }

    @Test
    void repeatedGroundContactsRemainFiniteAndDoNotTunnel() {
        var body = new TimberDynamics();
        body.position = new Vec3(0, 6, 0);
        var shape = new TimberCollision();
        var floor = new AABB(-20, -1, -20, 20, 0, 20);
        for (int i = 0; i < 2400; i++) {
            body.integrate(1.0 / 6);
            shape.orient(body.rotation);
            var hit = shape.contact(body.position, floor);
            if (hit != null) body.resolve(hit);
        }
        assertTrue(Double.isFinite(body.position.y));
        assertTrue(body.position.y > .49);
        assertTrue(body.position.y < .52);
        assertTrue(body.resting());
    }
}
