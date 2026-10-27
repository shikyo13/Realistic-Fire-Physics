package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.surfacefire.FireField;
import com.zerotheabsolute.surfacefire.FirePacket;
import com.zerotheabsolute.surfacefire.FireSettings;

import org.junit.jupiter.api.Test;

class BurnCornerStateTest {
    private static FirePacket.Sample sample(int fuel, long time, boolean quenched) {
        return new FirePacket.Sample(new FireField.Site(0, 0, 0), time, fuel, quenched);
    }

    @Test
    void cachedEnvelopesMatchContinuousRenderingAcrossEveryTransition() {
        var samples =
                new FirePacket.Sample[] {
                    sample(1, 5, false),
                    sample(2, 31, false),
                    sample(2, 17, true),
                    sample(3, 9, false)
                };
        var cached = new BurnCornerState(samples);
        for (double time = 0; time < 600; time += .137) {
            cached.update(time);
            float cover = 0, heat = 0, ember = 0, dry = 0;
            for (var s : samples) {
                double age = time - s.ignition();
                if (s.fuel() == 3) {
                    dry += clamp(age / FireSettings.TORCH_DRY_TICKS) / 4;
                } else {
                    cover += clamp(age / 8) / 4;
                    if (!s.quenched()) {
                        heat +=
                                clamp(age / 3)
                                        * clamp(
                                                (FireField.lifetime(s.fuel()) - age)
                                                        / (s.fuel() == 2 ? 40.0 : 12.0))
                                        / 4;
                        ember += clamp((FireField.coolTime(s.fuel()) - age) / 400) / 4;
                    }
                }
            }
            assertEquals(cover, cached.cover, 0.000001, "cover at " + time);
            assertEquals(heat, cached.heat, 0.000001, "heat at " + time);
            assertEquals(ember, cached.ember, 0.000001, "ember at " + time);
            assertEquals(dry, cached.dry, 0.000001, "dry at " + time);
        }
    }

    @Test
    void newIgnitionsAndQuenchingInvalidateASettledCorner() {
        var samples = new FirePacket.Sample[4];
        var cached = new BurnCornerState(samples);
        cached.update(1000);
        samples[0] = sample(2, 1000, false);
        cached.invalidate();
        cached.update(1003.5);
        assertEquals(.25f, cached.heat);
        assertTrue(cached.ember > 0);
        samples[0] = sample(2, 1000, true);
        cached.invalidate();
        cached.update(1003.5);
        assertEquals(0, cached.heat);
        assertEquals(0, cached.ember);
        assertTrue(cached.cover > 0);
    }

    private static float clamp(double value) {
        return (float) Math.clamp(value, 0, 1);
    }
}
