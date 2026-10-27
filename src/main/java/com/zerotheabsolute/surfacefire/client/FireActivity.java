package com.zerotheabsolute.surfacefire.client;

/** Fixed spatial bins share nearby combustion between flame motion and a bounded sound pool. */
final class FireActivity {
    static final int VOICES = 3;
    static final int CELL_SIZE = 8, RADIUS = 12, WIDTH = RADIUS * 2 + 1;
    private static final int CAPACITY = WIDTH * WIDTH * WIDTH;
    private static final double AUDIBLE_RANGE_SQUARED = 64 * 64;
    private static final double GROUP_RANGE_SQUARED = 18 * 18;
    private final float[] mass = new float[CAPACITY], wood = new float[CAPACITY];
    private final double[] x = new double[CAPACITY],
            y = new double[CAPACITY],
            z = new double[CAPACITY];
    private final int[] occupied = new int[CAPACITY];
    private final boolean[] selected = new boolean[CAPACITY];
    final Emitter[] emitters = {new Emitter(), new Emitter(), new Emitter()};
    private int count, originX, originY, originZ;
    private double listenerX, listenerY, listenerZ;

    static final class Emitter {
        double x, y, z;
        float energy, wood;
    }

    void clear() {
        for (int i = 0; i < count; i++) {
            int at = occupied[i];
            mass[at] = wood[at] = 0;
            x[at] = y[at] = z[at] = 0;
            selected[at] = false;
        }
        count = 0;
        for (var emitter : emitters) emitter.energy = 0;
    }

    void begin(double px, double py, double pz) {
        clear();
        listenerX = px;
        listenerY = py;
        listenerZ = pz;
        originX = cell(px) - RADIUS;
        originY = cell(py) - RADIUS;
        originZ = cell(pz) - RADIUS;
    }

    private static int cell(double position) {
        return (int) Math.floor(position / CELL_SIZE);
    }

    private int index(double px, double py, double pz) {
        int a = cell(px) - originX, b = cell(py) - originY, c = cell(pz) - originZ;
        return a < 0 || b < 0 || c < 0 || a >= WIDTH || b >= WIDTH || c >= WIDTH
                ? -1
                : (b * WIDTH + c) * WIDTH + a;
    }

    void add(double px, double py, double pz, float energy, float woodFraction) {
        if (!(energy > 0)
                || !Float.isFinite(energy)
                || !Double.isFinite(px)
                || !Double.isFinite(py)
                || !Double.isFinite(pz)) return;
        int at = index(px, py, pz);
        if (at < 0) return;
        if (mass[at] == 0) occupied[count++] = at;
        mass[at] += energy;
        wood[at] += energy * (Float.isFinite(woodFraction) ? Math.clamp(woodFraction, 0, 1) : 0);
        x[at] += px * energy;
        y[at] += py * energy;
        z[at] += pz * energy;
    }

    float intensity(double px, double py, double pz) {
        int at = index(px, py, pz);
        if (at < 0) return 0;
        float nearby = mass[at];
        int a = at % WIDTH, c = at / WIDTH % WIDTH, b = at / (WIDTH * WIDTH);
        if (a > 0) nearby += mass[at - 1] * .35f;
        if (a < WIDTH - 1) nearby += mass[at + 1] * .35f;
        if (c > 0) nearby += mass[at - WIDTH] * .35f;
        if (c < WIDTH - 1) nearby += mass[at + WIDTH] * .35f;
        if (b > 0) nearby += mass[at - WIDTH * WIDTH] * .35f;
        if (b < WIDTH - 1) nearby += mass[at + WIDTH * WIDTH] * .35f;
        return nearby / (8 + nearby);
    }

    void finish() {
        for (var emitter : emitters) {
            int best = -1;
            double score = 0;
            for (int i = 0; i < count; i++) {
                int at = occupied[i];
                if (selected[at]) continue;
                double dx = x[at] / mass[at] - listenerX;
                double dy = y[at] / mass[at] - listenerY;
                double dz = z[at] / mass[at] - listenerZ;
                double distance = dx * dx + dy * dy + dz * dz;
                double candidate = mass[at] / (64 + distance);
                if (distance <= AUDIBLE_RANGE_SQUARED && candidate > score) {
                    score = candidate;
                    best = at;
                }
            }
            if (best < 0) continue;
            double cx = x[best] / mass[best], cy = y[best] / mass[best], cz = z[best] / mass[best];
            double sx = 0, sy = 0, sz = 0;
            float energy = 0, timber = 0;
            for (int i = 0; i < count; i++) {
                int at = occupied[i];
                if (selected[at]) continue;
                double dx = x[at] / mass[at] - cx,
                        dy = y[at] / mass[at] - cy,
                        dz = z[at] / mass[at] - cz;
                if (dx * dx + dy * dy + dz * dz > GROUP_RANGE_SQUARED) continue;
                selected[at] = true;
                energy += mass[at];
                timber += wood[at];
                sx += x[at];
                sy += y[at];
                sz += z[at];
            }
            emitter.x = sx / energy;
            emitter.y = sy / energy;
            emitter.z = sz / energy;
            emitter.energy = energy;
            emitter.wood = timber / energy;
        }
    }
}
