package com.mervyn.miforeman.client.gui.widget;

final class GuiMath {

    private GuiMath() {
    }

    /** Half-open: [x, x+w) by [y, y+h). */
    static boolean contains(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
