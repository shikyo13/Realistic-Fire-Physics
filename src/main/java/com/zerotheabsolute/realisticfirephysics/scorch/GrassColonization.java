package com.zerotheabsolute.realisticfirephysics.scorch;

/** Persistent soil sources supplement spread from living grass without a per-tick random roll. */
public final class GrassColonization {
    private static final long SOIL_SEED = 0x72e3f19a65c4b80dL;
    private static final int CELL_MASK = GrassGrowth.CELL_COUNT - 1;
    private static final int EXTRA_SOURCE_MASK = 3;

    private GrassColonization() {}

    public static long sources(
            ChunkScars.Ground ground, long west, long east, long north, long south, long position) {
        long edge =
                ScorchBits.recoveryEdge(
                        ground.cells() & ~ground.growth().spreading(), west, east, north, south);
        return edge | (survivors(position) & ground.cells());
    }

    /** One or occasionally two surviving root/seed sites, stable through saving and chunk order. */
    static long survivors(long position) {
        long value = position ^ SOIL_SEED;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        long sites = 1L << (value & CELL_MASK);
        if (((value >>> 12) & EXTRA_SOURCE_MASK) == 0) sites |= 1L << ((value >>> 24) & CELL_MASK);
        return sites;
    }
}
