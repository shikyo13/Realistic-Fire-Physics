package com.zerotheabsolute.realisticfirephysics.client;

import java.util.Arrays;

/** Bounded texture-space colonies with staggered birth times and fixed native texel colors. */
final class GrassMicroGrowth {
    private static final int WIDTH = GrassGrowthFront.RESOLUTION;
    private static final int PIXELS = WIDTH * WIDTH;
    private static final int TUFT_MIN = 3, TUFT_VARIATION = 4;
    private static final int RUNNER_MIN = 8, RUNNER_VARIATION = 7;
    private static final float TUFT_STEP = .028f, RUNNER_STEP = .018f;
    private static final float TEXTURE_WEIGHT = .8f, JITTER_WEIGHT = .12f;
    private static final float TURN_COST = .18f, CROWD_COST = .2f;
    private static final int BRANCH_INTERVAL = 4;

    private GrassMicroGrowth() {}

    static int[] order(
            GrassGrowthStyle style,
            float[] texture,
            float[] variation,
            long footprint,
            byte[] directions) {
        boolean runners = style == GrassGrowthStyle.RUNNERS;
        int[] ranks = new int[PIXELS];
        Arrays.fill(ranks, -1);
        boolean[] occupied = new boolean[PIXELS];
        long[] roots = new long[PIXELS], births = new long[PIXELS];
        int count = 0;
        for (int pixel = 0; pixel < PIXELS; pixel++) {
            if (!covered(footprint, pixel)) continue;
            roots[count++] = key(.65f * variation[pixel] + .35f * detail(texture, pixel), pixel);
        }
        Arrays.sort(roots, 0, count);
        int[] colony = new int[RUNNER_MIN + RUNNER_VARIATION];
        int written = 0;
        for (int r = 0; r < count; r++) {
            int root = (int) roots[r];
            if (occupied[root]) continue;
            int target =
                    runners
                            ? RUNNER_MIN + (int) (variation[root] * RUNNER_VARIATION)
                            : TUFT_MIN + (int) (variation[root] * TUFT_VARIATION);
            float start =
                    .78f * variation[(root * 73 + 41) & (PIXELS - 1)]
                            + .12f * detail(texture, root);
            int length = 1;
            colony[0] = root;
            occupied[root] = true;
            births[written++] = key(start, root);
            while (length < target) {
                int chosen = -1;
                byte direction = 0;
                float best = Float.POSITIVE_INFINITY;
                int begin = runners && length % BRANCH_INTERVAL != 0 ? length - 1 : 0;
                for (int at = begin; at < length; at++) {
                    int parent = colony[at];
                    for (byte d = 0; d < 4; d++) {
                        int next = adjacent(parent, d);
                        if (next < 0 || occupied[next] || !covered(footprint, next)) continue;
                        float score =
                                TEXTURE_WEIGHT
                                                * Math.abs(
                                                        detail(texture, next)
                                                                - detail(
                                                                        texture,
                                                                        runners ? parent : root))
                                        + JITTER_WEIGHT * variation[next];
                        if (runners) {
                            score += length > 1 && d != directions[parent] ? TURN_COST : 0;
                            score += CROWD_COST * neighbors(occupied, next);
                        }
                        if (score < best) {
                            best = score;
                            chosen = next;
                            direction = d;
                        }
                    }
                }
                if (chosen < 0) break;
                directions[chosen] = direction;
                colony[length] = chosen;
                occupied[chosen] = true;
                births[written++] =
                        key(start + length * (runners ? RUNNER_STEP : TUFT_STEP), chosen);
                length++;
            }
        }
        Arrays.sort(births, 0, written);
        for (int i = 0; i < written; i++) ranks[(int) births[i]] = i;
        return ranks;
    }

    private static long key(float time, int pixel) {
        return (long) Float.floatToIntBits(time) << 32 | (pixel & 0xffffffffL);
    }

    private static float detail(float[] texture, int pixel) {
        return texture == null ? .5f : Math.clamp(texture[pixel], 0, 1);
    }

    private static boolean covered(long footprint, int pixel) {
        return (footprint & 1L << ((pixel / WIDTH / 2) * 8 + pixel % WIDTH / 2)) != 0;
    }

    private static int neighbors(boolean[] occupied, int pixel) {
        int count = 0;
        for (byte direction = 0; direction < 4; direction++) {
            int next = adjacent(pixel, direction);
            if (next >= 0 && occupied[next]) count++;
        }
        return count;
    }

    private static int adjacent(int pixel, byte direction) {
        return switch (direction) {
            case 0 -> pixel % WIDTH + 1 < WIDTH ? pixel + 1 : -1;
            case 1 -> pixel % WIDTH > 0 ? pixel - 1 : -1;
            case 2 -> pixel / WIDTH + 1 < WIDTH ? pixel + WIDTH : -1;
            default -> pixel / WIDTH > 0 ? pixel - WIDTH : -1;
        };
    }
}
