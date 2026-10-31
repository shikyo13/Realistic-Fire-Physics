package com.zerotheabsolute.realisticfirephysics.client;

import net.minecraft.client.renderer.block.model.BakedQuad;

/** Native model UV mapping and biome tint, independent of the regrowth boundary. */
final class GrassSurface {
    private static final int STRIDE = 8;
    private final float u0, ux, uz, v0, vx, vz;
    final int red, green, blue;

    GrassSurface(BakedQuad quad, int tint) {
        int[] data = quad.getVertices();
        float ax = f(data, 0), az = f(data, 2);
        float bx = f(data, STRIDE) - ax, bz = f(data, STRIDE + 2) - az;
        float dx = f(data, 3 * STRIDE) - ax, dz = f(data, 3 * STRIDE + 2) - az;
        float determinant = bx * dz - bz * dx;
        float bu = f(data, STRIDE + 4) - f(data, 4), du = f(data, 3 * STRIDE + 4) - f(data, 4);
        float bv = f(data, STRIDE + 5) - f(data, 5), dv = f(data, 3 * STRIDE + 5) - f(data, 5);
        ux = (bu * dz - du * bz) / determinant;
        uz = (du * bx - bu * dx) / determinant;
        vx = (bv * dz - dv * bz) / determinant;
        vz = (dv * bx - bv * dx) / determinant;
        u0 = f(data, 4) - ux * ax - uz * az;
        v0 = f(data, 5) - vx * ax - vz * az;
        int color = data[3], multiplier = quad.isTinted() ? tint : 0xffffff;
        red = (color & 255) * (multiplier >>> 16 & 255) / 255;
        green = (color >>> 8 & 255) * (multiplier >>> 8 & 255) / 255;
        blue = (color >>> 16 & 255) * (multiplier & 255) / 255;
    }

    float u(float x, float z) {
        return u0 + ux * x + uz * z;
    }

    float v(float x, float z) {
        return v0 + vx * x + vz * z;
    }

    private static float f(int[] data, int field) {
        return Float.intBitsToFloat(data[field]);
    }
}
