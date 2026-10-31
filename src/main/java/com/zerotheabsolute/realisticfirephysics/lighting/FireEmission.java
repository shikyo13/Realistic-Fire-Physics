package com.zerotheabsolute.realisticfirephysics.lighting;

/** Shared source brightness and cooling curve for ground and structure lighting. */
public final class FireEmission {
    public static final int FLAME = 15;
    public static final int MINIMUM_FLAME = 14;
    public static final int SMOLDER = 8;
    public static final int FADE_TICKS = 40;
    public static final int FLAME_CELLS_PER_SOURCE = 16;
    public static final int EMBER_CELLS_PER_SOURCE = 64;
    public static final int MAXIMUM_SOURCE = 19;
    private static final double LEVELS_PER_DOUBLING = 2;

    private FireEmission() {}

    public static int strength(long flameUntil, long coolUntil, long now) {
        if (now >= coolUntil) return 0;
        if (now <= flameUntil) return FLAME;
        long elapsed = now - flameUntil;
        if (elapsed < FADE_TICKS)
            return SMOLDER
                    + (int) Math.ceil((FLAME - SMOLDER) * (1 - elapsed / (double) FADE_TICKS));
        long fadeEnd = flameUntil + FADE_TICKS;
        double remaining = (coolUntil - now) / (double) Math.max(1, coolUntil - fadeEnd);
        return Math.max(1, (int) Math.ceil(SMOLDER * remaining));
    }

    public static double coverage(double level, double cells, boolean flaming) {
        if (cells <= 0 || level <= 0) return 0;
        int reference = flaming ? FLAME_CELLS_PER_SOURCE : EMBER_CELLS_PER_SOURCE;
        double combined = level + LEVELS_PER_DOUBLING * Math.log(cells / reference) / Math.log(2);
        return Math.clamp(combined, flaming ? MINIMUM_FLAME : 0, flaming ? MAXIMUM_SOURCE : FLAME);
    }
}
