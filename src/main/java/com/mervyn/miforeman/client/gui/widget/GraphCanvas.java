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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Interactive canvas widget for rendering and manipulating a {@link RecipeGraph}.
 */
public class GraphCanvas extends AbstractWidget {
    private static final int COLOUR_BORDER_LIGHT = 0xFF9C8058;
    private static final int COLOUR_BORDER_DARK = 0xFF4A3620;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_SELECTED = 0x336B5030;
    private static final int COLOUR_HOVER = 0x22FFFFFF;
    private static final int COLOUR_NODE_FILL_TOP = 0xDDFFFBEF;
    private static final int COLOUR_NODE_FILL_BOTTOM = 0xDDEFE0BE;
    private static final int COLOUR_EDGE = 0xFF8A7A68;
    private static final int COLOUR_EDGE_DIM = 0x558A7A68;
    private static final int COLOUR_EDGE_HIGHLIGHT = 0xFFD4A017;

    private static final int NODE_WIDTH = 96;
    private static final int NODE_HEIGHT = 26;
    // MACHINE nodes render as a chamfered octagon instead of a plain rectangle, so the two
    // alternating node kinds in the graph (see computeFilteredView's javadoc) read apart at a
    // glance without relying on colour alone.
    private static final int MACHINE_CHAMFER = 6;
    private static final int COLUMN_SPACING = 140;
    private static final int ROW_SPACING = 40;
    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 3.0f;

    private static final int COLOUR_NODE_FILL_TOP_DIM = 0x33FFFBEF;
    private static final int COLOUR_NODE_FILL_BOTTOM_DIM = 0x33EFE0BE;
    private static final int COLOUR_BORDER_LIGHT_DIM = 0x449C8058;
    private static final int COLOUR_BORDER_DARK_DIM = 0x444A3620;
    private static final int COLOUR_TEXT_DIM = 0x553A2A18;
    private static final int COLOUR_MUTED_DIM = 0x448A7A68;
    private static final int COLOUR_SEARCH_MATCH_BORDER = 0xFFFFD700;
    private static final int COLOUR_SEARCH_CURRENT_MATCH = 0xFFFF9900;

    public interface CameraChangeListener {
        void onCameraChange(double panX, double panY, float zoom);
    }

    private final RecipeGraph graph;
    private GraphLayoutState layoutState;
    private final GraphCamera camera;
    private final Map<ResourceLocation, RecipeGraphNode> visibleNodes = new LinkedHashMap<>();
    private final List<GraphEdge> visibleEdges = new ArrayList<>();
    private final Map<ResourceLocation, NodePosition> autoLayout = new HashMap<>();
    private @Nullable ResourceLocation selectedNodeId;
    private final Consumer<ResourceLocation> onSelect;
    private final Consumer<GraphLayoutState> onLayoutChange;
    private final CameraChangeListener onCameraChange;
    private final com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode viewMode;
    private final boolean dragEnabled;
    private final SearchState<ResourceLocation> searchState = new SearchState<>();
    private final GraphSearchBar searchBar;
    private final HiddenNodesDrawer hiddenNodesDrawer;

    public GraphCanvas(int x, int y, int width, int height, RecipeGraph graph,
                        GraphLayoutState layoutState, double panX, double panY, float zoom,
                        ResourceLocation selectedNodeId,
                        Consumer<ResourceLocation> onSelect,
                        Consumer<GraphLayoutState> onLayoutChange,
                        CameraChangeListener onCameraChange,
                        com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode viewMode,
                        boolean dragEnabled) {
        super(x, y, width, height, Component.literal("Recipe Graph"));
        this.graph = graph;
        this.layoutState = layoutState;
        this.camera = new GraphCamera(panX, panY, zoom);
        this.selectedNodeId = selectedNodeId;
        this.onSelect = onSelect;
        this.onLayoutChange = onLayoutChange;
        this.onCameraChange = onCameraChange;
        this.viewMode = viewMode;
        this.dragEnabled = dragEnabled;
        this.searchBar = new GraphSearchBar(this.searchState, Minecraft.getInstance().font, this::onSearchMatchChanged, null);
        this.hiddenNodesDrawer = new HiddenNodesDrawer(Minecraft.getInstance().font, () -> this.layoutState, () -> this.graph, this::toggleNodeVisibility, this::unhideAll);
        updateSearchBarPosition();
        updateDrawerPosition();
        computeFilteredView();
        computeAutoLayout();
    }

