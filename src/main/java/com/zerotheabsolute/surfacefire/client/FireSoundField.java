package com.zerotheabsolute.surfacefire.client;

/** Bounded, reusable sound bins anchored to real flames rather than the center of a burned area. */
final class FireSoundField {
    static final int VOICES = 3;
    static final int AUDIBLE_DISTANCE = 64;
    private static final int CELL_SIZE = 8;
    private static final int RADIUS = AUDIBLE_DISTANCE / CELL_SIZE;
    private static final int WIDTH = RADIUS * 2 + 1, CAPACITY = WIDTH * WIDTH * WIDTH;
    private static final double RANGE_SQUARED = AUDIBLE_DISTANCE * AUDIBLE_DISTANCE;
    private static final double GROUP_RANGE_SQUARED = 18 * 18;
    private static final double MIN_DIRECTION_COSINE = .5;
    private static final int[][] ASSIGNMENTS = {
        {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
    };
    private final float[] mass = new float[CAPACITY], wood = new float[CAPACITY];
    private final double[] x = new double[CAPACITY], y = new double[CAPACITY],
            z = new double[CAPACITY], distanceSquared = new double[CAPACITY];
    private final int[] occupied = new int[CAPACITY];
    private final boolean[] selected = new boolean[CAPACITY];
    private final Emitter[] candidates = {new Emitter(), new Emitter(), new Emitter()};
    final Emitter[] emitters = {new Emitter(), new Emitter(), new Emitter()};
    private int count;
    private double listenerX, listenerY, listenerZ;

    static final class Emitter {
        double x, y, z;
        float energy, wood;

        void copyFrom(Emitter source) {
            x = source.x;
            y = source.y;
            z = source.z;
            energy = source.energy;
            wood = source.wood;
        }
    }

    void clear() {
        resetBins();
        for (var emitter : emitters) emitter.energy = 0;
    }

    private void resetBins() {
        for (int i = 0; i < count; i++) {
            int at = occupied[i];
            mass[at] = wood[at] = 0;
            selected[at] = false;
        }
        count = 0;
        for (var candidate : candidates) candidate.energy = 0;
    }

    void begin(double px, double py, double pz) {
        resetBins();
        listenerX = px;
        listenerY = py;
        listenerZ = pz;
    }

    void add(double px, double py, double pz, float energy, float woodFraction) {
        double dx = px - listenerX, dy = py - listenerY, dz = pz - listenerZ;
        double distance = dx * dx + dy * dy + dz * dz;
        if (!(energy > 0) || !Float.isFinite(energy) || !(distance <= RANGE_SQUARED)) return;
        // Listener-aligned boundaries keep fires on opposite sides in separate bins, even up close.
        int a = (int) Math.floor(dx / CELL_SIZE) + RADIUS;
        int b = (int) Math.floor(dy / CELL_SIZE) + RADIUS;
        int c = (int) Math.floor(dz / CELL_SIZE) + RADIUS;
        int at = (b * WIDTH + c) * WIDTH + a;
        if (mass[at] == 0) occupied[count++] = at;
        if (mass[at] == 0 || distance < distanceSquared[at]) {
            x[at] = px;
            y[at] = py;
            z[at] = pz;
            distanceSquared[at] = distance;
        }
        mass[at] += energy;
        wood[at] += energy * (Float.isFinite(woodFraction) ? Math.clamp(woodFraction, 0, 1) : 0);
    }

    void finish() {
        for (int i = 0; i < count; i++) selected[occupied[i]] = false;
        for (var candidate : candidates) {
            candidate.energy = 0;
            int best = -1;
            double score = 0;
            for (int i = 0; i < count; i++) {
                int at = occupied[i];
                if (selected[at]) continue;
                double strength = mass[at] / (CELL_SIZE * CELL_SIZE + distanceSquared[at]);
                if (strength > score) {
                    score = strength;
                    best = at;
                }
            }
            if (best < 0) continue;
            candidate.x = x[best];
            candidate.y = y[best];
            candidate.z = z[best];
            float timber = 0;
            for (int i = 0; i < count; i++) {
                int at = occupied[i];
                if (selected[at] || !sameRegion(best, at)) continue;
                selected[at] = true;
                candidate.energy += mass[at];
                timber += wood[at];
            }
            candidate.wood = timber / candidate.energy;
        }
        assignVoices();
    }

    private boolean sameRegion(int first, int second) {
        if (first == second) return true;
        double dx = x[first] - x[second], dy = y[first] - y[second], dz = z[first] - z[second];
        if (dx * dx + dy * dy + dz * dz > GROUP_RANGE_SQUARED) return false;
        double dot = (x[first] - listenerX) * (x[second] - listenerX)
                + (y[first] - listenerY) * (y[second] - listenerY)
                + (z[first] - listenerZ) * (z[second] - listenerZ);
        return dot > 0 && dot * dot >= MIN_DIRECTION_COSINE * MIN_DIRECTION_COSINE
                * distanceSquared[first] * distanceSquared[second];
    }

    private void assignVoices() {
        int[] best = ASSIGNMENTS[0];
        double bestCost = Double.POSITIVE_INFINITY;
        // Six fixed assignments prevent louder regions from swapping left/right audio streams.
        for (int[] assignment : ASSIGNMENTS) {
            double cost = 0;
            for (int slot = 0; slot < VOICES; slot++) {
                var old = emitters[slot];
                var next = candidates[assignment[slot]];
                if (old.energy > 0 && next.energy > 0) {
                    double dx = old.x - next.x, dy = old.y - next.y, dz = old.z - next.z;
                    cost += dx * dx + dy * dy + dz * dz;
                } else if ((old.energy > 0) != (next.energy > 0)) cost += GROUP_RANGE_SQUARED;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = assignment;
            }
        }
        for (int slot = 0; slot < VOICES; slot++) emitters[slot].copyFrom(candidates[best[slot]]);
    }
}
