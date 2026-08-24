package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.gui.widget.ClipboardButton;
import com.mervyn.miforeman.client.gui.widget.ReviewListPanel;
import com.mervyn.miforeman.goal.MachineLinkHistory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Full-window scanned/linked machine review list, opened from {@link ClipboardScreen}'s Monitor
 * step so the list gets nearly the whole window instead of the small leftover strip it used to be
 * squeezed into. Reads/mutates the {@link MonitoringState} directly instead of reaching through a
 * parent screen; every mutation here delegates to the state's existing apply* methods (still
 * running {@code onChange}, which triggers syncGoal() on the real ClipboardScreen) and then
 * re-renders itself via {@link #rebuild()}, the same clear-and-repopulate idiom
 * {@code ClipboardScreen.rebuildStep()} already uses.
 */
public class ReviewMachinesScreen extends Screen {
    private static final int MIN_GUI_WIDTH = 440;
    private static final int MIN_GUI_HEIGHT = 230;
    private static final int PADDING = 8;
    private static final int COLOUR_TITLE = 0xFFDAA520;

    private final MonitoringState state;
    private final Runnable onChange;
    private final Screen backTarget;
    private int scrollOffset = 0;

    public ReviewMachinesScreen(MonitoringState state, Runnable onChange, Screen backTarget) {
        super(Component.literal("Review Machines"));
        this.state = state;
        this.onChange = onChange;
        this.backTarget = backTarget;
    }

    private int guiWidth() {
        return ClipboardChrome.guiWidth(this.width, MIN_GUI_WIDTH);
    }

    private int guiHeight() {
        return ClipboardChrome.guiHeight(this.height, MIN_GUI_HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;
        int contentW = guiWidth() - (PADDING + ClipboardChrome.MAIN_BORDER) * 2 - 4;
        int btnY = top + guiHeight() - PADDING - ClipboardChrome.MAIN_BORDER - 22;

        Button showRejectedButton = new ClipboardButton(contentX, contentY, 90, 14,
                Component.literal(state.showRejected ? "Hide Rejected" : "Show Rejected"),
                b -> {
                    state.showRejected = !state.showRejected;
                    rebuild();
                }
        );
        this.addRenderableWidget(showRejectedButton);

        Button undoButton = new ClipboardButton(contentX + 94, contentY, 40, 14,
                Component.literal("Undo"), b -> {
            MachineLinkHistory.UndoResult result = state.machineLinkHistory.undo();
            if (result != null) {
                state.applyLinkHistoryResult(result, onChange);
                rebuild();
            }
        });
        undoButton.active = !state.machineLinkHistory.undoStack().isEmpty();
        this.addRenderableWidget(undoButton);

        Button redoButton = new ClipboardButton(contentX + 138, contentY, 40, 14,
                Component.literal("Redo"), b -> {
            MachineLinkHistory.UndoResult result = state.machineLinkHistory.redo();
            if (result != null) {
                state.applyLinkHistoryResult(result, onChange);
                rebuild();
            }
        });
        redoButton.active = !state.machineLinkHistory.redoStack().isEmpty();
        this.addRenderableWidget(redoButton);

        List<ReviewListPanel.ReviewRow> rows = state.buildReviewRows();
        int listY = contentY + 18;
        ReviewListPanel listPanel = new ReviewListPanel(contentX, listY, contentW, btnY - 6 - listY,
                rows, this::handleReviewToggle, this::handleRejectCandidateRequest, this::handleUnreject,
                scrollOffset, v -> scrollOffset = v);
        this.addRenderableWidget(listPanel);
        state.updateWorldHighlightPositions(rows);

        Button backButton = new ClipboardButton(contentX, btnY, 80, 16,
                Component.literal("<- Back"),
                b -> Minecraft.getInstance().setScreen(backTarget)
        );
        this.addRenderableWidget(backButton);
    }

    private void handleReviewToggle(ReviewListPanel.ReviewRow row) {
        if (row.linked()) {
            state.applyUnlink(row.pos(), onChange);
        } else {
            state.applyLink(row.pos(), onChange);
        }
        rebuild();
    }

    private void handleRejectCandidateRequest(ReviewListPanel.ReviewRow row) {
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                confirmed -> {
                    Minecraft.getInstance().setScreen(this);
                    if (confirmed) {
                        state.applyReject(row.pos(), onChange);
                        rebuild();
                    }
                },
                Component.literal("Reject this machine?"),
                Component.literal("It will be hidden from future scans until un-rejected. Confirm?")
        ));
    }

    private void handleUnreject(ReviewListPanel.ReviewRow row) {
        state.applyUnreject(row.pos(), onChange);
        rebuild();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(backTarget);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately no dimming/vignette -- matches ClipboardScreen's own override, so this
        // reads as a level deeper into the same tool rather than a different, darker one.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        ClipboardChrome.drawBackground(guiGraphics, left, top, guiWidth(), guiHeight());

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        guiGraphics.drawString(this.font, Component.literal("Review Machines"), contentX, contentY, COLOUR_TITLE);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
