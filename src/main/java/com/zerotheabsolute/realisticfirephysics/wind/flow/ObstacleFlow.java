package com.zerotheabsolute.realisticfirephysics.wind.flow;

/** Linear pressure responses reuse a terrain solve while the incident gust changes continuously. */
public final class ObstacleFlow {
    public static final int CORE = 8, HALO = 2, SIZE = CORE + HALO * 2;
    private static final int ITERATIONS = 64;
    private static final double DIVERGENCE_TOLERANCE = .002;

    @FunctionalInterface
    public interface Terrain {
        boolean solid(int x, int y, int z);

        default boolean outdoors(int x, int y, int z) {
            return true;
        }
    }

    /** One workspace serves all cached tiles; no pressure solver is retained per tile. */
    public static final class Workspace {
        private final MacGrid grid = new MacGrid(SIZE, SIZE, SIZE, 1, MacGrid.Boundary.OPEN);
        private final PressureProjection pressure = new PressureProjection(grid);
        private final byte[] terrain = new byte[SIZE * SIZE * SIZE];
        private final byte[] exposure = new byte[SIZE * SIZE * SIZE];

        public long arrayBytes() {
            return grid.arrayBytes() + pressure.arrayBytes() + terrain.length + exposure.length;
        }
    }

    private final byte[] terrain = new byte[SIZE * SIZE * SIZE];
    private final byte[] exposure = new byte[SIZE * SIZE * SIZE];
    private float[][] responses;
    private boolean initialized;
    private double divergence;

    public boolean rebuild(Workspace workspace, Terrain access, int ox, int oy, int oz) {
        var grid = workspace.grid;
        boolean changed = !initialized;
        int solids = 0, sheltered = 0;
        for (int z = 0; z < SIZE; z++)
            for (int y = 0; y < SIZE; y++)
                for (int x = 0; x < SIZE; x++) {
                    int at = grid.cell(x, y, z);
                    byte kind =
                            access.solid(ox + x - HALO, oy + y - HALO, oz + z - HALO)
                                    ? MacGrid.SOLID
                                    : MacGrid.FLUID;
                    workspace.terrain[at] = kind;
                    changed |= terrain[at] != kind;
                    byte exposed =
                            (byte)
                                    (kind == MacGrid.SOLID
                                                    || !access.outdoors(
                                                            ox + x - HALO,
                                                            oy + y - HALO,
                                                            oz + z - HALO)
                                            ? 0
                                            : 1);
                    workspace.exposure[at] = exposed;
                    changed |= exposure[at] != exposed;
                    if (exposed == 0) sheltered++;
                    if (kind == MacGrid.SOLID) solids++;
                }
        if (!changed) return false;
        initialized = true;
        System.arraycopy(workspace.terrain, 0, terrain, 0, terrain.length);
        System.arraycopy(workspace.exposure, 0, exposure, 0, exposure.length);
        if (solids == 0 && sheltered == 0) {
            responses = null;
            divergence = 0;
            return true;
        }
        for (int z = 0; z < SIZE; z++)
            for (int y = 0; y < SIZE; y++)
                for (int x = 0; x < SIZE; x++) grid.material(x, y, z, terrain[grid.cell(x, y, z)]);
        int faces = grid.u.length;
        if (responses == null) responses = new float[9][faces];
        divergence = 0;
        for (int axis = 0; axis < 3; axis++) {
            grid.velocity(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
            if (sheltered != 0) {
                for (int z = 0; z < SIZE; z++)
                    for (int y = 0; y < SIZE; y++)
                        for (int x = 0; x <= SIZE; x++)
                            grid.u[grid.faceX(x, y, z)] *=
                                    Math.max(exposure(x - 1, y, z), exposure(x, y, z));
                for (int z = 0; z < SIZE; z++)
                    for (int y = 0; y <= SIZE; y++)
                        for (int x = 0; x < SIZE; x++)
                            grid.v[grid.faceY(x, y, z)] *=
                                    Math.max(exposure(x, y - 1, z), exposure(x, y, z));
                for (int z = 0; z <= SIZE; z++)
                    for (int y = 0; y < SIZE; y++)
                        for (int x = 0; x < SIZE; x++)
                            grid.w[grid.faceZ(x, y, z)] *=
                                    Math.max(exposure(x, y, z - 1), exposure(x, y, z));
            }
            workspace.pressure.project(ITERATIONS, DIVERGENCE_TOLERANCE);
            divergence = Math.max(divergence, grid.maximumDivergence());
            System.arraycopy(grid.u, 0, responses[axis * 3], 0, faces);
            System.arraycopy(grid.v, 0, responses[axis * 3 + 1], 0, faces);
            System.arraycopy(grid.w, 0, responses[axis * 3 + 2], 0, faces);
        }
        return true;
    }

    /** Position is relative to the core origin; input and output may use the same array. */
    public void sample(double x, double y, double z, double[] velocity) {
        if (responses == null) return;
        x += HALO;
        y += HALO;
        z += HALO;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (bx >= 0
                && by >= 0
                && bz >= 0
                && bx < SIZE
                && by < SIZE
                && bz < SIZE
                && terrain[bx + SIZE * (by + SIZE * bz)] == MacGrid.SOLID) {
            velocity[0] = velocity[1] = velocity[2] = 0;
            return;
        }
        double vx = velocity[0], vy = velocity[1], vz = velocity[2];
        velocity[0] = component(0, SIZE + 1, SIZE, SIZE, x, y - .5, z - .5, vx, vy, vz);
        velocity[1] = component(1, SIZE, SIZE + 1, SIZE, x - .5, y, z - .5, vx, vy, vz);
        velocity[2] = component(2, SIZE, SIZE, SIZE + 1, x - .5, y - .5, z, vx, vy, vz);
    }

    private double component(
            int component,
            int nx,
            int ny,
            int nz,
            double x,
            double y,
            double z,
            double vx,
            double vy,
            double vz) {
        return vx * MacGrid.sample(responses[component], nx, ny, nz, x, y, z)
                + vy * MacGrid.sample(responses[3 + component], nx, ny, nz, x, y, z)
                + vz * MacGrid.sample(responses[6 + component], nx, ny, nz, x, y, z);
    }

    private int exposure(int x, int y, int z) {
        x = Math.clamp(x, 0, SIZE - 1);
        y = Math.clamp(y, 0, SIZE - 1);
        z = Math.clamp(z, 0, SIZE - 1);
        return exposure[x + SIZE * (y + SIZE * z)];
    }

    public double divergence() {
        return divergence;
    }

    public long arrayBytes() {
        return terrain.length
                + exposure.length
                + (responses == null ? 0 : 36L * responses[0].length);
    }
}
