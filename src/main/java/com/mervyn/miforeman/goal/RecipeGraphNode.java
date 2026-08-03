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
    private final List<GraphEdge> inputs = new ArrayList<>();
    private final List<GraphEdge> outputs = new ArrayList<>();
    private int depth;
    private boolean expanded = true;

    public RecipeGraphNode(ResourceLocation id, NodeType type, @Nullable ResourceLocation machineType,
                           @Nullable MachineRecipe recipe, double requiredRate, double machineCount,
                           List<ResourceLocation> ambiguityOptions, @Nullable ResourceLocation selectedAmbiguity,
                           int depth) {
        this.id = id;
        this.type = type;
        this.machineType = machineType;
        this.recipe = recipe;
        this.requiredRate = requiredRate;
        this.machineCount = machineCount;
        this.ambiguityOptions = ambiguityOptions;
        this.selectedAmbiguity = selectedAmbiguity;
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
    public List<GraphEdge> getInputs() { return inputs; }
    public List<GraphEdge> getOutputs() { return outputs; }
    public int getDepth() { return depth; }
    public void setRequiredRate(double requiredRate) { this.requiredRate = requiredRate; }
    public void setMachineCount(double machineCount) { this.machineCount = machineCount; }
    public void setDepth(int depth) { this.depth = depth; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
}
