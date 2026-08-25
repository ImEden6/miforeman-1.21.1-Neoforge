package com.mervyn.miforeman.goal;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record RecipeGraph(
    ResourceLocation target,
    double targetRate,
    Map<ResourceLocation, RecipeGraphNode> nodes,
    List<GraphEdge> edges
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
        if (list.contains(node)) return;
        list.add(node);
        if (node.isExpanded()) {
            for (GraphEdge inputEdge : node.getInputs()) {
                RecipeGraphNode inputNode = nodes.get(inputEdge.from());
                if (inputNode != null) {
                    addFlattened(inputNode, list);
                }
            }
        }
    }
}
