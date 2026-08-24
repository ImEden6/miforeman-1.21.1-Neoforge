package com.mervyn.miforeman.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared 9-slice blitter for the clipboard's parchment-panel textures (corners
 * fixed, edges/center stretched by tiling). Extracted from
 * {@link ClipboardScreen} so widgets like GraphCanvas and DetailCard can paint
 * the same beveled-parchment background instead of a flat colour fill.
 */
public final class NineSliceTexture {
    private NineSliceTexture() {
    }

    public static void blit(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int width, int height, int border, int texSize) {
        int innerTexSize = texSize - border * 2;
        int innerWidth = width - border * 2;
        int innerHeight = height - border * 2;

        guiGraphics.blit(texture, x, y, 0, 0, border, border, texSize, texSize);
        guiGraphics.blit(texture, x + width - border, y, texSize - border, 0, border, border, texSize, texSize);
        guiGraphics.blit(texture, x, y + height - border, 0, texSize - border, border, border, texSize, texSize);
        guiGraphics.blit(texture, x + width - border, y + height - border, texSize - border, texSize - border, border, border, texSize, texSize);

        blitStretched(guiGraphics, texture, x + border, y, innerWidth, border, border, 0, innerTexSize, border, texSize);
        blitStretched(guiGraphics, texture, x + border, y + height - border, innerWidth, border, border, texSize - border, innerTexSize, border, texSize);
        blitStretched(guiGraphics, texture, x, y + border, border, innerHeight, 0, border, border, innerTexSize, texSize);
        blitStretched(guiGraphics, texture, x + width - border, y + border, border, innerHeight, texSize - border, border, border, innerTexSize, texSize);

        blitStretched(guiGraphics, texture, x + border, y + border, innerWidth, innerHeight, border, border, innerTexSize, innerTexSize, texSize);
    }

    private static void blitStretched(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int width, int height, int u, int v, int uWidth, int vHeight, int texSize) {
        for (int ty = 0; ty < height; ty += vHeight) {
            int drawH = Math.min(vHeight, height - ty);
            for (int tx = 0; tx < width; tx += uWidth) {
                int drawW = Math.min(uWidth, width - tx);
                guiGraphics.blit(texture, x + tx, y + ty, u, v, drawW, drawH, texSize, texSize);
            }
        }
    }
}
