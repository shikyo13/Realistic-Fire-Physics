package com.zerotheabsolute.surfacefire;

/** Burn exposure freezes when quenched, including after saving and reconnecting. */
public final class VegetationScorch {
    private VegetationScorch() {}

    public static float exposure(FirePacket.Sample sample, long now) {
        if (sample == null || sample.fuel() != 2) return 0;
        long end = sample.quenched() && sample.stoppedAt() >= 0 ? sample.stoppedAt() : now;
        return (float)
                Math.clamp(
                        (end - sample.ignition()) / (double) FireSettings.VEGETATION_CONSUME_TICKS,
                        0,
                        1);
    }
}
