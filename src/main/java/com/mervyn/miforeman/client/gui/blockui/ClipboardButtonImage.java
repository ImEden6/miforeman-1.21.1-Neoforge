package com.mervyn.miforeman.client.gui.blockui;

import com.ldtteam.blockui.BOGuiGraphics;
import com.ldtteam.blockui.controls.ButtonImage;
import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.client.gui.NineSliceTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * BlockUI button widget rendered with a 9-slice clipboard texture.
 */
public class ClipboardButtonImage extends ButtonImage {
    private static final ResourceLocation TEX_NORMAL = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_button.png");
    private static final ResourceLocation TEX_HOVER = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_button_hover.png");
    private static final ResourceLocation TEX_DISABLED = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_button_disabled.png");
    private static final int BORDER = 4;
    private static final int TEX_SIZE = 32;

    public ClipboardButtonImage() {
        super(true);
    }

    @Override
    public void drawSelf(BOGuiGraphics guiGraphics, double mx, double my) {
        ResourceLocation tex = !isEnabled() ? TEX_DISABLED : (isPointInPane(mx, my) ? TEX_HOVER : TEX_NORMAL);
        NineSliceTexture.blit(guiGraphics, tex, getX(), getY(), getWidth(), getHeight(), BORDER, TEX_SIZE);

        // Mirrors AbstractTextElement.drawSelf, which this override replaces entirely (rather than
        // calling super.drawSelf(), which is ButtonImage's -- the very non-uniform-stretch
        // background draw this class exists to bypass): recalc the text box before drawing, or
        // text renders using stale/zeroed offsets and clips against the bottom of the button.
        if (!getPreparedText().isEmpty()) {
            recalcPreparedTextBox();
            innerDrawSelf(guiGraphics, mx, my);
        }
    }
}
