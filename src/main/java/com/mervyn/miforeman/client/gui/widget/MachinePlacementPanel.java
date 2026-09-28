package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.goal.MachinePlacement;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The graph canvas's bottom-right chip for linked machines the graph has no node for, and the
 * list it opens: place a never-run machine on a node (directly when only one node fits, or by
 * picking one on the canvas), unplace one placed by hand, or see what an off-plan machine is
 * actually making.
 */
public class MachinePlacementPanel {
    /** What the canvas does when the player acts on a row. */
    public interface Actions {
        /** Places {@code pos} on node {@code recipeId}, or unplaces it when null. */
        void assign(GlobalPos pos, @Nullable ResourceLocation recipeId);

        /** Enters pick mode: the player clicks one of {@code targets} on the canvas. */
        void beginPicking(GlobalPos pos, String machineName, Set<ResourceLocation> targets);
    }

    private static final int CHIP_HEIGHT = 14;
    private static final int PANEL_WIDTH = 230;
    private static final int HEADER_HEIGHT = 13;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_ROWS = 6;
    private static final int MARGIN = 6;

    private static final int COLOUR_FILL = 0xEEEFE0BE;
    private static final int COLOUR_BORDER = 0xFF4A3620;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_ACTION = 0xFF6B5030;
    private static final int COLOUR_ROW_HOVER = 0x336B5030;

    private record Row(LiveMonitoringPayload.MachineStatusData machine, MachinePlacement.Kind kind,
                       List<RecipeGraphNode> compatible) {}

    private final Font font;
    private final Supplier<RecipeGraph> graph;
    private final Actions actions;
    private List<Row> rows = List.of();
    private boolean open = false;
    /** Bottom-right corner of the canvas, in screen space; set every frame by the canvas. */
    private int right;
    private int bottom;

    public MachinePlacementPanel(Font font, Supplier<RecipeGraph> graph, Actions actions) {
        this.font = font;
        this.graph = graph;
        this.actions = actions;
    }

    public void setCorner(int right, int bottom) {
        this.right = right;
        this.bottom = bottom;
    }

    public void update(List<LiveMonitoringPayload.MachineStatusData> machines, Set<ResourceLocation> machineNodeIds) {
        List<Row> built = new ArrayList<>();
        for (LiveMonitoringPayload.MachineStatusData machine : machines) {
            MachinePlacement.Kind kind = MachinePlacement.classify(machine, machineNodeIds);
            if (kind == MachinePlacement.Kind.ON_GRAPH) continue;
            List<RecipeGraphNode> compatible = kind == MachinePlacement.Kind.UNPLACED
                    ? MachinePlacement.compatibleNodes(graph.get().nodes().values(), machine.recipeTypeId().orElse(null))
                    : List.of();
            built.add(new Row(machine, kind, compatible));
        }
        this.rows = built;
        if (rows.isEmpty()) open = false;
    }

    /** Closes the list; returns true if it was open, so Esc can be consumed. */
    public boolean close() {
        boolean wasOpen = open;
        open = false;
        return wasOpen;
    }

    private Component chipText() {
        long notOnGraph = rows.stream().filter(r -> r.kind() != MachinePlacement.Kind.ASSIGNED).count();
        return notOnGraph > 0
                ? Component.translatable("miforeman.graph.machines_off_graph", notOnGraph)
                : Component.translatable("miforeman.graph.placed_waiting", rows.size());
    }

    private int chipWidth() {
        return font.width(chipText()) + 8;
    }

    private int chipX() {
        return right - MARGIN - chipWidth();
    }

    private int chipY() {
        return bottom - MARGIN - CHIP_HEIGHT;
    }

    private int visibleRowCount() {
        return Math.min(rows.size(), MAX_ROWS);
    }

    private int panelHeight() {
        int footer = rows.size() > MAX_ROWS ? 11 : 0;
        return HEADER_HEIGHT + visibleRowCount() * ROW_HEIGHT + footer + 3;
    }

    private int panelX() {
        return right - MARGIN - PANEL_WIDTH;
    }

    private int panelY() {
        return chipY() - 2 - panelHeight();
    }

