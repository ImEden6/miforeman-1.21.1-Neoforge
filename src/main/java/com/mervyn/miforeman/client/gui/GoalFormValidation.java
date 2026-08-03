package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Define Goal form validation rules, extracted out of {@link ClipboardScreen#validateInputs()} so
 * both the vanilla screen and {@link com.mervyn.miforeman.client.gui.blockui.DefineGoalWindow} call
 * one copy of the logic instead of duplicating it (see
 * .scratch/blockui-define-goal-trial/issues/04-state-handoff-contract.md).
 */
public final class GoalFormValidation {
    private GoalFormValidation() {
    }

    public static Optional<String> validateGoalInputs(String goalName, String targetIdStr, TargetType targetType, double rate, double threshold) {
        if (goalName == null || goalName.trim().isEmpty()) {
            return Optional.of("Goal Name cannot be empty");
        }

        if (targetIdStr == null || targetIdStr.trim().isEmpty()) {
            return Optional.of("Target ID cannot be empty");
        }

        ResourceLocation targetRes = ResourceLocation.tryParse(targetIdStr);
        if (targetRes == null) {
            return Optional.of("Invalid Target ID format");
        }

        if (targetType == TargetType.ITEM) {
            if (!BuiltInRegistries.ITEM.containsKey(targetRes)) {
                return Optional.of("Item not found in registry");
            }
        } else {
            if (!BuiltInRegistries.FLUID.containsKey(targetRes)) {
                return Optional.of("Fluid not found in registry");
            }
        }

        if (rate <= 0 || Double.isNaN(rate)) {
            return Optional.of("Rate must be positive");
        }

        if (threshold <= 0 || threshold > 1.0 || Double.isNaN(threshold)) {
            return Optional.of("Threshold must be between 1 and 100");
        }

        return Optional.empty();
    }
}
