package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

class GrassSurfaceTest {
    @Test
    void nativeTextureCoordinatesAndBiomeTintSurviveTheMovingFront() {
        int[] vertices = new int[32];
        float[] x = {0, 0, 1, 1}, z = {0, 1, 1, 0};
        for (int i = 0; i < 4; i++) {
            vertices[i * 8] = Float.floatToRawIntBits(x[i]);
            vertices[i * 8 + 1] = Float.floatToRawIntBits(1);
            vertices[i * 8 + 2] = Float.floatToRawIntBits(z[i]);
            vertices[i * 8 + 3] = 0xffffffff;
            vertices[i * 8 + 4] = Float.floatToRawIntBits(.25f + z[i] * .125f);
            vertices[i * 8 + 5] = Float.floatToRawIntBits(.75f - x[i] * .125f);
        }
        var surface =
                new GrassSurface(
                        new BakedQuad(vertices, 0, Direction.UP, null, true, true), 0x66aa33);
        for (int i = 0; i <= 100; i++) {
            float t = i / 100f;
            assertEquals(.25 + t * .125, surface.u(.2f, t), 1e-6);
            assertEquals(.75 - t * .125, surface.v(t, .2f), 1e-6);
        }
        assertEquals(0x66, surface.red);
        assertEquals(0xaa, surface.green);
        assertEquals(0x33, surface.blue);
    }
}
