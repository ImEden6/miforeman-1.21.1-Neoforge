package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted layout state for graph nodes, including node positions and undo/redo move history.
 * Saved on {@link ProductionGoal} and synchronized between client and server.
 */
public record GraphLayoutState(
        Map<ResourceLocation, NodePosition> nodePositions,
        List<NodeMoveAction> undoStack,
        List<NodeMoveAction> redoStack
) {
    public static final int MAX_HISTORY = 20;
    public static final GraphLayoutState EMPTY = new GraphLayoutState(Map.of(), List.of(), List.of());

    /** Records a node drag operation, updating position and move history. */
    public GraphLayoutState withMove(ResourceLocation nodeId, NodePosition from, NodePosition to) {
        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        positions.put(nodeId, to);

        List<NodeMoveAction> undo = new ArrayList<>(undoStack);
        undo.add(new NodeMoveAction(nodeId, from, to));
        while (undo.size() > MAX_HISTORY) {
            undo.remove(0);
        }

        return new GraphLayoutState(positions, undo, List.of());
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

        return new GraphLayoutState(positions, undo, redo);
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

        return new GraphLayoutState(positions, undo, redo);
    }

    /** Prunes positions and move history to match nodes in the given graph. */
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

        return new GraphLayoutState(positions, undo, redo);
    }

    public static final Codec<GraphLayoutState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(ResourceLocation.CODEC, NodePosition.CODEC).fieldOf("node_positions").forGetter(GraphLayoutState::nodePositions),
            NodeMoveAction.CODEC.listOf().fieldOf("undo_stack").forGetter(GraphLayoutState::undoStack),
            NodeMoveAction.CODEC.listOf().fieldOf("redo_stack").forGetter(GraphLayoutState::redoStack)
    ).apply(instance, GraphLayoutState::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphLayoutState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, NodePosition.STREAM_CODEC),
            GraphLayoutState::nodePositions,
            NodeMoveAction.STREAM_CODEC.apply(ByteBufCodecs.list()),
            GraphLayoutState::undoStack,
            NodeMoveAction.STREAM_CODEC.apply(ByteBufCodecs.list()),
            GraphLayoutState::redoStack,
            GraphLayoutState::new
    );
}
