package com.mervyn.miforeman.client.gui.widget;

/**
 * Small geometry helpers shared across the clipboard's hand-drawn GUI widgets.
 */
final class GuiMath {

    private GuiMath() {
    }

    /** Whether (mouseX, mouseY) falls within the [x, x+w) x [y, y+h) rectangle. */
    static boolean contains(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
