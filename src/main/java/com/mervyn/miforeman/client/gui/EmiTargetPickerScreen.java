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
 * Goal target picker for EMI. The left half is a drop target, the right half stays bare for EMI's
 * sidebar. Dropping a stack on the panel sets the parent's goal target.
 *
 * <p>Backed by the client-only, zero-slot {@link HostMenu} because EMI draws only on an
 * {@code AbstractContainerScreen}. A plain Screen never passes that check.
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

    // Public so the EMI compat package can register around the panel without this class
    // importing dev.emi.

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

    public void acceptDrop(TargetType type, String idStr) {
        parent.applyDroppedTarget(type, idStr);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        // Skip AbstractContainerScreen.onClose's closeContainer call. This menu was never the
        // player's container, so it would send a close packet for whatever they have open.
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // AbstractContainerScreen.renderBackground is what calls renderBg, so skip only the dimming
        // and vignette.
        this.renderBg(guiGraphics, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        ClipboardChrome.drawBackgroundWithTopClip(guiGraphics, leftPos, topPos, imageWidth, imageHeight);

        int iconCenterX = leftPos + imageWidth / 2;
        int iconCenterY = topPos + imageHeight / 2 - 10;

        // Centred above the icon because the top-left corner collides with other mods' icons.
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
        // Everything is drawn in renderBg in screen coordinates, so skip the vanilla labels.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

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
