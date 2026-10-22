package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class GoalFormValidation {
    private GoalFormValidation() {
    }

    public static Optional<Component> validateGoalInputs(String goalName, String targetIdStr, TargetType targetType, double rate, double threshold) {
        if (goalName == null || goalName.trim().isEmpty()) {
            return Optional.of(Component.translatable("miforeman.error.goal_name_empty"));
        }

        if (targetIdStr == null || targetIdStr.trim().isEmpty()) {
            return Optional.of(Component.translatable("miforeman.error.target_id_empty"));
        }

        ResourceLocation targetRes = ResourceLocation.tryParse(targetIdStr);
        if (targetRes == null) {
            return Optional.of(Component.translatable("miforeman.error.invalid_target_id"));
        }

        if (targetType == TargetType.ITEM) {
            if (!BuiltInRegistries.ITEM.containsKey(targetRes)) {
                return Optional.of(Component.translatable("miforeman.error.item_not_found"));
            }
        } else {
            if (!BuiltInRegistries.FLUID.containsKey(targetRes)) {
                return Optional.of(Component.translatable("miforeman.error.fluid_not_found"));
            }
        }

        if (rate <= 0 || Double.isNaN(rate)) {
            return Optional.of(Component.translatable("miforeman.error.rate_not_positive"));
        }

        if (threshold <= 0 || threshold > 1.0 || Double.isNaN(threshold)) {
            return Optional.of(Component.translatable("miforeman.error.threshold_range"));
        }

        return Optional.empty();
    }
}
