package com.mervyn.miforeman.goal;

import aztech.modern_industrialization.inventory.ConfigurableFluidStack;
import aztech.modern_industrialization.inventory.ConfigurableItemStack;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.MachineComponent;
import aztech.modern_industrialization.machines.components.CrafterComponent;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import aztech.modern_industrialization.machines.recipe.MachineRecipeType;
import com.mervyn.miforeman.mixin.CrafterComponentAccessor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
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

    @Nullable MachineRecipeType getRecipeType();

    boolean banRecipe(MachineRecipe recipe);

    /**
     * Inspects a {@link MachineBlockEntity} and returns a {@link UnifiedCrafter} adapter
     * for its crafter component, or {@code null} if no supported crafter is present.
     */
    @Nullable
    static UnifiedCrafter from(MachineBlockEntity machine) {
        if (machine == null || machine.components == null) {
            return null;
        }

        for (MachineComponent comp : machine.components) {
            if (comp instanceof CrafterComponent crafter) {
                return new StandardCrafterAdapter(crafter);
            }
        }

        // Addon/modular crafter fallback via cached MethodHandles
        for (MachineComponent comp : machine.components) {
            if (comp == null) continue;
            Class<?> clazz = comp.getClass();
            ModularAccessors accessors = ModularAccessors.get(clazz);
            if (accessors != null) {
                return new ModularCrafterAdapter(comp, accessors);
            }
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
     * Duck-typed adapter for modular / addon crafter components using cached {@link MethodHandle}s.
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
                if (accessors.hasActiveRecipeHandle != null) {
                    return (boolean) accessors.hasActiveRecipeHandle.invoke(component);
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
                if (accessors.getActiveRecipeHandle != null) {
                    raw = accessors.getActiveRecipeHandle.invoke(component);
                } else if (accessors.activeRecipeFieldHandle != null) {
                    raw = accessors.activeRecipeFieldHandle.invoke(component);
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
                if (accessors.getProgressHandle != null) {
                    return (float) accessors.getProgressHandle.invoke(component);
                }
            } catch (Throwable ignored) {
            }
            return 0.0f;
        }

        @Override
        public List<ConfigurableItemStack> getItemInputs() {
            try {
                if (accessors.getItemInputsHandle != null) {
                    Object inv = accessors.getInventoryHandle != null ? accessors.getInventoryHandle.invoke(component) : component;
                    if (inv != null) {
                        return (List<ConfigurableItemStack>) accessors.getItemInputsHandle.invoke(inv);
                    }
                }
            } catch (Throwable ignored) {
            }
            return Collections.emptyList();
        }

        @Override
        public List<ConfigurableFluidStack> getFluidInputs() {
            try {
                if (accessors.getFluidInputsHandle != null) {
                    Object inv = accessors.getInventoryHandle != null ? accessors.getInventoryHandle.invoke(component) : component;
                    if (inv != null) {
                        return (List<ConfigurableFluidStack>) accessors.getFluidInputsHandle.invoke(inv);
                    }
                }
            } catch (Throwable ignored) {
            }
            return Collections.emptyList();
        }

        @Override
        public @Nullable MachineRecipeType getRecipeType() {
            try {
                if (accessors.getRecipeTypeHandle != null) {
                    Object behavior = accessors.getBehaviorHandle != null ? accessors.getBehaviorHandle.invoke(component) : component;
                    if (behavior != null) {
                        Object type = accessors.getRecipeTypeHandle.invoke(behavior);
                        if (type instanceof MachineRecipeType mrt) {
                            return mrt;
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
                if (accessors.banRecipeHandle != null) {
                    Object behavior = accessors.getBehaviorHandle != null ? accessors.getBehaviorHandle.invoke(component) : component;
                    if (behavior != null) {
                        return (boolean) accessors.banRecipeHandle.invoke(behavior, recipe);
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
        private static final ModularAccessors NONE = new ModularAccessors(null, null, null, null, null, null, null, null, null, null);

        final @Nullable MethodHandle hasActiveRecipeHandle;
        final @Nullable MethodHandle getActiveRecipeHandle;
        final @Nullable MethodHandle activeRecipeFieldHandle;
        final @Nullable MethodHandle getProgressHandle;
        final @Nullable MethodHandle getInventoryHandle;
        final @Nullable MethodHandle getItemInputsHandle;
        final @Nullable MethodHandle getFluidInputsHandle;
        final @Nullable MethodHandle getBehaviorHandle;
        final @Nullable MethodHandle getRecipeTypeHandle;
        final @Nullable MethodHandle banRecipeHandle;

        private ModularAccessors(
                @Nullable MethodHandle hasActiveRecipeHandle,
                @Nullable MethodHandle getActiveRecipeHandle,
                @Nullable MethodHandle activeRecipeFieldHandle,
                @Nullable MethodHandle getProgressHandle,
                @Nullable MethodHandle getInventoryHandle,
                @Nullable MethodHandle getItemInputsHandle,
                @Nullable MethodHandle getFluidInputsHandle,
                @Nullable MethodHandle getBehaviorHandle,
                @Nullable MethodHandle getRecipeTypeHandle,
                @Nullable MethodHandle banRecipeHandle) {
            this.hasActiveRecipeHandle = hasActiveRecipeHandle;
            this.getActiveRecipeHandle = getActiveRecipeHandle;
            this.activeRecipeFieldHandle = activeRecipeFieldHandle;
            this.getProgressHandle = getProgressHandle;
            this.getInventoryHandle = getInventoryHandle;
            this.getItemInputsHandle = getItemInputsHandle;
            this.getFluidInputsHandle = getFluidInputsHandle;
            this.getBehaviorHandle = getBehaviorHandle;
            this.getRecipeTypeHandle = getRecipeTypeHandle;
            this.banRecipeHandle = banRecipeHandle;
        }

        public static @Nullable ModularAccessors get(Class<?> clazz) {
            ModularAccessors res = CACHE.computeIfAbsent(clazz, ModularAccessors::inspect);
            return res == NONE ? null : res;
        }

        private static ModularAccessors inspect(Class<?> clazz) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();

                Method hasActiveRecipeMethod = findMethod(clazz, "hasActiveRecipe");
                Method getActiveRecipeMethod = findMethod(clazz, "getActiveRecipe");
                Field activeRecipeField = findField(clazz, "activeRecipe");
                Method getProgressMethod = findMethod(clazz, "getProgress");

                // If class lacks core crafter methods, it's not a crafter component
                if (hasActiveRecipeMethod == null && getActiveRecipeMethod == null && activeRecipeField == null && getProgressMethod == null) {
                    return NONE;
                }

                MethodHandle hasActive = hasActiveRecipeMethod != null ? unreflectMethod(lookup, hasActiveRecipeMethod) : null;
                MethodHandle getActive = getActiveRecipeMethod != null ? unreflectMethod(lookup, getActiveRecipeMethod) : null;
                MethodHandle activeField = activeRecipeField != null ? unreflectGetter(lookup, activeRecipeField) : null;
                MethodHandle getProgress = getProgressMethod != null ? unreflectMethod(lookup, getProgressMethod) : null;

                Method getInvMethod = findMethod(clazz, "getInventory");
                MethodHandle getInv = getInvMethod != null ? unreflectMethod(lookup, getInvMethod) : null;
                Class<?> invClass = getInvMethod != null ? getInvMethod.getReturnType() : clazz;

                Method getItemInputsMethod = findMethod(invClass, "getItemInputs");
                MethodHandle getItemInputs = getItemInputsMethod != null ? unreflectMethod(lookup, getItemInputsMethod) : null;

                Method getFluidInputsMethod = findMethod(invClass, "getFluidInputs");
                MethodHandle getFluidInputs = getFluidInputsMethod != null ? unreflectMethod(lookup, getFluidInputsMethod) : null;

                Method getBehaviorMethod = findMethod(clazz, "getBehavior");
                MethodHandle getBehavior = getBehaviorMethod != null ? unreflectMethod(lookup, getBehaviorMethod) : null;
                Class<?> behaviorClass = getBehaviorMethod != null ? getBehaviorMethod.getReturnType() : clazz;

                Method getRecipeTypeMethod = findMethod(behaviorClass, "recipeType");
                if (getRecipeTypeMethod == null) {
                    getRecipeTypeMethod = findMethod(behaviorClass, "getRecipeType");
                }
                MethodHandle getRecipeType = getRecipeTypeMethod != null ? unreflectMethod(lookup, getRecipeTypeMethod) : null;

                Method banRecipeMethod = findMethod(behaviorClass, "banRecipe", MachineRecipe.class);
                MethodHandle banRecipe = banRecipeMethod != null ? unreflectMethod(lookup, banRecipeMethod) : null;

                return new ModularAccessors(
                        hasActive,
                        getActive,
                        activeField,
                        getProgress,
                        getInv,
                        getItemInputs,
                        getFluidInputs,
                        getBehavior,
                        getRecipeType,
                        banRecipe
                );
            } catch (Throwable t) {
                return NONE;
            }
        }

        private static @Nullable Method findMethod(Class<?> clazz, String name, Class<?>... params) {
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

        private static @Nullable Field findField(Class<?> clazz, String name) {
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

        private static @Nullable MethodHandle unreflectMethod(MethodHandles.Lookup lookup, Method method) {
            try {
                return lookup.unreflect(method);
            } catch (IllegalAccessException e) {
                return null;
            }
        }

        private static @Nullable MethodHandle unreflectGetter(MethodHandles.Lookup lookup, Field field) {
            try {
                return lookup.unreflectGetter(field);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
    }
}
