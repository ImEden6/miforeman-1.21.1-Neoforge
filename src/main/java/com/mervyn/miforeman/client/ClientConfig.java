package com.mervyn.miforeman.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-only configuration for highlight and UI colors.
 */
public class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Stored as "#AARRGGBB" hex strings so they're readable/editable straight in the config file.
    // Empty = use the built-in default for that key.
    public static final ModConfigSpec.ConfigValue<String> COLOUR_LINKED = BUILDER
            .comment("In-world highlight colour for linked machines (ARGB hex, e.g. #FF40CC52). Empty = default.")
            .define("colourLinked", "");

    public static final ModConfigSpec.ConfigValue<String> COLOUR_CANDIDATE = BUILDER
            .comment("In-world highlight colour for scan candidates (ARGB hex). Empty = default.")
            .define("colourCandidate", "");

    public static final ModConfigSpec.ConfigValue<String> COLOUR_SELECTED = BUILDER
            .comment("In-world highlight colour for the located machine (ARGB hex). Empty = default.")
            .define("colourSelected", "");

    public static final ModConfigSpec.ConfigValue<String> COLOUR_LOCATE_BUTTON = BUILDER
            .comment("Monitor list \"Locate\" button colour (ARGB hex). Empty = default.")
            .define("colourLocateButton", "");

    public static final ModConfigSpec.ConfigValue<String> COLOUR_LOCATED_BUTTON = BUILDER
            .comment("Monitor list \"Located\" button colour (ARGB hex). Empty = default.")
            .define("colourLocatedButton", "");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }
}
