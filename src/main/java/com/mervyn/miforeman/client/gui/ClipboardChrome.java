package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared parchment panel background rendering and window sizing math.
 */
public final class ClipboardChrome {
    public static final ResourceLocation TEX_MAIN = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_main.png");
    public static final ResourceLocation TEX_CLIP = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_clip.png");
    public static final ResourceLocation TEX_CLIP_TOP = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_clip_top.png");

    public static final int MAIN_TEX_SIZE = 64;
    public static final int MAIN_BORDER = 6;

    // Rotated to sit on the left edge, like a landscape clipboard's spring clip -- see
    // tools/gen_clipboard_textures.py.
    public static final int CLIP_WIDTH = 16;
    public static final int CLIP_HEIGHT = 32;
    public static final int CLIP_OVERHANG = 6;

    // Unrotated variant mounted on the top edge, used only by EmiTargetPickerScreen.
    public static final int CLIP_TOP_WIDTH = 32;
    public static final int CLIP_TOP_HEIGHT = 16;
    public static final int CLIP_TOP_OVERHANG = 6;

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

    /**
     * Same panel background as {@link #drawBackground}, but with the clip mounted on the
     * top edge instead of the left edge. Used only by {@link EmiTargetPickerScreen}, which
     * has no other header content to share the top edge with.
     */
    public static void drawBackgroundWithTopClip(GuiGraphics guiGraphics, int left, int top, int guiWidth, int guiHeight) {
        NineSliceTexture.blit(guiGraphics, TEX_MAIN, left, top, guiWidth, guiHeight, MAIN_BORDER, MAIN_TEX_SIZE);

        int clipX = left + (guiWidth - CLIP_TOP_WIDTH) / 2;
        int clipY = top - CLIP_TOP_OVERHANG;
        guiGraphics.blit(TEX_CLIP_TOP, clipX, clipY, 0, 0, CLIP_TOP_WIDTH, CLIP_TOP_HEIGHT, CLIP_TOP_WIDTH, CLIP_TOP_HEIGHT);
    }
}
