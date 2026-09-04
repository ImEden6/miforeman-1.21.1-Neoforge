package com.mervyn.miforeman.client.gui.widget;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Floating search bar widget inside {@link GraphCanvas} for real-time node
 * filtering and navigation.
 */
public class GraphSearchBar {
    private static final int COLOUR_BG_TOP = 0xEEFFFBEF;
    private static final int COLOUR_BG_BOTTOM = 0xEEEFE0BE;
    private static final int COLOUR_BORDER_LIGHT = 0xFF9C8058;
    private static final int COLOUR_BORDER_DARK = 0xFF4A3620;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_ERROR = 0xFFCC3333;
    private static final int COLOUR_HOVER_BTN = 0x22000000;

    private static final int BAR_WIDTH = 172;
    private static final int BAR_HEIGHT = 20;
    private static final int ICON_BTN_SIZE = 28;
    private static final int SEARCH_ICON_LENS = 6;

    private int x, y;
    private int anchorRightX, anchorTopY;
    private boolean visible = false;
    private final Font font;
    private final EditBox editBox;
    private final SearchState<ResourceLocation> state;
    private final Runnable onMatchChanged;
    private final Consumer<Boolean> onVisibilityChanged;

    public GraphSearchBar(SearchState<ResourceLocation> state, Font font, Runnable onMatchChanged,
            Consumer<Boolean> onVisibilityChanged) {
        this.state = state;
        this.font = font;
        this.onMatchChanged = onMatchChanged;
        this.onVisibilityChanged = onVisibilityChanged;
        this.editBox = new EditBox(font, 0, 0, 76, 12, Component.literal("Search"));
        this.editBox.setBordered(false);
        this.editBox.setTextColor(COLOUR_TEXT);
        this.editBox.setHint(Component.literal("Search...").withColor(COLOUR_MUTED));
        this.editBox.setResponder(query -> {
            onMatchChanged.run();
        });
    }

    public void setPosition(int rightX, int topY) {
        this.anchorRightX = rightX;
        this.anchorTopY = topY;
        resolvePosition();
    }

    /**
     * Recomputes x/y from the stored anchor and current visibility. Must be called
     * whenever {@code visible} changes, since the anchor is fixed to the right edge
     * but the two states occupy different widths (icon vs. full bar growing left).
     */
    private void resolvePosition() {
        if (visible) {
            this.x = anchorRightX - BAR_WIDTH;
            this.y = anchorTopY;
            this.editBox.setX(this.x + 18);
            this.editBox.setY(this.y + 4);
        } else {
            this.x = anchorRightX - ICON_BTN_SIZE;
            this.y = anchorTopY;
            this.editBox.setX(this.x);
            this.editBox.setY(this.y);
        }
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        resolvePosition();
        if (visible) {
            this.editBox.setFocused(true);
        } else {
            this.editBox.setFocused(false);
            this.editBox.setValue("");
            state.clear();
            onMatchChanged.run();
        }
        if (onVisibilityChanged != null) {
            onVisibilityChanged.accept(this.visible);
        }
    }

