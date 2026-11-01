package com.zerotheabsolute.realisticfirephysics.client;

import static org.junit.jupiter.api.Assertions.*;

import com.zerotheabsolute.realisticfirephysics.FireField;
import com.zerotheabsolute.realisticfirephysics.FirePacket;
import com.zerotheabsolute.realisticfirephysics.FireSettings;

import org.junit.jupiter.api.Test;

class BurnCornerStateTest {
    @Test
    void pausedClockFreezesFuelEnvelopeAndResumeRemainsContinuous() {
        var cells = new FirePacket.Sample[] {sample(1, 100, false)};
        var state = new BurnCornerState(cells);
        var clocks = com.zerotheabsolute.realisticfirephysics.simulation.FireClocks.get(null);
        try {
            com.zerotheabsolute.realisticfirephysics.simulation.FireClocks.receive(
                    new com.zerotheabsolute.realisticfirephysics.simulation.FireClockPacket(
                            "test",
                            true,
                            java.util.List.of(
                                    new com.zerotheabsolute.realisticfirephysics.simulation
                                            .FireClockPacket.State(0, 0, 104))));
            state.update(104, clocks);
            float cover = state.cover, heat = state.heat, ember = state.ember;
            state.update(10000, clocks);
            assertEquals(cover, state.cover);
            assertEquals(heat, state.heat);
            assertEquals(ember, state.ember);
            com.zerotheabsolute.realisticfirephysics.simulation.FireClocks.receive(
                    new com.zerotheabsolute.realisticfirephysics.simulation.FireClockPacket(
                            "test",
                            false,
                            java.util.List.of(
                                    new com.zerotheabsolute.realisticfirephysics.simulation
                                            .FireClockPacket.State(0, 9896, -1))));
            state.update(10000, clocks);
            assertEquals(cover, state.cover);
            state.update(10002, clocks);
            assertTrue(state.cover > cover);
        } finally {
            com.zerotheabsolute.realisticfirephysics.simulation.FireClocks.clearClient();
        }
    }

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

    @Test
    void quenchingStopsHeatImmediatelyButPreservesContinuousEmberCooling() {
        var burning = sample(1, 100, false);
        var samples = new FirePacket.Sample[] {burning};
        var state = new BurnCornerState(samples);
        long stopped = 220;
        state.update(stopped);
        float initialEmber = state.ember;
        samples[0] = burning.quench(stopped);
        state.invalidate();
        state.update(stopped);
        assertEquals(0, state.heat);
        assertEquals(initialEmber, state.ember, .000001);
        float previous = state.ember;
        for (double now = stopped; now < stopped + FireSettings.QUENCHED_EMBER_FADE_TICKS; now += .125) {
            state.update(now);
            assertEquals(0, state.heat);
            assertTrue(state.ember > 0 && state.ember <= previous);
            previous = state.ember;
        }
        state.update(FireField.emberEnd(samples[0]));
        assertEquals(0, state.ember, .000001);
        assertEquals(.25, state.cover, .000001);
    }

    @Test
    void quenchingCannotExtendTheNaturalEmberLifetime() {
        var burning = sample(1, 100, false);
        long naturalEnd = FireField.emberEnd(burning);
        var quenched = burning.quench(naturalEnd - 2);
        assertEquals(naturalEnd, FireField.emberEnd(quenched));
        var state = new BurnCornerState(new FirePacket.Sample[] {quenched});
        state.update(naturalEnd - 1.5);
        assertTrue(state.ember > 0 && state.ember < .01);
        state.update(naturalEnd);
        assertEquals(0, state.ember, .000001);
        assertTrue(FireField.emberEnd(sample(1, 100, true)) <= naturalEnd);
    }
}
