package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.goal.GraphEdge;
import com.mervyn.miforeman.goal.GraphLayoutState;
import com.mervyn.miforeman.goal.NodePosition;
import com.mervyn.miforeman.goal.NodeType;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Interactive draggable/pannable/zoomable node graph over a {@link RecipeGraph}. Replaces the
 * former TreePanel widget in Step 1 of the wizard. First widget in this codebase to use PoseStack
 * scale/translate for rendering (see branch research/graph-canvas-zoom-pan for the feasibility
 * research this was built against).
 */
public class GraphCanvas extends AbstractWidget {
    private static final int COLOR_BORDER = 0xFF6B5030;
    private static final int COLOR_TEXT = 0xFF3A2A18;
    private static final int COLOR_MUTED = 0xFF8A7A68;
    private static final int COLOR_SELECTED = 0x336B5030;
    private static final int COLOR_NODE_FILL = 0xDDFFF8DC;
    private static final int COLOR_EDGE = 0xFF8A7A68;

    private static final int NODE_WIDTH = 96;
    private static final int NODE_HEIGHT = 26;
    private static final int COLUMN_SPACING = 140;
    private static final int ROW_SPACING = 40;
    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 3.0f;

    public interface CameraChangeListener {
        void onCameraChange(double panX, double panY, float zoom);
    }

    private final RecipeGraph graph;
    private GraphLayoutState layoutState;
    private final boolean showMachineNodes;
    private final boolean dragEnabled;
    private final Map<ResourceLocation, RecipeGraphNode> visibleNodes = new LinkedHashMap<>();
    private final List<GraphEdge> visibleEdges = new ArrayList<>();
    private final Map<ResourceLocation, NodePosition> autoLayout = new HashMap<>();
    private ResourceLocation selectedNodeId;
    private final Consumer<ResourceLocation> onSelect;
    private final Consumer<GraphLayoutState> onLayoutChange;
    private final CameraChangeListener onCameraChange;

    private final GraphCamera camera;

    public GraphCanvas(int x, int y, int width, int height, RecipeGraph graph,
                        GraphLayoutState layoutState, double panX, double panY, float zoom,
                        ResourceLocation selectedNodeId,
                        Consumer<ResourceLocation> onSelect,
                        Consumer<GraphLayoutState> onLayoutChange,
                        CameraChangeListener onCameraChange,
                        boolean showMachineNodes,
                        boolean dragEnabled) {
        super(x, y, width, height, Component.literal("Recipe Graph"));
        this.graph = graph;
        this.layoutState = layoutState;
        this.camera = new GraphCamera(panX, panY, zoom);
        this.selectedNodeId = selectedNodeId;
        this.onSelect = onSelect;
        this.onLayoutChange = onLayoutChange;
        this.onCameraChange = onCameraChange;
        this.showMachineNodes = showMachineNodes;
        this.dragEnabled = dragEnabled;
        computeFilteredView();
        computeAutoLayout();
    }

    /**
     * Resource nodes and MACHINE (recipe) nodes alternate at every depth in {@link #graph}
     * (see RecipeGraphTraverser.buildGraph) -- visually two graphs merged into one tree. When
     * machines are hidden, bridge each hidden machine's inputs directly to its outputs (full
     * cross-product, since a recipe run consumes/produces all of them together in one batch)
     * so item-to-item dependencies stay visible without the recipe node in between.
     */
    private void computeFilteredView() {
        if (showMachineNodes) {
            visibleNodes.putAll(graph.nodes());
            visibleEdges.addAll(graph.edges());
            return;
        }
        for (RecipeGraphNode node : graph.nodes().values()) {
            if (node.getType() != NodeType.MACHINE) {
                visibleNodes.put(node.getId(), node);
            }
        }
        for (RecipeGraphNode node : graph.nodes().values()) {
            if (node.getType() != NodeType.MACHINE) continue;
            List<GraphEdge> inputs = node.getInputs();
            List<GraphEdge> outputs = node.getOutputs();
            if (inputs.isEmpty() || outputs.isEmpty()) continue;
            for (GraphEdge in : inputs) {
                for (GraphEdge out : outputs) {
                    visibleEdges.add(new GraphEdge(in.from(), out.to(), in.rate()));
                }
            }
        }
    }

    private void computeAutoLayout() {
        List<RecipeGraphNode> visitOrder = graph.flatten();
        TreeMap<Integer, List<RecipeGraphNode>> byDepth = new TreeMap<>();
        for (RecipeGraphNode node : visitOrder) {
            if (!visibleNodes.containsKey(node.getId())) continue;
            int column = showMachineNodes ? node.getDepth() : node.getDepth() / 2;
            byDepth.computeIfAbsent(column, d -> new java.util.ArrayList<>()).add(node);
        }
        for (Map.Entry<Integer, List<RecipeGraphNode>> entry : byDepth.entrySet()) {
            int column = entry.getKey();
            List<RecipeGraphNode> nodesAtDepth = entry.getValue();
            for (int i = 0; i < nodesAtDepth.size(); i++) {
                RecipeGraphNode node = nodesAtDepth.get(i);
                autoLayout.put(node.getId(), new NodePosition(column * COLUMN_SPACING, i * ROW_SPACING));
            }
        }
        // Safety net: any node not reached by flatten() (shouldn't happen in practice since
        // RecipeGraphNode.expanded defaults true everywhere) still needs a position.
        int fallbackColumn = byDepth.isEmpty() ? 0 : byDepth.lastKey() + 1;
        int fallbackIndex = 0;
        for (RecipeGraphNode node : visibleNodes.values()) {
            if (!autoLayout.containsKey(node.getId())) {
                autoLayout.put(node.getId(), new NodePosition(fallbackColumn * COLUMN_SPACING, fallbackIndex * ROW_SPACING));
                fallbackIndex++;
            }
        }
    }

