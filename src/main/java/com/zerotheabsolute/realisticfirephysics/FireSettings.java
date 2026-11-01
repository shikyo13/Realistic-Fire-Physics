package com.zerotheabsolute.realisticfirephysics;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owned gameplay limits. Resolution is fixed because it is part of the save format. */
public final class FireSettings {
    public static final int CELLS_PER_BLOCK = 8;
    public static final int MAX_SUPPORTED_SAMPLES = 262_144;
    public static final int GROUND_FLAME_TICKS = 26;
    public static final int VEGETATION_FLAME_TICKS = 105;
    public static final int SMOLDER_TICKS = 400;
    public static final int QUENCHED_EMBER_FADE_TICKS = 20;
    public static final int TORCH_DRY_TICKS = 38;
    public static final int TORCH_IGNITE_TICKS = 75;
    public static final int TORCH_DRY_RADIUS = 3;
    public static final int TORCH_DRY_HANDOFF_TICKS = 40;
    public static final int PLACED_TORCH_CONSUME_TICKS = 20;
    public static final int ENVIRONMENT_CHECK_TICKS = 10;
    public static final int TORCH_CHECK_TICKS = 5;
    public static final int MAX_WARMING_TORCHES = 128;
    public static final int TORCH_QUERY_RADIUS = 32;
    public static final int VEGETATION_CONSUME_TICKS = 38;
    public static final int NETWORK_BATCH_SIZE = 512;
    public static final int CONTACT_INTERVAL_TICKS = 10;
    public static final int SMOLDER_DAMAGE_INTERVAL_TICKS = 40;
    public static final float CONTACT_DAMAGE = 1;
    public static final int CONTACT_IGNITE_SECONDS = 2;
    public static final int STRUCTURE_TRANSFER_TICKS = 16;
    public static final int STRUCTURE_ENVIRONMENT_TICKS = 10;
    public static final int MAX_STRUCTURE_CELLS = 1_048_576;
    public static final int MAX_TRACKED_STRUCTURE_BLOCKS = 16_384;
    public static final double STOMP_RADIUS = .55;
    public static final float MINIMUM_STOMP_FALL = .65f;
    public static final float VEGETATION_CONSUMED_FRACTION = .84f;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_SAMPLES;
    public static final ModConfigSpec.IntValue SPREAD_WORK_PER_TICK;
    public static final ModConfigSpec.BooleanValue RAIN_EXTINGUISHES_TORCHES;
    public static final ModConfigSpec.BooleanValue RAIN_EXTINGUISHES_CANDLES;
    public static final ModConfigSpec.BooleanValue LAVA_IGNITES;
    public static final ModConfigSpec.BooleanValue LIGHTNING_IGNITES;
    public static final ModConfigSpec.IntValue STRUCTURE_CELL_LIMIT;
    public static final ModConfigSpec.IntValue STRUCTURE_WORK_PER_TICK;
    public static final ModConfigSpec.DoubleValue SPREAD_SPEED;
    public static final ModConfigSpec.BooleanValue WIND_DRIVEN_SPREAD;
    public static final ModConfigSpec.BooleanValue TERRAIN_WIND;
    public static final ModConfigSpec.BooleanValue RISING_HEAT;
    public static final ModConfigSpec.IntValue HEAT_WORK_PER_TICK;
    public static final ModConfigSpec.BooleanValue EMBER_IGNITION;
    public static final ModConfigSpec.DoubleValue EMBER_RELEASE_CHANCE;
    public static final ModConfigSpec.IntValue RAIN_RECOVERY_MINUTES;
    public static final ModConfigSpec.IntValue HISTORY_RETENTION_SECONDS;
    public static final ModConfigSpec.IntValue RETAINED_GROUND_LIMIT;
    public static final ModConfigSpec.IntValue RETAINED_STRUCTURE_LIMIT;
    public static final ModConfigSpec.IntValue HISTORY_CLEANUP_WORK;
    public static final ModConfigSpec.BooleanValue FALLING_LOGS;
    public static final ModConfigSpec.IntValue MAX_FALLING_BODIES;
    public static final ModConfigSpec.IntValue MAX_FALLING_LOGS;

