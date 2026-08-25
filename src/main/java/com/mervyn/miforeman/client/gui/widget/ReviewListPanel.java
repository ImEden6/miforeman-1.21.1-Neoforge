package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Presentational scrollable list displaying linked machines and detected candidates for review.
 */
public class ReviewListPanel extends AbstractWidget {
    public record ReviewRow(BlockPos pos, ResourceLocation machineId, boolean linked, boolean rejected,
                             boolean isNewCandidate, @Nullable String productLabel) {}

    private static final int ROW_HEIGHT = 34;
    private static final int COLOUR_BORDER = 0xFF6B5030;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_LINKED = 0xFF2E7D32;
    private static final int COLOUR_CANDIDATE = 0xFF9A6C00;
    private static final int COLOUR_REJECTED = 0xFF8A7A68;
    private static final int COLOUR_HOVER = 0x156B5030;
    private static final int CHECKBOX_SIZE = 10;
    private static final int ACTION_BUTTON_WIDTH = 46;
    private static final int ACTION_BUTTON_HEIGHT = 14;

    private final List<ReviewRow> rows;
    private final Consumer<ReviewRow> onToggleLink;
    private final Consumer<ReviewRow> onRejectRequest;
    private final Consumer<ReviewRow> onUnreject;
    private final ListScroll scroll;

    public ReviewListPanel(int x, int y, int width, int height, List<ReviewRow> rows,
                            Consumer<ReviewRow> onToggleLink,
                            Consumer<ReviewRow> onRejectRequest,
                            Consumer<ReviewRow> onUnreject,
                            int initialScrollOffset,
                            IntConsumer onScrollChange) {
        super(x, y, width, height, Component.literal("Machine Review List"));
        this.rows = rows;
        this.onToggleLink = onToggleLink;
        this.onRejectRequest = onRejectRequest;
        this.onUnreject = onUnreject;
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
        guiGraphics.renderOutline(getX(), getY(), getWidth(), getHeight(), COLOUR_BORDER);

        scroll.clampForRender(rows.size(), getHeight());

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        Minecraft mc = Minecraft.getInstance();
        int currentY = getY() + 2 - scroll.offset();

        for (ReviewRow row : rows) {
            if (currentY + ROW_HEIGHT > getY() && currentY < getY() + getHeight()) {
                boolean isHovered = mouseX >= getX() + 1 && mouseX < getX() + getWidth() - 1 &&
                        mouseY >= currentY && mouseY < currentY + ROW_HEIGHT;
                if (isHovered) {
                    guiGraphics.fill(getX() + 2, currentY, getX() + getWidth() - 2, currentY + ROW_HEIGHT, COLOUR_HOVER);
                }

                boolean showUnreject = row.rejected() && !row.linked();
                int checkboxX = getX() + 6;
                int checkboxY = currentY + (ROW_HEIGHT - CHECKBOX_SIZE) / 2;

                if (!showUnreject) {
                    guiGraphics.renderOutline(checkboxX, checkboxY, CHECKBOX_SIZE, CHECKBOX_SIZE, COLOUR_BORDER);
                    if (row.linked()) {
                        guiGraphics.fill(checkboxX + 2, checkboxY + 2, checkboxX + CHECKBOX_SIZE - 2, checkboxY + CHECKBOX_SIZE - 2, COLOUR_LINKED);
                    }
                }

                int textX = checkboxX + CHECKBOX_SIZE + 6;
                String name = DisplayFormat.formatId(row.machineId());
                int colour = row.rejected() ? COLOUR_REJECTED : (row.linked() ? COLOUR_LINKED : COLOUR_CANDIDATE);
                if (row.rejected()) {
                    name = "[rejected] " + name;
                } else if (row.isNewCandidate()) {
                    name = "[new] " + name;
                }
                int maxTextWidth = actionButtonX() - textX - 4;
                if (mc.font.width(name) > maxTextWidth && maxTextWidth > 0) {
                    name = mc.font.plainSubstrByWidth(name, Math.max(0, maxTextWidth - 8)) + "..";
                }
                guiGraphics.drawString(mc.font, name, textX, currentY + 4, colour, false);

                String posText = row.pos().toShortString();
                guiGraphics.drawString(mc.font, posText, textX, currentY + 4 + 9, COLOUR_MUTED, false);

                if (row.productLabel() != null) {
                    guiGraphics.drawString(mc.font, row.productLabel(), textX, currentY + 4 + 18, COLOUR_MUTED, false);
                }

                if (showUnreject) {
                    int btnX = actionButtonX();
                    int btnY = actionButtonY(currentY);
                    guiGraphics.fill(btnX, btnY, btnX + ACTION_BUTTON_WIDTH, btnY + ACTION_BUTTON_HEIGHT, 0xFFD8C3A5);
                    guiGraphics.renderOutline(btnX, btnY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, COLOUR_BORDER);
                    guiGraphics.drawString(mc.font, "Unreject", btnX + 3, btnY + 3, COLOUR_TEXT, false);
                } else if (row.isNewCandidate() && !row.linked()) {
                    int btnX = actionButtonX();
                    int btnY = actionButtonY(currentY);
                    guiGraphics.fill(btnX, btnY, btnX + ACTION_BUTTON_WIDTH, btnY + ACTION_BUTTON_HEIGHT, 0xFFD8C3A5);
                    guiGraphics.renderOutline(btnX, btnY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, COLOUR_BORDER);
                    guiGraphics.drawString(mc.font, "Reject", btnX + 8, btnY + 3, COLOUR_TEXT, false);
                }
            }
            currentY += ROW_HEIGHT;
        }

        guiGraphics.disableScissor();

        scroll.drawScrollbar(guiGraphics, getX(), getY(), getWidth(), getHeight(), rows.size(), COLOUR_BORDER);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        int currentY = getY() + 2 - scroll.offset();
        for (ReviewRow row : rows) {
            if (mouseY >= currentY && mouseY < currentY + ROW_HEIGHT) {
                boolean showUnreject = row.rejected() && !row.linked();
                int btnX = actionButtonX();
                int btnY = actionButtonY(currentY);
                boolean inActionButton = mouseX >= btnX && mouseX < btnX + ACTION_BUTTON_WIDTH &&
                        mouseY >= btnY && mouseY < btnY + ACTION_BUTTON_HEIGHT;

                if (showUnreject) {
                    if (inActionButton) {
                        onUnreject.accept(row);
                        this.playDownSound(Minecraft.getInstance().getSoundManager());
                        return true;
                    }
                } else if (row.isNewCandidate() && !row.linked()) {
                    if (inActionButton) {
                        onRejectRequest.accept(row);
                        this.playDownSound(Minecraft.getInstance().getSoundManager());
                        return true;
                    }
                    onToggleLink.accept(row);
                    this.playDownSound(Minecraft.getInstance().getSoundManager());
                    return true;
                } else {
                    onToggleLink.accept(row);
                    this.playDownSound(Minecraft.getInstance().getSoundManager());
                    return true;
                }
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

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
