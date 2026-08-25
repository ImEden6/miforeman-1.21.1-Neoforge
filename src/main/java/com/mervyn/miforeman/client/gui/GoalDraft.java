package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.goal.ClipboardUiState;
import com.mervyn.miforeman.goal.GraphLayoutState;
import com.mervyn.miforeman.goal.MachineLinkHistory;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Define Goal step's form/plan state -- split out of {@link ClipboardScreen} (see
 * .claude/plans/gleaming-mapping-waffle.md), which used to own this alongside the unrelated
 * step-3 monitoring state.
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

    void computePlan(Level level, List<BlockPos> linkedMachines) {
        this.errorMessage = null;
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        if (targetRes == null) {
            this.errorMessage = "Invalid Target ID format";
            return;
        }

        try {
            ProductionGoal tempGoal = new ProductionGoal(
                    this.goalName,
                    this.targetType,
                    targetRes,
                    adjustedRatePerMinute(),
                    this.recipeSelections,
                    Optional.empty(),
                    this.perHour,
                    this.threshold,
                    linkedMachines
            );
            this.currentPlan = RecipeGraphTraverser.computePlan(level, tempGoal);
            if (this.currentPlan != null) {
                RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, tempGoal);
                this.currentPlan = this.currentPlan.withGraph(graph);
                this.graphLayout = this.graphLayout.prunedTo(graph);
            }
        } catch (Exception e) {
            this.errorMessage = "Error calculating plan: " + e.getMessage();
            this.currentPlan = null;
        }
    }

    ProductionGoal buildGoal(List<BlockPos> linkedMachines, MachineLinkHistory machineLinkHistory,
                              List<BlockPos> rejectedMachines) {
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        return new ProductionGoal(
                this.goalName, this.targetType, targetRes, adjustedRatePerMinute(), this.recipeSelections,
                this.currentPlan != null ? Optional.of(this.currentPlan) : Optional.empty(),
                this.perHour, this.threshold, linkedMachines, this.graphLayout,
                machineLinkHistory, rejectedMachines, this.uiState
        );
    }

    /** Returns true if goal name and target ID inputs are non-empty and valid. */
    boolean isReadyToSave() {
        return this.errorMessage == null
                && this.goalName != null && !this.goalName.isEmpty()
                && this.targetIdStr != null && !this.targetIdStr.isEmpty()
                && ResourceLocation.tryParse(this.targetIdStr) != null;
    }
}
