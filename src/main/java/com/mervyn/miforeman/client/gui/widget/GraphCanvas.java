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
    private static final int COLOR_BORDER_LIGHT = 0xFF9C8058;
    private static final int COLOR_BORDER_DARK = 0xFF4A3620;
    private static final int COLOR_TEXT = 0xFF3A2A18;
    private static final int COLOR_MUTED = 0xFF8A7A68;
    private static final int COLOR_SELECTED = 0x336B5030;
    private static final int COLOR_HOVER = 0x22FFFFFF;
    private static final int COLOR_NODE_FILL_TOP = 0xDDFFFBEF;
    private static final int COLOR_NODE_FILL_BOTTOM = 0xDDEFE0BE;
    private static final int COLOR_EDGE = 0xFF8A7A68;
    private static final int COLOR_EDGE_DIM = 0x558A7A68;
    private static final int COLOR_EDGE_HIGHLIGHT = 0xFFD4A017;

    private static final int NODE_WIDTH = 96;
    private static final int NODE_HEIGHT = 26;
    // MACHINE nodes render as a chamfered octagon instead of a plain rectangle, so the two
    // alternating node kinds in the graph (see computeFilteredView's javadoc) read apart at a
    // glance without relying on color alone.
    private static final int MACHINE_CHAMFER = 6;
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

        RecipeGraphNode hovered = isMouseOver(mouseX, mouseY) ? nodeAt(mouseX, mouseY) : null;
        ResourceLocation hoveredNodeId = hovered != null ? hovered.getId() : null;

        // Edges: simple elbow (horizontal-vertical-horizontal) connectors. When a node is
        // selected, edges touching it pop in COLOR_EDGE_HIGHLIGHT and the rest dim, so
        // dependencies are traceable without hunting through crossing lines.
        for (GraphEdge edge : visibleEdges) {
            RecipeGraphNode from = visibleNodes.get(edge.from());
            RecipeGraphNode to = visibleNodes.get(edge.to());
            if (from == null || to == null) continue;
            NodePosition fromPos = positionOf(from);
            NodePosition toPos = positionOf(to);
            if (fromPos == null || toPos == null) continue;
            int color = COLOR_EDGE;
            if (selectedNodeId != null) {
                boolean touchesSelected = edge.from().equals(selectedNodeId) || edge.to().equals(selectedNodeId);
                color = touchesSelected ? COLOR_EDGE_HIGHLIGHT : COLOR_EDGE_DIM;
            }
            drawElbowConnector(guiGraphics,
                    fromPos.x() + NODE_WIDTH, fromPos.y() + NODE_HEIGHT / 2,
                    toPos.x(), toPos.y() + NODE_HEIGHT / 2, color);
        }

        Minecraft mc = Minecraft.getInstance();
        for (RecipeGraphNode node : visibleNodes.values()) {
            NodePosition pos = positionOf(node);
            if (pos == null) continue;
            boolean selected = node.getId().equals(selectedNodeId);
            boolean hoveredNode = node.getId().equals(hoveredNodeId);

            if (node.getType() == NodeType.MACHINE) {
                fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER, COLOR_BORDER_LIGHT);
                fillChamferedGradient(guiGraphics, pos.x() + 1, pos.y() + 1, NODE_WIDTH - 2, NODE_HEIGHT - 2,
                        Math.max(0, MACHINE_CHAMFER - 1), COLOR_NODE_FILL_TOP, COLOR_NODE_FILL_BOTTOM);
                if (selected) {
                    fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER, COLOR_SELECTED);
                }
                if (hoveredNode) {
                    fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER, COLOR_HOVER);
                }
            } else {
                guiGraphics.fillGradient(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOR_NODE_FILL_TOP, COLOR_NODE_FILL_BOTTOM);
                if (selected) {
                    guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOR_SELECTED);
                }
                if (hoveredNode) {
                    guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOR_HOVER);
                }
                // Raised-tile treatment: light top/left edge, dark bottom/right edge.
                guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + 1, COLOR_BORDER_LIGHT);
                guiGraphics.fill(pos.x(), pos.y(), pos.x() + 1, pos.y() + NODE_HEIGHT, COLOR_BORDER_LIGHT);
                guiGraphics.fill(pos.x(), pos.y() + NODE_HEIGHT - 1, pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOR_BORDER_DARK);
                guiGraphics.fill(pos.x() + NODE_WIDTH - 1, pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOR_BORDER_DARK);
            }

            // Nudged clear of the chamfered top-left corner on MACHINE nodes so the first glyph
            // doesn't render partly over the cut-off area.
            int textInset = node.getType() == NodeType.MACHINE ? 3 + MACHINE_CHAMFER / 2 : 3;

            String name = DisplayFormat.formatId(node.getId());
            boolean hasAmbiguity = !node.getAmbiguityOptions().isEmpty();
            if (hasAmbiguity) {
                name = "⚠ " + name;
            }
            int maxTextWidth = NODE_WIDTH - textInset - 3;
            if (mc.font.width(name) > maxTextWidth) {
                name = mc.font.plainSubstrByWidth(name, maxTextWidth - 8) + "..";
            }
            guiGraphics.drawString(mc.font, name, pos.x() + textInset, pos.y() + 3, COLOR_TEXT, false);

            String rateText = String.format("%.1f", node.getRequiredRate());
            guiGraphics.drawString(mc.font, rateText, pos.x() + textInset, pos.y() + 14, COLOR_MUTED, false);
        }

        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();
    }

    /** Flat-color chamfered-octagon fill: a full-width middle band plus corner rows that shrink
     *  inward by one pixel per row, cutting the four corners at 45 degrees. */
    private void fillChamfered(GuiGraphics guiGraphics, int x, int y, int w, int h, int chamfer, int color) {
        int c = Math.min(chamfer, h / 2);
        guiGraphics.fill(x, y + c, x + w, y + h - c, color);
        for (int i = 0; i < c; i++) {
            int inset = c - i;
            guiGraphics.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            guiGraphics.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    /** Same chamfered-octagon shape as {@link #fillChamfered}, but with a per-row lerped color
     *  instead of one flat color, approximating a vertical gradient across the whole shape. */
    private void fillChamferedGradient(GuiGraphics guiGraphics, int x, int y, int w, int h, int chamfer, int colorTop, int colorBottom) {
        int c = Math.min(chamfer, h / 2);
        for (int row = 0; row < h; row++) {
            float t = h <= 1 ? 0 : (float) row / (h - 1);
            int color = lerpColor(colorTop, colorBottom, t);
            int inset = 0;
            if (row < c) {
                inset = c - row;
            } else if (row >= h - c) {
                inset = c - (h - 1 - row);
            }
            guiGraphics.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    private static int lerpColor(int from, int to, float t) {
        int fa = (from >>> 24) & 0xFF, fr = (from >>> 16) & 0xFF, fg = (from >>> 8) & 0xFF, fb = from & 0xFF;
        int ta = (to >>> 24) & 0xFF, tr = (to >>> 16) & 0xFF, tg = (to >>> 8) & 0xFF, tb = to & 0xFF;
        int a = fa + Math.round((ta - fa) * t);
        int r = fr + Math.round((tr - fr) * t);
        int g = fg + Math.round((tg - fg) * t);
        int b = fb + Math.round((tb - fb) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawElbowConnector(GuiGraphics guiGraphics, int fromX, int fromY, int toX, int toY, int color) {
        int midX = (fromX + toX) / 2;
        drawLine(guiGraphics, fromX, fromY, midX, fromY, color);
        drawLine(guiGraphics, midX, fromY, midX, toY, color);
        drawLine(guiGraphics, midX, toY, toX, toY, color);
    }

    private void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int color) {
        int thickness = 1;
        if (y1 == y2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            guiGraphics.fill(minX, y1 - thickness, maxX, y1 + thickness, color);
        } else {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            guiGraphics.fill(x1 - thickness, minY, x1 + thickness, maxY, color);
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
