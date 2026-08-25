package com.mervyn.miforeman.mixin;

import aztech.modern_industrialization.machines.components.CrafterComponent;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor interface providing read access to {@link CrafterComponent#activeRecipe}.
 */
@Mixin(CrafterComponent.class)
public interface CrafterComponentAccessor {
    @Accessor("activeRecipe")
    RecipeHolder<MachineRecipe> miforeman$getActiveRecipe();
}
