package com.mervyn.miforeman.mixin;

import aztech.modern_industrialization.machines.components.CrafterComponent;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to CrafterComponent's private active recipe holder, replacing the previous
 * java.lang.reflect.Field approach. A renamed field in an MI update now fails loudly at mixin
 * apply time ("required": true) instead of silently returning null from every reflective get.
 */
@Mixin(CrafterComponent.class)
public interface CrafterComponentAccessor {
    @Accessor("activeRecipe")
    RecipeHolder<MachineRecipe> miforeman$getActiveRecipe();
}
