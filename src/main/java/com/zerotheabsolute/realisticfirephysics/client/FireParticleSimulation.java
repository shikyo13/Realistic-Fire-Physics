package com.zerotheabsolute.realisticfirephysics.client;

/** Bounded, reusable combustion particles with drag, thermal lift and cooling. */
final class FireParticleSimulation {
    static final int MAX_SMOKE = 320, MAX_SPARKS = 448;
    static final int CAPACITY = MAX_SMOKE + MAX_SPARKS;
    static final double MAX_STEP_SECONDS = .05;
    private static final double MAX_CATCH_UP_SECONDS = .25;
    private static final double SMOKE_DRAG = 2.1, SPARK_DRAG = 1.8;
    private final Particle[] particles = new Particle[CAPACITY];
    private final double[] wind = new double[3];
    private int size, smokeCount, sparkCount;

    @FunctionalInterface
    interface Airflow {
        void sample(double time, double x, double y, double z, double[] out);
    }

    static final class Particle {
        boolean smoke;
        double x, y, z, oldX, oldY, oldZ, vx, vy, vz, age, lifetime;
        double radius, rotation, spin, phase, heat, distance, scale;
    }

    FireParticleSimulation() {
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
    }

    int size() {
        return size;
    }

    Particle get(int index) {
        return particles[index];
    }

    void clear() {
        size = smokeCount = sparkCount = 0;
    }

    boolean spawn(boolean smoke, double x, double y, double z, double heat, long seed) {
        if (!Double.isFinite(x + y + z + heat)
                || (smoke ? smokeCount >= MAX_SMOKE : sparkCount >= MAX_SPARKS)) return false;
        var p = particles[size++];
        p.smoke = smoke;
        p.heat = Math.clamp(heat, 0, 1);
        p.age = 0;
        p.scale = 1;
        p.lifetime = smoke ? 4.8 + random(seed + 1) * 2.8 : 2.5 + random(seed + 1) * 3;
        p.x = p.oldX = x + (random(seed + 2) - .5) * .5;
        p.y = p.oldY = y + random(seed + 3) * .15;
        p.z = p.oldZ = z + (random(seed + 4) - .5) * .5;
        p.vx = (random(seed + 5) - .5) * (smoke ? .3 : 1.1);
        p.vy = smoke ? .55 : 1.1 + random(seed + 6) * 1.7;
        p.vz = (random(seed + 7) - .5) * (smoke ? .3 : 1.1);
        p.radius = smoke ? .35 + random(seed + 8) * .3 : .025 + random(seed + 8) * .025;
        p.rotation = random(seed + 9) * Math.PI * 2;
        p.spin = (random(seed + 10) - .5) * .5;
        p.phase = random(seed + 11);
        if (smoke) smokeCount++;
        else sparkCount++;
        return true;
    }

    boolean spawnSmall(
            boolean smoke,
            double x,
            double y,
            double z,
            double scale,
            double vx,
            double vy,
            double vz,
            long seed) {
        if (!Double.isFinite(scale + vx + vy + vz)
                || scale <= 0
                || scale > 1
                || !spawn(smoke, x, y, z, 1, seed)) return false;
        var p = particles[size - 1];
        p.scale = scale;
        p.x = p.oldX = x + (p.x - x) * scale;
        p.y = p.oldY = y + (p.y - y) * scale;
        p.z = p.oldZ = z + (p.z - z) * scale;
        p.radius *= scale;
        p.lifetime *= .65 + .35 * scale;
        p.heat *= Math.sqrt(scale);
        p.vx = p.vx * scale + vx * .35;
        p.vy = p.vy * Math.sqrt(scale) + vy * .35;
        p.vz = p.vz * scale + vz * .35;
        return true;
    }

    void advance(double seconds, double time, Airflow airflow) {
        if (!Double.isFinite(seconds) || seconds <= 0 || !Double.isFinite(time)) return;
        double elapsed = Math.min(seconds, MAX_CATCH_UP_SECONDS);
        int steps = (int) Math.ceil(elapsed / MAX_STEP_SECONDS);
        double dt = elapsed / steps;
        for (int i = 0; i < size; ) {
            var p = particles[i];
            p.oldX = p.x;
            p.oldY = p.y;
            p.oldZ = p.z;
            // Expiry uses real elapsed time; a stalled frame must not preserve old particles.
            p.age += seconds;
            if (p.age >= p.lifetime) {
                remove(i);
                continue;
            }
            for (int step = 0; step < steps; step++) {
                airflow.sample(time - elapsed + (step + 1) * dt, p.x, p.y, p.z, wind);
                double cooling = 1 - p.age / p.lifetime;
                double lift = p.smoke ? .45 + .75 * p.heat * cooling : 2.3 * p.heat * cooling - .7;
                double drag = p.smoke ? SMOKE_DRAG : SPARK_DRAG;
                double blend = -Math.expm1(-drag * dt);
                p.vx += (wind[0] - p.vx) * blend;
                p.vy += (wind[1] + lift - p.vy) * blend;
                p.vz += (wind[2] - p.vz) * blend;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.z += p.vz * dt;
                p.rotation += p.spin * dt;
            }
            if (!Double.isFinite(p.x + p.y + p.z)) {
                remove(i);
                continue;
            }
            i++;
        }
    }

    void remove(int index) {
        var expired = particles[index];
        if (expired.smoke) smokeCount--;
        else sparkCount--;
        particles[index] = particles[--size];
        particles[size] = expired;
    }

    static double random(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
