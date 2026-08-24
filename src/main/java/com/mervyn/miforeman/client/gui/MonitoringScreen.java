package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.client.WorldHighlightRenderer;
import com.mervyn.miforeman.client.gui.widget.ClipboardButton;
import com.mervyn.miforeman.client.gui.widget.MonitoringListPanel;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import com.mervyn.miforeman.network.RequestMonitoringUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Full-window live machine status list, opened from ClipboardScreen's Monitor step so every
 * machine gets a row instead of the old hardcoded top-3 summary. Reads/mutates the
 * {@link MonitoringState} directly instead of reaching through a parent screen --
 * {@code state.liveData} stays the single source of truth. Keeps polling
 * (RequestMonitoringUpdatePayload) while it's the active screen and refreshes its list whenever a
 * response arrives (see ClientAccess.handleLiveMonitoring); polling naturally hands back to
 * ClipboardScreen the moment the player leaves, since only the active Screen gets ticked.
 */
public class MonitoringScreen extends Screen {
    private static final int MIN_GUI_WIDTH = 440;
    private static final int MIN_GUI_HEIGHT = 230;
    private static final int PADDING = 8;
    private static final int COLOR_TITLE = 0xFFDAA520;
    private static final int POLL_INTERVAL_TICKS = 20;
    private static final List<String> STATUS_PRIORITY = List.of("RED", "ORANGE", "YELLOW", "GREEN");

    private final MonitoringState state;
    private final boolean perHour;
    private final Screen backTarget;
    private int tickCount = 0;
    private int scrollOffset = 0;

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
    }

    private void rebuild() {
        this.clearWidgets();

        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;
        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;
        int contentW = guiWidth() - (PADDING + ClipboardChrome.MAIN_BORDER) * 2 - 4;
        int btnY = top + guiHeight() - PADDING - ClipboardChrome.MAIN_BORDER - 22;

        List<LiveMonitoringPayload.MachineStatusData> sorted = new ArrayList<>(state.liveData);
        sorted.sort(Comparator.comparingInt(this::statusRank));

        List<MonitoringListPanel.MonitoringRow> displayRows = sorted.stream()
                .map(m -> new MonitoringListPanel.MonitoringRow(m, m.recipeId().map(MonitoringState::resolveProductLabel).orElse(null)))
                .toList();

        MonitoringListPanel listPanel = new MonitoringListPanel(contentX, contentY, contentW, btnY - 6 - contentY,
                displayRows, perHour, WorldHighlightRenderer.getSelected(), this::handleLocate,
                scrollOffset, v -> scrollOffset = v);
        this.addRenderableWidget(listPanel);

        Button backButton = new ClipboardButton(contentX, btnY, 80, 16,
                Component.literal("<- Back"),
                b -> Minecraft.getInstance().setScreen(backTarget)
        );
        this.addRenderableWidget(backButton);

        new RequestMonitoringUpdatePayload().sendToServer();
    }

    /** Independent of the general Highlights on/off toggle -- clicking Locate on the current
     *  selection clears it, otherwise it becomes the new selection. Either way it's visible
     *  regardless of whether the linked/candidate highlights are on. */
    private void handleLocate(MonitoringListPanel.MonitoringRow row) {
        BlockPos pos = row.machine().pos();
        if (pos.equals(WorldHighlightRenderer.getSelected())) {
            WorldHighlightRenderer.setSelected(null);
        } else {
            WorldHighlightRenderer.setSelected(pos);
        }
        rebuild();
    }

    private int statusRank(LiveMonitoringPayload.MachineStatusData machine) {
        int idx = STATUS_PRIORITY.indexOf(machine.status());
        return idx < 0 ? STATUS_PRIORITY.size() : idx;
    }

    public void updateLiveMonitoring(List<LiveMonitoringPayload.MachineStatusData> data) {
        state.setLiveData(data);
        rebuild();
    }

    @Override
    public void tick() {
        super.tick();
        tickCount++;
        if (tickCount >= POLL_INTERVAL_TICKS) {
            tickCount = 0;
            new RequestMonitoringUpdatePayload().sendToServer();
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
        guiGraphics.drawString(this.font, Component.literal("Live Monitoring"), contentX, contentY, COLOR_TITLE);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
