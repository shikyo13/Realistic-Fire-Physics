package com.zerotheabsolute.realisticfirephysics.client;

/** Reusable ribbon profile: a steady root feeds travelling folds farther up the flame. */
final class TorchPlume {
    static final int SEGMENTS = 8;
    private static final double CURL_STEP = 5.0 / SEGMENTS;
    private static final double FINE_STEP = 9.0 / SEGMENTS;
    private static final double CURL_SIN = Math.sin(CURL_STEP), CURL_COS = Math.cos(CURL_STEP);
    private static final double FINE_SIN = Math.sin(FINE_STEP), FINE_COS = Math.cos(FINE_STEP);
    final float[] curl = new float[SEGMENTS + 1];
    final float[] width = new float[SEGMENTS + 1];
    final float[] rise = new float[SEGMENTS + 1];
    final float[] bend = new float[SEGMENTS + 1];

    static float response(float strength) {
        float s = Float.isFinite(strength) ? Math.clamp(strength, 0, 1) : 0;
        return s / (.45f + .55f * s);
    }

    void update(double seconds, float seed, float strength) {
        float flow = response(strength);
        // Fixed frequencies keep changing airflow from causing a discontinuous phase jump.
        double phase = seconds * 16.3 + seed * 31;
        double fine = seconds * 25.7 + seed * 53;
        double a = Math.sin(phase), b = Math.cos(phase);
        double c = Math.sin(fine), d = Math.cos(fine);
        for (int i = 0; i <= SEGMENTS; i++) {
            float h = (float) i / SEGMENTS, tip = h * h;
            curl[i] = (float) ((.025 + .075 * flow) * tip * (a + .25 * c));
            width[i] = (float) (1 + (.12 + .45 * flow) * 4 * h * (1 - h) * (.65 + .35 * b));
            rise[i] = (float) (h - .22 * flow * tip + .02 * tip * c);
            bend[i] = (float) (tip * (1 + .24 * h * a));
            double nextA = a * CURL_COS - b * CURL_SIN;
            b = b * CURL_COS + a * CURL_SIN;
            a = nextA;
            double nextC = c * FINE_COS - d * FINE_SIN;
            d = d * FINE_COS + c * FINE_SIN;
            c = nextC;
        }
    }
}
