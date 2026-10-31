package com.zerotheabsolute.realisticfirephysics.wind.flow;

import java.util.Arrays;

/**
 * Matrix-free pressure projection with a cached modified incomplete Cholesky preconditioner.
 *
 * @see <a href="https://www.cs.ubc.ca/~rbridson/fluidsimulation/fluids_notes.pdf">Bridson and
 *     Müller-Fischer, chapter 4</a>
 */
public final class PressureProjection {
    private static final int XM = 1, XP = 2, YM = 4, YP = 8, ZM = 16, ZP = 32;
    private static final double MODIFIED_CHOLESKY_WEIGHT = .97;
    private static final double MINIMUM_PIVOT_RATIO = .25;
    private static final double RESIDUAL_SAFETY_MARGIN = .9;
    private final MacGrid grid;
    private final double[] pressure, residual, direction, product, auxiliary, inversePivot;
    private final byte[] neighbors, diagonal;
    private final int[] active;
    private int count, iterations;
    private long topology = Long.MIN_VALUE;
    private double residualMaximum;
    private boolean converged;

    public PressureProjection(MacGrid grid) {
        this.grid = grid;
        pressure = new double[grid.cells];
        residual = new double[grid.cells];
        direction = new double[grid.cells];
        product = new double[grid.cells];
        auxiliary = new double[grid.cells];
        inversePivot = new double[grid.cells];
        neighbors = new byte[grid.cells];
        diagonal = new byte[grid.cells];
        active = new int[grid.cells];
    }

    public int iterations() {
        return iterations;
    }

    public boolean converged() {
        return converged;
    }

    public double residualMaximum() {
        return residualMaximum;
    }

    public long arrayBytes() {
        return 54L * grid.cells;
    }

    /** Tolerance is maximum allowed divergence in inverse seconds, not a visual quality knob. */
    public boolean project(int maximumIterations, double divergenceTolerance) {
        if (maximumIterations < 1
                || !Double.isFinite(divergenceTolerance)
                || divergenceTolerance <= 0)
            throw new IllegalArgumentException("Invalid pressure solve limits");
        grid.enforceWalls();
        if (topology != grid.topology()) rebuild();
        Arrays.fill(pressure, 0);
        residualMaximum = 0;
        for (int n = 0; n < count; n++) {
            int i = active[n], z = i / grid.plane, y = i / grid.nx % grid.ny, x = i % grid.nx;
            double value = -grid.flux(x, y, z);
            if (!Double.isFinite(value))
                throw new IllegalStateException("Non-finite fluid velocity");
            residual[i] = value;
            residualMaximum = Math.max(residualMaximum, Math.abs(value));
        }
        solve(maximumIterations, divergenceTolerance * grid.spacing * RESIDUAL_SAFETY_MARGIN);
        applyGradient();
        // Measure the actual stored velocity, including the final float conversion.
        residualMaximum = grid.maximumDivergence();
        converged = residualMaximum <= divergenceTolerance;
        return converged;
    }

    private void solve(int maximumIterations, double tolerance) {
        precondition();
        double rho = 0;
        for (int n = 0; n < count; n++) {
            int i = active[n];
            direction[i] = auxiliary[i];
            rho += residual[i] * auxiliary[i];
        }
        iterations = 0;
        while (residualMaximum > tolerance && iterations < maximumIterations) {
            multiply(direction, product);
            double denominator = dot(direction, product);
            if (!(denominator > 0) || !Double.isFinite(denominator)) break;
            double alpha = rho / denominator;
            residualMaximum = 0;
            for (int n = 0; n < count; n++) {
                int i = active[n];
                pressure[i] += alpha * direction[i];
                residual[i] -= alpha * product[i];
                residualMaximum = Math.max(residualMaximum, Math.abs(residual[i]));
            }
            iterations++;
            if (residualMaximum <= tolerance || !(rho > 0)) break;
            precondition();
            double nextRho = dot(residual, auxiliary);
            double beta = nextRho / rho;
            for (int n = 0; n < count; n++) {
                int i = active[n];
                direction[i] = auxiliary[i] + beta * direction[i];
            }
            rho = nextRho;
        }
    }

    private void rebuild() {
        count = 0;
        Arrays.fill(neighbors, (byte) 0);
        Arrays.fill(diagonal, (byte) 0);
        for (int z = 0; z < grid.nz; z++)
            for (int y = 0; y < grid.ny; y++)
                for (int x = 0; x < grid.nx; x++) {
                    int i = grid.cell(x, y, z);
                    if (grid.material[i] != MacGrid.FLUID) continue;
                    neighbor(i, grid.material(x - 1, y, z), XM);
                    neighbor(i, grid.material(x + 1, y, z), XP);
                    neighbor(i, grid.material(x, y - 1, z), YM);
                    neighbor(i, grid.material(x, y + 1, z), YP);
                    neighbor(i, grid.material(x, y, z - 1), ZM);
                    neighbor(i, grid.material(x, y, z + 1), ZP);
                    if (diagonal[i] > 0) active[count++] = i;
                }
        factor();
        topology = grid.topology();
    }

