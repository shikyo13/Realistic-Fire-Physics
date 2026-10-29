package com.zerotheabsolute.surfacefire.client;

/** Fixed spatial bins share nearby combustion between flame motion and a bounded sound pool. */
final class FireActivity {
    static final int CELL_SIZE = 8, RADIUS = 12, WIDTH = RADIUS * 2 + 1;
    private static final int CAPACITY = WIDTH * WIDTH * WIDTH;
    private final float[] mass = new float[CAPACITY];
    private final int[] occupied = new int[CAPACITY];
    private final FireSoundField sounds = new FireSoundField();
    final FireSoundField.Emitter[] emitters = sounds.emitters;
    private int count, originX, originY, originZ;

    void clear() {
        resetBins();
        sounds.clear();
    }

    private void resetBins() {
        for (int i = 0; i < count; i++) mass[occupied[i]] = 0;
        count = 0;
    }

    void begin(double px, double py, double pz) {
        resetBins();
        sounds.begin(px, py, pz);
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
        sounds.add(px, py, pz, energy, woodFraction);
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

    void addSound(double px, double py, double pz, float energy, float woodFraction) {
        sounds.add(px, py, pz, energy, woodFraction);
    }

    void finish() {
        sounds.finish();
    }
}
