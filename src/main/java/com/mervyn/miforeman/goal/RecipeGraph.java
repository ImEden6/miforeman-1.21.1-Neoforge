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
    Set<ResourceLocation> cyclicResourceIds,
    Map<ResourceLocation, Double> surplusRates,
    Set<ResourceLocation> unsourcedResourceIds,
    @org.jetbrains.annotations.Nullable PlanSolver.Model planModel,
    Map<ResourceLocation, ResourceLocation> autoSelections,
    Set<ResourceLocation> autoImports
) {
    public RecipeGraph(ResourceLocation target, double targetRate, Map<ResourceLocation, RecipeGraphNode> nodes,
            List<GraphEdge> edges, Set<ResourceLocation> cyclicResourceIds) {
        this(target, targetRate, nodes, edges, cyclicResourceIds, Map.of(), Set.of(), null, Map.of(), Set.of());
    }

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

    /** Defensive copy for returning a graph from shared cache. See {@link RecipeGraphNode#copy()}.
     *  Edges are immutable records, so the edge list only needs a fresh backing list. */
    public RecipeGraph copy() {
        Map<ResourceLocation, RecipeGraphNode> copiedNodes = new java.util.HashMap<>();
        for (var entry : nodes.entrySet()) {
            copiedNodes.put(entry.getKey(), entry.getValue().copy());
        }
        return new RecipeGraph(target, targetRate, copiedNodes, new ArrayList<>(edges), cyclicResourceIds, surplusRates,
                unsourcedResourceIds, planModel, autoSelections, autoImports);
    }
}
