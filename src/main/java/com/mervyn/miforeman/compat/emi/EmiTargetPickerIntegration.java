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

/** Keeps EmiTargetPickerScreen free of dev.emi imports. */
final class EmiTargetPickerIntegration {

    record TargetSelection(TargetType type, String idStr) {
    }

    static Bounds getPanelBounds(EmiTargetPickerScreen screen) {
        return new Bounds(screen.getPanelX(), screen.getPanelY(), screen.getPanelWidth(), screen.getPanelHeight());
    }

    /** Empty if the ingredient can't be resolved. */
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
