package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.goal.GraphEdge;
import com.mervyn.miforeman.goal.GraphLayoutEngine;
import com.mervyn.miforeman.goal.GraphLayoutState;
import com.mervyn.miforeman.goal.MachineStatus;
import com.mervyn.miforeman.goal.NodeGroup;
import com.mervyn.miforeman.goal.NodeMoveAction;
import com.mervyn.miforeman.goal.NodePosition;
import com.mervyn.miforeman.goal.NodeType;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Interactive canvas widget for rendering and manipulating a
 * {@link RecipeGraph}.
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
    private static final int COLOUR_GROUP_FILL = 0x22D4A017;
    private static final int COLOUR_GROUP_BORDER = 0x66D4A017;
    private static final int COLOUR_MARQUEE_FILL = 0x334A90D9;
    private static final int COLOUR_MARQUEE_BORDER = 0xCC4A90D9;
    private static final int GROUP_PAD = 8;

    private static final int NODE_WIDTH = 96;
    private static final int NODE_HEIGHT = 26;
    // MACHINE nodes render as a chamfered octagon instead of a plain rectangle, so
    // the two
    // alternating node kinds in the graph (see computeFilteredView's javadoc) read
    // apart at a
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
    private Set<ResourceLocation> selectedNodeIds;
    private final Consumer<Set<ResourceLocation>> onSelect;
    private final Consumer<GraphLayoutState> onLayoutChange;
    private final CameraChangeListener onCameraChange;
    private final com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode viewMode;
    private final boolean dragEnabled;
    private final boolean perHour;
    private final SearchState<ResourceLocation> searchState = new SearchState<>();
    private final GraphSearchBar searchBar;
    private final HiddenNodesDrawer hiddenNodesDrawer;
    /** Live machine status keyed by recipe ID. Empty until {@link #updateLiveStatus} is called. */
    private Map<ResourceLocation, LiveMonitoringPayload.MachineStatusData> liveStatusByRecipeId = Map.of();

    public GraphCanvas(int x, int y, int width, int height, RecipeGraph graph,
            GraphLayoutState layoutState, double panX, double panY, float zoom,
            Set<ResourceLocation> selectedNodeIds,
            Consumer<Set<ResourceLocation>> onSelect,
            Consumer<GraphLayoutState> onLayoutChange,
            CameraChangeListener onCameraChange,
            com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode viewMode,
            boolean dragEnabled) {
        this(x, y, width, height, graph, layoutState, panX, panY, zoom, selectedNodeIds,
                onSelect, onLayoutChange, onCameraChange, viewMode, dragEnabled, false);
    }

    public GraphCanvas(int x, int y, int width, int height, RecipeGraph graph,
            GraphLayoutState layoutState, double panX, double panY, float zoom,
            Set<ResourceLocation> selectedNodeIds,
            Consumer<Set<ResourceLocation>> onSelect,
            Consumer<GraphLayoutState> onLayoutChange,
            CameraChangeListener onCameraChange,
            com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode viewMode,
            boolean dragEnabled,
            boolean perHour) {
        super(x, y, width, height, Component.literal("Recipe Graph"));
        this.graph = graph;
        this.layoutState = layoutState;
        this.camera = new GraphCamera(panX, panY, zoom);
        this.selectedNodeIds = new LinkedHashSet<>(selectedNodeIds);
        this.onSelect = onSelect;
        this.onLayoutChange = onLayoutChange;
        this.onCameraChange = onCameraChange;
        this.viewMode = viewMode;
        this.dragEnabled = dragEnabled;
        this.perHour = perHour;
        this.searchBar = new GraphSearchBar(this.searchState, Minecraft.getInstance().font, this::onSearchMatchChanged,
                null);
        this.hiddenNodesDrawer = new HiddenNodesDrawer(Minecraft.getInstance().font, () -> this.layoutState,
                () -> this.graph, this::toggleNodeVisibility, this::unhideAll);
        updateSearchBarPosition();
        updateDrawerPosition();
        computeFilteredView();
        computeAutoLayout();
        centerOnGraphIfUntouched();
    }

    /**
     * Centers the camera on the graph's bounding box when first opened at default pan and zoom.
     */
    private void centerOnGraphIfUntouched() {
        if (camera.panX() != 0 || camera.panY() != 0 || camera.zoom() != 1.0f || visibleNodes.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (RecipeGraphNode node : visibleNodes.values()) {
            NodePosition pos = positionOf(node);
            if (pos == null)
                continue;
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            maxX = Math.max(maxX, pos.x() + NODE_WIDTH);
            maxY = Math.max(maxY, pos.y() + NODE_HEIGHT);
        }
        if (minX > maxX)
            return;
        camera.centerOn(getWidth(), getHeight(), minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * Updates machine status colors from incoming monitoring data.
     */
    public void updateLiveStatus(List<LiveMonitoringPayload.MachineStatusData> data) {
        Map<ResourceLocation, LiveMonitoringPayload.MachineStatusData> byRecipeId = new HashMap<>();
        for (LiveMonitoringPayload.MachineStatusData entry : data) {
            entry.recipeId().ifPresent(recipeId -> byRecipeId.put(recipeId, entry));
        }
        this.liveStatusByRecipeId = byRecipeId;
    }

    /**
     * Default (non-selected, non-searching) edge colour: tints an edge with the
     * worse
     * endpoint's live status colour when either endpoint is a MACHINE node
     * reporting RED or
     * ORANGE (the two "not producing" statuses), otherwise the plain default.
     */
    private int bottleneckEdgeColour(GraphEdge edge) {
        MachineStatus worst = null;
        for (ResourceLocation nodeId : List.of(edge.from(), edge.to())) {
            LiveMonitoringPayload.MachineStatusData live = liveStatusByRecipeId.get(nodeId);
            if (live == null)
                continue;
            MachineStatus status = live.status();
            if (status != MachineStatus.RED && status != MachineStatus.ORANGE)
                continue;
            if (worst == null || status.ordinal() < worst.ordinal()) {
                worst = status;
            }
        }
        return worst != null ? (0x55000000 | (worst.colour() & 0x00FFFFFF)) : COLOUR_EDGE;
    }

    /**
     * Filters visible nodes and bridges edges according to {@link #viewMode} and
     * {@link GraphLayoutState#hiddenNodes()}:
     * <ul>
     * <li>{@code ALL}: Displays all unhidden resource and machine nodes.</li>
     * <li>{@code ITEMS_ONLY}: Displays only unhidden item/fluid nodes, bridging
     * across machines.</li>
     * <li>{@code MACHINES_ONLY}: Displays only unhidden MACHINE nodes, bridging
     * across intermediate items.</li>
     * </ul>
     * Any intermediate non-visible nodes are bridged so that upstream visible nodes
     * connect directly to downstream visible nodes.
     */
    private void computeFilteredView() {
        for (RecipeGraphNode node : graph.nodes().values()) {
            if (layoutState.isHidden(node.getId()))
                continue;
            if (viewMode == com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.ITEMS_ONLY
                    && node.getType() == NodeType.MACHINE)
                continue;
            if (viewMode == com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.MACHINES_ONLY
                    && node.getType() != NodeType.MACHINE)
                continue;
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
            if (!visibleNodes.containsKey(node.getId()))
                continue;
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
        // Safety net: any node not reached by flatten() (shouldn't happen in practice
        // since
        // RecipeGraphNode.expanded defaults true everywhere) still needs a position.
        int fallbackColumn = byDepth.isEmpty() ? 0 : byDepth.lastKey() + 1;
        int fallbackIndex = 0;
        for (RecipeGraphNode node : visibleNodes.values()) {
            if (!autoLayout.containsKey(node.getId())) {
                autoLayout.put(node.getId(),
                        new NodePosition(fallbackColumn * COLUMN_SPACING, fallbackIndex * ROW_SPACING));
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
            if (p == null)
                continue;
            if (cx >= p.x() && cx < p.x() + NODE_WIDTH && cy >= p.y() && cy < p.y() + NODE_HEIGHT) {
                return node;
            }
        }
        return null;
    }

    /**
     * Every visible node whose bounds intersect a marquee rectangle (canvas space).
     */
    private Set<ResourceLocation> nodesIntersecting(GraphCamera.MarqueeRect rect) {
        Set<ResourceLocation> hits = new LinkedHashSet<>();
        for (RecipeGraphNode node : visibleNodes.values()) {
            NodePosition p = positionOf(node);
            if (p == null)
                continue;
            boolean intersects = p.x() < rect.maxX() && p.x() + NODE_WIDTH > rect.minX()
                    && p.y() < rect.maxY() && p.y() + NODE_HEIGHT > rect.minY();
            if (intersects)
                hits.add(node.getId());
        }
        return hits;
    }

    /**
     * Returns a snapshot of node positions for group bounding boxes and auto-arrange.
     */
    private Map<ResourceLocation, NodePosition> currentPositionSnapshot() {
        Map<ResourceLocation, NodePosition> snapshot = new HashMap<>();
        for (RecipeGraphNode node : graph.nodes().values()) {
            NodePosition p = positionOf(node);
            if (p != null)
                snapshot.put(node.getId(), p);
        }
        return snapshot;
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

    /**
     * Search text for each visible node: raw id, path, and formatted display name.
     * Same three
     * fields {@link SearchState} matched against back when this logic lived inline
     * in
     * {@code GraphSearchState}.
     */
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
            this.selectedNodeIds = new LinkedHashSet<>(Set.of(currentMatch));
            this.onSelect.accept(Set.copyOf(selectedNodeIds));
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
        if (layoutState.isHidden(nodeId) && selectedNodeIds.remove(nodeId)) {
            onSelect.accept(Set.copyOf(selectedNodeIds));
        }
        recalculateVisibility();
        onLayoutChange.accept(layoutState);
    }

    public void unhideAll() {
        if (layoutState.hiddenNodes().isEmpty())
            return;
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

    public Set<ResourceLocation> getSelectedNodeIds() {
        return Set.copyOf(selectedNodeIds);
    }

    /**
     * Whether the current selection can be locked into a new group (2+ nodes
     * selected).
     */
    public boolean canGroupSelection() {
        return selectedNodeIds.size() >= 2;
    }

    /**
     * Locks the current selection into a new group, snapshotting a manual position
     * for any
     * member that doesn't already have one.
     */
    public void groupSelection() {
        if (!canGroupSelection())
            return;
        layoutState = layoutState.withGroupCreated(Set.copyOf(selectedNodeIds), currentPositionSnapshot());
        recalculateVisibility();
        onLayoutChange.accept(layoutState);
    }

    /**
     * Returns the ID of a group whose members exactly match the current selection, or null if none.
     */
    public @Nullable UUID groupCoveringSelection() {
        if (selectedNodeIds.isEmpty())
            return null;
        for (NodeGroup group : layoutState.groups()) {
            if (group.memberIds().equals(selectedNodeIds)) {
                return group.id();
            }
        }
        return null;
    }

    public void dissolveGroup(UUID groupId) {
        layoutState = layoutState.withGroupDissolved(groupId);
        onLayoutChange.accept(layoutState);
    }

    /**
     * Returns the group ID for the selected node when exactly one node is selected, or null if un-grouped.
     */
    public @Nullable UUID groupOfSingleSelection() {
        if (selectedNodeIds.size() != 1)
            return null;
        return layoutState.groupOf(selectedNodeIds.iterator().next()).map(NodeGroup::id).orElse(null);
    }

    /**
     * Removes the selected node from its group. Dissolves the group if fewer than two members remain.
     */
    public void removeSingleSelectionFromGroup() {
        if (selectedNodeIds.size() != 1)
            return;
        layoutState = layoutState.withNodeRemovedFromGroup(selectedNodeIds.iterator().next());
        recalculateVisibility();
        onLayoutChange.accept(layoutState);
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

    /**
     * Resets all node positions to automatic layout defaults and unhides all nodes.
     */
    public void resetLayout() {
        if (!layoutState.equals(GraphLayoutState.EMPTY)) {
            layoutState = GraphLayoutState.EMPTY;
            recalculateVisibility();
            onLayoutChange.accept(layoutState);
        }
    }

    /**
     * Arranges the graph into layered columns using {@link GraphLayoutEngine}.
     * When {@code rearrangeInsideGroups} is true, nodes inside groups are reordered.
     * All changes record as a single undo step.
     */
    public void autoArrange(boolean rearrangeInsideGroups) {
        Map<ResourceLocation, NodePosition> snapshot = currentPositionSnapshot();
        Map<ResourceLocation, NodePosition> arranged = GraphLayoutEngine.arrange(
                graph, layoutState.groups(), snapshot, rearrangeInsideGroups,
                NODE_WIDTH, NODE_HEIGHT, COLUMN_SPACING - NODE_WIDTH);

        List<NodeMoveAction> batch = new ArrayList<>();
        for (Map.Entry<ResourceLocation, NodePosition> entry : arranged.entrySet()) {
            NodePosition before = snapshot.get(entry.getKey());
            NodePosition after = entry.getValue();
            if (!after.equals(before)) {
                batch.add(new NodeMoveAction(entry.getKey(), before != null ? before : after, after));
            }
        }
        if (!batch.isEmpty()) {
            layoutState = layoutState.withBatchMove(batch);
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

    public void recalculateVisibility() {
        visibleNodes.clear();
        visibleEdges.clear();
        computeFilteredView();
        autoLayout.clear();
        computeAutoLayout();
        searchState.setQuery(searchBar.getValue(), searchableNodeTexts());
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

        // Group frames render first so nodes and edges draw on top of them.
        if (!layoutState.groups().isEmpty()) {
            Map<ResourceLocation, NodePosition> snapshot = currentPositionSnapshot();
            for (NodeGroup group : layoutState.groups()) {
                GraphLayoutEngine.Bounds bounds = GraphLayoutEngine.groupBounds(group.memberIds(), snapshot, NODE_WIDTH,
                        NODE_HEIGHT);
                if (bounds == null)
                    continue;
                int minX = bounds.minX() - GROUP_PAD, minY = bounds.minY() - GROUP_PAD;
                int maxX = bounds.maxX() + GROUP_PAD, maxY = bounds.maxY() + GROUP_PAD;
                guiGraphics.fill(minX, minY, maxX, maxY, COLOUR_GROUP_FILL);
                guiGraphics.fill(minX, minY, maxX, minY + 1, COLOUR_GROUP_BORDER);
                guiGraphics.fill(minX, maxY - 1, maxX, maxY, COLOUR_GROUP_BORDER);
                guiGraphics.fill(minX, minY, minX + 1, maxY, COLOUR_GROUP_BORDER);
                guiGraphics.fill(maxX - 1, minY, maxX, maxY, COLOUR_GROUP_BORDER);
                guiGraphics.drawString(Minecraft.getInstance().font,
                        Component.translatable("miforeman.gui.group_label", group.memberIds().size()),
                        minX + 2, minY - 9, COLOUR_MUTED, false);
            }
        }

        // Edges: simple elbow (horizontal-vertical-horizontal) connectors.
        for (GraphEdge edge : visibleEdges) {
            RecipeGraphNode from = visibleNodes.get(edge.from());
            RecipeGraphNode to = visibleNodes.get(edge.to());
            if (from == null || to == null)
                continue;
            NodePosition fromPos = positionOf(from);
            NodePosition toPos = positionOf(to);
            if (fromPos == null || toPos == null)
                continue;
            int colour = bottleneckEdgeColour(edge);
            if (isSearching) {
                boolean bothMatch = searchState.isMatch(edge.from()) && searchState.isMatch(edge.to());
                boolean touchesSelected = selectedNodeIds.contains(edge.from()) || selectedNodeIds.contains(edge.to());
                colour = (bothMatch || touchesSelected) ? COLOUR_EDGE_HIGHLIGHT : COLOUR_EDGE_DIM;
            } else if (!selectedNodeIds.isEmpty()) {
                boolean touchesSelected = selectedNodeIds.contains(edge.from()) || selectedNodeIds.contains(edge.to());
                colour = touchesSelected ? COLOUR_EDGE_HIGHLIGHT : COLOUR_EDGE_DIM;
            }
            drawElbowConnector(guiGraphics,
                    fromPos.x() + NODE_WIDTH, fromPos.y() + NODE_HEIGHT / 2,
                    toPos.x(), toPos.y() + NODE_HEIGHT / 2, colour);
        }

        Minecraft mc = Minecraft.getInstance();
        for (RecipeGraphNode node : visibleNodes.values()) {
            NodePosition pos = positionOf(node);
            if (pos == null)
                continue;
            boolean selected = selectedNodeIds.contains(node.getId());
            boolean hoveredNode = node.getId().equals(hoveredNodeId);
            boolean isMatch = !isSearching || searchState.isMatch(node.getId());
            boolean isCurrentMatch = isSearching && searchState.isCurrentMatch(node.getId());

            int fillTop = !isMatch ? COLOUR_NODE_FILL_TOP_DIM : COLOUR_NODE_FILL_TOP;
            int fillBottom = !isMatch ? COLOUR_NODE_FILL_BOTTOM_DIM : COLOUR_NODE_FILL_BOTTOM;
            LiveMonitoringPayload.MachineStatusData liveStatus = node.getType() == NodeType.MACHINE
                    ? liveStatusByRecipeId.get(node.getId())
                    : null;
            // Use the status color directly for borders when live status is present.
            int defaultBorderLight = liveStatus != null ? liveStatus.status().colour() : COLOUR_BORDER_LIGHT;
            int defaultBorderDark = liveStatus != null ? liveStatus.status().colour() : COLOUR_BORDER_DARK;
            int borderLight = isCurrentMatch ? COLOUR_SEARCH_CURRENT_MATCH
                    : (isSearching && isMatch ? COLOUR_SEARCH_MATCH_BORDER
                            : (!isMatch ? COLOUR_BORDER_LIGHT_DIM : defaultBorderLight));
            int borderDark = isCurrentMatch ? COLOUR_SEARCH_CURRENT_MATCH
                    : (isSearching && isMatch ? COLOUR_SEARCH_MATCH_BORDER
                            : (!isMatch ? COLOUR_BORDER_DARK_DIM : defaultBorderDark));

            if (node.getType() == NodeType.MACHINE) {
                fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER, borderLight);
                fillChamferedGradient(guiGraphics, pos.x() + 1, pos.y() + 1, NODE_WIDTH - 2, NODE_HEIGHT - 2,
                        Math.max(0, MACHINE_CHAMFER - 1), fillTop, fillBottom);
                if (selected || isCurrentMatch) {
                    fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER,
                            COLOUR_SELECTED);
                }
                if (hoveredNode) {
                    fillChamfered(guiGraphics, pos.x(), pos.y(), NODE_WIDTH, NODE_HEIGHT, MACHINE_CHAMFER,
                            COLOUR_HOVER);
                }
            } else {
                guiGraphics.fillGradient(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, fillTop,
                        fillBottom);
                if (selected || isCurrentMatch) {
                    guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOUR_SELECTED);
                }
                if (hoveredNode) {
                    guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT, COLOUR_HOVER);
                }
                // Raised-tile treatment: light top/left edge, dark bottom/right edge.
                guiGraphics.fill(pos.x(), pos.y(), pos.x() + NODE_WIDTH, pos.y() + 1, borderLight);
                guiGraphics.fill(pos.x(), pos.y(), pos.x() + 1, pos.y() + NODE_HEIGHT, borderLight);
                guiGraphics.fill(pos.x(), pos.y() + NODE_HEIGHT - 1, pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT,
                        borderDark);
                guiGraphics.fill(pos.x() + NODE_WIDTH - 1, pos.y(), pos.x() + NODE_WIDTH, pos.y() + NODE_HEIGHT,
                        borderDark);
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

            String rateText = DisplayFormat.formatRate(node.getRequiredRate(), this.perHour);
            if (mc.font.width(rateText) > maxTextWidth) {
                rateText = mc.font.plainSubstrByWidth(rateText, maxTextWidth - 8) + "..";
            }
            guiGraphics.drawString(mc.font, rateText, pos.x() + textInset, pos.y() + 14, rateColour, false);
        }

        GraphCamera.MarqueeRect marquee = camera.liveMarqueeRect();
        if (marquee != null) {
            guiGraphics.fill((int) marquee.minX(), (int) marquee.minY(), (int) marquee.maxX(), (int) marquee.maxY(),
                    COLOUR_MARQUEE_FILL);
            guiGraphics.fill((int) marquee.minX(), (int) marquee.minY(), (int) marquee.maxX(), (int) marquee.minY() + 1,
                    COLOUR_MARQUEE_BORDER);
            guiGraphics.fill((int) marquee.minX(), (int) marquee.maxY() - 1, (int) marquee.maxX(), (int) marquee.maxY(),
                    COLOUR_MARQUEE_BORDER);
            guiGraphics.fill((int) marquee.minX(), (int) marquee.minY(), (int) marquee.minX() + 1, (int) marquee.maxY(),
                    COLOUR_MARQUEE_BORDER);
            guiGraphics.fill((int) marquee.maxX() - 1, (int) marquee.minY(), (int) marquee.maxX(), (int) marquee.maxY(),
                    COLOUR_MARQUEE_BORDER);
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

    /**
     * Renders a chamfered rectangle with a vertical gradient, drawing corners row-by-row
     * and the center band with a single gradient fill.
     */
    private void fillChamferedGradient(GuiGraphics guiGraphics, int x, int y, int w, int h, int chamfer, int colourTop,
            int colourBottom) {
        int c = Math.min(chamfer, h / 2);

        for (int row = 0; row < c; row++) {
            int colour = lerpColour(colourTop, colourBottom, chamferRowT(row, h));
            int inset = c - row;
            guiGraphics.fill(x + inset, y + row, x + w - inset, y + row + 1, colour);
        }

        if (h - 2 * c > 0) {
            int midTop = lerpColour(colourTop, colourBottom, chamferRowT(c, h));
            int midBottom = lerpColour(colourTop, colourBottom, chamferRowT(h - c - 1, h));
            guiGraphics.fillGradient(x, y + c, x + w, y + h - c, midTop, midBottom);
        }

        for (int row = h - c; row < h; row++) {
            int colour = lerpColour(colourTop, colourBottom, chamferRowT(row, h));
            int inset = c - (h - 1 - row);
            guiGraphics.fill(x + inset, y + row, x + w - inset, y + row + 1, colour);
        }
    }

    private static float chamferRowT(int row, int h) {
        return h <= 1 ? 0 : (float) row / (h - 1);
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
        if (!visible || !active)
            return false;

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

        if (button != 0)
            return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        RecipeGraphNode hit = nodeAt(mouseX, mouseY);
        boolean modifierHeld = Screen.hasShiftDown() || Screen.hasControlDown();
        double startCanvasX = camera.toCanvasX(getX(), mouseX);
        double startCanvasY = camera.toCanvasY(getY(), mouseY);
        camera.beginDrag(hit != null ? hit.getId() : null, hit != null ? positionOf(hit) : null, modifierHeld,
                startCanvasX, startCanvasY);
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
        } else if (end.marqueeRect() != null) {
            Set<ResourceLocation> hits = nodesIntersecting(end.marqueeRect());
            if (!hits.isEmpty()) {
                selectedNodeIds.addAll(hits);
                onSelect.accept(Set.copyOf(selectedNodeIds));
            }
        } else if (end.clickedNodeId() != null) {
            ResourceLocation id = end.clickedNodeId();
            if (end.modifierHeld()) {
                if (!selectedNodeIds.remove(id)) {
                    selectedNodeIds.add(id);
                }
            } else {
                selectedNodeIds = new LinkedHashSet<>();
                selectedNodeIds.add(id);
            }
            onSelect.accept(Set.copyOf(selectedNodeIds));
        } else if (end.clickedEmptySpace() && !selectedNodeIds.isEmpty()) {
            // Clicking empty canvas space clears the selection to return DetailCard to summary mode.
            selectedNodeIds = new LinkedHashSet<>();
            onSelect.accept(Set.of());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !active)
            return false;
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