    private NodePosition positionOf(RecipeGraphNode node) {
        if (node.getId().equals(camera.draggingNodeId()) && camera.liveDragPos() != null) {
            return camera.liveDragPos();
        }
        NodePosition manual = layoutState.nodePositions().get(node.getId());
        return manual != null ? manual : autoLayout.get(node.getId());
    }

    private @Nullable RecipeGraphNode nodeAt(double screenMouseX, double screenMouseY) {
        double cx = camera.toCanvasX(getX(), screenMouseX);
        double cy = camera.toCanvasY(getY(), screenMouseY);
        for (RecipeGraphNode node : visibleNodes.values()) {
            NodePosition p = positionOf(node);
            if (p == null) continue;
            if (cx >= p.x() && cx < p.x() + NODE_WIDTH && cy >= p.y() && cy < p.y() + NODE_HEIGHT) {
                return node;
            }
        }
        return null;
    }

    public void undo() {
        GraphLayoutState next = layoutState.undo();
        if (next != layoutState) {
            layoutState = next;
            onLayoutChange.accept(layoutState);
        }
    }

    public void redo() {
        GraphLayoutState next = layoutState.redo();
        if (next != layoutState) {
            layoutState = next;
            onLayoutChange.accept(layoutState);
        }
    }

    public boolean canUndo() {
        return !layoutState.undoStack().isEmpty();
    }

    public boolean canRedo() {
        return !layoutState.redoStack().isEmpty();
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // No border/fill of its own -- ClipboardScreen's own main panel is already the
        // background here (drawn before widgets, in Screen.render()); a second bordered panel
        // read as a nested "clipboard within the clipboard" rather than one continuous surface.

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(getX() + camera.panX(), getY() + camera.panY(), 0);
        guiGraphics.pose().scale(camera.zoom(), camera.zoom(), 1);

        // Edges: simple elbow (horizontal-vertical-horizontal) connectors, one flat color.
        // Plain edges only in this build -- rate-thickness/utilization styling is out of scope.
        for (GraphEdge edge : visibleEdges) {
            RecipeGraphNode from = visibleNodes.get(edge.from());
            RecipeGraphNode to = visibleNodes.get(edge.to());
            if (from == null || to == null) continue;
            NodePosition fromPos = positionOf(from);
            NodePosition toPos = positionOf(to);
            if (fromPos == null || toPos == null) continue;
            drawElbowConnector(guiGraphics,
                    fromPos.x() + NODE_WIDTH, fromPos.y() + NODE_HEIGHT / 2,
                    toPos.x(), toPos.y() + NODE_HEIGHT / 2);
        }

        Minecraft mc = Minecraft.getInstance();
        for (RecipeGraphNode node : visibleNodes.values()) {
            NodePosition pos = positionOf(node);
            if (pos == null) continue;
            boolean selected = node.getId().equals(selectedNodeId);
            guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOR_NODE_FILL);
            if (selected) {
                guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOR_SELECTED);
            }
            guiGraphics.renderOutline(pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, COLOR_BORDER);

            String name = DisplayFormat.formatId(node.getId());
            boolean hasAmbiguity = !node.getAmbiguityOptions().isEmpty();
            if (hasAmbiguity) {
                name = "⚠ " + name;
            }
            int maxTextWidth = NODE_WIDTH - 6;
            if (mc.font.width(name) > maxTextWidth) {
                name = mc.font.plainSubstrByWidth(name, maxTextWidth - 8) + "..";
            }
            guiGraphics.drawString(mc.font, name, pos.x() + 3, pos.y() + 3, COLOR_TEXT, false);

            String rateText = String.format("%.1f", node.getRequiredRate());
            guiGraphics.drawString(mc.font, rateText, pos.x() + 3, pos.y() + 14, COLOR_MUTED, false);
        }

        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();
    }

    private void drawElbowConnector(GuiGraphics guiGraphics, int fromX, int fromY, int toX, int toY) {
        int midX = (fromX + toX) / 2;
        drawLine(guiGraphics, fromX, fromY, midX, fromY);
        drawLine(guiGraphics, midX, fromY, midX, toY);
        drawLine(guiGraphics, midX, toY, toX, toY);
    }

    private void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2) {
        int thickness = 1;
        if (y1 == y2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            guiGraphics.fill(minX, y1 - thickness, maxX, y1 + thickness, COLOR_EDGE);
        } else {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            guiGraphics.fill(x1 - thickness, minY, x1 + thickness, maxY, COLOR_EDGE);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        RecipeGraphNode hit = nodeAt(mouseX, mouseY);
        camera.beginDrag(hit != null ? hit.getId() : null, hit != null ? positionOf(hit) : null);
        return true;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        boolean panned = camera.onDrag(dragEnabled, dragX, dragY);
        if (panned) {
            onCameraChange.onCameraChange(camera.panX(), camera.panY(), camera.zoom());
        }
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        GraphCamera.DragEnd end = camera.onRelease(dragEnabled);
        if (end.committedNodeId() != null) {
            layoutState = layoutState.withMove(end.committedNodeId(), end.committedFrom(), end.committedTo());
            onLayoutChange.accept(layoutState);
        } else if (end.clickedNodeId() != null) {
            selectedNodeId = end.clickedNodeId();
            onSelect.accept(end.clickedNodeId());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !active) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        camera.zoomAt(getX(), getY(), mouseX, mouseY, scrollY, MIN_ZOOM, MAX_ZOOM);
        onCameraChange.onCameraChange(camera.panX(), camera.panY(), camera.zoom());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
