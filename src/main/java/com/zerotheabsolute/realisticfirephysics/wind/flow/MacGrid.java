package com.zerotheabsolute.realisticfirephysics.wind.flow;

import java.util.Arrays;

/** Face-centered velocity in metres/second; one world block corresponds to one metre. */
public final class MacGrid {
    public enum Boundary {
        CLOSED,
        OPEN
    }

    public static final byte AIR = 0, FLUID = 1, SOLID = 2;
    private static final int MAX_AXIS_CELLS = 128;
    final int nx, ny, nz, plane, cells;
    final float spacing;
    final Boundary boundary;
    final byte[] material;
    final float[] u, v, w;
    private long topology;

    public MacGrid(int nx, int ny, int nz, float spacing, Boundary boundary) {
        if (nx < 1
                || ny < 1
                || nz < 1
                || nx > MAX_AXIS_CELLS
                || ny > MAX_AXIS_CELLS
                || nz > MAX_AXIS_CELLS
                || !Float.isFinite(spacing)
                || spacing <= 0
                || boundary == null)
            throw new IllegalArgumentException("Invalid fluid grid dimensions");
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.spacing = spacing;
        this.boundary = boundary;
        plane = nx * ny;
        cells = plane * nz;
        material = new byte[cells];
        u = new float[(nx + 1) * ny * nz];
        v = new float[nx * (ny + 1) * nz];
        w = new float[nx * ny * (nz + 1)];
    }

    public int sizeX() {
        return nx;
    }

    public int sizeY() {
        return ny;
    }

    public int sizeZ() {
        return nz;
    }

    public float spacing() {
        return spacing;
    }

    public int cellCount() {
        return cells;
    }

    public long arrayBytes() {
        return material.length + 4L * (u.length + v.length + w.length);
    }

    long topology() {
        return topology;
    }

    int cell(int x, int y, int z) {
        return x + nx * y + plane * z;
    }

    int faceX(int x, int y, int z) {
        return x + (nx + 1) * (y + ny * z);
    }

    int faceY(int x, int y, int z) {
        return x + nx * (y + (ny + 1) * z);
    }

    int faceZ(int x, int y, int z) {
        return x + nx * (y + ny * z);
    }

    public byte material(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz)
            return boundary == Boundary.CLOSED ? SOLID : AIR;
        return material[cell(x, y, z)];
    }

    public void material(int x, int y, int z, byte value) {
        checkCell(x, y, z);
        if (value < AIR || value > SOLID)
            throw new IllegalArgumentException("Unknown fluid cell material");
        int i = cell(x, y, z);
        if (material[i] != value) {
            material[i] = value;
            topology++;
        }
    }

    public void fill(byte value) {
        if (value < AIR || value > SOLID)
            throw new IllegalArgumentException("Unknown fluid cell material");
        Arrays.fill(material, value);
        topology++;
    }

    public void velocity(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new IllegalArgumentException("Non-finite velocity");
        Arrays.fill(u, x);
        Arrays.fill(v, y);
        Arrays.fill(w, z);
        enforceWalls();
    }

    public void enforceWalls() {
        for (int z = 0; z < nz; z++)
            for (int y = 0; y < ny; y++)
                for (int x = 0; x <= nx; x++)
                    if (material(x - 1, y, z) == SOLID || material(x, y, z) == SOLID)
                        u[faceX(x, y, z)] = 0;
        for (int z = 0; z < nz; z++)
            for (int y = 0; y <= ny; y++)
                for (int x = 0; x < nx; x++)
                    if (material(x, y - 1, z) == SOLID || material(x, y, z) == SOLID)
                        v[faceY(x, y, z)] = 0;
        for (int z = 0; z <= nz; z++)
            for (int y = 0; y < ny; y++)
                for (int x = 0; x < nx; x++)
                    if (material(x, y, z - 1) == SOLID || material(x, y, z) == SOLID)
                        w[faceZ(x, y, z)] = 0;
    }

    double flux(int x, int y, int z) {
        return (double) u[faceX(x + 1, y, z)]
                - u[faceX(x, y, z)]
                + (double) v[faceY(x, y + 1, z)]
                - v[faceY(x, y, z)]
                + (double) w[faceZ(x, y, z + 1)]
                - w[faceZ(x, y, z)];
    }

    public double maximumDivergence() {
        double maximum = 0;
        for (int z = 0; z < nz; z++)
            for (int y = 0; y < ny; y++)
                for (int x = 0; x < nx; x++)
                    if (material[cell(x, y, z)] == FLUID)
                        maximum = Math.max(maximum, Math.abs(flux(x, y, z)) / spacing);
        return maximum;
    }

    public double maximumSpeed() {
        double x = 0, y = 0, z = 0;
        for (float value : u) x = Math.max(x, Math.abs(value));
        for (float value : v) y = Math.max(y, Math.abs(value));
        for (float value : w) z = Math.max(z, Math.abs(value));
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Samples a local position in cell units into caller-owned storage. */
    public void sample(double x, double y, double z, double[] result) {
        if (result.length < 3 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Invalid velocity sample");
        result[0] = sample(u, nx + 1, ny, nz, x, y - .5, z - .5);
        result[1] = sample(v, nx, ny + 1, nz, x - .5, y, z - .5);
        result[2] = sample(w, nx, ny, nz + 1, x - .5, y - .5, z);
    }

    static float sample(float[] values, int nx, int ny, int nz, double x, double y, double z) {
        x = Math.clamp(x, 0, nx - 1);
        y = Math.clamp(y, 0, ny - 1);
        z = Math.clamp(z, 0, nz - 1);
        int x0 = (int) x, y0 = (int) y, z0 = (int) z;
        int x1 = Math.min(x0 + 1, nx - 1),
                y1 = Math.min(y0 + 1, ny - 1),
                z1 = Math.min(z0 + 1, nz - 1);
        double tx = x - x0, ty = y - y0, tz = z - z0;
        int a = nx * (y0 + ny * z0), b = nx * (y1 + ny * z0);
        int c = nx * (y0 + ny * z1), d = nx * (y1 + ny * z1);
        double low =
                mix(
                        mix(values[a + x0], values[a + x1], tx),
                        mix(values[b + x0], values[b + x1], tx),
                        ty);
        double high =
                mix(
                        mix(values[c + x0], values[c + x1], tx),
                        mix(values[d + x0], values[d + x1], tx),
                        ty);
        return (float) mix(low, high, tz);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private void checkCell(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz)
            throw new IndexOutOfBoundsException("Cell outside fluid grid");
    }
}
