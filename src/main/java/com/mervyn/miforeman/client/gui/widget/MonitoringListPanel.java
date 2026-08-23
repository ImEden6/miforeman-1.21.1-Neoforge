package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Read-only scrollable list of every live-monitored machine, opened from ClipboardScreen's
 * Monitor step so the full list gets room instead of the old hardcoded top-3 summary. Rows are
 * expected to already be sorted (worst status first) by the caller -- this widget just renders
 * whatever order it's given, matching ReviewListPanel's "purely presentational" convention.
 */
public class MonitoringListPanel extends AbstractWidget {
    public record MonitoringRow(LiveMonitoringPayload.MachineStatusData machine, @Nullable String productLabel) {}

    private static final int ROW_HEIGHT = 24;
    private static final int COLOR_BORDER = 0xFF6B5030;
    private static final int COLOR_TEXT = 0xFF3A2A18;
    private static final int COLOR_MUTED = 0xFF8A7A68;
    private static final int COLOR_GREEN = 0xFF2E7D32;
    private static final int COLOR_AMBER = 0xFF9A6C00;
    private static final int COLOR_RED = 0xFFCC3333;
    private static final int COLOR_ORANGE = 0xFFE67700;
    private static final int COLOR_HOVER = 0x156B5030;
    private static final int COLOR_SELECTED_ROW = 0x2000E5FF;
    private static final int COLOR_LOCATE_BUTTON = 0xFFD8C3A5;
    private static final int COLOR_LOCATED_BUTTON = 0xFF9FE8EE;
    private static final int ACTION_BUTTON_WIDTH = 46;
    private static final int ACTION_BUTTON_HEIGHT = 14;

    private final List<MonitoringRow> rows;
    private final boolean perHour;
    private final @Nullable BlockPos selectedPos;
    private final Consumer<MonitoringRow> onLocate;
    private final ListScroll scroll;

    public MonitoringListPanel(int x, int y, int width, int height,
                                List<MonitoringRow> rows, boolean perHour, @Nullable BlockPos selectedPos,
                                Consumer<MonitoringRow> onLocate,
                                int initialScrollOffset, IntConsumer onScrollChange) {
        super(x, y, width, height, Component.literal("Machine Monitoring List"));
        this.rows = rows;
        this.perHour = perHour;
        this.selectedPos = selectedPos;
        this.onLocate = onLocate;
        this.scroll = new ListScroll(ROW_HEIGHT, initialScrollOffset, onScrollChange);
    }

    private int actionButtonX() {
        return getX() + getWidth() - ACTION_BUTTON_WIDTH - 6;
    }

    private int actionButtonY(int rowY) {
        return rowY + (ROW_HEIGHT - ACTION_BUTTON_HEIGHT) / 2;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x113A2A18);
        guiGraphics.renderOutline(getX(), getY(), getWidth(), getHeight(), COLOR_BORDER);

        scroll.clampForRender(rows.size(), getHeight());

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        Minecraft mc = Minecraft.getInstance();
        int currentY = getY() + 2 - scroll.offset();

        for (MonitoringRow row : rows) {
            LiveMonitoringPayload.MachineStatusData machine = row.machine();
            boolean isSelected = machine.pos().equals(selectedPos);
            if (currentY + ROW_HEIGHT > getY() && currentY < getY() + getHeight()) {
                if (isSelected) {
                    guiGraphics.fill(getX() + 2, currentY, getX() + getWidth() - 2, currentY + ROW_HEIGHT, COLOR_SELECTED_ROW);
                }
                boolean isHovered = mouseX >= getX() + 1 && mouseX < getX() + getWidth() - 1 &&
                        mouseY >= currentY && mouseY < currentY + ROW_HEIGHT;
                if (isHovered) {
                    guiGraphics.fill(getX() + 2, currentY, getX() + getWidth() - 2, currentY + ROW_HEIGHT, COLOR_HOVER);
                }

                int color = statusColor(machine.status());
                guiGraphics.fill(getX() + 4, currentY + 4, getX() + 8, currentY + 8, color);

                double rateVal = machine.actualRate() * (perHour ? 60.0 : 1.0);
                String text = String.format("%s: %.2f/%s (%s)", DisplayFormat.formatId(machine.machineId()), rateVal, perHour ? "hr" : "min", machine.status());
                int maxTextWidth = actionButtonX() - (getX() + 12) - 4;
                if (mc.font.width(text) > maxTextWidth && maxTextWidth > 0) {
                    text = mc.font.plainSubstrByWidth(text, Math.max(0, maxTextWidth - 8)) + "..";
                }
                guiGraphics.drawString(mc.font, text, getX() + 12, currentY + 3, color, false);

                if (row.productLabel() != null) {
                    guiGraphics.drawString(mc.font, row.productLabel(), getX() + 12, currentY + 13, COLOR_MUTED, false);
                }

                int btnX = actionButtonX();
                int btnY = actionButtonY(currentY);
                guiGraphics.fill(btnX, btnY, btnX + ACTION_BUTTON_WIDTH, btnY + ACTION_BUTTON_HEIGHT,
                        isSelected ? COLOR_LOCATED_BUTTON : COLOR_LOCATE_BUTTON);
                guiGraphics.renderOutline(btnX, btnY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, COLOR_BORDER);
                guiGraphics.drawString(mc.font, isSelected ? "Located" : "Locate", btnX + 3, btnY + 3, COLOR_TEXT, false);
            }
            currentY += ROW_HEIGHT;
        }

        guiGraphics.disableScissor();

        scroll.drawScrollbar(guiGraphics, getX(), getY(), getWidth(), getHeight(), rows.size(), COLOR_BORDER);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        int currentY = getY() + 2 - scroll.offset();
        for (MonitoringRow row : rows) {
            if (mouseY >= currentY && mouseY < currentY + ROW_HEIGHT) {
                int btnX = actionButtonX();
                int btnY = actionButtonY(currentY);
                boolean inActionButton = mouseX >= btnX && mouseX < btnX + ACTION_BUTTON_WIDTH &&
                        mouseY >= btnY && mouseY < btnY + ACTION_BUTTON_HEIGHT;
                if (inActionButton) {
                    onLocate.accept(row);
                    this.playDownSound(Minecraft.getInstance().getSoundManager());
                    return true;
                }
                return false;
            }
            currentY += ROW_HEIGHT;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !active) return false;
        return scroll.onWheel(scrollY, rows.size(), getHeight());
    }

    private int statusColor(String status) {
        return switch (status) {
            case "RED" -> COLOR_RED;
            case "ORANGE" -> COLOR_ORANGE;
            case "YELLOW" -> COLOR_AMBER;
            case "GREEN" -> COLOR_GREEN;
            default -> COLOR_MUTED;
        };
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