    /**
     * Filters visible nodes and bridges edges according to {@link #viewMode} and {@link GraphLayoutState#hiddenNodes()}:
     * <ul>
     *   <li>{@code ALL}: Displays all unhidden resource and machine nodes.</li>
     *   <li>{@code ITEMS_ONLY}: Displays only unhidden item/fluid nodes, bridging across machines.</li>
     *   <li>{@code MACHINES_ONLY}: Displays only unhidden MACHINE nodes, bridging across intermediate items.</li>
     * </ul>
     * Any intermediate non-visible nodes are bridged so that upstream visible nodes connect directly to downstream visible nodes.
     */
    private void computeFilteredView() {
        for (RecipeGraphNode node : graph.nodes().values()) {
            if (layoutState.isHidden(node.getId())) continue;
            if (viewMode == com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.ITEMS_ONLY && node.getType() == NodeType.MACHINE) continue;
            if (viewMode == com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.MACHINES_ONLY && node.getType() != NodeType.MACHINE) continue;
            visibleNodes.put(node.getId(), node);
        }

        Set<String> addedEdgeKeys = new HashSet<>();
        for (RecipeGraphNode sourceNode : visibleNodes.values()) {
            for (GraphEdge out : sourceNode.getOutputs()) {
                collectBridgedEdges(sourceNode.getId(), out.to(), out.rate(), new HashSet<>(), addedEdgeKeys);
            }
        }
    }

    private void collectBridgedEdges(ResourceLocation sourceId, ResourceLocation currentTargetId, double rate,
                                     Set<ResourceLocation> visited, Set<String> addedEdgeKeys) {
        if (sourceId.equals(currentTargetId) || !visited.add(currentTargetId)) {
            return;
        }

        if (visibleNodes.containsKey(currentTargetId)) {
            String key = sourceId + "->" + currentTargetId;
            if (addedEdgeKeys.add(key)) {
                visibleEdges.add(new GraphEdge(sourceId, currentTargetId, rate));
            }
            return;
        }

        RecipeGraphNode intermediate = graph.node(currentTargetId);
        if (intermediate != null) {
            for (GraphEdge nextOut : intermediate.getOutputs()) {
                collectBridgedEdges(sourceId, nextOut.to(), rate, visited, addedEdgeKeys);
            }
        }
    }