    private int rowY(int index) {
        return panelY() + HEADER_HEIGHT + index * ROW_HEIGHT;
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (rows.isEmpty()) return;

        int cx = chipX();
        int cy = chipY();
        int cw = chipWidth();
        guiGraphics.fill(cx, cy, cx + cw, cy + CHIP_HEIGHT, COLOUR_FILL);
        if (inside(mouseX, mouseY, cx, cy, cw, CHIP_HEIGHT)) {
            guiGraphics.fill(cx, cy, cx + cw, cy + CHIP_HEIGHT, COLOUR_ROW_HOVER);
        }
        guiGraphics.renderOutline(cx, cy, cw, CHIP_HEIGHT, COLOUR_BORDER);
        guiGraphics.drawString(font, chipText(), cx + 4, cy + 3, COLOUR_TEXT, false);

        if (!open) return;

        int px = panelX();
        int py = panelY();
        int ph = panelHeight();
        guiGraphics.fill(px, py, px + PANEL_WIDTH, py + ph, COLOUR_FILL);
        guiGraphics.renderOutline(px, py, PANEL_WIDTH, ph, COLOUR_BORDER);
        guiGraphics.drawString(font, Component.translatable("miforeman.graph.placement_title"), px + 4, py + 3,
                COLOUR_TEXT, false);

        int textWidth = PANEL_WIDTH - 8;
        for (int i = 0; i < visibleRowCount(); i++) {
            Row row = rows.get(i);
            int ry = rowY(i);
            boolean actionable = isActionable(row);
            if (actionable && inside(mouseX, mouseY, px + 1, ry, PANEL_WIDTH - 2, ROW_HEIGHT)) {
                guiGraphics.fill(px + 1, ry, px + PANEL_WIDTH - 1, ry + ROW_HEIGHT, COLOUR_ROW_HOVER);
            }
            guiGraphics.drawString(font, trim(machineLabel(row.machine()), textWidth), px + 4, ry + 2, COLOUR_TEXT, false);
            guiGraphics.drawString(font, trim(actionLabel(row).getString(), textWidth), px + 4, ry + 12,
                    actionable ? COLOUR_ACTION : COLOUR_MUTED, false);
        }
        if (rows.size() > MAX_ROWS) {
            guiGraphics.drawString(font, Component.translatable("miforeman.graph.more", rows.size() - MAX_ROWS),
                    px + 4, rowY(MAX_ROWS) + 1, COLOUR_MUTED, false);
        }
    }

    /** Returns true when the click landed on the chip or the open list (consumed either way). */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (rows.isEmpty() || button != 0) return false;
        if (inside(mouseX, mouseY, chipX(), chipY(), chipWidth(), CHIP_HEIGHT)) {
            open = !open;
            return true;
        }
        if (!open) return false;
        if (!inside(mouseX, mouseY, panelX(), panelY(), PANEL_WIDTH, panelHeight())) {
            // A click elsewhere on the canvas closes the list but still reaches the canvas.
            open = false;
            return false;
        }
        for (int i = 0; i < visibleRowCount(); i++) {
            if (!inside(mouseX, mouseY, panelX(), rowY(i), PANEL_WIDTH, ROW_HEIGHT)) continue;
            Row row = rows.get(i);
            if (!isActionable(row)) return true;
            GlobalPos pos = row.machine().pos();
            if (row.kind() == MachinePlacement.Kind.ASSIGNED) {
                actions.assign(pos, null);
            } else if (row.compatible().size() == 1) {
                actions.assign(pos, row.compatible().get(0).getId());
            } else {
                actions.beginPicking(pos, DisplayFormat.formatId(row.machine().machineId()),
                        row.compatible().stream().map(RecipeGraphNode::getId).collect(Collectors.toSet()));
            }
            open = false;
            return true;
        }
        return true;
    }

    private static boolean isActionable(Row row) {
        return row.kind() == MachinePlacement.Kind.ASSIGNED
                || (row.kind() == MachinePlacement.Kind.UNPLACED && !row.compatible().isEmpty());
    }

    private static String machineLabel(LiveMonitoringPayload.MachineStatusData machine) {
        BlockPos p = machine.pos().pos();
        return DisplayFormat.formatId(machine.machineId()) + " (" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    private static Component actionLabel(Row row) {
        return switch (row.kind()) {
            case ASSIGNED -> Component.translatable("miforeman.graph.unplace");
            case OFF_PLAN -> {
                ResourceLocation recipeId = row.machine().recipeId().orElseThrow();
                String product = DisplayFormat.productLabel(recipeId);
                yield Component.translatable("miforeman.graph.off_plan",
                        product != null ? product : DisplayFormat.formatId(recipeId));
            }
            default -> switch (row.compatible().size()) {
                case 0 -> Component.translatable("miforeman.graph.no_node");
                case 1 -> Component.translatable("miforeman.graph.place_on",
                        DisplayFormat.formatId(row.compatible().get(0).getId()));
                default -> Component.translatable("miforeman.graph.place_choose", row.compatible().size());
            };
        };
    }

    private String trim(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, width - 8) + "..";
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
