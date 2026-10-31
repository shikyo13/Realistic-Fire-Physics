package com.zerotheabsolute.realisticfirephysics.client;

import java.util.Arrays;

/** Reusable nearest-source selection with hysteresis to avoid restarting equally distant sounds. */
final class NearestSoundSources<T> {
    private static final double RETAINED_SOURCE_WEIGHT = .8;
    private final Object[] sources;
    private final double[] distances;

    NearestSoundSources(int capacity) {
        sources = new Object[capacity];
        distances = new double[capacity];
        clear();
    }

    void clear() {
        Arrays.fill(sources, null);
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
    }

    void offer(T source, double distanceSquared, boolean playing) {
        if (source == null || !(distanceSquared >= 0) || !Double.isFinite(distanceSquared)) return;
        double score = distanceSquared * (playing ? RETAINED_SOURCE_WEIGHT : 1);
        for (int at = 0; at < sources.length; at++) {
            if (score >= distances[at]) continue;
            for (int i = sources.length - 1; i > at; i--) {
                sources[i] = sources[i - 1];
                distances[i] = distances[i - 1];
            }
            sources[at] = source;
            distances[at] = score;
            return;
        }
    }

    boolean contains(T source) {
        for (Object value : sources) if (value == source) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    T get(int index) {
        return (T) sources[index];
    }
}
