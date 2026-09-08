package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.gui.widget.ClipboardButton;
import com.mervyn.miforeman.client.gui.widget.ReviewListPanel;
import com.mervyn.miforeman.client.gui.widget.SearchState;
import com.mervyn.miforeman.goal.MachineLinkHistory;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import com.mervyn.miforeman.network.RequestMonitoringUpdatePayload;
import com.mervyn.miforeman.network.ScanResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-window screen for reviewing scanned and linked machine lists.
 */
public class ReviewMachinesScreen extends Screen {
    private static final int MIN_GUI_WIDTH = 440;
    private static final int MIN_GUI_HEIGHT = 230;
    private static final int PADDING = 8;
    private static final int COLOUR_TITLE = 0xFFDAA520;
    private static final int SEARCH_FIELD_WIDTH = 120;

    private final MonitoringState state;
    private final Runnable onChange;
    private final Screen backTarget;
    private int scrollOffset = 0;
    /** Kept so poll responses can push new row data in place (see {@link #refreshData()})
     *  instead of clearing and recreating every widget on the screen every ~1s. */
    private @Nullable ReviewListPanel listPanel;
    /** Same matching/cycling logic the recipe graph's search bar uses ({@code SearchState}), keyed
     *  by row position since neither {@code ReviewRow} has a single resource identity. Only
     *  {@link SearchState#isSearching()}/{@link SearchState#isMatch} are used here. Narrowing the
     *  list to matches makes next/prev cycling unnecessary, unlike the graph canvas. */
    private final SearchState<GlobalPos> searchState = new SearchState<>();
    private String searchQuery = "";

    public ReviewMachinesScreen(MonitoringState state, Runnable onChange, Screen backTarget) {
        super(Component.translatable("miforeman.screen.review_machines.title"));
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
        new RequestMonitoringUpdatePayload(state.hand).sendToServer();
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
                state.showRejected ? Component.translatable("miforeman.button.hide_rejected")
                        : Component.translatable("miforeman.button.show_rejected"),
                b -> {
                    state.showRejected = !state.showRejected;
                    rebuild();
                }
        );
        this.addRenderableWidget(showRejectedButton);

        Button undoButton = new ClipboardButton(contentX + 94, contentY, 40, 14,
                Component.translatable("miforeman.button.undo"), b -> {
            MachineLinkHistory.UndoResult result = state.machineLinkHistory.undo();
            if (result != null) {
                state.applyLinkHistoryResult(result, onChange);
                rebuild();
            }
        });
        undoButton.active = !state.machineLinkHistory.undoStack().isEmpty();
        this.addRenderableWidget(undoButton);

        Button redoButton = new ClipboardButton(contentX + 138, contentY, 40, 14,
                Component.translatable("miforeman.button.redo"), b -> {
            MachineLinkHistory.UndoResult result = state.machineLinkHistory.redo();
            if (result != null) {
                state.applyLinkHistoryResult(result, onChange);
                rebuild();
            }
        });
        redoButton.active = !state.machineLinkHistory.redoStack().isEmpty();
        this.addRenderableWidget(redoButton);

        EditBox searchField = new EditBox(this.font, contentX + contentW - SEARCH_FIELD_WIDTH, contentY,
                SEARCH_FIELD_WIDTH, 14, Component.literal("Search"));
        searchField.setMaxLength(128);
        searchField.setHint(Component.translatable("miforeman.screen.search_hint").withColor(0xFF8A7A68));
        // setValue() fires whatever responder is attached. Set the restored value first,
        // while the responder is still the default no-op. This avoids redundant calls
        // to refreshData() before listPanel exists.
        searchField.setValue(searchQuery);
        searchField.setResponder(val -> {
            searchQuery = val;
            refreshData();
        });
        this.addRenderableWidget(searchField);

        List<ReviewListPanel.ReviewRow> rows = state.buildReviewRows();
        searchState.setQuery(searchQuery, searchQuery.isBlank() ? Map.of() : searchableTexts(rows));
        int listY = contentY + 18;
        listPanel = new ReviewListPanel(contentX, listY, contentW, btnY - 6 - listY,
                visibleRows(rows), this::handleReviewToggle, this::handleRejectCandidateRequest, this::handleUnreject,
                scrollOffset, v -> scrollOffset = v);
        this.addRenderableWidget(listPanel);
        state.updateWorldHighlightPositions(rows);

        Button backButton = new ClipboardButton(contentX, btnY, 80, 16,
                Component.translatable("miforeman.button.back"),
                b -> Minecraft.getInstance().setScreen(backTarget)
        );
        this.addRenderableWidget(backButton);

        long addableCount = rows.stream().filter(r -> !r.linked() && !r.rejected()).count();
        long linkedCount = rows.stream().filter(ReviewListPanel.ReviewRow::linked).count();

        Button addAllButton = new ClipboardButton(contentX + contentW - 90, btnY, 90, 16,
                Component.translatable("miforeman.button.add_all"), b -> handleAddAll());
        addAllButton.active = addableCount > 0;
        this.addRenderableWidget(addAllButton);

