package com.zerotheabsolute.surfacefire.wind;

/** Advected, divergence-free turbulence from the curl of a smooth vector potential. */
public final class WindField {
    private static final double LARGE_EDDY_SIZE = 8;
    private static final double SMALL_EDDY_SIZE = 2.5;
    private static final double LARGE_EDDY_SPEED = .85;
    private static final double SMALL_EDDY_SPEED = .38;
    private static final double GUST_SECONDS = 5;
    private static final double CALM_SPEED = .12;
    private static final double GUST_SPEED = 3.2;
    private static final double ADVECTION_X = .63, ADVECTION_Y = .13, ADVECTION_Z = .29;
    private final double[] gradient = new double[9];

    /** Output is in blocks/second. Each instance is confined to its sampling thread. */
    public void sample(double seconds, double x, double y, double z, double[] out) {
        if (!Double.isFinite(seconds)
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            out[0] = out[1] = out[2] = 0;
            return;
        }
        double gust = noise1(seconds / GUST_SECONDS, 193);
        double speed = CALM_SPEED + GUST_SPEED * gust * gust;
        double direction = (noise1(seconds / 13, 719) - .5) * Math.PI * 1.7;
        out[0] = Math.cos(direction) * speed;
        out[1] = 0;
        out[2] = Math.sin(direction) * speed;
        curl(seconds, x, y, z, LARGE_EDDY_SIZE, LARGE_EDDY_SPEED * (.25 + gust), out);
        curl(seconds, x, y, z, SMALL_EDDY_SIZE, SMALL_EDDY_SPEED * (.25 + gust), out);
    }

    private void curl(
            double time, double x, double y, double z, double size, double speed, double[] out) {
        x = (x - time * ADVECTION_X) / size;
        y = (y - time * ADVECTION_Y) / size;
        z = (z - time * ADVECTION_Z) / size;
        gradient(x, y, z, 11, gradient, 0);
        gradient(x, y, z, 37, gradient, 3);
        gradient(x, y, z, 73, gradient, 6);
        // Scaling the potential by the eddy size cancels the spatial derivative's 1/size.
        out[0] += speed * (gradient[7] - gradient[5]);
        out[1] += speed * (gradient[2] - gradient[6]);
        out[2] += speed * (gradient[3] - gradient[1]);
    }

    private static void gradient(double x, double y, double z, long seed, double[] out, int at) {
        long ix = (long) Math.floor(x), iy = (long) Math.floor(y), iz = (long) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double sx = fade(fx), sy = fade(fy), sz = fade(fz);
        double dx = derivative(fx), dy = derivative(fy), dz = derivative(fz);
        double gx = 0, gy = 0, gz = 0;
        for (int k = 0; k < 2; k++)
            for (int j = 0; j < 2; j++)
                for (int i = 0; i < 2; i++) {
                    double value = lattice(ix + i, iy + j, iz + k, seed);
                    double wx = i == 0 ? 1 - sx : sx;
                    double wy = j == 0 ? 1 - sy : sy;
                    double wz = k == 0 ? 1 - sz : sz;
                    gx += value * (i == 0 ? -dx : dx) * wy * wz;
                    gy += value * wx * (j == 0 ? -dy : dy) * wz;
                    gz += value * wx * wy * (k == 0 ? -dz : dz);
                }
        out[at] = gx;
        out[at + 1] = gy;
        out[at + 2] = gz;
    }

    private static double noise1(double t, long seed) {
        long i = (long) Math.floor(t);
        double a = lattice(i, 0, 0, seed), b = lattice(i + 1, 0, 0, seed);
        return .5 + .5 * (a + (b - a) * fade(t - i));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double derivative(double t) {
        return 30 * t * t * (t - 1) * (t - 1);
    }

    private static double lattice(long x, long y, long z, long seed) {
        long h = x * 0x9e3779b97f4a7c15L ^ y * 0xc2b2ae3d27d4eb4fL ^ z * 0x165667b19e3779f9L ^ seed;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return ((h ^ (h >>> 31)) >>> 11) * 0x1.0p-52 - 1;
    }
}
