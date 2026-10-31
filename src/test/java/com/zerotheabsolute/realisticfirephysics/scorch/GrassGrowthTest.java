package com.zerotheabsolute.realisticfirephysics.scorch;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GrassGrowthTest {
    @Test
    void packedProgressPreservesIndependentCellsAndDoesNotExposeMutableStorage() {
        long[] words = new long[GrassGrowth.WORD_COUNT];
        for (int i = 0; i < 64; i++) GrassGrowth.put(words, i, i * 1000);
        var growth = GrassGrowth.decode(words);
        words[0] = 0;
        growth.encode()[1] = 0;
        for (int i = 0; i < 64; i++) assertEquals(i * 1000, growth.level(i));
        assertEquals(growth, GrassGrowth.decode(growth.encode()));
        assertSame(GrassGrowth.EMPTY, growth.without(-1L));
    }

    @Test
    void completedAndUnstartedGroundDoNotNeedAnArray() {
        assertEquals(0, GrassGrowth.EMPTY.encode().length);
        assertSame(GrassGrowth.EMPTY, GrassGrowth.uniform(0, .5f));
        assertSame(GrassGrowth.EMPTY, GrassGrowth.uniform(-1L, 0));
        assertThrows(IllegalArgumentException.class, () -> GrassGrowth.decode(new long[100]));
    }
}
