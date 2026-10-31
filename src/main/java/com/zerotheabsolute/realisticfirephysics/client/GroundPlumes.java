package com.zerotheabsolute.realisticfirephysics.client;

import java.util.Arrays;

/** Heat-weighted roots track the fine fire front without drawing one flame per sample. */
final class GroundPlumes {
    static final int WIDTH = 4, COUNT = 4;
    final double[] x = new double[COUNT], z = new double[COUNT], age = new double[COUNT];
    final float[] weight = new float[COUNT], heat = new float[COUNT];
    final boolean[] vegetation = new boolean[COUNT];
    boolean occupied;

    void clear() {
        Arrays.fill(x, 0);
        Arrays.fill(z, 0);
        Arrays.fill(age, 0);
        Arrays.fill(weight, 0);
        Arrays.fill(heat, 0);
        Arrays.fill(vegetation, false);
        occupied = false;
    }

    void add(int cellX, int cellZ, float strength, double fraction, boolean grass) {
        int group = (cellX & 7) / WIDTH + ((cellZ & 7) / WIDTH) * 2;
        x[group] += (cellX + .5) / 8.0 * strength;
        z[group] += (cellZ + .5) / 8.0 * strength;
        age[group] += fraction * strength;
        weight[group] += strength;
        heat[group] = Math.max(heat[group], strength);
        vegetation[group] |= grass;
        occupied = true;
    }
}
