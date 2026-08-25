package com.mervyn.miforeman;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue AUTOLINK_SCAN_RADIUS_CHUNKS = BUILDER
            .comment("Default chunk radius for the machine auto-detect scan (centered on the player). Can be overridden per-scan in the review panel.")
            .defineInRange("autolinkScanRadiusChunks", 4, 1, 16);

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
                    + "runtime instead of registering through the vanilla RecipeManager -- invisible to "
                    + "plans/graphs otherwise. This isn't just an addon thing: MI's OWN FurnaceMachineRecipeType, "
                    + "CuttingMachineRecipeType, and CentrifugeMachineRecipeType already work this way, and "
                    + "FurnaceMachineRecipeType additionally synthesizes a MachineRecipe for every vanilla "
                    + "smelting recipe on top of whatever's already registered. So enabling this changes "
                    + "candidate recipe sets -- and therefore default ambiguous-recipe selection -- across plans, "
                    + "even with zero addons installed. Off by default so existing plans/graphs are unaffected. "
                    + "Only affects server-side plan computation (commands, packet handlers) -- the live plan "
                    + "preview in ClipboardScreen runs client-side and will NOT reflect proxied recipes even with "
                    + "this enabled, since the API has no client-side equivalent.")
            .define("includeProxiedRecipeTypes", false);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
