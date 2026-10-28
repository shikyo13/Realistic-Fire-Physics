package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.Vec3;

/** Shared flame/smoke geometry for horizontal ground, vertical fuel and moving embers. */
final class FlameGeometry {
    private FlameGeometry() {}

    static void plume(
            VertexConsumer vertices,
            Vec3 camera,
            boolean textured,
            double x,
            double y,
            double z,
            double width,
            double height,
            Vec3 right,
            Vec3 up,
            float seed,
            float alpha,
            int light) {
        Vec3 depth = new Vec3(right.z, 0, -right.x);
        int frame = textured ? ShaderPackRendering.frame(FlameWave.texturePhase(x, y, z, seed)) : 0;
        // A bowed sheet has actual depth, and a narrower crossing tongue fills diagonal views.
        for (int slice = 0; slice < 3; slice++) {
            double a = slice / 3.0, b = (slice + 1) / 3.0;
            double ax =
                    x + right.x * width * (a - .5) + depth.x * width * .22 * Math.sin(a * Math.PI);
            double az =
                    z + right.z * width * (a - .5) + depth.z * width * .22 * Math.sin(a * Math.PI);
            double bx =
                    x + right.x * width * (b - .5) + depth.x * width * .22 * Math.sin(b * Math.PI);
            double bz =
                    z + right.z * width * (b - .5) + depth.z * width * .22 * Math.sin(b * Math.PI);
            vertex(
                    vertices, camera, textured, ax, y, az, (float) a, 0, frame, seed, alpha, false,
                    light);
            vertex(
                    vertices, camera, textured, bx, y, bz, (float) b, 0, frame, seed, alpha, false,
                    light);
            vertex(
                    vertices,
                    camera,
                    textured,
                    bx + up.x * height,
                    y + up.y * height,
                    bz + up.z * height,
                    (float) b,
                    1,
                    frame,
                    seed,
                    alpha,
                    false,
                    light);
            vertex(
                    vertices,
                    camera,
                    textured,
                    ax + up.x * height,
                    y + up.y * height,
                    az + up.z * height,
                    (float) a,
                    1,
                    frame,
                    seed,
                    alpha,
                    false,
                    light);
        }
        billboard(
                vertices,
                camera,
                textured,
                x,
                y + .015,
                z,
                width * .72,
                height * .88,
                depth,
                up,
                seed,
                alpha * .6f,
                false,
                light);
    }

    static void billboard(
            VertexConsumer vertices,
            Vec3 camera,
            boolean textured,
            double x,
            double y,
            double z,
            double width,
            double height,
            Vec3 right,
            Vec3 up,
            float seed,
            float alpha,
            boolean smoke,
            int light) {
        double hx = right.x * width * .5, hy = right.y * width * .5, hz = right.z * width * .5;
        double tx = up.x * height, ty = up.y * height, tz = up.z * height;
        boolean mirrored = !smoke && seed < .5f;
        float leftU = mirrored ? 1 : 0, rightU = 1 - leftU;
        if (!smoke) seed = FlameWave.texturePhase(x, y, z, seed);
        int frame = textured ? ShaderPackRendering.frame(seed) : 0;
        vertex(
                vertices, camera, textured, x - hx, y - hy, z - hz, leftU, 0, frame, seed, alpha,
                smoke, light);
        vertex(
                vertices, camera, textured, x + hx, y + hy, z + hz, rightU, 0, frame, seed, alpha,
                smoke, light);
        vertex(
                vertices,
                camera,
                textured,
                x + hx + tx,
                y + hy + ty,
                z + hz + tz,
                rightU,
                1,
                frame,
                seed,
                alpha,
                smoke,
                light);
        vertex(
                vertices,
                camera,
                textured,
                x - hx + tx,
                y - hy + ty,
                z - hz + tz,
                leftU,
                1,
                frame,
                seed,
                alpha,
                smoke,
                light);
    }

    private static void vertex(
            VertexConsumer v,
            Vec3 camera,
            boolean textured,
            double x,
            double y,
            double z,
            float u,
            float w,
            int frame,
            float seed,
            float alpha,
            boolean smoke,
            int light) {
        float px = (float) (x - camera.x), py = (float) (y - camera.y), pz = (float) (z - camera.z);
        if (textured) ShaderPackRendering.animatedVertex(v, px, py, pz, u, w, frame, alpha, light);
        else
            v.addVertex(px, py, pz)
                    .setColor(smoke ? .37f : 1, smoke ? .35f : seed, smoke ? .32f : seed, alpha)
                    .setUv(u, w);
    }
}
