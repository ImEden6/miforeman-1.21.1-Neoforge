package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared parchment-panel background (9-slice main texture + rotated clip accent) and
 * window-sizing math for the clipboard's screens. Extracted from {@link ClipboardScreen} so
 * {@link ReviewMachinesScreen} can paint the same background and size itself the same way,
 * reading as "the same clipboard, one level deeper" rather than a different tool -- same
 * rationale {@link NineSliceTexture} was already pulled out for.
 */
public final class ClipboardChrome {
    public static final ResourceLocation TEX_MAIN = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_main.png");
    public static final ResourceLocation TEX_CLIP = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_clip.png");

    public static final int MAIN_TEX_SIZE = 64;
    public static final int MAIN_BORDER = 6;

    // Rotated to sit on the left edge, like a landscape clipboard's spring clip -- see
    // tools/gen_clipboard_textures.py.
    public static final int CLIP_WIDTH = 16;
    public static final int CLIP_HEIGHT = 32;
    public static final int CLIP_OVERHANG = 6;

    public static final int SCREEN_MARGIN = 20;

    private ClipboardChrome() {
    }

    public static int guiWidth(int screenWidth, int minWidth) {
        return Math.max(minWidth, screenWidth - SCREEN_MARGIN * 2);
    }

    public static int guiHeight(int screenHeight, int minHeight) {
        return Math.max(minHeight, screenHeight - SCREEN_MARGIN * 2);
    }

    public static void drawBackground(GuiGraphics guiGraphics, int left, int top, int guiWidth, int guiHeight) {
        NineSliceTexture.blit(guiGraphics, TEX_MAIN, left, top, guiWidth, guiHeight, MAIN_BORDER, MAIN_TEX_SIZE);

        int clipX = left - CLIP_OVERHANG;
        int clipY = top + (guiHeight - CLIP_HEIGHT) / 2;
        guiGraphics.blit(TEX_CLIP, clipX, clipY, 0, 0, CLIP_WIDTH, CLIP_HEIGHT, CLIP_WIDTH, CLIP_HEIGHT);
    }
}
