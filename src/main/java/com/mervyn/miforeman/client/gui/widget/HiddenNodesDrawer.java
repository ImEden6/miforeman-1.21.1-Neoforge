package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.client.gui.ColourPalette.ColourKey;
import com.mervyn.miforeman.client.gui.GuiIcon;
import com.mervyn.miforeman.goal.GraphLayoutState;
import com.mervyn.miforeman.goal.MachineStatus;
import com.mervyn.miforeman.goal.NodeType;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class HiddenNodesDrawer {
    private static final int COLOUR_BG_TOP = 0xF8FFFBEF;
    private static final int COLOUR_BG_BOTTOM = 0xF8EFE0BE;
    private static final int COLOUR_BORDER_LIGHT = 0xFF9C8058;
    private static final int COLOUR_BORDER_DARK = 0xFF4A3620;
    private static final int COLOUR_BORDER = 0xFF6B5030;
    private static final int COLOUR_TITLE = 0xFF4A2E0A;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_GREEN = MachineStatus.GREEN.colour();
    private static final int COLOUR_HOVER_BTN = 0x22000000;
    private static final int COLOUR_PILL_BG = ColourKey.LOCATE_BUTTON.defaultArgb;
    private static final int COLOUR_PILL_HOVER = 0xFFEAE7D9;

    public static final int TAB_WIDTH = 28;
    public static final int TAB_HEIGHT = 28;
    public static final int DRAWER_WIDTH = 156;

    private int x, y;
    private int canvasHeight;
    private boolean expanded = false;
    private final Font font;
    private final Supplier<GraphLayoutState> layoutSupplier;
    private final Supplier<RecipeGraph> graphSupplier;
    private final Consumer<ResourceLocation> onUnhideNode;
    private final Runnable onUnhideAll;

    private int scrollOffset = 0;
    private int totalContentHeight = 0;

    private int unhideAllX, unhideAllY, unhideAllW, unhideAllH;
    private boolean unhideAllHovered = false;

    private record UnhideTarget(int x, int y, int w, int h, ResourceLocation nodeId) {}
    private final List<UnhideTarget> unhideTargets = new ArrayList<>();

    public HiddenNodesDrawer(Font font,
                             Supplier<GraphLayoutState> layoutSupplier,
                             Supplier<RecipeGraph> graphSupplier,
                             Consumer<ResourceLocation> onUnhideNode,
                             Runnable onUnhideAll) {
        this.font = font;
        this.layoutSupplier = layoutSupplier;
        this.graphSupplier = graphSupplier;
        this.onUnhideNode = onUnhideNode;
        this.onUnhideAll = onUnhideAll;
    }

    public void setPosition(int leftX, int topY, int canvasHeight) {
        this.x = leftX;
        this.y = topY;
        this.canvasHeight = canvasHeight;
    }

    public boolean isHovered(double mouseX, double mouseY) {
        if (expanded) {
            int drawerH = Math.min(canvasHeight - 12, 220);
            return GuiMath.contains(x, y, DRAWER_WIDTH, drawerH, mouseX, mouseY);
        } else {
            return GuiMath.contains(x, y, TAB_WIDTH, TAB_HEIGHT, mouseX, mouseY);
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!expanded) {
            if (GuiMath.contains(x, y, TAB_WIDTH, TAB_HEIGHT, mouseX, mouseY)) {
                expanded = true;
            }
        } else {
            int drawerH = Math.min(canvasHeight - 12, 220);
            if (!GuiMath.contains(x, y, DRAWER_WIDTH, drawerH, mouseX, mouseY)) {
                expanded = false;
            }
        }

        GraphLayoutState layout = layoutSupplier.get();
        Set<ResourceLocation> hiddenNodes = layout != null ? layout.hiddenNodes() : Set.of();
        int hiddenCount = hiddenNodes.size();

        unhideTargets.clear();
        unhideAllW = 0;
        unhideAllH = 0;

        if (!expanded) {
            guiGraphics.fillGradient(x, y, x + TAB_WIDTH, y + TAB_HEIGHT, COLOUR_BG_TOP, COLOUR_BG_BOTTOM);
            guiGraphics.fill(x, y, x + TAB_WIDTH, y + 1, COLOUR_BORDER_LIGHT);
            guiGraphics.fill(x, y, x + 1, y + TAB_HEIGHT, COLOUR_BORDER_LIGHT);
            guiGraphics.fill(x, y + TAB_HEIGHT - 1, x + TAB_WIDTH, y + TAB_HEIGHT, COLOUR_BORDER_DARK);
            guiGraphics.fill(x + TAB_WIDTH - 1, y, x + TAB_WIDTH, y + TAB_HEIGHT, COLOUR_BORDER_DARK);

            boolean hoverTab = GuiMath.contains(x, y, TAB_WIDTH, TAB_HEIGHT, mouseX, mouseY);
            if (hoverTab) {
                guiGraphics.fill(x, y, x + TAB_WIDTH, y + TAB_HEIGHT, COLOUR_HOVER_BTN);
            }

            // With a badge showing, the icon sits below it.
            int eyeColour = hiddenCount > 0 ? COLOUR_GREEN : COLOUR_TEXT;
            int eyeTop = hiddenCount > 0 ? y + 12 : y + (TAB_HEIGHT - GuiIcon.EYE.height()) / 2;
            GuiIcon.EYE.draw(guiGraphics, x + (TAB_WIDTH - GuiIcon.EYE.width()) / 2, eyeTop, eyeColour);

            if (hiddenCount > 0) {
                String countText = String.valueOf(hiddenCount);
                int badgeW = font.width(countText) + 4;
                int badgeH = 8;
                int badgeX = x + TAB_WIDTH - badgeW - 1;
                int badgeY = y + 1;
                guiGraphics.fill(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, COLOUR_GREEN);
                guiGraphics.drawString(font, countText, badgeX + (badgeW - font.width(countText)) / 2, badgeY, 0xFFFFFFFF, false);
            }
            return;
        }

        int drawerH = Math.min(canvasHeight - 12, 220);
        guiGraphics.fillGradient(x, y, x + DRAWER_WIDTH, y + drawerH, COLOUR_BG_TOP, COLOUR_BG_BOTTOM);
        guiGraphics.fill(x, y, x + DRAWER_WIDTH, y + 1, COLOUR_BORDER_LIGHT);
        guiGraphics.fill(x, y, x + 1, y + drawerH, COLOUR_BORDER_LIGHT);
        guiGraphics.fill(x, y + drawerH - 1, x + DRAWER_WIDTH, y + drawerH, COLOUR_BORDER_DARK);
        guiGraphics.fill(x + DRAWER_WIDTH - 1, y, x + DRAWER_WIDTH, y + drawerH, COLOUR_BORDER_DARK);

        GuiIcon.EYE.draw(guiGraphics, x + 6, y + 7, COLOUR_TITLE);
        guiGraphics.drawString(font, I18n.get("miforeman.drawer.hidden_title", hiddenCount),
                x + 10 + GuiIcon.EYE.width(), y + 6, COLOUR_TITLE, false);

        int currentY = y + 20;

        if (hiddenCount > 0) {
            String unhideAllText = I18n.get("miforeman.drawer.unhide_all");
            int btnW = font.width(unhideAllText) + 10;
            int btnH = 12;
            unhideAllX = x + 6;
            unhideAllY = currentY;
            unhideAllW = btnW;
            unhideAllH = btnH;

            unhideAllHovered = mouseX >= unhideAllX && mouseX < unhideAllX + unhideAllW &&
                               mouseY >= unhideAllY && mouseY < unhideAllY + unhideAllH;
            int pillBg = unhideAllHovered ? COLOUR_PILL_HOVER : COLOUR_PILL_BG;
            guiGraphics.fill(unhideAllX, unhideAllY, unhideAllX + unhideAllW, unhideAllY + unhideAllH, pillBg);
            guiGraphics.renderOutline(unhideAllX, unhideAllY, unhideAllW, unhideAllH, COLOUR_BORDER);
            guiGraphics.drawString(font, unhideAllText, unhideAllX + 5, unhideAllY + 2, COLOUR_TEXT, false);
            currentY += 16;
        }

        int listTop = currentY;
        int listHeight = (y + drawerH - 4) - listTop;
        int maxScroll = Math.max(0, totalContentHeight - listHeight);
        if (totalContentHeight > 0) {
            scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        }

        guiGraphics.enableScissor(x + 1, listTop, x + DRAWER_WIDTH - 1, y + drawerH - 4);
        int itemY = listTop - scrollOffset;

        if (hiddenCount == 0) {
            guiGraphics.drawString(font, I18n.get("miforeman.drawer.empty"), x + 8, itemY + 4, COLOUR_MUTED, false);
            guiGraphics.drawString(font, I18n.get("miforeman.drawer.empty_hint_1"), x + 8, itemY + 16, COLOUR_MUTED, false);
            guiGraphics.drawString(font, I18n.get("miforeman.drawer.empty_hint_2"), x + 8, itemY + 26, COLOUR_MUTED, false);
            itemY += 40;
        } else {
            RecipeGraph graph = graphSupplier.get();
            for (ResourceLocation id : hiddenNodes) {
                RecipeGraphNode node = graph != null ? graph.node(id) : null;
                int btnX = x + 6;
                int btnY = itemY;
                int btnW = GuiIcon.EYE.width() + 4;
                int btnH = 10;

                boolean btnHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
                int btnBg = btnHover ? COLOUR_PILL_HOVER : COLOUR_PILL_BG;
                guiGraphics.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBg);
                guiGraphics.renderOutline(btnX, btnY, btnW, btnH, COLOUR_BORDER);
                GuiIcon.EYE.drawCentered(guiGraphics, btnX, btnY, btnW, btnH, COLOUR_GREEN);
                unhideTargets.add(new UnhideTarget(btnX, btnY, btnW, btnH, id));

                String name = DisplayFormat.formatId(id);
                if (node != null && node.getType() == NodeType.MACHINE) {
                    name = I18n.get("miforeman.detail.recipe_title", name);
                }
                int maxLabelW = DRAWER_WIDTH - (btnW + 16);
                if (font.width(name) > maxLabelW) {
                    name = font.plainSubstrByWidth(name, maxLabelW - 6) + "..";
                }
                guiGraphics.drawString(font, name, btnX + btnW + 4, itemY + 1, COLOUR_TEXT, false);
                itemY += 13;
            }
        }

        totalContentHeight = itemY + scrollOffset - listTop;
        guiGraphics.disableScissor();

        if (maxScroll > 0) {
            int scrollbarW = 3;
            int scrollbarH = (int) (((double) listHeight / totalContentHeight) * listHeight);
            scrollbarH = Math.max(8, scrollbarH);
            int sbX = x + DRAWER_WIDTH - scrollbarW - 2;
            int sbY = listTop + (int) (((double) scrollOffset / maxScroll) * (listHeight - scrollbarH));
            guiGraphics.fill(sbX, sbY, sbX + scrollbarW, sbY + scrollbarH, COLOUR_BORDER);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!expanded || !isHovered(mouseX, mouseY)) {
            return false;
        }

        if (button == 0 && onUnhideAll != null &&
                mouseX >= unhideAllX && mouseX < unhideAllX + unhideAllW &&
                mouseY >= unhideAllY && mouseY < unhideAllY + unhideAllH) {
            onUnhideAll.run();
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }

        if (button == 0 && onUnhideNode != null) {
            for (UnhideTarget target : unhideTargets) {
                if (mouseX >= target.x() && mouseX < target.x() + target.w() &&
                    mouseY >= target.y() && mouseY < target.y() + target.h()) {
                    onUnhideNode.accept(target.nodeId());
                    Minecraft.getInstance().getSoundManager().play(
                            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    return true;
                }
            }
        }

        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!expanded || !isHovered(mouseX, mouseY)) {
            return false;
        }

        int drawerH = Math.min(canvasHeight - 12, 220);
        int listHeight = drawerH - 40;
        int maxScroll = Math.max(0, totalContentHeight - listHeight);
        if (maxScroll > 0) {
            scrollOffset = Mth.clamp(scrollOffset - (int) (scrollY * 12), 0, maxScroll);
            return true;
        }
        return false;
    }
}
