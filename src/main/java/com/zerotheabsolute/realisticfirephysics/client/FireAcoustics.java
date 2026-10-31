package com.zerotheabsolute.realisticfirephysics.client;

/** Distance rolloff for extended fire sources, in addition to Minecraft's finite audible range. */
final class FireAcoustics {
    private static final double NEAR_DISTANCE = 1;
    private static final double MIN_REFERENCE_DISTANCE = 3, MAX_REFERENCE_DISTANCE = 12;

    private FireAcoustics() {}

    static float distanceGain(double distance, float energy) {
        if (!(energy > 0) || !Float.isFinite(energy) || !Double.isFinite(distance)) return 0;
        double reference =
                Math.min(MAX_REFERENCE_DISTANCE, MIN_REFERENCE_DISTANCE + Math.sqrt(energy));
        double beyondNearField = Math.max(0, distance - NEAR_DISTANCE);
        return (float)
                (reference / Math.sqrt(reference * reference + beyondNearField * beyondNearField));
    }
}
