package com.mervyn.miforeman.goal;

import aztech.modern_industrialization.api.machine.component.CrafterAccess;
import aztech.modern_industrialization.api.machine.holder.CrafterComponentHolder;
import aztech.modern_industrialization.inventory.ConfigurableFluidStack;
import aztech.modern_industrialization.inventory.ConfigurableItemStack;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.MachineComponent;
import aztech.modern_industrialization.machines.components.CrafterComponent;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import aztech.modern_industrialization.machines.recipe.MachineRecipeType;
import com.mervyn.miforeman.mixin.CrafterComponentAccessor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Unified interface wrapping both base MI's {@link CrafterComponent} and addon/modular
 * multiblock crafter components (such as Tesseract / MI-Tweaks {@code AbstractModularCrafterComponent}).
 */
public interface UnifiedCrafter {

    boolean hasActiveRecipe();

    @Nullable RecipeHolder<MachineRecipe> getActiveRecipe();

    float getProgress();

    List<ConfigurableItemStack> getItemInputs();

    List<ConfigurableFluidStack> getFluidInputs();

    List<ConfigurableItemStack> getItemOutputs();

    List<ConfigurableFluidStack> getFluidOutputs();

    @Nullable MachineRecipeType getRecipeType();

    boolean banRecipe(MachineRecipe recipe);

