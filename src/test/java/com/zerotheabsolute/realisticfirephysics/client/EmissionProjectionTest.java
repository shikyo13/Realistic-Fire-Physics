package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class EmissionProjectionTest {
    @Test
    void handEmissionKeepsItsScreenPositionAcrossDifferentFieldsOfView() {
        var hand = new Matrix4f().perspective((float) Math.toRadians(70), 1.7f, .05f, 128);
        var world = new Matrix4f().perspective((float) Math.toRadians(95), 1.7f, .05f, 128);
        var view = new Matrix4f().rotateX(.23f).rotateY(.72f);
        var point = new Vector3f(.4f, -.25f, -.7f);
        new Matrix4f(view).invert().transformPosition(point);
        var before = new Vector3f(point);
        view.transformPosition(before);
        hand.transformProject(before);
        var inverse = new Matrix4f(world).mul(view).invert();
        EmissionProjection.handToWorld(point, view, hand, world, inverse);
        view.transformPosition(point);
        world.transformProject(point);
        assertEquals(before.x, point.x, 1e-5);
        assertEquals(before.y, point.y, 1e-5);
        assertEquals(before.z, point.z, 1e-5);
    }

    @Test
    void shaderHandDepthDoesNotPullParticlesIntoTheCamera() {
        var world = new Matrix4f().perspective((float) Math.toRadians(90), 1.7f, .05f, 128);
        var hand =
                new Matrix4f()
                        .scaling(1, 1, .125f)
                        .mul(
                                new Matrix4f()
                                        .perspective((float) Math.toRadians(70), 1.7f, .05f, 128));
        for (int yaw = 0; yaw < 360; yaw += 15) {
            var view = new Matrix4f().rotateX(.23f).rotateY((float) Math.toRadians(yaw));
            var point = new Vector3f(.3f, -.25f, -.65f);
            var expected = hand.transformProject(new Vector3f(point));
            EmissionProjection.handToWorld(
                    point, new Matrix4f(), hand, world, new Matrix4f(world).mul(view).invert());
            view.transformPosition(point);
            assertEquals(-.65f, point.z, .0001f);
            world.transformProject(point);
            assertEquals(expected.x, point.x, .0001f);
            assertEquals(expected.y, point.y, .0001f);
        }
    }
}
