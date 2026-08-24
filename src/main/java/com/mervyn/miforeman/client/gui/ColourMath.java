package com.mervyn.miforeman.client.gui;

/**
 * RGB/HSV/HSL conversion math for {@link ColourPickerScreen}'s sliders. Kept separate from
 * {@link ColourPalette}, which owns persistence and defaults; this is just numeric conversion,
 * testable on its own. Standard formulas. See
 * references/color-picker/src/utils/colorConversion.ts for the larger OKLCH/HWB-aware original
 * this was scoped down from.
 */
public final class ColourMath {
    private ColourMath() {
    }

    public static int[] rgbFromArgb(int argb) {
        return new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF};
    }

    /** @return {h (0-360), s (0-100), v (0-100)} */
    public static float[] hsvFromRgb(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        float h = hueFromRgb(rf, gf, bf, max, delta);
        float s = max == 0 ? 0 : delta / max;
        float v = max;
        return new float[]{h, s * 100, v * 100};
    }

    public static int[] rgbFromHsv(float h, float s, float v) {
        float sf = s / 100f, vf = v / 100f;
        float c = vf * sf;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = vf - c;
        return rgbFromChroma(h, c, x, m);
    }

    /** @return {h (0-360), s (0-100), l (0-100)} */
    public static float[] hslFromRgb(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        float h = hueFromRgb(rf, gf, bf, max, delta);
        float l = (max + min) / 2f;
        float s = delta == 0 ? 0 : delta / (1 - Math.abs(2 * l - 1));
        return new float[]{h, s * 100, l * 100};
    }

    public static int[] rgbFromHsl(float h, float s, float l) {
        float sf = s / 100f, lf = l / 100f;
        float c = (1 - Math.abs(2 * lf - 1)) * sf;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = lf - c / 2f;
        return rgbFromChroma(h, c, x, m);
    }

    private static float hueFromRgb(float rf, float gf, float bf, float max, float delta) {
        if (delta == 0) return 0;
        float h;
        if (max == rf) h = 60 * (((gf - bf) / delta) % 6);
        else if (max == gf) h = 60 * (((bf - rf) / delta) + 2);
        else h = 60 * (((rf - gf) / delta) + 4);
        return h < 0 ? h + 360 : h;
    }

    private static int[] rgbFromChroma(float h, float c, float x, float m) {
        float rf, gf, bf;
        if (h < 60) { rf = c; gf = x; bf = 0; }
        else if (h < 120) { rf = x; gf = c; bf = 0; }
        else if (h < 180) { rf = 0; gf = c; bf = x; }
        else if (h < 240) { rf = 0; gf = x; bf = c; }
        else if (h < 300) { rf = x; gf = 0; bf = c; }
        else { rf = c; gf = 0; bf = x; }
        return new int[]{
                clamp(Math.round((rf + m) * 255)),
                clamp(Math.round((gf + m) * 255)),
                clamp(Math.round((bf + m) * 255))
        };
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