    /**
     * Inspects a {@link MachineBlockEntity} and returns a {@link UnifiedCrafter} adapter
     * for its crafter component, or {@code null} if no supported crafter is present.
     */
    @Nullable
    static UnifiedCrafter from(MachineBlockEntity machine) {
        if (machine == null) {
            return null;
        }

        if (machine.components != null) {
            for (MachineComponent comp : machine.components) {
                if (comp instanceof CrafterComponent crafter) {
                    return new StandardCrafterAdapter(crafter);
                }
            }

            // Addon/modular crafter fallback via cached reflection
            for (MachineComponent comp : machine.components) {
                if (comp == null) continue;
                ModularAccessors accessors = ModularAccessors.get(comp.getClass());
                if (accessors != null) {
                    return new ModularCrafterAdapter(comp, accessors);
                }
            }
        }

        if (machine instanceof CrafterComponentHolder holder) {
            CrafterAccess crafter = holder.getCrafterComponent();
            if (crafter instanceof CrafterComponent cc) {
                return new StandardCrafterAdapter(cc);
            }
            if (crafter != null) {
                ModularAccessors accessors = ModularAccessors.get(crafter.getClass());
                if (accessors != null) {
                    return new ModularCrafterAdapter(crafter, accessors);
                }
            }
        }

        // Direct field fallback on machine class (e.g. crafter field)
        try {
            Field crafterField = ModularAccessors.findField(machine.getClass(), "crafter");
            if (crafterField != null) {
                Object crafterObj = crafterField.get(machine);
                if (crafterObj instanceof CrafterComponent cc) {
                    return new StandardCrafterAdapter(cc);
                }
                if (crafterObj != null) {
                    ModularAccessors accessors = ModularAccessors.get(crafterObj.getClass());
                    if (accessors != null) {
                        return new ModularCrafterAdapter(crafterObj, accessors);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * Creates a {@link UnifiedCrafter} adapter directly from a {@link CrafterComponent}.
     */
    static UnifiedCrafter from(CrafterComponent crafter) {
        return new StandardCrafterAdapter(crafter);
    }

    /**
     * Direct zero-overhead adapter for standard Modern Industrialization {@link CrafterComponent}.
     */
    final class StandardCrafterAdapter implements UnifiedCrafter {
        private final CrafterComponent crafter;

        public StandardCrafterAdapter(CrafterComponent crafter) {
            this.crafter = crafter;
        }

        @Override
        public boolean hasActiveRecipe() {
            return crafter.hasActiveRecipe();
        }

        @Override
        public @Nullable RecipeHolder<MachineRecipe> getActiveRecipe() {
            return ((CrafterComponentAccessor) crafter).miforeman$getActiveRecipe();
        }

        @Override
        public float getProgress() {
            return crafter.getProgress();
        }

        @Override
        public List<ConfigurableItemStack> getItemInputs() {
            return crafter.getInventory().getItemInputs();
        }

        @Override
        public List<ConfigurableFluidStack> getFluidInputs() {
            return crafter.getInventory().getFluidInputs();
        }

        @Override
        public List<ConfigurableItemStack> getItemOutputs() {
            return crafter.getInventory().getItemOutputs();
        }

        @Override
        public List<ConfigurableFluidStack> getFluidOutputs() {
            return crafter.getInventory().getFluidOutputs();
        }

        @Override
        public @Nullable MachineRecipeType getRecipeType() {
            var behavior = crafter.getBehavior();
            return behavior != null ? behavior.recipeType() : null;
        }

        @Override
        public boolean banRecipe(MachineRecipe recipe) {
            var behavior = crafter.getBehavior();
            return behavior != null && behavior.banRecipe(recipe);
        }

        public CrafterComponent getUnderlying() {
            return crafter;
        }
    }

    /**
     * Duck-typed adapter for modular / addon crafter components using cached reflection.
     */
    final class ModularCrafterAdapter implements UnifiedCrafter {
        private final Object component;
        private final ModularAccessors accessors;

        public ModularCrafterAdapter(Object component, ModularAccessors accessors) {
            this.component = component;
            this.accessors = accessors;
        }

        @Override
        public boolean hasActiveRecipe() {
            try {
                if (accessors.hasActiveRecipeMethod != null) {
                    return (boolean) accessors.hasActiveRecipeMethod.invoke(component);
                }
                return getActiveRecipe() != null;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public @Nullable RecipeHolder<MachineRecipe> getActiveRecipe() {
            try {
                Object raw = null;
                if (accessors.getActiveRecipeMethod != null) {
                    raw = accessors.getActiveRecipeMethod.invoke(component);
                } else if (accessors.activeRecipeField != null) {
                    raw = accessors.activeRecipeField.get(component);
                }

                if (raw instanceof RecipeHolder<?> holder && holder.value() instanceof MachineRecipe) {
                    return (RecipeHolder<MachineRecipe>) holder;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        @Override
        public float getProgress() {
            try {
                if (accessors.getProgressMethod != null) {
                    Object res = accessors.getProgressMethod.invoke(component);
                    if (res instanceof Number num) {
                        return num.floatValue();
                    }
                }
            } catch (Throwable ignored) {
            }
            return 0.0f;
        }

        /** Shared helper for inventory list accessors via reflection. Invokes the accessor
         *  on the resolved inventory and returns an empty list on failure. */
        @SuppressWarnings("unchecked")
        private <T> List<T> invokeListAccessor(@Nullable Method accessorMethod) {
            try {
                if (accessorMethod != null) {
                    Object inv = accessors.getInvMethod != null ? accessors.getInvMethod.invoke(component) : component;
                    if (inv != null) {
                        return (List<T>) accessorMethod.invoke(inv);
                    }
                }
            } catch (Throwable ignored) {
            }
            return Collections.emptyList();
        }

        @Override
        public List<ConfigurableItemStack> getItemInputs() {
            return invokeListAccessor(accessors.getItemInputsMethod);
        }

        @Override
        public List<ConfigurableFluidStack> getFluidInputs() {
            return invokeListAccessor(accessors.getFluidInputsMethod);
        }

        @Override
        public List<ConfigurableItemStack> getItemOutputs() {
            return invokeListAccessor(accessors.getItemOutputsMethod);
        }

        @Override
        public List<ConfigurableFluidStack> getFluidOutputs() {
            return invokeListAccessor(accessors.getFluidOutputsMethod);
        }

        @Override
        public @Nullable MachineRecipeType getRecipeType() {
            try {
                if (accessors.getRecipeTypeMethod != null) {
                    if (accessors.recipeTypeOnComponent) {
                        Object type = accessors.getRecipeTypeMethod.invoke(component);
                        if (type instanceof MachineRecipeType mrt) {
                            return mrt;
                        }
                    } else {
                        Object behavior = accessors.getBehaviorMethod != null ? accessors.getBehaviorMethod.invoke(component) : component;
                        if (behavior != null) {
                            Object type = accessors.getRecipeTypeMethod.invoke(behavior);
                            if (type instanceof MachineRecipeType mrt) {
                                return mrt;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        @Override
        public boolean banRecipe(MachineRecipe recipe) {
            try {
                if (accessors.banRecipeMethod != null) {
                    if (accessors.banRecipeOnComponent) {
                        return (boolean) accessors.banRecipeMethod.invoke(component, recipe);
                    } else {
                        Object behavior = accessors.getBehaviorMethod != null ? accessors.getBehaviorMethod.invoke(component) : component;
                        if (behavior != null) {
                            return (boolean) accessors.banRecipeMethod.invoke(behavior, recipe);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /**
     * Cached reflective accessors per class.
     */
    final class ModularAccessors {
        private static final Map<Class<?>, ModularAccessors> CACHE = new ConcurrentHashMap<>();
        private static final ModularAccessors NONE = new ModularAccessors(null, null, null, null, null, null, null, null, null, null, null, false, null, false);

        final @Nullable Method hasActiveRecipeMethod;
        final @Nullable Method getActiveRecipeMethod;
        final @Nullable Field activeRecipeField;
        final @Nullable Method getProgressMethod;
        final @Nullable Method getInvMethod;
        final @Nullable Method getItemInputsMethod;
        final @Nullable Method getFluidInputsMethod;
        final @Nullable Method getItemOutputsMethod;
        final @Nullable Method getFluidOutputsMethod;
        final @Nullable Method getBehaviorMethod;
        final @Nullable Method getRecipeTypeMethod;
        final boolean recipeTypeOnComponent;
        final @Nullable Method banRecipeMethod;
        final boolean banRecipeOnComponent;

        private ModularAccessors(
                @Nullable Method hasActiveRecipeMethod,
                @Nullable Method getActiveRecipeMethod,
                @Nullable Field activeRecipeField,
                @Nullable Method getProgressMethod,
                @Nullable Method getInvMethod,
                @Nullable Method getItemInputsMethod,
                @Nullable Method getFluidInputsMethod,
                @Nullable Method getItemOutputsMethod,
                @Nullable Method getFluidOutputsMethod,
                @Nullable Method getBehaviorMethod,
                @Nullable Method getRecipeTypeMethod,
                boolean recipeTypeOnComponent,
                @Nullable Method banRecipeMethod,
                boolean banRecipeOnComponent) {
            this.hasActiveRecipeMethod = hasActiveRecipeMethod;
            this.getActiveRecipeMethod = getActiveRecipeMethod;
            this.activeRecipeField = activeRecipeField;
            this.getProgressMethod = getProgressMethod;
            this.getInvMethod = getInvMethod;
            this.getItemInputsMethod = getItemInputsMethod;
            this.getFluidInputsMethod = getFluidInputsMethod;
            this.getItemOutputsMethod = getItemOutputsMethod;
            this.getFluidOutputsMethod = getFluidOutputsMethod;
            this.getBehaviorMethod = getBehaviorMethod;
            this.getRecipeTypeMethod = getRecipeTypeMethod;
            this.recipeTypeOnComponent = recipeTypeOnComponent;
            this.banRecipeMethod = banRecipeMethod;
            this.banRecipeOnComponent = banRecipeOnComponent;
        }

        public static @Nullable ModularAccessors get(Class<?> clazz) {
            ModularAccessors res = CACHE.computeIfAbsent(clazz, ModularAccessors::inspect);
            return res == NONE ? null : res;
        }

        private static ModularAccessors inspect(Class<?> clazz) {
            try {
                Method hasActiveRecipeMethod = findMethod(clazz, "hasActiveRecipe");
                Method getActiveRecipeMethod = findMethod(clazz, "getActiveRecipe");
                Field activeRecipeField = findField(clazz, "activeRecipe");
                Method getProgressMethod = findMethod(clazz, "getProgress");

                // If class lacks core crafter methods, it's not a crafter component
                if (hasActiveRecipeMethod == null && getActiveRecipeMethod == null && activeRecipeField == null && getProgressMethod == null) {
                    return NONE;
                }

                Method getInvMethod = findMethod(clazz, "getInventory");
                Class<?> invClass = getInvMethod != null ? getInvMethod.getReturnType() : clazz;

                Method getItemInputsMethod = findMethod(invClass, "getItemInputs");
                Method getFluidInputsMethod = findMethod(invClass, "getFluidInputs");
                Method getItemOutputsMethod = findMethod(invClass, "getItemOutputs");
                Method getFluidOutputsMethod = findMethod(invClass, "getFluidOutputs");

                Method getBehaviorMethod = findMethod(clazz, "getBehavior");
                Class<?> behaviorClass = getBehaviorMethod != null ? getBehaviorMethod.getReturnType() : clazz;

                // Inspect recipeType on clazz first (e.g. MultipliedCrafterComponent), then fallback to behaviorClass
                boolean recipeTypeOnComp = true;
                Method getRecipeTypeMethod = findMethod(clazz, "recipeType");
                if (getRecipeTypeMethod == null) {
                    getRecipeTypeMethod = findMethod(clazz, "getRecipeType");
                }
                if (getRecipeTypeMethod == null && behaviorClass != clazz) {
                    recipeTypeOnComp = false;
                    getRecipeTypeMethod = findMethod(behaviorClass, "recipeType");
                    if (getRecipeTypeMethod == null) {
                        getRecipeTypeMethod = findMethod(behaviorClass, "getRecipeType");
                    }
                }

                // Inspect banRecipe on clazz first, then fallback to behaviorClass
                boolean banRecipeOnComp = true;
                Method banRecipeMethod = findMethod(clazz, "banRecipe", MachineRecipe.class);
                if (banRecipeMethod == null && behaviorClass != clazz) {
                    banRecipeOnComp = false;
                    banRecipeMethod = findMethod(behaviorClass, "banRecipe", MachineRecipe.class);
                }

                return new ModularAccessors(
                        hasActiveRecipeMethod,
                        getActiveRecipeMethod,
                        activeRecipeField,
                        getProgressMethod,
                        getInvMethod,
                        getItemInputsMethod,
                        getFluidInputsMethod,
                        getItemOutputsMethod,
                        getFluidOutputsMethod,
                        getBehaviorMethod,
                        getRecipeTypeMethod,
                        recipeTypeOnComp,
                        banRecipeMethod,
                        banRecipeOnComp
                );
            } catch (Throwable t) {
                return NONE;
            }
        }

        public static @Nullable Method findMethod(Class<?> clazz, String name, Class<?>... params) {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Method m = current.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
                for (Class<?> iface : current.getInterfaces()) {
                    try {
                        Method m = iface.getDeclaredMethod(name, params);
                        m.setAccessible(true);
                        return m;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                current = current.getSuperclass();
            }
            return null;
        }

        public static @Nullable Field findField(Class<?> clazz, String name) {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    Field f = current.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
                current = current.getSuperclass();
            }
            return null;
        }
    }
}
