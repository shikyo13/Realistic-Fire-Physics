package com.zerotheabsolute.realisticfirephysics.scorch;

import java.util.Arrays;

/** Immutable, sixteen-bit progress for each of a block's sixty-four surface cells. */
public final class GrassGrowth {
    public static final int CELL_COUNT = 64, FULL = 65535, WORD_COUNT = 16;
    public static final GrassGrowth EMPTY = new GrassGrowth(new long[0]);
    private final long[] words;
    private final long active;

    private GrassGrowth(long[] words) {
        this.words = words;
        long bits = 0;
        for (int i = 0; i < CELL_COUNT; i++) if (level(i) > 0) bits |= 1L << i;
        active = bits;
    }

    public static GrassGrowth decode(long[] words) {
        if (words.length == 0) return EMPTY;
        if (words.length != WORD_COUNT)
            throw new IllegalArgumentException("Invalid grass progress");
        var growth = new GrassGrowth(words.clone());
        return growth.active == 0 ? EMPTY : growth;
    }

    static GrassGrowth take(long[] words) {
        var growth = new GrassGrowth(words);
        return growth.active == 0 ? EMPTY : growth;
    }

    public static GrassGrowth uniform(long cells, float progress) {
        int amount = Math.round(Math.clamp(progress, 0, 1) * FULL);
        if (amount == 0 || cells == 0) return EMPTY;
        long[] words = new long[WORD_COUNT];
        while (cells != 0) {
            int cell = Long.numberOfTrailingZeros(cells);
            put(words, cell, amount);
            cells &= cells - 1;
        }
        return take(words);
    }

    static void put(long[] words, int cell, int amount) {
        int shift = (cell & 3) * 16;
        words[cell >>> 2] =
                (words[cell >>> 2] & ~((long) FULL << shift)) | ((long) amount << shift);
    }

    public int level(int cell) {
        return words.length == 0 ? 0 : (int) (words[cell >>> 2] >>> ((cell & 3) * 16)) & FULL;
    }

    public float progress(int cell) {
        return level(cell) / (float) FULL;
    }

    public long active() {
        return active;
    }

    public long[] encode() {
        return words.clone();
    }

    public GrassGrowth without(long removed) {
        if ((active & removed) == 0) return this;
        long[] next = words.clone();
        while (removed != 0) {
            int cell = Long.numberOfTrailingZeros(removed);
            put(next, cell, 0);
            removed &= removed - 1;
        }
        return take(next);
    }

    public long spreading() {
        long result = 0, bits = active;
        while (bits != 0) {
            int cell = Long.numberOfTrailingZeros(bits);
            if (level(cell) >= GrassRegrowth.SPREAD_LEVEL) result |= 1L << cell;
            bits &= bits - 1;
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof GrassGrowth g && Arrays.equals(words, g.words);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(words);
    }
}
