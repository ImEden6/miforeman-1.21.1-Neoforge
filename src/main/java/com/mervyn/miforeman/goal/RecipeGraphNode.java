package com.mervyn.miforeman.goal;

import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class RecipeGraphNode {
    private final ResourceLocation id;
    private final NodeType type;
    private final @Nullable ResourceLocation machineType;
    private final @Nullable MachineRecipe recipe;
    private double requiredRate;
    private double machineCount;
    private final List<ResourceLocation> ambiguityOptions;
    private @Nullable ResourceLocation selectedAmbiguity;
    /** The resourceId that {@link #ambiguityOptions}/{@link #selectedAmbiguity} actually describe.
     *  For a resource node this is always its own {@link #id}. For a MACHINE node it's whichever
     *  resourceId first finalized this node -- a multi-output recipe's MACHINE node is shared
     *  across every resourceId it produces, but only one of them "owns" the displayed cycle list,
     *  so callers (e.g. the cycle-button click handler) must target this id rather than guessing
     *  one from edge order. */
    private final @Nullable ResourceLocation ambiguityOwnerId;
    private final List<GraphEdge> inputs = new ArrayList<>();
    private final List<GraphEdge> outputs = new ArrayList<>();
    private int depth;
    private boolean expanded = true;

    public RecipeGraphNode(ResourceLocation id, NodeType type, @Nullable ResourceLocation machineType,
                           @Nullable MachineRecipe recipe, double requiredRate, double machineCount,
                           List<ResourceLocation> ambiguityOptions, @Nullable ResourceLocation selectedAmbiguity,
                           @Nullable ResourceLocation ambiguityOwnerId, int depth) {
        this.id = id;
        this.type = type;
        this.machineType = machineType;
        this.recipe = recipe;
        this.requiredRate = requiredRate;
        this.machineCount = machineCount;
        this.ambiguityOptions = ambiguityOptions;
        this.selectedAmbiguity = selectedAmbiguity;
        this.ambiguityOwnerId = ambiguityOwnerId;
        this.depth = depth;
    }

    public ResourceLocation getId() { return id; }
    public NodeType getType() { return type; }
    public @Nullable ResourceLocation getMachineType() { return machineType; }
    public @Nullable MachineRecipe getRecipe() { return recipe; }
    public double getRequiredRate() { return requiredRate; }
    public double getMachineCount() { return machineCount; }
    public List<ResourceLocation> getAmbiguityOptions() { return ambiguityOptions; }
    public @Nullable ResourceLocation getSelectedAmbiguity() { return selectedAmbiguity; }
    public void setSelectedAmbiguity(@Nullable ResourceLocation selectedAmbiguity) { this.selectedAmbiguity = selectedAmbiguity; }
    public @Nullable ResourceLocation getAmbiguityOwnerId() { return ambiguityOwnerId; }
    public List<GraphEdge> getInputs() { return inputs; }
    public List<GraphEdge> getOutputs() { return outputs; }
    /** Replaces (rather than appends) any existing entry for the same {@code (from,to)} pair --
     *  edges are keyed by endpoints only, so a rate-updated {@link GraphEdge} for a pair already
     *  present must overwrite it instead of sitting alongside a stale duplicate. */
    public void putInput(GraphEdge edge) { replaceByFromTo(inputs, edge); }
    public void putOutput(GraphEdge edge) { replaceByFromTo(outputs, edge); }
    private static void replaceByFromTo(List<GraphEdge> list, GraphEdge edge) {
        list.removeIf(e -> e.from().equals(edge.from()) && e.to().equals(edge.to()));
        list.add(edge);
    }
    public int getDepth() { return depth; }
    public void setRequiredRate(double requiredRate) { this.requiredRate = requiredRate; }
    public void setMachineCount(double machineCount) { this.machineCount = machineCount; }
    public void setDepth(int depth) { this.depth = depth; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
}
