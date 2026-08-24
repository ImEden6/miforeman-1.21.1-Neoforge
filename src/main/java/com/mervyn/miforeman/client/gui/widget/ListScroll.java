package com.mervyn.miforeman.client.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.function.IntConsumer;

/**
 * Scroll offset + clamp/scrollbar math shared by {@link ReviewListPanel} and
 * {@link MonitoringListPanel} -- both widgets had this copy-pasted three times over (render-time
 * clamp, wheel handling, scrollbar draw) before this extraction (see
 * .claude/plans/gleaming-mapping-waffle.md).
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

    /** Clamps a scroll offset that may have gone stale (e.g. the row count shrank) -- called at
     *  the top of renderWidget(), same as both panels did inline before. */
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

    void drawScrollbar(GuiGraphics guiGraphics, int x, int y, int width, int height, int rowCount, int colour) {
        int maxScroll = maxScroll(rowCount, height);
        if (maxScroll <= 0) return;
        int totalHeight = rowCount * rowHeight;
        int scrollbarWidth = 4;
        int scrollbarHeight = Math.max(10, (int) (((double) height / totalHeight) * height));
        int scrollbarX = x + width - scrollbarWidth - 2;
        int scrollbarY = y + 2 + (int) (((double) offset / maxScroll) * (height - scrollbarHeight - 4));
        guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, colour);
    }
}
