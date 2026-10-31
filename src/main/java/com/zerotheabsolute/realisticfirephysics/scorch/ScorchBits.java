package com.zerotheabsolute.realisticfirephysics.scorch;

/** One bit per eighth-block cell. Bit 0 is the north-west corner. */
public final class ScorchBits {
    private static final long WEST = 0x0101010101010101L, EAST = WEST << 7;

    private ScorchBits() {}

    public static int index(int x, int z) {
        return (z & 7) * 8 + (x & 7);
    }

    public static boolean contains(long bits, int index) {
        return (bits & (1L << index)) != 0;
    }

    /** Burned cells adjacent to restored or unburned terrain, including across block edges. */
    public static long recoveryEdge(long bits, long west, long east, long north, long south) {
        long green = ~bits;
        long neighbors =
                ((green & ~EAST) << 1) | ((green & ~WEST) >>> 1) | (green << 8) | (green >>> 8);
        neighbors |=
                ((~west & EAST) >>> 7)
                        | ((~east & WEST) << 7)
                        | ((~north >>> 56) & 255L)
                        | ((~south & 255L) << 56);
        return bits & neighbors;
    }
}
