package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.WorldHighlightRenderer;
import com.mervyn.miforeman.client.gui.widget.ClipboardButton;
import com.mervyn.miforeman.client.gui.widget.MonitoringListPanel;
import com.mervyn.miforeman.client.gui.widget.SearchState;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import com.mervyn.miforeman.network.RequestMonitoringUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-window screen displaying live machine status data and monitoring metrics.
 */
public class MonitoringScreen extends Screen {
    private static final int MIN_GUI_WIDTH = 440;
    private static final int MIN_GUI_HEIGHT = 230;
    private static final int PADDING = 8;
    private static final int COLOUR_TITLE = 0xFFDAA520;
    private static final int POLL_INTERVAL_TICKS = 20;

    private final MonitoringState state;
    private final boolean perHour;
    private final Screen backTarget;
    private int tickCount = 0;
    private int scrollOffset = 0;
    /** Kept so poll responses can push new row data in place (see {@link #refreshData()})
     *  instead of clearing and recreating every widget on the screen every ~1s. */
    private @Nullable MonitoringListPanel listPanel;
    /** Same matching logic the recipe graph's search bar uses ({@code SearchState}), keyed by
     *  row position. Only {@link SearchState#isSearching()}/{@link SearchState#isMatch} are used
     *  here. Narrowing the list to matches makes next/prev cycling unnecessary. */
    private final SearchState<GlobalPos> searchState = new SearchState<>();
    private String searchQuery = "";

    public MonitoringScreen(MonitoringState state, boolean perHour, Screen backTarget) {
        super(Component.literal("Live Monitoring"));
        this.state = state;
        this.perHour = perHour;
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

        EditBox searchField = new EditBox(this.font, contentX, contentY, contentW, 14, Component.literal("Search"));
        searchField.setMaxLength(128);
        searchField.setHint(Component.literal("Search...").withColor(0xFF8A7A68));
        // setValue() unconditionally fires whatever responder is attached -- set the restored
        // value first, while the responder is still EditBox's own no-op default, so restoring
        // the query on rebuild() doesn't redundantly re-trigger refreshData().
        searchField.setValue(searchQuery);
        searchField.setResponder(val -> {
            searchQuery = val;
            refreshData();
        });
        this.addRenderableWidget(searchField);

        int listY = contentY + 18;
        listPanel = new MonitoringListPanel(contentX, listY, contentW, btnY - 6 - listY,
                buildDisplayRows(), perHour, WorldHighlightRenderer.getSelected(), this::handleLocate,
                scrollOffset, v -> scrollOffset = v);
        this.addRenderableWidget(listPanel);

        Button backButton = new ClipboardButton(contentX, btnY, 80, 16,
                Component.literal("<- Back"),
                b -> Minecraft.getInstance().setScreen(backTarget)
        );
        this.addRenderableWidget(backButton);
    }

    /** Pushes fresh poll data into the existing list panel in place, instead of the full
     *  {@link #rebuild()} -- avoids resetting scroll/hover state and recreating widgets on
     *  every ~1s poll response. Layout (positions, the back button, selection) only changes
     *  via {@link #rebuild()}, triggered from init()/resize/handleLocate. */
    private void refreshData() {
        if (listPanel != null) {
            listPanel.updateRows(buildDisplayRows());
        } else {
            rebuild();
        }
    }

    private List<MonitoringListPanel.MonitoringRow> buildDisplayRows() {
        List<LiveMonitoringPayload.MachineStatusData> sorted = new ArrayList<>(state.liveData);
        sorted.sort(Comparator.comparingInt(this::statusRank));

        List<MonitoringListPanel.MonitoringRow> rows = sorted.stream()
                .map(m -> new MonitoringListPanel.MonitoringRow(m, m.recipeId().map(MonitoringState::resolveProductLabel).orElse(null)))
                .toList();

        searchState.setQuery(searchQuery, searchQuery.isBlank() ? Map.of() : searchableTexts(rows));
        if (!searchState.isSearching()) {
            return rows;
        }
        return rows.stream().filter(r -> searchState.isMatch(r.machine().pos())).toList();
    }

    /** Search text per row: machine name, immediate product, and every resource between this
     *  machine and the goal's final target (see {@link MonitoringState#endProductNames}). Searching
     *  the end product's name surfaces every machine contributing to it, not just one whose own
     *  recipe happens to output that exact item. */
    private Map<GlobalPos, List<String>> searchableTexts(List<MonitoringListPanel.MonitoringRow> rows) {
        Map<GlobalPos, List<String>> texts = new HashMap<>();
        for (MonitoringListPanel.MonitoringRow row : rows) {
            texts.put(row.machine().pos(), state.rowSearchableTexts(
                    row.machine().machineId(), row.productLabel(), row.machine().recipeId().orElse(null)));
        }
        return texts;
    }

    /** Toggles locating a machine in-world when clicked. */
    private void handleLocate(MonitoringListPanel.MonitoringRow row) {
        GlobalPos pos = row.machine().pos();
        if (pos.equals(WorldHighlightRenderer.getSelected())) {
            WorldHighlightRenderer.setSelected(null);
        } else {
            WorldHighlightRenderer.setSelected(pos);
        }
        rebuild();
    }

    private int statusRank(LiveMonitoringPayload.MachineStatusData machine) {
        return machine.status().ordinal();
    }

    public void updateLiveMonitoring(List<LiveMonitoringPayload.MachineStatusData> data) {
        state.setLiveData(data);
        refreshData();
    }

    @Override
    public void tick() {
        super.tick();
        tickCount++;
        if (tickCount >= POLL_INTERVAL_TICKS) {
            tickCount = 0;
            new RequestMonitoringUpdatePayload(state.hand).sendToServer();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(backTarget);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately no dimming/vignette -- matches ClipboardScreen's own override.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        ClipboardChrome.drawBackground(guiGraphics, left, top, guiWidth(), guiHeight());

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        guiGraphics.drawString(this.font, Component.literal("Live Monitoring"), contentX, contentY, COLOUR_TITLE);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
