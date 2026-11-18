package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.FireField;
import com.zerotheabsolute.realisticfirephysics.FirePacket;
import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;

/**
 * Exact linear segments keep continuous burn animation without revisiting settled samples.
 *
 * <p>Channel offsets and slopes are plain fields: tens of thousands of corners are read every
 * frame, so each one stays a single object instead of one object plus two small arrays.
 */
final class BurnCornerState {
    private static final int SCORCH_TICKS = 8, HEAT_RISE_TICKS = 3;
    private static final int GROUND_FADE_TICKS = 12, VEGETATION_FADE_TICKS = 40;
    private static final double CORNER_WEIGHT = .25;
    private static final int[] SCORCH_BOUNDARIES = {0, SCORCH_TICKS};
    private static final int[] DRY_BOUNDARIES = {0, FireSettings.TORCH_DRY_TICKS};
    private static final int[] GROUND_BOUNDARIES = boundaries(1);
    private static final int[] VEGETATION_BOUNDARIES = boundaries(2);
    private final FirePacket.Sample[] samples;
    private double coverStart, heatStart, emberStart, dryStart;
    private double coverSlope, heatSlope, emberSlope, drySlope;
    private long epoch = Long.MIN_VALUE, nextChange = Long.MIN_VALUE;
    private long clockRevision = Long.MIN_VALUE;
    private boolean constant;
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
            constant = coverSlope == 0 && heatSlope == 0 && emberSlope == 0 && drySlope == 0;
        } else if (constant) return;
        double elapsed = now - epoch;
        cover = (float) (coverStart + coverSlope * elapsed);
        heat = (float) (heatStart + heatSlope * elapsed);
        ember = (float) (emberStart + emberSlope * elapsed);
        dry = (float) (dryStart + drySlope * elapsed);
    }

    private void prepare(long now, FireClocks clocks) {
        epoch = now;
        nextChange = Long.MAX_VALUE;
        coverStart = heatStart = emberStart = dryStart = 0;
        coverSlope = heatSlope = emberSlope = drySlope = 0;
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
                dryStart += clamp(age / FireSettings.TORCH_DRY_TICKS) * CORNER_WEIGHT;
                if (running && age >= 0 && age < FireSettings.TORCH_DRY_TICKS)
                    drySlope += CORNER_WEIGHT / FireSettings.TORCH_DRY_TICKS;
                continue;
            }
            coverStart += clamp(age / SCORCH_TICKS) * CORNER_WEIGHT;
            if (running && age >= 0 && age < SCORCH_TICKS) coverSlope += CORNER_WEIGHT / SCORCH_TICKS;
            if (sample.quenched()) {
                if (sample.stoppedAt() >= 0) {
                    long end = FireField.emberEnd(sample);
                    double duration = end - sample.stoppedAt();
                    if (duration > 0) {
                        double atStop = clamp((sample.ignition() + FireField.coolTime(sample.fuel())
                                - sample.stoppedAt()) / (double) FireSettings.SMOLDER_TICKS);
                        emberStart += atStop * clamp((end - local) / duration) * CORNER_WEIGHT;
                        if (running && local >= sample.stoppedAt() && local < end) {
                            emberSlope -= atStop * CORNER_WEIGHT / duration;
                            nextChange = Math.min(nextChange, now + end - local);
                        }
                    }
                }
                continue;
            }
            int lifetime = FireField.lifetime(sample.fuel());
            int fade = fadeTicks(sample.fuel());
            heatStart +=
                    clamp(age / HEAT_RISE_TICKS) * clamp((lifetime - age) / fade) * CORNER_WEIGHT;
            if (running && age >= 0 && age < HEAT_RISE_TICKS)
                heatSlope += CORNER_WEIGHT / HEAT_RISE_TICKS;
            else if (running && age >= lifetime - fade && age < lifetime)
                heatSlope -= CORNER_WEIGHT / fade;
            double cooling = age - lifetime;
            emberStart += clamp(1 - cooling / FireSettings.SMOLDER_TICKS) * CORNER_WEIGHT;
            if (running && cooling >= 0 && cooling < FireSettings.SMOLDER_TICKS)
                emberSlope -= CORNER_WEIGHT / FireSettings.SMOLDER_TICKS;
        }
    }

    private static double clamp(double value) {
        return Math.clamp(value, 0, 1);
    }
}
