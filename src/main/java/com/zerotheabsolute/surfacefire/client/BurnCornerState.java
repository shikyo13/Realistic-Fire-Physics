package com.zerotheabsolute.surfacefire.client;

import com.zerotheabsolute.surfacefire.FireField;
import com.zerotheabsolute.surfacefire.FirePacket;
import com.zerotheabsolute.surfacefire.FireSettings;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;

/** Exact linear segments keep continuous burn animation without revisiting settled samples. */
final class BurnCornerState {
    private static final int COVER = 0, HEAT = 1, EMBER = 2, DRY = 3;
    private static final int SCORCH_TICKS = 8, HEAT_RISE_TICKS = 3;
    private static final int GROUND_FADE_TICKS = 12, VEGETATION_FADE_TICKS = 40;
    private static final double CORNER_WEIGHT = .25;
    private static final int[] SCORCH_BOUNDARIES = {0, SCORCH_TICKS};
    private static final int[] DRY_BOUNDARIES = {0, FireSettings.TORCH_DRY_TICKS};
    private static final int[] GROUND_BOUNDARIES = boundaries(1);
    private static final int[] VEGETATION_BOUNDARIES = boundaries(2);
    private final FirePacket.Sample[] samples;
    private final double[] start = new double[4], slope = new double[4];
    private long epoch = Long.MIN_VALUE, nextChange = Long.MIN_VALUE;
    private long clockRevision = Long.MIN_VALUE;
    float cover, heat, ember, dry;

    BurnCornerState(FirePacket.Sample[] samples) {
        this.samples = samples;
    }

    private static int fadeTicks(int fuel) {
        return fuel == 2 ? VEGETATION_FADE_TICKS : GROUND_FADE_TICKS;
    }

    private static int[] boundaries(int fuel) {
        return new int[] {
            0,
            HEAT_RISE_TICKS,
            SCORCH_TICKS,
            FireField.lifetime(fuel) - fadeTicks(fuel),
            FireField.lifetime(fuel),
            FireField.coolTime(fuel)
        };
    }

    void invalidate() {
        epoch = nextChange = Long.MIN_VALUE;
    }

    void update(double now) {
        update(now, null);
    }

    void update(double now, FireClocks clocks) {
        long revision = clocks == null ? 0 : clocks.revision();
        if (now < epoch || now >= nextChange || revision != clockRevision) {
            prepare((long) Math.floor(now), clocks);
            clockRevision = revision;
        }
        double elapsed = now - epoch;
        cover = value(COVER, elapsed);
        heat = value(HEAT, elapsed);
        ember = value(EMBER, elapsed);
        dry = value(DRY, elapsed);
    }

    private float value(int channel, double elapsed) {
        return (float) (start[channel] + slope[channel] * elapsed);
    }

    private void prepare(long now, FireClocks clocks) {
        epoch = now;
        nextChange = Long.MAX_VALUE;
        java.util.Arrays.fill(start, 0);
        java.util.Arrays.fill(slope, 0);
        for (var sample : samples) {
            if (sample == null) continue;
            var clock =
                    clocks == null
                            ? null
                            : clocks.clock(
                                    net.minecraft.world.level.ChunkPos.asLong(sample.block()));
            long local = clock == null ? now : clock.time(now);
            boolean running = clock == null || !clock.paused();
            double age = local - sample.ignition();
            int[] boundaries =
                    sample.fuel() == 3
                            ? DRY_BOUNDARIES
                            : sample.quenched()
                                    ? SCORCH_BOUNDARIES
                                    : sample.fuel() == 2
                                            ? VEGETATION_BOUNDARIES
                                            : GROUND_BOUNDARIES;
            for (int boundary : boundaries) {
                long change = sample.ignition() + boundary;
                if (running && change > local)
                    nextChange = Math.min(nextChange, now + change - local);
            }
            if (sample.fuel() == 3) {
                ramp(DRY, age, FireSettings.TORCH_DRY_TICKS, running);
                continue;
            }
            ramp(COVER, age, SCORCH_TICKS, running);
            if (sample.quenched()) continue;
            int lifetime = FireField.lifetime(sample.fuel());
            int fade = fadeTicks(sample.fuel());
            start[HEAT] +=
                    clamp(age / HEAT_RISE_TICKS) * clamp((lifetime - age) / fade) * CORNER_WEIGHT;
            if (running && age >= 0 && age < HEAT_RISE_TICKS)
                slope[HEAT] += CORNER_WEIGHT / HEAT_RISE_TICKS;
            else if (running && age >= lifetime - fade && age < lifetime)
                slope[HEAT] -= CORNER_WEIGHT / fade;
            double cooling = age - lifetime;
            start[EMBER] += clamp(1 - cooling / FireSettings.SMOLDER_TICKS) * CORNER_WEIGHT;
            if (running && cooling >= 0 && cooling < FireSettings.SMOLDER_TICKS)
                slope[EMBER] -= CORNER_WEIGHT / FireSettings.SMOLDER_TICKS;
        }
    }

    private void ramp(int channel, double age, int duration, boolean running) {
        start[channel] += clamp(age / duration) * CORNER_WEIGHT;
        if (running && age >= 0 && age < duration) slope[channel] += CORNER_WEIGHT / duration;
    }

    private static double clamp(double value) {
        return Math.clamp(value, 0, 1);
    }
}
