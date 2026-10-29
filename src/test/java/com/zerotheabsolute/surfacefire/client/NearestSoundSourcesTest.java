package com.zerotheabsolute.surfacefire.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NearestSoundSourcesTest {
    @Test
    void nearestSourcesAreChosenBeforeAnyStreamsAreReplaced() {
        var selection = new NearestSoundSources<Integer>(3);
        for (int i = 12; i >= 1; i--) selection.offer(i, i * i, false);
        assertEquals(1, selection.get(0));
        assertEquals(2, selection.get(1));
        assertEquals(3, selection.get(2));
    }

    @Test
    void smallDistanceChangesDoNotChurnPlayingStreams() {
        var selection = new NearestSoundSources<Object>(1);
        var retained = new Object();
        var incoming = new Object();
        selection.offer(incoming, 10, false);
        selection.offer(retained, 11, true);
        assertSame(retained, selection.get(0));
        selection.clear();
        selection.offer(retained, 20, true);
        selection.offer(incoming, 10, false);
        assertSame(incoming, selection.get(0));
    }

    @Test
    void clearingReleasesEntityReferencesAndRejectsInvalidDistances() {
        var selection = new NearestSoundSources<Object>(2);
        var source = new Object();
        selection.offer(source, 1, false);
        assertTrue(selection.contains(source));
        selection.clear();
        selection.offer(source, Double.NaN, false);
        selection.offer(source, Double.POSITIVE_INFINITY, false);
        assertFalse(selection.contains(source));
        assertNull(selection.get(0));
        assertNull(selection.get(1));
    }
}
