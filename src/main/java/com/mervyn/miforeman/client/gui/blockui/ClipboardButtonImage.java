package com.mervyn.miforeman.client.gui.blockui;

import com.ldtteam.blockui.BOGuiGraphics;
import com.ldtteam.blockui.controls.ButtonImage;
import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.client.gui.NineSliceTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A {@link ButtonImage} that bypasses BlockUI's own background renderer, which stretches
 * {@code setImage(...)} non-uniformly to fill the button (source aspect ratio is not preserved
 * per-axis), badly distorting clipboard_button.png's chamfered corners on the wide/short buttons
 * this window uses. {@link BOGuiGraphics} extends vanilla {@code GuiGraphics} directly, so the
 * same {@link NineSliceTexture} blitter {@code ClipboardButton} (the vanilla-Screen equivalent of
 * this widget) uses works here unchanged.
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
