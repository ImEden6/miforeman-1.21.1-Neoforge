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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persisted layout state for graph nodes, including node positions, hidden nodes, locked
 * groups, and undo/redo move history. Saved on {@link ProductionGoal} and synchronized between
 * client and server.
 */
public record GraphLayoutState(
        Map<ResourceLocation, NodePosition> nodePositions,
        List<HistoryEntry> undoStack,
        List<HistoryEntry> redoStack,
        Set<ResourceLocation> hiddenNodes,
        List<NodeGroup> groups
) {
    public static final int MAX_HISTORY = 20;
    public static final GraphLayoutState EMPTY = new GraphLayoutState(Map.of(), List.of(), List.of(), Set.of(), List.of());

    public GraphLayoutState(Map<ResourceLocation, NodePosition> nodePositions, List<HistoryEntry> undoStack, List<HistoryEntry> redoStack) {
        this(nodePositions, undoStack, redoStack, Set.of(), List.of());
    }

    public GraphLayoutState(Map<ResourceLocation, NodePosition> nodePositions, List<HistoryEntry> undoStack, List<HistoryEntry> redoStack, Set<ResourceLocation> hiddenNodes) {
        this(nodePositions, undoStack, redoStack, hiddenNodes, List.of());
    }

    /** Records a single node drag operation, updating position and move history. */
    public GraphLayoutState withMove(ResourceLocation nodeId, NodePosition from, NodePosition to) {
        return withBatchMove(List.of(new NodeMoveAction(nodeId, from, to)));
    }

    /** Records multiple node moves as a single undoable step. Empty move lists are ignored. */
    public GraphLayoutState withBatchMove(List<NodeMoveAction> moves) {
        if (moves.isEmpty()) {
            return this;
        }
        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        for (NodeMoveAction move : moves) {
            positions.put(move.nodeId(), move.to());
        }

        List<HistoryEntry> undo = new ArrayList<>(undoStack);
        undo.add(new HistoryEntry(moves));
        while (undo.size() > MAX_HISTORY) {
            undo.remove(0);
        }

        return new GraphLayoutState(positions, undo, List.of(), hiddenNodes, groups);
    }

    /** Reverts the most recent move (or move batch) and pushes it onto redo. No-op if nothing to undo. */
    public GraphLayoutState undo() {
        if (undoStack.isEmpty()) {
            return this;
        }
        List<HistoryEntry> undo = new ArrayList<>(undoStack);
        HistoryEntry entry = undo.remove(undo.size() - 1);

        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        for (NodeMoveAction move : entry.moves()) {
            positions.put(move.nodeId(), move.from());
        }

        List<HistoryEntry> redo = new ArrayList<>(redoStack);
        redo.add(entry);

        return new GraphLayoutState(positions, undo, redo, hiddenNodes, groups);
    }

    /** Re-applies the most recently undone move (or move batch). No-op if nothing to redo. */
    public GraphLayoutState redo() {
        if (redoStack.isEmpty()) {
            return this;
        }
        List<HistoryEntry> redo = new ArrayList<>(redoStack);
        HistoryEntry entry = redo.remove(redo.size() - 1);

        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        for (NodeMoveAction move : entry.moves()) {
            positions.put(move.nodeId(), move.to());
        }

        List<HistoryEntry> undo = new ArrayList<>(undoStack);
        undo.add(entry);

        return new GraphLayoutState(positions, undo, redo, hiddenNodes, groups);
    }

    /** Toggles the hidden visibility state of a node. */
    public GraphLayoutState withToggledNodeVisibility(ResourceLocation nodeId) {
        Set<ResourceLocation> updated = new HashSet<>(hiddenNodes);
        if (updated.contains(nodeId)) {
            updated.remove(nodeId);
        } else {
            updated.add(nodeId);
        }
        return new GraphLayoutState(nodePositions, undoStack, redoStack, updated, groups);
    }

    /** Unhides all currently hidden nodes. */
    public GraphLayoutState withUnhideAll() {
        if (hiddenNodes.isEmpty()) {
            return this;
        }
        return new GraphLayoutState(nodePositions, undoStack, redoStack, Set.of(), groups);
    }

    /** Checks whether a node is visually hidden. */
    public boolean isHidden(ResourceLocation nodeId) {
        return hiddenNodes.contains(nodeId);
    }

    /** The group a node currently belongs to, if any. A node belongs to at most one group. */
    public Optional<NodeGroup> groupOf(ResourceLocation nodeId) {
        return groups.stream().filter(g -> g.memberIds().contains(nodeId)).findFirst();
    }

    /**
     * Groups {@code memberIds}. Members already in another group are removed from it,
     * dissolving that group if fewer than two members remain. Unpositioned members take
     * their current position from {@code currentPositions}.
     */
    public GraphLayoutState withGroupCreated(Set<ResourceLocation> memberIds, Map<ResourceLocation, NodePosition> currentPositions) {
        if (memberIds.size() < 2) {
            return this;
        }
        List<NodeGroup> updatedGroups = new ArrayList<>();
        for (NodeGroup existing : groups) {
            Set<ResourceLocation> remaining = new HashSet<>(existing.memberIds());
            remaining.removeAll(memberIds);
            if (remaining.size() >= 2) {
                updatedGroups.add(new NodeGroup(existing.id(), remaining));
            }
            // Dissolve the old group when fewer than two members remain.
        }
        updatedGroups.add(new NodeGroup(UUID.randomUUID(), new HashSet<>(memberIds)));

        Map<ResourceLocation, NodePosition> positions = new HashMap<>(nodePositions);
        for (ResourceLocation member : memberIds) {
            if (!positions.containsKey(member)) {
                NodePosition current = currentPositions.get(member);
                if (current != null) {
                    positions.put(member, current);
                }
            }
        }

        return new GraphLayoutState(positions, undoStack, redoStack, hiddenNodes, updatedGroups);
    }

    /** Dissolves a group while keeping member node positions unchanged. */
    public GraphLayoutState withGroupDissolved(UUID groupId) {
        List<NodeGroup> updated = groups.stream().filter(g -> !g.id().equals(groupId)).toList();
        if (updated.size() == groups.size()) {
            return this;
        }
        return new GraphLayoutState(nodePositions, undoStack, redoStack, hiddenNodes, updated);
    }

    /** Removes a node from its group. Dissolves the group if fewer than two members remain. */
    public GraphLayoutState withNodeRemovedFromGroup(ResourceLocation nodeId) {
        List<NodeGroup> updated = new ArrayList<>();
        boolean changed = false;
        for (NodeGroup existing : groups) {
            if (!existing.memberIds().contains(nodeId)) {
                updated.add(existing);
                continue;
            }
            changed = true;
            Set<ResourceLocation> remaining = new HashSet<>(existing.memberIds());
            remaining.remove(nodeId);
            if (remaining.size() >= 2) {
                updated.add(new NodeGroup(existing.id(), remaining));
            }
        }
        if (!changed) {
            return this;
        }
        return new GraphLayoutState(nodePositions, undoStack, redoStack, hiddenNodes, updated);
    }

    /** Prunes positions, hidden nodes, move history, and groups to match nodes in the given graph. */
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

        List<HistoryEntry> undo = undoStack.stream()
                .filter(e -> e.moves().stream().allMatch(a -> liveNodes.containsKey(a.nodeId())))
                .toList();
        List<HistoryEntry> redo = redoStack.stream()
                .filter(e -> e.moves().stream().allMatch(a -> liveNodes.containsKey(a.nodeId())))
                .toList();

        Set<ResourceLocation> hidden = hiddenNodes.stream()
                .filter(liveNodes::containsKey)
                .collect(Collectors.toSet());

        List<NodeGroup> prunedGroups = new ArrayList<>();
        for (NodeGroup group : groups) {
            Set<ResourceLocation> members = group.memberIds().stream()
                    .filter(liveNodes::containsKey)
                    .collect(Collectors.toCollection(HashSet::new));
            if (members.size() >= 2) {
                prunedGroups.add(new NodeGroup(group.id(), members));
            }
        }

        return new GraphLayoutState(positions, undo, redo, hidden, prunedGroups);
    }

    public static final Codec<GraphLayoutState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(ResourceLocation.CODEC, NodePosition.CODEC).fieldOf("node_positions").forGetter(GraphLayoutState::nodePositions),
            HistoryEntry.CODEC.listOf().fieldOf("undo_stack").forGetter(GraphLayoutState::undoStack),
            HistoryEntry.CODEC.listOf().fieldOf("redo_stack").forGetter(GraphLayoutState::redoStack),
            ResourceLocation.CODEC.listOf().<Set<ResourceLocation>>xmap(HashSet::new, ArrayList::new).optionalFieldOf("hidden_nodes", Set.of()).forGetter(GraphLayoutState::hiddenNodes),
            NodeGroup.CODEC.listOf().optionalFieldOf("groups", List.of()).forGetter(GraphLayoutState::groups)
    ).apply(instance, GraphLayoutState::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphLayoutState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, NodePosition.STREAM_CODEC),
            GraphLayoutState::nodePositions,
            HistoryEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),
            GraphLayoutState::undoStack,
            HistoryEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),
            GraphLayoutState::redoStack,
            ByteBufCodecs.collection(HashSet::new, ResourceLocation.STREAM_CODEC),
            GraphLayoutState::hiddenNodes,
            NodeGroup.STREAM_CODEC.apply(ByteBufCodecs.list()),
            GraphLayoutState::groups,
            GraphLayoutState::new
    );
}