    static {
        var builder = new ModConfigSpec.Builder();
        MAX_SAMPLES =
                builder.comment(
                                "Maximum ground cells ignited by one connected outbreak. Old char"
                                    + " does not consume this allowance. 64 cells cover one block.")
                        .defineInRange("maxBurnCells", 65_536, 64, MAX_SUPPORTED_SAMPLES);
        SPREAD_WORK_PER_TICK =
                builder.comment("Maximum queued propagation events processed each server tick.")
                        .defineInRange("spreadWorkPerTick", 2_048, 64, 8_192);
        RAIN_EXTINGUISHES_TORCHES = builder.define("rainExtinguishesTorches", true);
        RAIN_EXTINGUISHES_CANDLES = builder.define("rainExtinguishesCandles", true);
        LAVA_IGNITES = builder.define("lavaIgnitesGround", true);
        LIGHTNING_IGNITES = builder.define("lightningIgnitesGround", true);
        STRUCTURE_CELL_LIMIT =
                builder.comment(
                                "Maximum wood/leaf face cells ignited by one connected outbreak."
                                    + " Consumed cells count until that outbreak cools; historical"
                                    + " burns do not reserve new capacity.")
                        .defineInRange("maxStructureBurnCells", 262_144, 64, MAX_STRUCTURE_CELLS);
        STRUCTURE_WORK_PER_TICK =
                builder.comment("Maximum wood/leaf propagation events per tick.")
                        .defineInRange("structureWorkPerTick", 1_024, 64, 8_192);
        SPREAD_SPEED =
                builder.comment(
                                "Propagation speed multiplier. Does not"
                                        + " change simulation resolution or work limits.")
                        .defineInRange("spreadSpeedMultiplier", 1.3, .1, 4);
        WIND_DRIVEN_SPREAD =
                builder.comment("Gusts accelerate downwind ignition and slow upwind spread.")
                        .define("windDrivenSpread", true);
        TERRAIN_WIND =
                builder.comment(
                                "Nearby solid terrain redirects gusts and shelters enclosed"
                                        + " flames.")
                        .define("terrainAwareWind", true);
        RISING_HEAT =
                builder.comment("Established flames heat exposed fuel above and beside them.")
                        .define("risingHeatIgnites", true);
        HEAT_WORK_PER_TICK =
                builder.comment("Maximum terrain probes for rising heat per server tick.")
                        .defineInRange("heatWorkPerTick", 1024, 80, 8192);
        EMBER_IGNITION =
                builder.comment("Rare windborne embers can ignite fuel on contact.")
                        .define("embersIgnite", true);
        EMBER_RELEASE_CHANCE =
                builder.comment(
                                "Chance per established burning block's heat pulse to release a hot"
                                    + " ember. At most 24 hot embers are simulated per dimension.")
                        .defineInRange("emberReleaseChance", .002, 0, 1);
        RAIN_RECOVERY_MINUTES =
                builder.comment(
                                "Minutes of exposed rain for native grass to spread about two"
                                    + " blocks. Growth starts beside living grass and at scattered"
                                    + " soil sites that survive burning. Zero disables recovery."
                                    + " Unloaded or inactive chunks pause recovery.")
                        .defineInRange("rainRecoveryMinutes", 30, 0, 10080);
        HISTORY_RETENTION_SECONDS =
                builder.comment(
                                "Remove fully cooled burn history after this many seconds. Zero"
                                        + " disables age-based cleanup.")
                        .defineInRange("burnHistoryRetentionSeconds", 0, 0, 31_536_000);
        RETAINED_GROUND_LIMIT =
                builder.comment(
                                "Optional retained ground-cell target. Zero disables capacity"
                                        + " cleanup. Only fully cooled history may be removed.")
                        .defineInRange("maxRetainedGroundCells", 0, 0, Integer.MAX_VALUE);
        RETAINED_STRUCTURE_LIMIT =
                builder.comment(
                                "Optional retained scorched-structure block target. Zero disables"
                                        + " capacity cleanup. Active and smoldering blocks are"
                                        + " protected.")
                        .defineInRange("maxRetainedStructureBlocks", 0, 0, Integer.MAX_VALUE);
        HISTORY_CLEANUP_WORK =
                builder.comment(
                                "Maximum history records examined per simulation tick when cleanup"
                                        + " is enabled.")
                        .defineInRange("historyCleanupWorkPerTick", 128, 16, 2048);
        FALLING_LOGS =
                builder.comment("Burned-through logs release unsupported trunks and branches.")
                        .define("fallingLogs", true);
        MAX_FALLING_BODIES =
                builder.comment(
                                "Maximum moving log groups per dimension. Further support checks"
                                        + " wait for capacity.")
                        .defineInRange("maxFallingTimberBodies", 24, 1, 64);
        MAX_FALLING_LOGS =
                builder.comment(
                                "Maximum logs in one connected falling group. Larger structures"
                                        + " remain static.")
                        .defineInRange("maxLogsPerFallingBody", 64, 1, 256);
        SPEC = builder.build();
    }

    private FireSettings() {}

    public static long spreadDelay(long ticks) {
        return Math.max(1, Math.round(ticks / SPREAD_SPEED.get()));
    }
}
