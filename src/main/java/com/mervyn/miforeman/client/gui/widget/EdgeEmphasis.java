package com.mervyn.miforeman.client.gui.widget;

/** How a graph wire is drawn relative to the rest. Each keeps the wire's own colour, so a stalled
 *  machine's red wire stays red whether it is highlighted or dimmed. */
public enum EdgeEmphasis {
    NORMAL,
    HIGHLIGHTED,
    DIMMED;

    static final int DIMMED_MAX_ALPHA = 0x55;
    static final int GLOW_ALPHA = 0x40;

    /** The glow around a highlighted wire: the player's pick, else a faint band of the wire's colour. */
    public static int glow(int wireColour, @org.jetbrains.annotations.Nullable Integer custom) {
        return custom != null ? custom : (GLOW_ALPHA << 24) | (wireColour & 0x00FFFFFF);
    }

    public int apply(int colour) {
        return apply(colour, null);
    }

    /** {@code highlight} replaces a highlighted wire's colour when the player has picked one. */
    public int apply(int colour, @org.jetbrains.annotations.Nullable Integer highlight) {
        int alpha = colour >>> 24;
        return switch (this) {
            case NORMAL -> colour;
            case HIGHLIGHTED -> highlight != null ? highlight : 0xFF000000 | (colour & 0x00FFFFFF);
            case DIMMED -> (Math.min(alpha, DIMMED_MAX_ALPHA) << 24) | (colour & 0x00FFFFFF);
        };
    }
}
