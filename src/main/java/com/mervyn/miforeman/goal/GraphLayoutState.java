package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Persisted layout state for graph nodes, including node positions, hidden nodes, and undo/redo move history.
 * Saved on {@link ProductionGoal} and synchronized between client and server.
 */
public record GraphLayoutState(
        Map<ResourceLocation, NodePosition> nodePositions,
        List<NodeMoveAction> undoStack,
        List<NodeMoveAction> redoStack,
        Set<ResourceLocation> hiddenNodes
) {
    public static final int MAX_HISTORY = 20;
    public static final GraphLayoutState EMPTY = new GraphLayoutState(Map.of(), List.of(), List.of(), Set.of());

    public GraphLayoutState(Map<ResourceLocation, NodePosition> nodePositions, List<NodeMoveAction> undoStack, List<NodeMoveAction> redoStack) {
        this(nodePositions, undoStack, redoStack, Set.of());
    }

    /** Records a node drag operation, updating position and move history. */
    public GraphLayoutState withMove(ResourceLocation nodeId, NodePosition from, NodePosition to) {
        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        positions.put(nodeId, to);

        List<NodeMoveAction> undo = new ArrayList<>(undoStack);
        undo.add(new NodeMoveAction(nodeId, from, to));
        while (undo.size() > MAX_HISTORY) {
            undo.remove(0);
        }

        return new GraphLayoutState(positions, undo, List.of(), hiddenNodes);
    }

    /** Reverts the most recent move and pushes it onto redo. No-op if nothing to undo. */
    public GraphLayoutState undo() {
        if (undoStack.isEmpty()) {
            return this;
        }
        List<NodeMoveAction> undo = new ArrayList<>(undoStack);
        NodeMoveAction action = undo.remove(undo.size() - 1);

        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        positions.put(action.nodeId(), action.from());

        List<NodeMoveAction> redo = new ArrayList<>(redoStack);
        redo.add(action);

        return new GraphLayoutState(positions, undo, redo, hiddenNodes);
    }

    /** Re-applies the most recently undone move. No-op if nothing to redo. */
    public GraphLayoutState redo() {
        if (redoStack.isEmpty()) {
            return this;
        }
        List<NodeMoveAction> redo = new ArrayList<>(redoStack);
        NodeMoveAction action = redo.remove(redo.size() - 1);

        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        positions.put(action.nodeId(), action.to());

        List<NodeMoveAction> undo = new ArrayList<>(undoStack);
        undo.add(action);

        return new GraphLayoutState(positions, undo, redo, hiddenNodes);
    }

    /** Toggles the hidden visibility state of a node. */
    public GraphLayoutState withToggledNodeVisibility(ResourceLocation nodeId) {
        Set<ResourceLocation> updated = new HashSet<>(hiddenNodes);
        if (updated.contains(nodeId)) {
            updated.remove(nodeId);
        } else {
            updated.add(nodeId);
        }
        return new GraphLayoutState(nodePositions, undoStack, redoStack, updated);
    }

    /** Unhides all currently hidden nodes. */
    public GraphLayoutState withUnhideAll() {
        if (hiddenNodes.isEmpty()) {
            return this;
        }
        return new GraphLayoutState(nodePositions, undoStack, redoStack, Set.of());
    }

    /** Checks whether a node is visually hidden. */
    public boolean isHidden(ResourceLocation nodeId) {
        return hiddenNodes.contains(nodeId);
    }

    /** Prunes positions, hidden nodes, and move history to match nodes in the given graph. */
    public GraphLayoutState prunedTo(RecipeGraph graph) {
        if (graph == null) {
            return EMPTY;
        }
        Map<ResourceLocation, RecipeGraphNode> liveNodes = graph.nodes();

        Map<ResourceLocation, NodePosition> positions = new HashMap<>();
        nodePositions.forEach((id, pos) -> {
            if (liveNodes.containsKey(id)) {
                positions.put(id, pos);
            }
        });

        List<NodeMoveAction> undo = undoStack.stream()
                .filter(a -> liveNodes.containsKey(a.nodeId()))
                .toList();
        List<NodeMoveAction> redo = redoStack.stream()
                .filter(a -> liveNodes.containsKey(a.nodeId()))
                .toList();

        Set<ResourceLocation> hidden = hiddenNodes.stream()
                .filter(liveNodes::containsKey)
                .collect(Collectors.toSet());

        return new GraphLayoutState(positions, undo, redo, hidden);
    }

    public static final Codec<GraphLayoutState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(ResourceLocation.CODEC, NodePosition.CODEC).fieldOf("node_positions").forGetter(GraphLayoutState::nodePositions),
            NodeMoveAction.CODEC.listOf().fieldOf("undo_stack").forGetter(GraphLayoutState::undoStack),
            NodeMoveAction.CODEC.listOf().fieldOf("redo_stack").forGetter(GraphLayoutState::redoStack),
            ResourceLocation.CODEC.listOf().<Set<ResourceLocation>>xmap(HashSet::new, ArrayList::new).optionalFieldOf("hidden_nodes", Set.of()).forGetter(GraphLayoutState::hiddenNodes)
    ).apply(instance, GraphLayoutState::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphLayoutState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, NodePosition.STREAM_CODEC),
            GraphLayoutState::nodePositions,
            NodeMoveAction.STREAM_CODEC.apply(ByteBufCodecs.list()),
            GraphLayoutState::undoStack,
            NodeMoveAction.STREAM_CODEC.apply(ByteBufCodecs.list()),
            GraphLayoutState::redoStack,
            ByteBufCodecs.collection(HashSet::new, ResourceLocation.STREAM_CODEC),
            GraphLayoutState::hiddenNodes,
            GraphLayoutState::new
    );
}