    private void factor() {
        Arrays.fill(inversePivot, 0);
        for (int n = 0; n < count; n++) {
            int i = active[n], mask = neighbors[i];
            double pivot = diagonal[i];
            if ((mask & XM) != 0) pivot -= pivotContribution(i - 1, YP | ZP);
            if ((mask & YM) != 0) pivot -= pivotContribution(i - grid.nx, XP | ZP);
            if ((mask & ZM) != 0) pivot -= pivotContribution(i - grid.plane, XP | YP);
            if (pivot < MINIMUM_PIVOT_RATIO * diagonal[i]) pivot = diagonal[i];
            inversePivot[i] = 1 / Math.sqrt(pivot);
        }
    }

    private double pivotContribution(int previous, int crossDirections) {
        int dropped = Integer.bitCount(neighbors[previous] & crossDirections);
        double factor = inversePivot[previous];
        return factor * factor * (1 + MODIFIED_CHOLESKY_WEIGHT * dropped);
    }

    private void precondition() {
        for (int n = 0; n < count; n++) {
            int i = active[n], mask = neighbors[i];
            double value = residual[i];
            if ((mask & XM) != 0) value += inversePivot[i - 1] * auxiliary[i - 1];
            if ((mask & YM) != 0) value += inversePivot[i - grid.nx] * auxiliary[i - grid.nx];
            if ((mask & ZM) != 0) value += inversePivot[i - grid.plane] * auxiliary[i - grid.plane];
            auxiliary[i] = value * inversePivot[i];
        }
        for (int n = count - 1; n >= 0; n--) {
            int i = active[n], mask = neighbors[i];
            double value = auxiliary[i], scale = inversePivot[i];
            if ((mask & XP) != 0) value += scale * auxiliary[i + 1];
            if ((mask & YP) != 0) value += scale * auxiliary[i + grid.nx];
            if ((mask & ZP) != 0) value += scale * auxiliary[i + grid.plane];
            auxiliary[i] = value * scale;
        }
    }

    private void neighbor(int i, byte material, int bit) {
        if (material != MacGrid.SOLID) diagonal[i]++;
        if (material == MacGrid.FLUID) neighbors[i] |= (byte) bit;
    }

    private void multiply(double[] input, double[] output) {
        for (int n = 0; n < count; n++) {
            int i = active[n], mask = neighbors[i];
            double value = diagonal[i] * input[i];
            if ((mask & XM) != 0) value -= input[i - 1];
            if ((mask & XP) != 0) value -= input[i + 1];
            if ((mask & YM) != 0) value -= input[i - grid.nx];
            if ((mask & YP) != 0) value -= input[i + grid.nx];
            if ((mask & ZM) != 0) value -= input[i - grid.plane];
            if ((mask & ZP) != 0) value -= input[i + grid.plane];
            output[i] = value;
        }
    }

    private double dot(double[] a, double[] b) {
        double sum = 0;
        for (int n = 0; n < count; n++) {
            int i = active[n];
            sum += a[i] * b[i];
        }
        return sum;
    }

    private double pressure(int x, int y, int z) {
        return grid.material(x, y, z) == MacGrid.FLUID ? pressure[grid.cell(x, y, z)] : 0;
    }

    private void applyGradient() {
        for (int z = 0; z < grid.nz; z++)
            for (int y = 0; y < grid.ny; y++)
                for (int x = 0; x <= grid.nx; x++)
                    if (grid.material(x - 1, y, z) != MacGrid.SOLID
                            && grid.material(x, y, z) != MacGrid.SOLID)
                        grid.u[grid.faceX(x, y, z)] -=
                                (float) (pressure(x, y, z) - pressure(x - 1, y, z));
        for (int z = 0; z < grid.nz; z++)
            for (int y = 0; y <= grid.ny; y++)
                for (int x = 0; x < grid.nx; x++)
                    if (grid.material(x, y - 1, z) != MacGrid.SOLID
                            && grid.material(x, y, z) != MacGrid.SOLID)
                        grid.v[grid.faceY(x, y, z)] -=
                                (float) (pressure(x, y, z) - pressure(x, y - 1, z));
        for (int z = 0; z <= grid.nz; z++)
            for (int y = 0; y < grid.ny; y++)
                for (int x = 0; x < grid.nx; x++)
                    if (grid.material(x, y, z - 1) != MacGrid.SOLID
                            && grid.material(x, y, z) != MacGrid.SOLID)
                        grid.w[grid.faceZ(x, y, z)] -=
                                (float) (pressure(x, y, z) - pressure(x, y, z - 1));
    }
}
