package com.zerotheabsolute.realisticfirephysics.client;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Keeps detached effects aligned when the hand and world use different projections. */
final class EmissionProjection {
    private EmissionProjection() {}

    static void handToWorld(
            Vector3f point,
            Matrix4fc modelView,
            Matrix4fc handProjection,
            Matrix4fc worldProjection,
            Matrix4fc inverseWorldClip) {
        modelView.transformPosition(point);
        float depth =
                (worldProjection.m02() * point.x
                                + worldProjection.m12() * point.y
                                + worldProjection.m22() * point.z
                                + worldProjection.m32())
                        / (worldProjection.m03() * point.x
                                + worldProjection.m13() * point.y
                                + worldProjection.m23() * point.z
                                + worldProjection.m33());
        handProjection.transformProject(point);
        // Hand depth is compressed by some shader loaders; particles need physical world depth.
        point.z = depth;
        inverseWorldClip.transformProject(point);
    }
}
