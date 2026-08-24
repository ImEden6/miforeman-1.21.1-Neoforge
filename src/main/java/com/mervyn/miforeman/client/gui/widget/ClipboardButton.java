package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.client.gui.NineSliceTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A {@link Button} skinned with the clipboard's chamfered-leather texture instead of vanilla's
 * sprite background, via the same {@link NineSliceTexture} engine {@code ClipboardChrome} uses
 * for the main panel. clipboard_button.png was generated 9-slice-safe specifically for this.
 */
public class ClipboardButton extends Button {
    private static final ResourceLocation TEX_NORMAL = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_button.png");
    private static final ResourceLocation TEX_HOVER = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_button_hover.png");
    private static final ResourceLocation TEX_DISABLED = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_button_disabled.png");
    private static final int BORDER = 4;
    private static final int TEX_SIZE = 32;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DISABLED = 0xFFA0A0A0;

    public ClipboardButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ResourceLocation tex = !active ? TEX_DISABLED : (isHoveredOrFocused() ? TEX_HOVER : TEX_NORMAL);
        NineSliceTexture.blit(guiGraphics, tex, getX(), getY(), getWidth(), getHeight(), BORDER, TEX_SIZE);

        int color = active ? COLOR_TEXT : COLOR_TEXT_DISABLED;
        guiGraphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
    }
}
