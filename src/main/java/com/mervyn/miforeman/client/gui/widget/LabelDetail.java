package com.mervyn.miforeman.client.gui.widget;

/** Whether graph labels are drawn as text or, when too small to read, as plain bars. */
public enum LabelDetail {
    TEXT,
    BARS;

    /** Below this line height in screen pixels, text is unreadable and costly to draw. */
    static final double MIN_TEXT_PIXELS = 6;

    public static LabelDetail of(float zoom, double guiScale, int lineHeight) {
        return lineHeight * zoom * guiScale < MIN_TEXT_PIXELS ? BARS : TEXT;
    }
}