    public void toggleVisible() {
        setVisible(!this.visible);
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isFocused() {
        return visible && editBox.isFocused();
    }

    public String getValue() {
        return editBox.getValue();
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            // Render closed search trigger button
            boolean hover = mouseX >= x && mouseX < x + ICON_BTN_SIZE && mouseY >= y && mouseY < y + ICON_BTN_SIZE;
            guiGraphics.fillGradient(x, y, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_BG_TOP, COLOUR_BG_BOTTOM);
            guiGraphics.fill(x, y, x + ICON_BTN_SIZE, y + 1, COLOUR_BORDER_LIGHT);
            guiGraphics.fill(x, y, x + 1, y + ICON_BTN_SIZE, COLOUR_BORDER_LIGHT);
            guiGraphics.fill(x, y + ICON_BTN_SIZE - 1, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_BORDER_DARK);
            guiGraphics.fill(x + ICON_BTN_SIZE - 1, y, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_BORDER_DARK);
            if (hover) {
                guiGraphics.fill(x, y, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_HOVER_BTN);
            }
            drawSearchIcon(guiGraphics, x + (ICON_BTN_SIZE - SEARCH_ICON_LENS - 3) / 2,
                    y + (ICON_BTN_SIZE - SEARCH_ICON_LENS - 3) / 2, SEARCH_ICON_LENS, COLOUR_TEXT);
            return;
        }

        // Render open search bar background
        guiGraphics.fillGradient(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOUR_BG_TOP, COLOUR_BG_BOTTOM);
        guiGraphics.fill(x, y, x + BAR_WIDTH, y + 1, COLOUR_BORDER_LIGHT);
        guiGraphics.fill(x, y, x + 1, y + BAR_HEIGHT, COLOUR_BORDER_LIGHT);
        guiGraphics.fill(x, y + BAR_HEIGHT - 1, x + BAR_WIDTH, y + BAR_HEIGHT, COLOUR_BORDER_DARK);
        guiGraphics.fill(x + BAR_WIDTH - 1, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOUR_BORDER_DARK);

        // Search icon prefix
        drawSearchIcon(guiGraphics, x + 4, y + 5, SEARCH_ICON_LENS, COLOUR_MUTED);

        // Edit box background inner field
        int ebX = x + 16;
        int ebY = y + 3;
        int ebW = 76;
        int ebH = 14;
        guiGraphics.fill(ebX, ebY, ebX + ebW, ebY + ebH, 0x18000000);

        // Render edit box
        editBox.render(guiGraphics, mouseX, mouseY, partialTick);

        // Render match count badge
        int countX = x + 95;
        String countStr;
        int countColour = COLOUR_MUTED;
        if (!state.isSearching()) {
            countStr = "";
        } else if (state.getMatchCount() == 0) {
            countStr = "0/0";
            countColour = COLOUR_ERROR;
        } else {
            countStr = (state.getCurrentIndex() + 1) + "/" + state.getMatchCount();
        }
        if (!countStr.isEmpty()) {
            guiGraphics.drawString(font, countStr, countX, y + 6, countColour, false);
        }

        // Action buttons: ▲ (Prev), ▼ (Next), ✕ (Close)
        int btnY = y + 3;
        int btnW = 12;
        int btnH = 14;

        // Prev button
        int prevX = x + 129;
        boolean hoverPrev = mouseX >= prevX && mouseX < prevX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        if (hoverPrev)
            guiGraphics.fill(prevX, btnY, prevX + btnW, btnY + btnH, COLOUR_HOVER_BTN);
        guiGraphics.drawCenteredString(font, "▲", prevX + btnW / 2, btnY + 3,
                state.getMatchCount() > 0 ? COLOUR_TEXT : COLOUR_MUTED);

        // Next button
        int nextX = x + 142;
        boolean hoverNext = mouseX >= nextX && mouseX < nextX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        if (hoverNext)
            guiGraphics.fill(nextX, btnY, nextX + btnW, btnY + btnH, COLOUR_HOVER_BTN);
        guiGraphics.drawCenteredString(font, "▼", nextX + btnW / 2, btnY + 3,
                state.getMatchCount() > 0 ? COLOUR_TEXT : COLOUR_MUTED);

        // Close button
        int closeX = x + 156;
        boolean hoverClose = mouseX >= closeX && mouseX < closeX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        if (hoverClose)
            guiGraphics.fill(closeX, btnY, closeX + btnW, btnY + btnH, COLOUR_HOVER_BTN);
        guiGraphics.drawCenteredString(font, "✕", closeX + btnW / 2, btnY + 3, COLOUR_TEXT);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) {
            if (mouseX >= x && mouseX < x + ICON_BTN_SIZE && mouseY >= y && mouseY < y + ICON_BTN_SIZE) {
                setVisible(true);
                return true;
            }
            return false;
        }

        if (mouseX < x || mouseX >= x + BAR_WIDTH || mouseY < y || mouseY >= y + BAR_HEIGHT) {
            return false;
        }

        // Edit box clicked
        if (mouseX >= x + 16 && mouseX < x + 94) {
            editBox.mouseClicked(mouseX, mouseY, button);
            editBox.setFocused(true);
            return true;
        }

        // Prev button clicked
        if (mouseX >= x + 129 && mouseX < x + 141) {
            state.prevMatch();
            onMatchChanged.run();
            return true;
        }

        // Next button clicked
        if (mouseX >= x + 142 && mouseX < x + 155) {
            state.nextMatch();
            onMatchChanged.run();
            return true;
        }

        // Close button clicked
        if (mouseX >= x + 156 && mouseX < x + 169) {
            setVisible(false);
            return true;
        }

        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) {
            return false;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            setVisible(false);
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                state.prevMatch();
            } else {
                state.nextMatch();
            }
            onMatchChanged.run();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_UP) {
            state.prevMatch();
            onMatchChanged.run();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            state.nextMatch();
            onMatchChanged.run();
            return true;
        }

        return editBox.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!visible) {
            return false;
        }
        return editBox.charTyped(codePoint, modifiers);
    }

    private static final int SEARCH_ICON_HANDLE = 3;

    /**
     * Draws a magnifying-glass icon (ring + diagonal handle) out of flat-filled pixels,
     * rather than the Unicode telephone-recorder character (U+2315), which falls back to a
     * blocky unifont glyph that reads as a clipped smudge at this size. {@code left}/{@code
     * top} is the overall icon's bounding-box top-left corner (lens + handle); the lens sits
     * shifted right by {@link #SEARCH_ICON_HANDLE} so the handle can extend down-left of it.
     */
    private static void drawSearchIcon(GuiGraphics guiGraphics, int left, int top, int lensSize, int colour) {
        int lensLeft = left + SEARCH_ICON_HANDLE;
        guiGraphics.fill(lensLeft + 1, top, lensLeft + lensSize - 1, top + 1, colour);
        guiGraphics.fill(lensLeft + 1, top + lensSize - 1, lensLeft + lensSize - 1, top + lensSize, colour);
        guiGraphics.fill(lensLeft, top + 1, lensLeft + 1, top + lensSize - 1, colour);
        guiGraphics.fill(lensLeft + lensSize - 1, top + 1, lensLeft + lensSize, top + lensSize - 1, colour);

        int hx = lensLeft;
        int hy = top + lensSize - 1;
        guiGraphics.fill(hx - 2, hy, hx, hy + 2, colour);
        guiGraphics.fill(hx - 3, hy + 1, hx - 1, hy + 3, colour);
    }
}
