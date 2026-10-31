package com.zerotheabsolute.realisticfirephysics.lighting;

/** A radiance-weighted source derived from fractional surface cells, independent of rendering. */
public final class LightCluster {
    private static final int MAX_LIGHT = 15;
    private static final double LIGHT_LEVELS_PER_DOUBLING = 2;
    private static final double[] RADIANCE = new double[MAX_LIGHT + 1];

    static {
        for (int level = 1; level <= MAX_LIGHT; level++) {
            RADIANCE[level] = Math.pow(2, (level - MAX_LIGHT) / LIGHT_LEVELS_PER_DOUBLING);
        }
    }

    private double energy, weightedX, weightedY, weightedZ;
    private boolean flaming;

    public void reset() {
        energy = weightedX = weightedY = weightedZ = 0;
        flaming = false;
    }

    public void add(double x, double y, double z, int light, boolean flame) {
        if (light <= 0) return;
        double power = RADIANCE[Math.min(light, MAX_LIGHT)];
        energy += power;
        weightedX += x * power;
        weightedY += y * power;
        weightedZ += z * power;
        flaming |= flame;
    }

    public Source source() {
        if (energy == 0) return new Source(0, 0, 0, 0);
        double level = FireEmission.coverage(MAX_LIGHT, energy, flaming);
        return new Source(weightedX / energy, weightedY / energy, weightedZ / energy, level);
    }

    public record Source(double x, double y, double z, double intensity) {
        /** Surround the fractional source with light-engine samples; never snap its origin. */
        public void emit(Nodes nodes) {
            emit(nodes, (sx, sy, sz, px, py, pz) -> true);
        }

        public void emit(Nodes nodes, Visibility visible) {
            if (intensity <= 0) return;
            int minX = (int) Math.floor(x - .5),
                    minY = (int) Math.floor(y - .5),
                    minZ = (int) Math.floor(z - .5);
            for (int dx = 0; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        int px = minX + dx, py = minY + dy, pz = minZ + dz;
                        double distance =
                                Math.sqrt(
                                        square(px + .5 - x)
                                                + square(py + .5 - y)
                                                + square(pz + .5 - z));
                        int light = Math.clamp((int) Math.ceil(intensity - distance), 0, MAX_LIGHT);
                        if (light > 0 && visible.between(x, y, z, px, py, pz))
                            nodes.accept(px, py, pz, light);
                    }
                }
            }
            // An extended source represents excess energy without exceeding the light map's
            // per-node range. Six axial samples bound the additional work per source.
            double reach = Math.min(FireEmission.MAXIMUM_SOURCE, intensity) - MAX_LIGHT;
            if (reach < 1) return;
            extended(nodes, visible, x + reach, y, z);
            extended(nodes, visible, x - reach, y, z);
            extended(nodes, visible, x, y + reach, z);
            extended(nodes, visible, x, y - reach, z);
            extended(nodes, visible, x, y, z + reach);
            extended(nodes, visible, x, y, z - reach);
        }

        private void extended(Nodes nodes, Visibility visible, double sx, double sy, double sz) {
            int px = (int) Math.floor(sx), py = (int) Math.floor(sy), pz = (int) Math.floor(sz);
            if (!visible.between(x, y, z, px, py, pz)) return;
            double distance =
                    Math.sqrt(square(px + .5 - x) + square(py + .5 - y) + square(pz + .5 - z));
            int light = Math.clamp((int) Math.ceil(intensity - distance), 0, MAX_LIGHT);
            if (light > 0) nodes.accept(px, py, pz, light);
        }

        private static double square(double value) {
            return value * value;
        }
    }

    @FunctionalInterface
    public interface Nodes {
        void accept(int x, int y, int z, int light);
    }

    @FunctionalInterface
    public interface Visibility {
        boolean between(double x, double y, double z, int targetX, int targetY, int targetZ);
    }
}
