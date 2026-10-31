package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class GrassGrowthEffectTest {
    private static final float[] TEXTURE = new float[256];

    static {
        for (int i = 0; i < TEXTURE.length; i++) TEXTURE[i] = (i % 13) / 12f;
    }

    private static final class Sample implements GrassGrowthFront.Surface {
        final List<Float> values = new ArrayList<>();
        double area;
        int quads;
        float youth, nx, nz;
        boolean treated;

        public void material(float youth, float nx, float nz) {
            this.youth = youth;
            this.nx = nx;
            this.nz = nz;
        }

        public void quad(
                float ax, float az, float bx, float bz, float cx, float cz, float dx, float dz) {
            treated |= youth != 0 || nx != 0 || nz != 0;
            for (float value : new float[] {ax, az, bx, bz, cx, cz, dx, dz, youth, nx, nz}) {
                assertTrue(Float.isFinite(value));
                values.add(value);
            }
            assertTrue(ax >= 0 && az >= 0 && cx <= 1 && cz <= 1);
            area += (cx - ax) * (cz - az);
            quads++;
        }
    }

    private static Sample sample(GrassGrowthFront front, float p) {
        float[] cells = new float[64];
        Arrays.fill(cells, p);
        Sample sample = new Sample();
        front.draw(cells, sample);
        return sample;
    }

    @Test
    void treatmentsPreserveCoverageAndFinishAtExactNativeGeometry() {
        for (var effect : GrassGrowthEffect.values()) {
            var baseline = new GrassGrowthFront(3000L << 38);
            var front = new GrassGrowthFront(3000L << 38);
            front.configure(GrassGrowthStyle.SCATTERED, TEXTURE, -1L, effect);
            double previous = 0;
            for (int i = 0; i <= 1000; i++) {
                float p = i / 1000f;
                var result = sample(front, p);
                assertEquals(sample(baseline, p).area, result.area, 2e-6);
                assertTrue(result.area + 1e-6 >= previous);
                previous = result.area;
                var again = sample(front, p);
                assertEquals(result.values, again.values);
                assertFalse(front.movingChanged(), "Stationary material must retain its mesh");
            }
            var complete = sample(front, 1);
            assertEquals(1, complete.quads);
            assertFalse(complete.treated);
            assertEquals(1, complete.area, 1e-6);
            assertEquals(0, sample(front, 0).quads);
        }
    }

    @Test
    void materialEffectsDifferWithoutChangingTheDefaultOrItsPixelOrder() {
        var front = new GrassGrowthFront(3000L << 38);
        var original = sample(front, .42f);
        for (var effect : GrassGrowthEffect.values()) {
            front.configure(GrassGrowthStyle.SCATTERED, TEXTURE, -1L, effect);
            var result = sample(front, .42f);
            if (effect == GrassGrowthEffect.NONE) assertEquals(original.values, result.values);
            else assertNotEquals(original.values, result.values);
            if (effect == GrassGrowthEffect.FRESH || effect == GrassGrowthEffect.RELIEF)
                assertTrue(result.treated);
        }
        front.configure(GrassGrowthStyle.SCATTERED, TEXTURE, -1L, GrassGrowthEffect.NONE);
        assertEquals(original.values, sample(front, .42f).values);
    }

    @Test
    void treatmentInputsAreBoundedAndHaveNoResidualMatureTint() {
        for (int channel = 0; channel <= 255; channel++) {
            assertEquals(channel, GrassGrowthShading.red(channel, 0));
            assertEquals(channel, GrassGrowthShading.green(channel, 0));
            assertEquals(channel, GrassGrowthShading.blue(channel, 0));
            assertTrue(GrassGrowthShading.red(channel, 1) <= 255);
        }
        assertEquals(0, GrassGrowthShading.youth(1));
        assertEquals(1, GrassGrowthShading.youth(0));
        assertEquals(0, GrassGrowthShading.slope(null, 0, 0, true));
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                assertTrue(Math.abs(GrassGrowthShading.slope(TEXTURE, x, z, true)) <= .28f);
                assertTrue(Math.abs(GrassGrowthShading.slope(TEXTURE, x, z, false)) <= .28f);
            }
    }
}
