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
    private @Nullable MonitoringListPanel listPanel;
    private final SearchState<GlobalPos> searchState = new SearchState<>();
    private String searchQuery = "";

    public MonitoringScreen(MonitoringState state, boolean perHour, Screen backTarget) {
        super(Component.translatable("miforeman.screen.live_monitoring.title"));
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
        searchField.setHint(Component.translatable("miforeman.screen.search_hint").withColor(0xFF8A7A68));
        // setValue fires the attached responder, so restore the value before attaching it.
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
                Component.translatable("miforeman.button.back"),
                b -> Minecraft.getInstance().setScreen(backTarget)
        );
        this.addRenderableWidget(backButton);
    }

    /** Updates rows in place on each poll, keeping scroll and hover state. Only rebuild changes
     *  the layout. */
    private void refreshData() {
        if (listPanel != null) {
            listPanel.updateRows(buildDisplayRows());
        } else {
            rebuild();
        }
    }

    /** A recipe the client can't resolve to a label, such as a runtime-built furnace recipe, still
     *  ran, so it gets no label. */
    private static @org.jetbrains.annotations.Nullable String rowLabel(LiveMonitoringPayload.MachineStatusData m) {
        if (m.recipeId().isEmpty()) {
            return net.minecraft.client.resources.language.I18n.get("miforeman.monitoring.waiting_first_craft");
        }
        String product = com.mervyn.miforeman.client.DisplayFormat.productLabel(m.recipeId().get());
        if (m.assigned()) {
            return net.minecraft.client.resources.language.I18n.get("miforeman.monitoring.placed_waiting",
                    product != null ? product : com.mervyn.miforeman.client.DisplayFormat.formatId(m.recipeId().get()));
        }
        return product;
    }

    private List<MonitoringListPanel.MonitoringRow> buildDisplayRows() {
        List<LiveMonitoringPayload.MachineStatusData> sorted = new ArrayList<>(state.liveData);
        sorted.sort(Comparator.comparingInt(this::statusRank));

        List<MonitoringListPanel.MonitoringRow> rows = sorted.stream()
                // A machine with no recipe yet has only ever been idle since it was linked.
                .map(m -> new MonitoringListPanel.MonitoringRow(m, rowLabel(m)))
                .toList();

        searchState.setQuery(searchQuery, searchQuery.isBlank() ? Map.of() : searchableTexts(rows));
        if (!searchState.isSearching()) {
            return rows;
        }
        return rows.stream().filter(r -> searchState.isMatch(r.machine().pos())).toList();
    }

    /** Includes every resource between this machine and the goal's target, so searching the end
     *  product finds every machine feeding it. */
    private Map<GlobalPos, List<String>> searchableTexts(List<MonitoringListPanel.MonitoringRow> rows) {
        Map<GlobalPos, List<String>> texts = new HashMap<>();
        for (MonitoringListPanel.MonitoringRow row : rows) {
            texts.put(row.machine().pos(), state.rowSearchableTexts(
                    row.machine().machineId(), row.productLabel(), row.machine().recipeId().orElse(null)));
        }
        return texts;
    }

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
        // No dimming, to match ClipboardScreen.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        ClipboardChrome.drawBackground(guiGraphics, left, top, guiWidth(), guiHeight());

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        guiGraphics.drawString(this.font, Component.translatable("miforeman.screen.live_monitoring.title"), contentX, contentY, COLOUR_TITLE, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
