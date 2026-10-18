package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.goal.ClipboardUiState;
import com.mervyn.miforeman.goal.GraphLayoutState;
import com.mervyn.miforeman.goal.MachineLinkHistory;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Form and plan state for defining a goal, separated from {@link ClipboardScreen}.
 */
class GoalDraft {
    String goalName;
    TargetType targetType;
    String targetIdStr;
    double rate;
    final Map<ResourceLocation, ResourceLocation> recipeSelections = new HashMap<>();
    boolean perHour;
    double threshold;

    FactoryPlan currentPlan;
    String errorMessage;
    GraphLayoutState graphLayout = GraphLayoutState.EMPTY;
    ClipboardUiState uiState = ClipboardUiState.EMPTY;

    private GoalDraft() {
    }

    static GoalDraft fromGoal(ProductionGoal goal) {
        GoalDraft draft = new GoalDraft();
        draft.currentPlan = goal.plan().orElse(null);
        draft.goalName = goal.name();
        draft.targetType = goal.type();
        draft.targetIdStr = goal.targetId().toString();
        draft.rate = goal.rate();
        draft.recipeSelections.putAll(goal.recipeSelections());
        draft.perHour = goal.perHour();
        draft.threshold = goal.threshold();
        draft.graphLayout = goal.graphLayout();
        draft.uiState = goal.uiState();

        // Adjust rate back to per hour for display if perHour is enabled
        if (draft.perHour) {
            draft.rate = draft.rate * 60.0;
        }
        return draft;
    }

    static GoalDraft defaults() {
        GoalDraft draft = new GoalDraft();
        draft.goalName = "Quantum Production";
        draft.targetType = TargetType.ITEM;
        draft.targetIdStr = "modern_industrialization:quantum_upgrade";
        draft.rate = 1.0;
        draft.perHour = false;
        draft.threshold = ProductionGoal.getDefaultThreshold();
        return draft;
    }

    void applyFormResult(GoalFormResult result) {
        this.goalName = result.goalName();
        this.targetType = result.targetType();
        this.targetIdStr = result.targetIdStr();
        this.rate = result.rate();
        this.perHour = result.perHour();
        this.threshold = result.threshold();
    }

    private double adjustedRatePerMinute() {
        return this.rate / (this.perHour ? 60.0 : 1.0);
    }

    /** The goal to plan from the form, or null with {@link #errorMessage} set when it's invalid. Its
     *  maps are copies, since the plan may be computed on another thread while the form changes. */
    @org.jetbrains.annotations.Nullable ProductionGoal planRequest(List<GlobalPos> linkedMachines) {
        this.errorMessage = null;
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        if (targetRes == null) {
            this.errorMessage = "Invalid Target ID format";
            return null;
        }
        return new ProductionGoal(this.goalName, this.targetType, targetRes, adjustedRatePerMinute(),
                Map.copyOf(this.recipeSelections), Optional.empty(), this.perHour, this.threshold,
                List.copyOf(linkedMachines));
    }

    void applyPlan(@org.jetbrains.annotations.Nullable FactoryPlan plan, @org.jetbrains.annotations.Nullable Exception error) {
        if (error != null) {
            this.errorMessage = "Error calculating plan: " + error.getMessage();
            this.currentPlan = null;
            return;
        }
        this.currentPlan = plan;
        if (plan != null && plan.graph() != null) {
            this.graphLayout = this.graphLayout.prunedTo(plan.graph());
        }
    }

    ProductionGoal buildGoal(List<GlobalPos> linkedMachines, MachineLinkHistory machineLinkHistory,
                              List<GlobalPos> rejectedMachines, java.util.Map<GlobalPos, ResourceLocation> machineAssignments) {
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        // Re-applying the links drops assignments for machines unlinked since they were placed.
        return new ProductionGoal(
                this.goalName, this.targetType, targetRes, adjustedRatePerMinute(), this.recipeSelections,
                this.currentPlan != null ? Optional.of(this.currentPlan) : Optional.empty(),
                this.perHour, this.threshold, linkedMachines, this.graphLayout,
                machineLinkHistory, rejectedMachines, this.uiState, java.util.Map.copyOf(machineAssignments)
        ).withLinkedMachines(linkedMachines, machineLinkHistory);
    }

    /** Returns true if goal name and target ID inputs are non-empty and valid. */
    boolean isReadyToSave() {
        return this.errorMessage == null
                && this.goalName != null && !this.goalName.isEmpty()
                && this.targetIdStr != null && !this.targetIdStr.isEmpty()
                && ResourceLocation.tryParse(this.targetIdStr) != null;
    }
}
