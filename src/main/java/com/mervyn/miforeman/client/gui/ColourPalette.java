package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.ClientConfig;
import com.mervyn.miforeman.goal.MachineStatus;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.Map;

public final class ColourPalette {
    public enum ColourKey {
        LINKED(0xFF40CC52),
        CANDIDATE(0xFFF2A60D),
        SELECTED(0xFF33E6F2),
        LOCATE_BUTTON(0xFFD8C3A5),
        LOCATED_BUTTON(0xFF9FE8EE),
        // Inlined because calling ColourPalette.withAlpha during enum initialization loads the
        // outer class early, before these constants are assigned.
        INPUT_PANEL((0x33 << 24) | (MachineStatus.RED.colour() & 0xFFFFFF)),
        OUTPUT_PANEL((0x33 << 24) | (MachineStatus.GREEN.colour() & 0xFFFFFF)),
        // Unset means each wire keeps its own colour. This default only seeds the picker.
        EDGE_HIGHLIGHT(0xFF8A7A68),
        EDGE_GLOW(0x408A7A68);

        public final int defaultArgb;

        ColourKey(int defaultArgb) {
            this.defaultArgb = defaultArgb;
        }

        public String translationKey() {
            return "miforeman.colour." + name().toLowerCase(java.util.Locale.ROOT);
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
        CONFIG.put(ColourKey.EDGE_HIGHLIGHT, ClientConfig.COLOUR_EDGE_HIGHLIGHT);
        CONFIG.put(ColourKey.EDGE_GLOW, ClientConfig.COLOUR_EDGE_GLOW);
    }

    private ColourPalette() {
    }

    public static int get(ColourKey key) {
        Integer custom = custom(key);
        return custom != null ? custom : key.defaultArgb;
    }

    /** The player's colour for a key, or null when they have not set one. */
    public static @org.jetbrains.annotations.Nullable Integer custom(ColourKey key) {
        String hex = CONFIG.get(key).get();
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String digits = hex.replace("#", "");
        try {
            int argb = (int) Long.parseLong(digits, 16);
            return digits.length() == 6 ? 0xFF000000 | argb : argb;
        } catch (NumberFormatException e) {
            return null;
        }
    }

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

    public static void set(ColourKey key, String hex) {
        CONFIG.get(key).set(hex == null ? "" : hex);
    }

    public static void persist() {
        ClientConfig.SPEC.save();
    }

    public static void resetToDefault(ColourKey key) {
        set(key, "");
        persist();
    }
}
