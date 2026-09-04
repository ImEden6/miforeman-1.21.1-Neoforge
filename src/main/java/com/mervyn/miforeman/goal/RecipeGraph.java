package com.mervyn.miforeman.goal;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RecipeGraph(
    ResourceLocation target,
    double targetRate,
    Map<ResourceLocation, RecipeGraphNode> nodes,
    List<GraphEdge> edges,
    /** Resource IDs that sit on a recycling loop (a back-edge was cut when this graph's DAG was
     *  collected). See {@link RecipeGraphTraverser#computeRecipeGraph}. Used to tell a starved
     *  loop member ("dead-loop") apart from ordinary starvation in passive status checks. */
    Set<ResourceLocation> cyclicResourceIds
) {
    public RecipeGraphNode root() {
        return nodes.get(target);
    }

    public RecipeGraphNode node(ResourceLocation id) {
        return nodes.get(id);
    }

    /**
     * Flattens visible nodes into an ordered list based on expanded state.
     */
    public List<RecipeGraphNode> flatten() {
        List<RecipeGraphNode> list = new ArrayList<>();
        RecipeGraphNode rootNode = root();
        if (rootNode != null) {
            addFlattened(rootNode, list);
        }
        return list;
    }

    private void addFlattened(RecipeGraphNode node, List<RecipeGraphNode> list) {
        addFlattened(node, list, new java.util.HashSet<>());
    }

    private void addFlattened(RecipeGraphNode node, List<RecipeGraphNode> list, java.util.Set<ResourceLocation> visited) {
        if (!visited.add(node.getId())) return;
        list.add(node);
        if (node.isExpanded()) {
            for (GraphEdge inputEdge : node.getInputs()) {
                RecipeGraphNode inputNode = nodes.get(inputEdge.from());
                if (inputNode != null) {
                    addFlattened(inputNode, list, visited);
                }
            }
        }
    }

    /** Deep-enough copy for handing a graph out of a shared cache -- see
     *  {@link RecipeGraphNode#copy()}. Edges are already immutable records, so the edge list
     *  itself just needs a fresh backing list, not a per-edge copy. */
    public RecipeGraph copy() {
        Map<ResourceLocation, RecipeGraphNode> copiedNodes = new java.util.HashMap<>();
        for (var entry : nodes.entrySet()) {
            copiedNodes.put(entry.getKey(), entry.getValue().copy());
        }
        return new RecipeGraph(target, targetRate, copiedNodes, new ArrayList<>(edges), cyclicResourceIds);
    }
}
