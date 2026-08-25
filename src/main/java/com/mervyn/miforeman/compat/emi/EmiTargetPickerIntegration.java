package com.mervyn.miforeman.compat.emi;

import java.util.List;
import java.util.Optional;

import com.mervyn.miforeman.client.gui.EmiTargetPickerScreen;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

/**
 * Bridges EMI's types to {@link EmiTargetPickerScreen}'s plain-int panel bounds and to a goal
 * target, so that screen itself never needs to import anything from {@code dev.emi}.
 */
final class EmiTargetPickerIntegration {

    record TargetSelection(TargetType type, String idStr) {
    }

    static Bounds getPanelBounds(EmiTargetPickerScreen screen) {
        return new Bounds(screen.getPanelX(), screen.getPanelY(), screen.getPanelWidth(), screen.getPanelHeight());
    }

    /** Converts a dragged EMI ingredient into a goal target, or empty if it can't be resolved. */
    static Optional<TargetSelection> toTargetSelection(EmiIngredient ingredient) {
        List<EmiStack> stacks = ingredient.getEmiStacks();
        if (stacks.isEmpty()) {
            return Optional.empty();
        }
        EmiStack stack = stacks.get(0);
        Object key = stack.getKey();
        ResourceLocation id = stack.getId();
        if (id == null) {
            return Optional.empty();
        }
        if (key instanceof Item) {
            return Optional.of(new TargetSelection(TargetType.ITEM, id.toString()));
        } else if (key instanceof Fluid) {
            return Optional.of(new TargetSelection(TargetType.FLUID, id.toString()));
        }
        return Optional.empty();
    }

    private EmiTargetPickerIntegration() {
    }
}
