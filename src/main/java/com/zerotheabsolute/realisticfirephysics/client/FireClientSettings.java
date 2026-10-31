package com.zerotheabsolute.realisticfirephysics.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Local display preferences do not affect combustion or multiplayer spread. */
public final class FireClientSettings {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue HEAT_HAZE;
    public static final ModConfigSpec.DoubleValue HEAT_HAZE_STRENGTH;
    public static final ModConfigSpec.EnumValue<GrassGrowthStyle> GRASS_GROWTH_STYLE;

    static {
        var builder = new ModConfigSpec.Builder();
        HEAT_HAZE =
                builder.comment("Refract the world behind hot air above flames.")
                        .define("heatHaze", true);
        HEAT_HAZE_STRENGTH =
                builder.comment("Heat distortion strength; zero disables distortion.")
                        .defineInRange("heatHazeStrength", 1.0, 0.0, 2.0);
        GRASS_GROWTH_STYLE =
                builder.comment(
                                "Grass recovery appearance: scattered pixels, connected patches, or"
                                    + " native texture details.")
                        .defineEnum("grassGrowthStyle", GrassGrowthStyle.SCATTERED);
        SPEC = builder.build();
    }

    private FireClientSettings() {}
}
