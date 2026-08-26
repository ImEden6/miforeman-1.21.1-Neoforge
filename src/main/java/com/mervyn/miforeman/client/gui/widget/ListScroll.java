package com.mervyn.miforeman.client.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.function.IntConsumer;

/**
 * Shared scroll offset and scrollbar logic for custom list widgets.
 */
class ListScroll {
    private final int rowHeight;
    private final IntConsumer onChange;
    private int offset;

    ListScroll(int rowHeight, int initialOffset, IntConsumer onChange) {
        this.rowHeight = rowHeight;
        this.offset = initialOffset;
        this.onChange = onChange;
    }

    int offset() {
        return offset;
    }

    int maxScroll(int rowCount, int viewHeight) {
        return Math.max(0, rowCount * rowHeight - viewHeight + 4);
    }

    /**
     * Clamps the scroll offset to fit within current view height and row count
     * bounds.
     */
    void clampForRender(int rowCount, int viewHeight) {
        int clamped = Mth.clamp(offset, 0, maxScroll(rowCount, viewHeight));
        if (clamped != offset) {
            offset = clamped;
            onChange.accept(offset);
        }
    }

    boolean onWheel(double scrollY, int rowCount, int viewHeight) {
        int maxScroll = maxScroll(rowCount, viewHeight);
        if (maxScroll > 0) {
            offset = Mth.clamp(offset - (int) (scrollY * rowHeight * 2), 0, maxScroll);
            onChange.accept(offset);
            return true;
        }
        return false;
    }

    static final int SCROLLBAR_WIDTH = 4;
    static final int SCROLLBAR_MARGIN = 2;

    void drawScrollbar(GuiGraphics guiGraphics, int x, int y, int width, int height, int rowCount, int colour) {
        int maxScroll = maxScroll(rowCount, height);
        if (maxScroll <= 0)
            return;
        int totalHeight = rowCount * rowHeight;
        int scrollbarWidth = SCROLLBAR_WIDTH;
        int scrollbarHeight = Math.max(10, (int) (((double) height / totalHeight) * height));
        int scrollbarX = x + width - scrollbarWidth - SCROLLBAR_MARGIN;
        int scrollbarY = y + 2 + (int) (((double) offset / maxScroll) * (height - scrollbarHeight - 4));
        guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, colour);
    }
}
