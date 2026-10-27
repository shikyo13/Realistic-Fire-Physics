package com.zerotheabsolute.surfacefire.audio;

/** Gentle air motion keeps a stable flame; stronger relative flow entrains turbulent bursts. */
final class CombustionAirflow {
    private static final double CALM_SPEED = 1.5;
    private static final double RESPONSE_SPEED = 3;

    private CombustionAirflow() {}

    static double activity(double speed) {
        double excess = Math.max(0, speed - CALM_SPEED);
        return excess * excess / (excess * excess + RESPONSE_SPEED * RESPONSE_SPEED);
    }
}
