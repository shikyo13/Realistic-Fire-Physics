package com.zerotheabsolute.realisticfirephysics.simulation;

/** Short rising transport paths stop at obstructions and ignite only exposed neighboring fuel. */
public final class RisingHeat {
    public static final int OPEN = 0, FUEL = 1, BLOCKED = 2;
    public static final int MAX_HEIGHT = 8;
    public static final int MAX_PROBES = MAX_HEIGHT * 10;
    private static final double WIND_BEND = .12, MAX_DRIFT = .28;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public interface Access {
        int inspect(int x, int y, int z);

        void ignite(int x, int y, int z, double fromX, double fromY, double fromZ);

        /** Optional caller-owned local velocity; null retains the supplied background wind. */
        default double[] airflow(double x, double y, double z) {
            return null;
        }
    }

    private RisingHeat() {}

    public static int trace(
            double x, double y, double z, double windX, double windZ, int height, Access access) {
        double driftX = Math.clamp(windX * WIND_BEND, -MAX_DRIFT, MAX_DRIFT);
        double driftZ = Math.clamp(windZ * WIND_BEND, -MAX_DRIFT, MAX_DRIFT);
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        int probes = 0;
        double previousX = x, previousY = y - .1, previousZ = z;
        double px = x, py = y, pz = z;
        for (int step = 0;
                step < Math.clamp(height, 0, MAX_HEIGHT) * 2;
                step++, px += driftX * .5, py += .5, pz += driftZ * .5) {
            var flow = access.airflow(px, py, pz);
            if (flow != null) {
                driftX = Math.clamp(flow[0] * WIND_BEND, -MAX_DRIFT, MAX_DRIFT);
                driftZ = Math.clamp(flow[2] * WIND_BEND, -MAX_DRIFT, MAX_DRIFT);
            }
            int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
            if (bx == lastX && by == lastY && bz == lastZ) continue;
            lastX = bx;
            lastY = by;
            lastZ = bz;
            int kind = access.inspect(bx, by, bz);
            probes++;
            if (kind == BLOCKED) break;
            if (kind == FUEL) {
                access.ignite(bx, by, bz, previousX, previousY, previousZ);
                break;
            }
            for (var side : SIDES) {
                int sx = bx + side[0], sz = bz + side[1];
                probes++;
                if (access.inspect(sx, by, sz) == FUEL) access.ignite(sx, by, sz, px, py, pz);
            }
            previousX = px;
            previousY = py;
            previousZ = pz;
        }
        return probes;
    }
}
