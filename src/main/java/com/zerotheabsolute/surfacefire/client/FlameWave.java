package com.zerotheabsolute.surfacefire.client;

/** Continuous travelling folds shared by adjacent flames, independent of their static shape. */
final class FlameWave {
    private static final double BROAD_RATE = 9.2, SECONDARY_RATE = 5.1, TIP_RATE = 17.6;
    private static final double BODY_RATE = .9, BODY_SECONDARY_RATE = .66;
    private static final double BODY_FLOOR = .22, BODY_RANGE = .70, TONGUE_RANGE = .25;
    private static final double BODY_WIDTH = .26, SHOULDER_WIDTH = .16, WIDTH_VARIATION = .08;
    private double broad, tip, crest;

    void update(double seconds, double x, double y, double z) {
        broad =
                Math.sin(seconds * BROAD_RATE - x * .42 - z * .29 + y * .15) * .75
                        + Math.sin(seconds * SECONDARY_RATE + x * .19 - z * .27) * .25;
        tip = Math.sin(seconds * TIP_RATE - x * .76 + z * .55 + y * .22);
        crest =
                .5
                        + .5
                                * (Math.sin(x * .9 + z * .61 + seconds * BODY_RATE) * .7
                                        + Math.sin(
                                                        x * .42
                                                                - z * .81
                                                                - seconds * BODY_SECONDARY_RATE)
                                                * .3);
    }

    double height(double heat, double vigor) {
        return 1 + (.07 + .09 * heat + .04 * vigor) * broad + .015 * tip;
    }

    float brightness(float shape) {
        return (float) (.78 + .16 * shape + .02 * broad);
    }

    double vegetationHeight(float shape) {
        double tongue = shape * shape;
        return BODY_FLOOR + BODY_RANGE * crest * crest + TONGUE_RANGE * tongue * tongue;
    }

    double vegetationWidth(float shape) {
        return BODY_WIDTH + SHOULDER_WIDTH * (1 - crest) + WIDTH_VARIATION * shape;
    }

    static float texturePhase(double x, double y, double z) {
        double phase = x * .035 + y * .0125 + z * .025;
        return (float) (phase - Math.floor(phase));
    }
}
