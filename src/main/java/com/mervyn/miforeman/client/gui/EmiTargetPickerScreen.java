package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.client.gui.widget.ClipboardButton;
import com.mervyn.miforeman.compat.emi.EmiCompat;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Opened from {@link ClipboardScreen}'s Define Goal step via the "From EMI"
 * button. Splits the
 * window in two: the left side is our huge clipboard drop-target panel, the
 * right side is
 * deliberately left bare so EMI's own sidebar has genuine room to render a
 * searchable item/fluid
 * list into (see {@code com.mervyn.miforeman.compat.emi.MiforemanEmiPlugin},
 * which anchors EMI's
 * sidebar to stay outside {@link #getPanelX()}/{@link #getPanelWidth()} etc).
 * Dragging a stack
 * from EMI's sidebar onto the panel sets the parent's goal target and returns
 * to it.
 *
 * <p>
 * Backed by {@link HostMenu}, a trivial client-only zero-slot menu that's never
 * opened via the
 * normal server round-trip. It exists purely so this screen is an
 * {@code AbstractContainerScreen}
 * (Mojang mappings' name for what EMI's own NeoForge code calls
 * {@code HandledScreen}), which
 * EMI's render hooks require via an {@code instanceof} check before they'll
 * draw anything at all.
 * A plain {@link net.minecraft.client.gui.screens.Screen} never clears that
 * check, no matter what's
 * registered through {@code EmiRegistry}.
 */
public class EmiTargetPickerScreen extends AbstractContainerScreen<EmiTargetPickerScreen.HostMenu> {
    private static final int MARGIN = ClipboardChrome.SCREEN_MARGIN;
    private static final double PANEL_WIDTH_FRACTION = 0.45;
    private static final int MIN_PANEL_WIDTH = 260;
    private static final int MIN_PANEL_HEIGHT = 230;
    private static final int PADDING = 8;
    private static final int ICON_SIZE = 96;
    private static final int HINT_ICON_SIZE = 16;

    private static final int COLOUR_TEXT = 0xFF8B6C4B;

    private static final ResourceLocation TEX_CLIPBOARD_ITEM = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID,
            "textures/item/foreman_clipboard.png");
    private static final ResourceLocation TEX_DROP_HINT = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID,
            "textures/gui/emi_drop_hint.png");

    private final ClipboardScreen parent;

    public EmiTargetPickerScreen(ClipboardScreen parent) {
        super(new HostMenu(), Minecraft.getInstance().player.getInventory(), Component.translatable("miforeman.screen.emi_picker.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int totalW = this.width - MARGIN * 2;
        int totalH = this.height - MARGIN * 2;
        this.imageWidth = Math.max(MIN_PANEL_WIDTH, (int) (totalW * PANEL_WIDTH_FRACTION));
        this.imageHeight = Math.max(MIN_PANEL_HEIGHT, totalH);
        this.leftPos = MARGIN;
        this.topPos = (this.height - this.imageHeight) / 2;

        Button cancel = new ClipboardButton(leftPos + PADDING, topPos + imageHeight - PADDING - 16, 80, 16,
                Component.translatable("miforeman.button.cancel"), b -> Minecraft.getInstance().setScreen(parent));
        this.addRenderableWidget(cancel);
    }

    // --- EMI integration hooks (com.mervyn.miforeman.compat.emi) ---
    // Public so that package can register EMI's sidebar bounds/drag-drop around the
    // panel
    // without this class importing anything from dev.emi itself.

    public int getPanelX() {
        return leftPos;
    }

    public int getPanelY() {
        return topPos;
    }

    public int getPanelWidth() {
        return imageWidth;
    }

    public int getPanelHeight() {
        return imageHeight;
    }

    /**
     * Applies a stack dropped from EMI as the parent goal's target, then returns to
     * the parent.
     */
    public void acceptDrop(TargetType type, String idStr) {
        parent.applyDroppedTarget(type, idStr);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        // Bypass AbstractContainerScreen.onClose()'s minecraft.player.closeContainer()
        // call.
        // this.menu was never assigned to the player's real containerMenu, so that
        // would just
        // send a stray close packet for whatever container the player actually has
        // open.
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Unlike plain Screen, AbstractContainerScreen.renderBackground() is what
        // actually calls
        // renderBg() (see its vanilla implementation). A blanket no-op override here,
        // copied from
        // ClipboardScreen/ColourPickerScreen's plain-Screen convention, silently killed
        // our panel
        // too. Skip only renderTransparentBackground()'s dimming/vignette, keep
        // renderBg().
        this.renderBg(guiGraphics, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        ClipboardChrome.drawBackgroundWithTopClip(guiGraphics, leftPos, topPos, imageWidth, imageHeight);

        int iconCenterX = leftPos + imageWidth / 2;
        int iconCenterY = topPos + imageHeight / 2 - 10;

        // Centered above the icon rather than the usual top-left corner. This screen
        // has no other
        // header content to share that corner with, and top-left collides with other
        // mods' own
        // screen-corner icons (seen in testing), so centering avoids that entirely.
        int titleY = iconCenterY - ICON_SIZE / 2 - 16;
        Component titleComp = Component.translatable("miforeman.screen.emi_picker.title");
        guiGraphics.drawString(this.font, titleComp, iconCenterX - this.font.width(titleComp) / 2,
                titleY, COLOUR_TEXT, false);

        Component hintComp = EmiCompat.isLoaded()
                ? Component.translatable("miforeman.screen.emi_picker.hint_loaded")
                : Component.translatable("miforeman.screen.emi_picker.hint_not_loaded");
        guiGraphics.drawString(this.font, hintComp, iconCenterX - this.font.width(hintComp) / 2,
                titleY + this.font.lineHeight + 4, COLOUR_TEXT, false);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(iconCenterX, iconCenterY, 0);
        float scale = ICON_SIZE / 32f;
        guiGraphics.pose().scale(scale, scale, 1f);
        guiGraphics.blit(TEX_CLIPBOARD_ITEM, -16, -16, 0, 0, 32, 32, 32, 32);
        guiGraphics.pose().popPose();

        int hintIconY = iconCenterY + ICON_SIZE / 2 + 6;
        guiGraphics.blit(TEX_DROP_HINT, iconCenterX - HINT_ICON_SIZE / 2, hintIconY, 0, 0,
                HINT_ICON_SIZE, HINT_ICON_SIZE, HINT_ICON_SIZE, HINT_ICON_SIZE);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // No-op. Everything is already drawn in renderBg(), in absolute screen
        // coordinates, so
        // skip vanilla's default title/"Inventory" label draw, which assumes local
        // coordinates
        // and doesn't apply to this menu-less panel.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Trivial client-only, zero-slot menu that exists purely to make this screen an
     * {@code AbstractContainerScreen} for EMI's benefit. Never opened via the
     * normal server
     * round-trip or {@code MenuType} registration, just constructed directly.
     */
    static class HostMenu extends AbstractContainerMenu {
        private HostMenu() {
            super(null, 0);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
