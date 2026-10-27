package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.phys.Vec3;

/** Shared flame/smoke geometry for horizontal ground, vertical fuel and moving embers. */
final class FlameGeometry {
    private FlameGeometry() {}

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
        if (!smoke) seed = FlameWave.texturePhase(x, y, z);
        int frame = textured ? ShaderPackRendering.frame(seed) : 0;
        vertex(
                vertices, camera, textured, x - hx, y - hy, z - hz, 0, 0, frame, seed, alpha, smoke,
                light);
        vertex(
                vertices, camera, textured, x + hx, y + hy, z + hz, 1, 0, frame, seed, alpha, smoke,
                light);
        vertex(
                vertices,
                camera,
                textured,
                x + hx + tx,
                y + hy + ty,
                z + hz + tz,
                1,
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
                0,
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
