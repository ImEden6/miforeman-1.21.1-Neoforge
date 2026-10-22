package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.goal.MachineStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public class GraphSearchBar {
    private static final int COLOUR_BG_TOP = 0xEEFFFBEF;
    private static final int COLOUR_BG_BOTTOM = 0xEEEFE0BE;
    private static final int COLOUR_BORDER_LIGHT = 0xFF9C8058;
    private static final int COLOUR_BORDER_DARK = 0xFF4A3620;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_ERROR = MachineStatus.RED.colour();
    private static final int COLOUR_HOVER_BTN = 0x22000000;

    private static final int BAR_WIDTH = 172;
    private static final int BAR_HEIGHT = 20;
    private static final int ICON_BTN_SIZE = 28;

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
        this.editBox = new EditBox(font, 0, 0, 76, 12, Component.translatable("miforeman.screen.search"));
        this.editBox.setBordered(false);
        this.editBox.setTextColor(COLOUR_TEXT);
        this.editBox.setTextShadow(false);
        this.editBox.setHint(Component.translatable("miforeman.screen.search_hint").withColor(COLOUR_MUTED));
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
     * Must be called whenever {@code visible} changes. The anchor is the right edge, and the
     * icon and the open bar have different widths.
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
            boolean hover = mouseX >= x && mouseX < x + ICON_BTN_SIZE && mouseY >= y && mouseY < y + ICON_BTN_SIZE;
            guiGraphics.fillGradient(x, y, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_BG_TOP, COLOUR_BG_BOTTOM);
            guiGraphics.fill(x, y, x + ICON_BTN_SIZE, y + 1, COLOUR_BORDER_LIGHT);
            guiGraphics.fill(x, y, x + 1, y + ICON_BTN_SIZE, COLOUR_BORDER_LIGHT);
            guiGraphics.fill(x, y + ICON_BTN_SIZE - 1, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_BORDER_DARK);
            guiGraphics.fill(x + ICON_BTN_SIZE - 1, y, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_BORDER_DARK);
            if (hover) {
                guiGraphics.fill(x, y, x + ICON_BTN_SIZE, y + ICON_BTN_SIZE, COLOUR_HOVER_BTN);
            }
            com.mervyn.miforeman.client.gui.GuiIcon.SEARCH.drawCentered(guiGraphics, x, y, ICON_BTN_SIZE, ICON_BTN_SIZE,
                    COLOUR_TEXT);
            return;
        }

        guiGraphics.fillGradient(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOUR_BG_TOP, COLOUR_BG_BOTTOM);
        guiGraphics.fill(x, y, x + BAR_WIDTH, y + 1, COLOUR_BORDER_LIGHT);
        guiGraphics.fill(x, y, x + 1, y + BAR_HEIGHT, COLOUR_BORDER_LIGHT);
        guiGraphics.fill(x, y + BAR_HEIGHT - 1, x + BAR_WIDTH, y + BAR_HEIGHT, COLOUR_BORDER_DARK);
        guiGraphics.fill(x + BAR_WIDTH - 1, y, x + BAR_WIDTH, y + BAR_HEIGHT, COLOUR_BORDER_DARK);

        com.mervyn.miforeman.client.gui.GuiIcon.SEARCH.draw(guiGraphics, x + 4, y + 5, COLOUR_MUTED);

        int ebX = x + 16;
        int ebY = y + 3;
        int ebW = 76;
        int ebH = 14;
        guiGraphics.fill(ebX, ebY, ebX + ebW, ebY + ebH, 0x18000000);

        editBox.render(guiGraphics, mouseX, mouseY, partialTick);

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

        int btnY = y + 3;
        int btnW = 12;
        int btnH = 14;

        int prevX = x + 129;
        boolean hoverPrev = mouseX >= prevX && mouseX < prevX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        if (hoverPrev)
            guiGraphics.fill(prevX, btnY, prevX + btnW, btnY + btnH, COLOUR_HOVER_BTN);
        com.mervyn.miforeman.client.gui.GuiIcon.ARROW_UP.drawCentered(guiGraphics, prevX, btnY, btnW, btnH,
                state.getMatchCount() > 0 ? COLOUR_TEXT : COLOUR_MUTED);

        int nextX = x + 142;
        boolean hoverNext = mouseX >= nextX && mouseX < nextX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        if (hoverNext)
            guiGraphics.fill(nextX, btnY, nextX + btnW, btnY + btnH, COLOUR_HOVER_BTN);
        com.mervyn.miforeman.client.gui.GuiIcon.ARROW_DOWN.drawCentered(guiGraphics, nextX, btnY, btnW, btnH,
                state.getMatchCount() > 0 ? COLOUR_TEXT : COLOUR_MUTED);

        int closeX = x + 156;
        boolean hoverClose = mouseX >= closeX && mouseX < closeX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
        if (hoverClose)
            guiGraphics.fill(closeX, btnY, closeX + btnW, btnY + btnH, COLOUR_HOVER_BTN);
        com.mervyn.miforeman.client.gui.GuiIcon.CLOSE.drawCentered(guiGraphics, closeX, btnY, btnW, btnH, COLOUR_TEXT);
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

        if (mouseX >= x + 16 && mouseX < x + 94) {
            editBox.mouseClicked(mouseX, mouseY, button);
            editBox.setFocused(true);
            return true;
        }

        if (mouseX >= x + 129 && mouseX < x + 141) {
            state.prevMatch();
            onMatchChanged.run();
            return true;
        }

        if (mouseX >= x + 142 && mouseX < x + 155) {
            state.nextMatch();
            onMatchChanged.run();
            return true;
        }

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

}