    private void computeAutoLayout() {
        List<RecipeGraphNode> visitOrder = graph.flatten();
        TreeMap<Integer, List<RecipeGraphNode>> byDepth = new TreeMap<>();
        for (RecipeGraphNode node : visitOrder) {
            if (!visibleNodes.containsKey(node.getId())) continue;
            int column = (viewMode == com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.ALL)
                    ? node.getDepth()
                    : node.getDepth() / 2;
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

    private void updateSearchBarPosition() {
        if (searchBar != null) {
            searchBar.setPosition(getX() + getWidth() - 6, getY() + 6);
        }
    }

    private void updateDrawerPosition() {
        if (hiddenNodesDrawer != null) {
            hiddenNodesDrawer.setPosition(getX() + 1, getY() + 6, getHeight());
        }
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        updateSearchBarPosition();
        updateDrawerPosition();
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        updateSearchBarPosition();
        updateDrawerPosition();
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        updateSearchBarPosition();
        updateDrawerPosition();
    }

    @Override
    public void setHeight(int height) {
        super.setHeight(height);
        updateSearchBarPosition();
        updateDrawerPosition();
    }

    @Override
    public void setPosition(int x, int y) {
        super.setPosition(x, y);
        updateSearchBarPosition();
        updateDrawerPosition();
    }

    /** Search text for each visible node: raw id, path, and formatted display name. Same three
     *  fields {@link SearchState} matched against back when this logic lived inline in
     *  {@code GraphSearchState}. */
    private Map<ResourceLocation, List<String>> searchableNodeTexts() {
        Map<ResourceLocation, List<String>> texts = new HashMap<>();
        for (RecipeGraphNode node : visibleNodes.values()) {
            ResourceLocation id = node.getId();
            texts.put(id, List.of(id.toString(), id.getPath(), DisplayFormat.formatId(id)));
        }
        return texts;
    }

    private void onSearchMatchChanged() {
        searchState.setQuery(searchBar.getValue(), searchableNodeTexts());
        ResourceLocation currentMatch = searchState.currentMatchId();
        if (currentMatch != null) {
            this.selectedNodeId = currentMatch;
            this.onSelect.accept(currentMatch);
            RecipeGraphNode node = visibleNodes.get(currentMatch);
            if (node != null) {
                NodePosition pos = positionOf(node);
                if (pos != null) {
                    camera.centerOn(getWidth(), getHeight(), pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT);
                    onCameraChange.onCameraChange(camera.panX(), camera.panY(), camera.zoom());
                }
            }
        }
    }

    public void toggleSearch() {
        searchBar.toggleVisible();
    }

    public void openSearch() {
        searchBar.setVisible(true);
    }

    public boolean isSearchFocused() {
        return searchBar.isFocused();
    }

    public boolean isSearchVisible() {
        return searchBar.isVisible();
    }

    public SearchState<ResourceLocation> getSearchState() {
        return searchState;
    }

    public void toggleNodeVisibility(ResourceLocation nodeId) {
        layoutState = layoutState.withToggledNodeVisibility(nodeId);
        if (layoutState.isHidden(nodeId) && nodeId.equals(selectedNodeId)) {
            selectedNodeId = null;
            onSelect.accept(null);
        }
        recalculateVisibility();
        onLayoutChange.accept(layoutState);
    }

    public void unhideAll() {
        if (layoutState.hiddenNodes().isEmpty()) return;
        layoutState = layoutState.withUnhideAll();
        recalculateVisibility();
        onLayoutChange.accept(layoutState);
    }

    public boolean hasHiddenNodes() {
        return !layoutState.hiddenNodes().isEmpty();
    }

    public boolean isNodeHidden(ResourceLocation nodeId) {
        return layoutState.isHidden(nodeId);
    }

    public GraphLayoutState getLayoutState() {
        return layoutState;
    }

    public void recalculateVisibility() {
        visibleNodes.clear();
        visibleEdges.clear();
        computeFilteredView();
        autoLayout.clear();
        computeAutoLayout();
        searchState.setQuery(searchBar.getValue(), searchableNodeTexts());
    }

    public void undo() {
        GraphLayoutState next = layoutState.undo();
        if (next != layoutState) {
            layoutState = next;
            recalculateVisibility();
            onLayoutChange.accept(layoutState);
        }
    }

    public void redo() {
        GraphLayoutState next = layoutState.redo();
        if (next != layoutState) {
            layoutState = next;
            recalculateVisibility();
            onLayoutChange.accept(layoutState);
        }
    }

    /** Resets all node positions to automatic layout defaults and unhides all nodes. */
    public void resetLayout() {
        if (!layoutState.equals(GraphLayoutState.EMPTY)) {
            layoutState = GraphLayoutState.EMPTY;
            recalculateVisibility();
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
        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(getX() + camera.panX(), getY() + camera.panY(), 0);
        guiGraphics.pose().scale(camera.zoom(), camera.zoom(), 1);

        RecipeGraphNode hovered = isMouseOver(mouseX, mouseY) ? nodeAt(mouseX, mouseY) : null;
        ResourceLocation hoveredNodeId = hovered != null ? hovered.getId() : null;
        boolean isSearching = searchState.isSearching();

        // Edges: simple elbow (horizontal-vertical-horizontal) connectors.
        for (GraphEdge edge : visibleEdges) {
            RecipeGraphNode from = visibleNodes.get(edge.from());
            RecipeGraphNode to = visibleNodes.get(edge.to());
            if (from == null || to == null) continue;
            NodePosition fromPos = positionOf(from);
            NodePosition toPos = positionOf(to);
            if (fromPos == null || toPos == null) continue;
            int colour = COLOUR_EDGE;
            if (isSearching) {
                boolean bothMatch = searchState.isMatch(edge.from()) && searchState.isMatch(edge.to());
                boolean touchesSelected = selectedNodeId != null && (edge.from().equals(selectedNodeId) || edge.to().equals(selectedNodeId));
                colour = (bothMatch || touchesSelected) ? COLOUR_EDGE_HIGHLIGHT : COLOUR_EDGE_DIM;
            } else if (selectedNodeId != null) {
                boolean touchesSelected = edge.from().equals(selectedNodeId) || edge.to().equals(selectedNodeId);
                colour = touchesSelected ? COLOUR_EDGE_HIGHLIGHT : COLOUR_EDGE_DIM;
            }
            drawElbowConnector(guiGraphics,
                    fromPos.x() + NODE_WIDTH, fromPos.y() + NODE_HEIGHT / 2,
                    toPos.x(), toPos.y() + NODE_HEIGHT / 2, colour);
        }

        Minecraft mc = Minecraft.getInstance();
        for (RecipeGraphNode node : visibleNodes.values()) {
            NodePosition pos = positionOf(node);
            if (pos == null) continue;
            boolean selected = node.getId().equals(selectedNodeId);
            boolean hoveredNode = node.getId().equals(hoveredNodeId);
            boolean isMatch = !isSearching || searchState.isMatch(node.getId());
            boolean isCurrentMatch = isSearching && searchState.isCurrentMatch(node.getId());

            int fillTop = !isMatch ? COLOUR_NODE_FILL_TOP_DIM : COLOUR_NODE_FILL_TOP;
            int fillBottom = !isMatch ? COLOUR_NODE_FILL_BOTTOM_DIM : COLOUR_NODE_FILL_BOTTOM;
            int borderLight = isCurrentMatch ? COLOUR_SEARCH_CURRENT_MATCH : (isSearching && isMatch ? COLOUR_SEARCH_MATCH_BORDER : (!isMatch ? COLOUR_BORDER_LIGHT_DIM : COLOUR_BORDER_LIGHT));
            int borderDark = isCurrentMatch ? COLOUR_SEARCH_CURRENT_MATCH : (isSearching && isMatch ? COLOUR_SEARCH_MATCH_BORDER : (!isMatch ? COLOUR_BORDER_DARK_DIM : COLOUR_BORDER_DARK));

            if (node.getType() == NodeType.MACHINE) {
                fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER, borderLight);
                fillChamferedGradient(guiGraphics, pos.x() + 1, pos.y() + 1, NODE_WIDTH - 2, NODE_HEIGHT - 2,
                        Math.max(0, MACHINE_CHAMFER - 1), fillTop, fillBottom);
                if (selected || isCurrentMatch) {
                    fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER, COLOUR_SELECTED);
                }
                if (hoveredNode) {
                    fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER, COLOUR_HOVER);
                }
            } else {
                guiGraphics.fillGradient(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, fillTop, fillBottom);
                if (selected || isCurrentMatch) {
                    guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOUR_SELECTED);
                }
                if (hoveredNode) {
                    guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOUR_HOVER);
                }
                // Raised-tile treatment: light top/left edge, dark bottom/right edge.
                guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + 1, borderLight);
                guiGraphics.fill(pos.x(), pos.y(), pos.x() + 1, pos.y() + NODE_HEIGHT, borderLight);
                guiGraphics.fill(pos.x(), pos.y() + NODE_HEIGHT - 1, pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, borderDark);
                guiGraphics.fill(pos.x() + NODE_WIDTH - 1, pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, borderDark);
            }

            int textInset = node.getType() == NodeType.MACHINE ? 3 + MACHINE_CHAMFER / 2 : 3;
            int textColour = !isMatch ? COLOUR_TEXT_DIM : COLOUR_TEXT;
            int rateColour = !isMatch ? COLOUR_MUTED_DIM : COLOUR_MUTED;

            String name = DisplayFormat.formatId(node.getId());
            boolean hasAmbiguity = !node.getAmbiguityOptions().isEmpty();
            if (hasAmbiguity) {
                name = "⚠ " + name;
            }
            int maxTextWidth = NODE_WIDTH - textInset - 3;
            if (mc.font.width(name) > maxTextWidth) {
                name = mc.font.plainSubstrByWidth(name, maxTextWidth - 8) + "..";
            }
            guiGraphics.drawString(mc.font, name, pos.x() + textInset, pos.y() + 3, textColour, false);

            String rateText = String.format("%.1f", node.getRequiredRate());
            guiGraphics.drawString(mc.font, rateText, pos.x() + textInset, pos.y() + 14, rateColour, false);
        }

        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();

        // Render search bar overlay on top of canvas
        searchBar.render(guiGraphics, mouseX, mouseY, partialTick);

        // Render hidden nodes left drawer on top of canvas
        hiddenNodesDrawer.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /** Renders a flat-color chamfered rectangle. */
    private void fillChamfered(GuiGraphics guiGraphics, int x, int y, int w, int h, int chamfer, int colour) {
        int c = Math.min(chamfer, h / 2);
        guiGraphics.fill(x, y + c, x + w, y + h - c, colour);
        for (int i = 0; i < c; i++) {
            int inset = c - i;
            guiGraphics.fill(x + inset, y + i, x + w - inset, y + i + 1, colour);
            guiGraphics.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, colour);
        }
    }

    /** Renders a vertical gradient chamfered rectangle. */
    private void fillChamferedGradient(GuiGraphics guiGraphics, int x, int y, int w, int h, int chamfer, int colourTop, int colourBottom) {
        int c = Math.min(chamfer, h / 2);
        for (int row = 0; row < h; row++) {
            float t = h <= 1 ? 0 : (float) row / (h - 1);
            int colour = lerpColour(colourTop, colourBottom, t);
            int inset = 0;
            if (row < c) {
                inset = c - row;
            } else if (row >= h - c) {
                inset = c - (h - 1 - row);
            }
            guiGraphics.fill(x + inset, y + row, x + w - inset, y + row + 1, colour);
        }
    }

    private static int lerpColour(int from, int to, float t) {
        int fa = (from >>> 24) & 0xFF, fr = (from >>> 16) & 0xFF, fg = (from >>> 8) & 0xFF, fb = from & 0xFF;
        int ta = (to >>> 24) & 0xFF, tr = (to >>> 16) & 0xFF, tg = (to >>> 8) & 0xFF, tb = to & 0xFF;
        int a = fa + Math.round((ta - fa) * t);
        int r = fr + Math.round((tr - fr) * t);
        int g = fg + Math.round((tg - fg) * t);
        int b = fb + Math.round((tb - fb) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawElbowConnector(GuiGraphics guiGraphics, int fromX, int fromY, int toX, int toY, int colour) {
        int midX = (fromX + toX) / 2;
        drawLine(guiGraphics, fromX, fromY, midX, fromY, colour);
        drawLine(guiGraphics, midX, fromY, midX, toY, colour);
        drawLine(guiGraphics, midX, toY, toX, toY, colour);
    }

    private void drawLine(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int colour) {
        int thickness = 1;
        if (y1 == y2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            guiGraphics.fill(minX, y1 - thickness, maxX, y1 + thickness, colour);
        } else {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            guiGraphics.fill(x1 - thickness, minY, x1 + thickness, maxY, colour);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;

        // Give floating search bar priority on mouse click
        if (searchBar.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // Give hidden nodes left drawer priority on mouse click
        if (hiddenNodesDrawer.isHovered(mouseX, mouseY)) {
            if (hiddenNodesDrawer.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        if (button == 1) {
            if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
                return false;
            }
            RecipeGraphNode hit = nodeAt(mouseX, mouseY);
            if (hit != null) {
                toggleNodeVisibility(hit.getId());
                return true;
            }
            return false;
        }

        if (button != 0) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        RecipeGraphNode hit = nodeAt(mouseX, mouseY);
        camera.beginDrag(hit != null ? hit.getId() : null, hit != null ? positionOf(hit) : null);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBar.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBar.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
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

        if (hiddenNodesDrawer.isHovered(mouseX, mouseY)) {
            if (hiddenNodesDrawer.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
                return true;
            }
        }

        camera.zoomAt(getX(), getY(), mouseX, mouseY, scrollY, MIN_ZOOM, MAX_ZOOM);
        onCameraChange.onCameraChange(camera.panX(), camera.panY(), camera.zoom());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
