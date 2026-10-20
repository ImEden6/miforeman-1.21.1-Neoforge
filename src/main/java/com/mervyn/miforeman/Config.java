package com.mervyn.miforeman;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue AUTOLINK_SCAN_RADIUS_CHUNKS = BUILDER
            .comment("Chunk radius for the machine auto-detect scan, centered on the player.")
            .defineInRange("autolinkScanRadiusChunks", 4, 1, com.mervyn.miforeman.goal.MachineScanner.MAX_SCAN_RADIUS);

    public static final ModConfigSpec.IntValue AUTOLINK_SCAN_MAX_RADIUS_CHUNKS = BUILDER
            .comment("Largest chunk radius a player may pick for a single scan from the clipboard. "
                    + "Lower it on busy servers: only already-loaded chunks are scanned, but each scan walks "
                    + "every block entity in range. The default scan never exceeds this either.")
            .defineInRange("autolinkScanMaxRadiusChunks", 16, 1, com.mervyn.miforeman.goal.MachineScanner.MAX_SCAN_RADIUS);

    public static final ModConfigSpec.IntValue MONITORING_WINDOW_TICKS = BUILDER
            .comment("Rolling energy event history window in ticks per linked machine (default: 72000 ticks = 1 hour).")
            .defineInRange("monitoringWindowTicks", 72000, 1200, 144000);

    public static final ModConfigSpec.IntValue TRACKER_PRUNE_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between periodic stale tracker pruning passes (default: 200 ticks = 10 seconds).")
            .defineInRange("trackerPruneIntervalTicks", 200, 20, 1200);

    public static final ModConfigSpec.DoubleValue DEFAULT_EFFICIENCY_THRESHOLD = BUILDER
            .comment("Default threshold ratio of actual to expected rate for machine performance status (default: 0.8 = 80%).")
            .defineInRange("defaultEfficiencyThreshold", 0.8, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue INCLUDE_PROXIED_RECIPE_TYPES = BUILDER
            .comment("Also index recipes from ProxyableMachineRecipeType, which builds its recipe list at "
                    + "runtime instead of registering through vanilla RecipeManager. "
                    + "FurnaceMachineRecipeType, CuttingMachineRecipeType, and CentrifugeMachineRecipeType "
                    + "work this way in Modern Industrialization, synthesizing recipes on top of registered ones. "
                    + "Enabling this changes candidate recipe sets and default ambiguous-recipe selections across plans. "
                    + "Off by default so existing plans and graphs remain unaffected. "
                    + "Applies to both server-side plan computation and client-side clipboard preview.")
            .define("includeProxiedRecipeTypes", false);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
