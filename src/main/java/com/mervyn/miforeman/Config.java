package com.mervyn.miforeman;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue AUTOLINK_SCAN_RADIUS_CHUNKS = BUILDER
            .comment("Default chunk radius for the machine auto-detect scan (centered on the player). Can be overridden per-scan in the review panel.")
            .defineInRange("autolinkScanRadiusChunks", 4, 1, 16);

    static final ModConfigSpec SPEC = BUILDER.build();
}
