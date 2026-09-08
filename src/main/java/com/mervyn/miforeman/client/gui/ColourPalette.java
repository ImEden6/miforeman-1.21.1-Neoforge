package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.ClientConfig;
import com.mervyn.miforeman.goal.MachineStatus;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/**
 * Configuration-backed palette for customizable UI and highlight colors.
 */
public final class ColourPalette {
    public enum ColourKey {
        LINKED(0xFF40CC52, "Linked machine"),
        CANDIDATE(0xFFF2A60D, "Scan candidate"),
        SELECTED(0xFF33E6F2, "Located machine"),
        LOCATE_BUTTON(0xFFD8C3A5, "Locate button"),
        LOCATED_BUTTON(0xFF9FE8EE, "Located button"),
        // Translucent so icons and text drawn on top stay legible. Derived from MachineStatus's
        // RED/GREEN to keep hues synchronized. Inlined rather than calling ColourPalette.withAlpha(...)
        // because calling the outer class during nested enum initialization triggers premature
        // class loading, where static initializers access unassigned enum constants.
        INPUT_PANEL((0x33 << 24) | (MachineStatus.RED.colour() & 0xFFFFFF), "Detail card inputs panel"),
        OUTPUT_PANEL((0x33 << 24) | (MachineStatus.GREEN.colour() & 0xFFFFFF), "Detail card outputs panel");

        public final int defaultArgb;
        public final String label;

        ColourKey(int defaultArgb, String label) {
            this.defaultArgb = defaultArgb;
            this.label = label;
        }
    }

    private static final Map<ColourKey, ModConfigSpec.ConfigValue<String>> CONFIG = new EnumMap<>(ColourKey.class);
    static {
        CONFIG.put(ColourKey.LINKED, ClientConfig.COLOUR_LINKED);
        CONFIG.put(ColourKey.CANDIDATE, ClientConfig.COLOUR_CANDIDATE);
        CONFIG.put(ColourKey.SELECTED, ClientConfig.COLOUR_SELECTED);
        CONFIG.put(ColourKey.LOCATE_BUTTON, ClientConfig.COLOUR_LOCATE_BUTTON);
        CONFIG.put(ColourKey.LOCATED_BUTTON, ClientConfig.COLOUR_LOCATED_BUTTON);
        CONFIG.put(ColourKey.INPUT_PANEL, ClientConfig.COLOUR_INPUT_PANEL);
        CONFIG.put(ColourKey.OUTPUT_PANEL, ClientConfig.COLOUR_OUTPUT_PANEL);
    }

    private ColourPalette() {
    }

    /** Returns the effective ARGB color for a key using config overrides when present. */
    public static int get(ColourKey key) {
        String hex = CONFIG.get(key).get();
        if (hex == null || hex.isBlank()) {
            return key.defaultArgb;
        }
        try {
            return (int) Long.parseLong(hex.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return key.defaultArgb;
        }
    }

    /** Unpacks the ARGB color for a key into normalized RGB float values. */
    public static float[] getRgbFloats(ColourKey key) {
        int argb = get(key);
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        return new float[]{r, g, b};
    }

    public static String toHex(int argb) {
        return String.format("#%08X", argb);
    }

    /** Updates a color key override in memory. */
    public static void set(ColourKey key, String hex) {
        CONFIG.get(key).set(hex == null ? "" : hex);
    }

    /** Persists in-memory palette overrides to the client config file. */
    public static void persist() {
        ClientConfig.SPEC.save();
    }

    public static void resetToDefault(ColourKey key) {
        set(key, "");
        persist();
    }
}
