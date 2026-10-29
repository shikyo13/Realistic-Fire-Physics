package com.zerotheabsolute.surfacefire.client;

/** Shared world-space torch positioning and bounded motion gain. */
final class TorchAcoustics {
    static final float BURN_VOLUME = .3234f;
    static final int AUDIBLE_DISTANCE = 32;
    static final double TICKS_PER_SECOND = 20;
    private static final double WICK_FORWARD = .45, WICK_RIGHT = .32, WICK_UP = -.20;
    private static final double REFERENCE_DISTANCE = 2.5, NEAR_DISTANCE = 1;
    private static final float RUSH_START_SPEED = 1, FULL_RUSH_SPEED = 8, RUSH_GAIN = 1.4f;

    private TorchAcoustics() {}

    static void heldPosition(double x, double eyeY, double z, float yawDegrees,
            float pitchDegrees, boolean rightHand, double[] result) {
        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        double side = rightHand ? WICK_RIGHT : -WICK_RIGHT;
        result[0] = x - cy * side - sy * sp * WICK_UP - sy * cp * WICK_FORWARD;
        result[1] = eyeY + cp * WICK_UP - sp * WICK_FORWARD;
        result[2] = z - sy * side + cy * sp * WICK_UP + cy * cp * WICK_FORWARD;
    }

    static float distanceGain(double distance) {
        if (!Double.isFinite(distance)) return 0;
        double beyondNear = Math.max(0, distance - NEAR_DISTANCE);
        return (float) (REFERENCE_DISTANCE / Math.sqrt(REFERENCE_DISTANCE * REFERENCE_DISTANCE
                + beyondNear * beyondNear));
    }

    static float motionGain(float speed) {
        if (!Float.isFinite(speed)) return 1;
        float rush = Math.clamp((speed - RUSH_START_SPEED) / (FULL_RUSH_SPEED - RUSH_START_SPEED), 0, 1);
        return 1 + RUSH_GAIN * rush;
    }
}