        Button removeAllButton = new ClipboardButton(contentX + contentW - 90 - 90 - 6, btnY, 90, 16,
                Component.translatable("miforeman.button.remove_all"), b -> handleRemoveAllRequest());
        removeAllButton.active = linkedCount > 0;
        this.addRenderableWidget(removeAllButton);
    }

    public void updateLiveMonitoring(List<LiveMonitoringPayload.MachineStatusData> data) {
        state.setLiveData(data);
        refreshData();
    }

    /** Pushes fresh poll data into the existing list panel in place, avoiding a full
     *  {@link #rebuild()}. This preserves scroll and hover state without recreating widgets
     *  on every ~1s poll response. Live data only updates row product labels, never the
     *  linked or rejected machine sets. Layout only changes via rebuild() during init, resize,
     *  or explicit user actions. */
    private void refreshData() {
        if (listPanel == null) {
            rebuild();
            return;
        }
        List<ReviewListPanel.ReviewRow> rows = state.buildReviewRows();
        searchState.setQuery(searchQuery, searchQuery.isBlank() ? Map.of() : searchableTexts(rows));
        listPanel.updateRows(visibleRows(rows));
        state.updateWorldHighlightPositions(rows);
    }

    /** Search text per row: machine name, immediate product, and every resource between this
     *  machine and the goal's final target (see {@link MonitoringState#endProductNames}). Searching
     *  the end product's name surfaces every machine contributing to it, not just one whose own
     *  recipe happens to output that exact item. */
    private Map<GlobalPos, List<String>> searchableTexts(List<ReviewListPanel.ReviewRow> rows) {
        Map<GlobalPos, List<String>> texts = new HashMap<>();
        for (ReviewListPanel.ReviewRow row : rows) {
            texts.put(row.pos(), state.rowSearchableTexts(row.machineId(), row.productLabel(), row.recipeId()));
        }
        return texts;
    }

    /** Narrows to search matches when a query is active. Add All/Remove All deliberately keep
     *  operating on the full, unfiltered row list elsewhere in this class. Acting on only the
     *  currently-visible search results would be a surprising footgun. */
    private List<ReviewListPanel.ReviewRow> visibleRows(List<ReviewListPanel.ReviewRow> rows) {
        if (!searchState.isSearching()) {
            return rows;
        }
        return rows.stream().filter(r -> searchState.isMatch(r.pos())).toList();
    }

    public void updateScanResults(List<ScanResultPayload.Candidate> candidates) {
        state.setScanResults(candidates);
        rebuild();
    }

    @Override
    public void tick() {
        super.tick();
        if (state.tickAndShouldPoll()) {
            new RequestMonitoringUpdatePayload(state.hand).sendToServer();
        }
    }

    /** Links every unlinked, non-rejected candidate. Rejected candidates are skipped, since a
     * rejection is a deliberate per-machine decision the player has to undo explicitly. */
    private void handleAddAll() {
        for (ReviewListPanel.ReviewRow row : state.buildReviewRows()) {
            if (!row.linked() && !row.rejected()) {
                state.applyLink(row.pos(), () -> {});
            }
        }
        onChange.run();
        new RequestMonitoringUpdatePayload(state.hand).sendToServer();
        rebuild();
    }

    /** Unlinks every currently-linked machine, the direct inverse of {@link #handleAddAll()}. */
    private void handleRemoveAllRequest() {
        List<ReviewListPanel.ReviewRow> linked = state.buildReviewRows().stream()
                .filter(ReviewListPanel.ReviewRow::linked).toList();
        if (linked.isEmpty()) return;

        Minecraft.getInstance().setScreen(new ConfirmScreen(
                confirmed -> {
                    Minecraft.getInstance().setScreen(this);
                    if (confirmed) {
                        for (ReviewListPanel.ReviewRow row : linked) {
                            state.applyUnlink(row.pos(), () -> {});
                        }
                        onChange.run();
                        new RequestMonitoringUpdatePayload(state.hand).sendToServer();
                        rebuild();
                    }
                },
                Component.translatable("miforeman.review.unlink_all.title", linked.size()),
                Component.translatable("miforeman.review.unlink_all.message")
        ));
    }

    private void handleReviewToggle(ReviewListPanel.ReviewRow row) {
        if (row.linked()) {
            state.applyUnlink(row.pos(), onChange);
        } else {
            state.applyLink(row.pos(), onChange);
        }
        new RequestMonitoringUpdatePayload(state.hand).sendToServer();
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
                Component.translatable("miforeman.review.reject.title"),
                Component.translatable("miforeman.review.reject.message")
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
        // No dimming or vignette, matching ClipboardScreen's override.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        ClipboardChrome.drawBackground(guiGraphics, left, top, guiWidth(), guiHeight());

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        guiGraphics.drawString(this.font, Component.translatable("miforeman.screen.review_machines.title"), contentX, contentY, COLOUR_TITLE);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
