package com.mervyn.miforeman.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Unified toggle list of linked machines and newly-detected candidates for the auto-detect
 * review flow (Step 2). Purely presentational -- like TreePanel/DetailCard, it contains no
 * confirmation logic; ClipboardScreen owns that and decides what each callback actually does.
 */
public class ReviewListPanel extends AbstractWidget {
    public record ReviewRow(BlockPos pos, ResourceLocation machineId, boolean linked, boolean rejected,
                             boolean isNewCandidate, @Nullable String productLabel) {}

    private static final int ROW_HEIGHT = 26;
    private static final int COLOR_BORDER = 0xFF6B5030;
    private static final int COLOR_TEXT = 0xFF3A2A18;
    private static final int COLOR_MUTED = 0xFF8A7A68;
    private static final int COLOR_LINKED = 0xFF2E7D32;
    private static final int COLOR_CANDIDATE = 0xFF9A6C00;
    private static final int COLOR_REJECTED = 0xFF8A7A68;
    private static final int COLOR_HOVER = 0x156B5030;
    private static final int CHECKBOX_SIZE = 10;
    private static final int ACTION_BUTTON_WIDTH = 46;
    private static final int ACTION_BUTTON_HEIGHT = 14;

    private final List<ReviewRow> rows;
    private final Consumer<ReviewRow> onToggleLink;
    private final Consumer<ReviewRow> onRejectRequest;
    private final Consumer<ReviewRow> onUnreject;
    private final IntConsumer onScrollChange;
    private int scrollOffset;

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
        this.scrollOffset = initialScrollOffset;
        this.onScrollChange = onScrollChange;
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

        int totalHeight = rows.size() * ROW_HEIGHT;
        int maxScroll = Math.max(0, totalHeight - getHeight() + 4);
        int clamped = Mth.clamp(scrollOffset, 0, maxScroll);
        if (clamped != scrollOffset) {
            scrollOffset = clamped;
            onScrollChange.accept(scrollOffset);
        }

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        Minecraft mc = Minecraft.getInstance();
        int currentY = getY() + 2 - scrollOffset;

        for (ReviewRow row : rows) {
            if (currentY + ROW_HEIGHT > getY() && currentY < getY() + getHeight()) {
                boolean isHovered = mouseX >= getX() + 1 && mouseX < getX() + getWidth() - 1 &&
                        mouseY >= currentY && mouseY < currentY + ROW_HEIGHT;
                if (isHovered) {
                    guiGraphics.fill(getX() + 2, currentY, getX() + getWidth() - 2, currentY + ROW_HEIGHT, COLOR_HOVER);
                }

                boolean showUnreject = row.rejected() && !row.linked();
                int checkboxX = getX() + 6;
                int checkboxY = currentY + (ROW_HEIGHT - CHECKBOX_SIZE) / 2;

                if (!showUnreject) {
                    guiGraphics.renderOutline(checkboxX, checkboxY, CHECKBOX_SIZE, CHECKBOX_SIZE, COLOR_BORDER);
                    if (row.linked()) {
                        guiGraphics.fill(checkboxX + 2, checkboxY + 2, checkboxX + CHECKBOX_SIZE - 2, checkboxY + CHECKBOX_SIZE - 2, COLOR_LINKED);
                    }
                }

                int textX = checkboxX + CHECKBOX_SIZE + 6;
                String name = formatId(row.machineId());
                int color = row.rejected() ? COLOR_REJECTED : (row.linked() ? COLOR_LINKED : COLOR_CANDIDATE);
                if (row.rejected()) {
                    name = "[rejected] " + name;
                } else if (row.isNewCandidate()) {
                    name = "[new] " + name;
                }
                int maxTextWidth = actionButtonX() - textX - 4;
                if (mc.font.width(name) > maxTextWidth && maxTextWidth > 0) {
                    name = mc.font.plainSubstrByWidth(name, Math.max(0, maxTextWidth - 8)) + "..";
                }
                guiGraphics.drawString(mc.font, name, textX, currentY + 4, color, false);

                String posText = row.pos().toShortString();
                guiGraphics.drawString(mc.font, posText, textX, currentY + 4 + 9, COLOR_MUTED, false);

                if (row.productLabel() != null) {
                    guiGraphics.drawString(mc.font, row.productLabel(), textX, currentY + 4 + 18, COLOR_MUTED, false);
                }

                if (showUnreject) {
                    int btnX = actionButtonX();
                    int btnY = actionButtonY(currentY);
                    guiGraphics.fill(btnX, btnY, btnX + ACTION_BUTTON_WIDTH, btnY + ACTION_BUTTON_HEIGHT, 0xFFD8C3A5);
                    guiGraphics.renderOutline(btnX, btnY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, COLOR_BORDER);
                    guiGraphics.drawString(mc.font, "Unreject", btnX + 3, btnY + 3, COLOR_TEXT, false);
                } else if (row.isNewCandidate() && !row.linked()) {
                    int btnX = actionButtonX();
                    int btnY = actionButtonY(currentY);
                    guiGraphics.fill(btnX, btnY, btnX + ACTION_BUTTON_WIDTH, btnY + ACTION_BUTTON_HEIGHT, 0xFFD8C3A5);
                    guiGraphics.renderOutline(btnX, btnY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, COLOR_BORDER);
                    guiGraphics.drawString(mc.font, "Reject", btnX + 8, btnY + 3, COLOR_TEXT, false);
                }
            }
            currentY += ROW_HEIGHT;
        }

        guiGraphics.disableScissor();

        if (maxScroll > 0) {
            int scrollbarWidth = 4;
            int scrollbarHeight = Math.max(10, (int) (((double) getHeight() / totalHeight) * getHeight()));
            int scrollbarX = getX() + getWidth() - scrollbarWidth - 2;
            int scrollbarY = getY() + 2 + (int) (((double) scrollOffset / maxScroll) * (getHeight() - scrollbarHeight - 4));
            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, COLOR_BORDER);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        int currentY = getY() + 2 - scrollOffset;
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
        int totalHeight = rows.size() * ROW_HEIGHT;
        int maxScroll = Math.max(0, totalHeight - getHeight() + 4);
        if (maxScroll > 0) {
            scrollOffset = Mth.clamp(scrollOffset - (int) (scrollY * ROW_HEIGHT * 2), 0, maxScroll);
            onScrollChange.accept(scrollOffset);
            return true;
        }
        return false;
    }

    private String formatId(ResourceLocation id) {
        String path = id.getPath();
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1))
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
