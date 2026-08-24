package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.ClientConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/**
 * Config-backed palette for the colours players can customize in-game: the in-world highlight
 * boxes and the monitor list's Locate/Located buttons. See {@link ColourPickerScreen} for the
 * editor. Everything else in the GUI stays a hardcoded {@code COLOUR_*} constant. Extend
 * {@link ColourKey} if more things earn colour-coding later.
 */
public final class ColourPalette {
    public enum ColourKey {
        LINKED(0xFF40CC52, "Linked machine"),
        CANDIDATE(0xFFF2A60D, "Scan candidate"),
        SELECTED(0xFF33E6F2, "Located machine"),
        LOCATE_BUTTON(0xFFD8C3A5, "Locate button"),
        LOCATED_BUTTON(0xFF9FE8EE, "Located button");

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
    }

    private ColourPalette() {
    }

    /** Current effective ARGB colour for the given key: the config override if set, else the default. */
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

    /**
     * Unpacks {@link #get(ColourKey)} into 0-1 floats for world rendering, which wants separate
     * r/g/b instead of one packed ARGB int. Alpha is ignored; {@link com.mervyn.miforeman.client.WorldHighlightRenderer}
     * applies its own fill/outline alpha on top.
     */
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

    /**
     * Updates an override in memory only. Per {@link ModConfigSpec.ConfigValue#set}'s own
     * javadoc, this does not write the config file. Used for live preview while the player is
     * still typing in {@link ColourPickerScreen}'s hex box. Call {@link #persist()} once they're
     * done (Done/close, or a reset) instead of writing the whole config file on every keystroke.
     */
    public static void set(ColourKey key, String hex) {
        CONFIG.get(key).set(hex == null ? "" : hex);
    }

    /** Writes every current in-memory value to the config file. */
    public static void persist() {
        ClientConfig.SPEC.save();
    }

    public static void resetToDefault(ColourKey key) {
        set(key, "");
        persist();
    }
}
