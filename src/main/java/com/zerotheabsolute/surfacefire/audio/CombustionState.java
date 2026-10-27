package com.zerotheabsolute.surfacefire.audio;

/** Dimensionless combustion controls; airflow is emitter-relative speed in blocks per second. */
public record CombustionState(float heat, float fuel, float moisture, float airflow, float wood) {
    public static final CombustionState SILENT = new CombustionState(0, 0, 0, 0, 0);

    public CombustionState {
        heat = bounded(heat, 1);
        fuel = bounded(fuel, 1);
        moisture = bounded(moisture, 1);
        airflow = bounded(airflow, 12);
        wood = bounded(wood, 1);
    }

    private static float bounded(float value, float maximum) {
        return Float.isFinite(value) ? Math.clamp(value, 0, maximum) : 0;
    }
}
