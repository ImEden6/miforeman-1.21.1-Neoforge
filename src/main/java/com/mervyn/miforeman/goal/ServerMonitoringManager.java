package com.mervyn.miforeman.goal;

import aztech.modern_industrialization.inventory.ConfigurableFluidStack;
import aztech.modern_industrialization.inventory.ConfigurableItemStack;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.components.CrafterComponent;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import com.mervyn.miforeman.MIForeman;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = MIForeman.MODID)
public class ServerMonitoringManager {
    public static class EnergyEvent {
        public final long tick;
        public final ResourceLocation recipeId;
        public final double energy;
        public final double totalRecipeEnergy;

        public EnergyEvent(long tick, ResourceLocation recipeId, double energy, double totalRecipeEnergy) {
            this.tick = tick;
            this.recipeId = recipeId;
            this.energy = energy;
            this.totalRecipeEnergy = totalRecipeEnergy;
        }
    }

    public static class MachineTracker {
        public final BlockPos pos;
        public String status = "RED";
        public ResourceLocation lastRecipeId = null;
        public long lastUsedEnergy = 0;
        public long lastRecipeEnergy = 0;
        public final List<EnergyEvent> energyEvents = new ArrayList<>();

        public MachineTracker(BlockPos pos) {
            this.pos = pos;
        }

        public void addEnergy(long tick, ResourceLocation recipeId, double energy, double totalEnergy) {
            energyEvents.add(new EnergyEvent(tick, recipeId, energy, totalEnergy));
            energyEvents.removeIf(e -> tick - e.tick > 72000); // Keep max 1 hour (72000 ticks)
        }
    }

    public static final Map<BlockPos, MachineTracker> TRACKERS = new ConcurrentHashMap<>();

    private static final java.lang.reflect.Field ACTIVE_RECIPE_FIELD;
    static {
        java.lang.reflect.Field f = null;
        try {
            f = CrafterComponent.class.getDeclaredField("activeRecipe");
            f.setAccessible(true);
        } catch (Exception e) {
            MIForeman.LOGGER.error("Failed to bind CrafterComponent activeRecipe field", e);
        }
        ACTIVE_RECIPE_FIELD = f;
    }

    @SuppressWarnings("unchecked")
    public static RecipeHolder<MachineRecipe> getActiveRecipeHolder(CrafterComponent crafter) {
        if (ACTIVE_RECIPE_FIELD == null) return null;
        try {
            return (RecipeHolder<MachineRecipe>) ACTIVE_RECIPE_FIELD.get(crafter);
        } catch (Exception e) {
            return null;
        }
    }

    public static CrafterComponent getCrafter(MachineBlockEntity machine) {
        for (var comp : machine.components) {
            if (comp instanceof CrafterComponent) {
                return (CrafterComponent) comp;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            long tick = level.getGameTime();
            Set<BlockPos> activePositions = new HashSet<>();
            for (ServerPlayer player : level.players()) {
                for (var hand : net.minecraft.world.InteractionHand.values()) {
                    ItemStack stack = player.getItemInHand(hand);
                    if (stack.is(com.mervyn.miforeman.registry.ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                        ProductionGoal goal = stack.get(com.mervyn.miforeman.registry.ModComponents.PRODUCTION_GOAL.get());
                        if (goal != null) {
                            activePositions.addAll(goal.linkedMachines());
                        }
                    }
                }
            }

            for (BlockPos pos : activePositions) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof MachineBlockEntity machine) {
                    CrafterComponent crafter = getCrafter(machine);
                    if (crafter == null) continue;

                    MachineTracker tracker = TRACKERS.computeIfAbsent(pos, MachineTracker::new);
                    boolean hasActive = crafter.hasActiveRecipe();

                    if (hasActive) {
                        var activeHolder = getActiveRecipeHolder(crafter);
                        if (activeHolder != null) {
                            ResourceLocation recipeId = activeHolder.id();
                            long recipeEnergy = activeHolder.value().getTotalEu();
                            long usedEnergy = Math.round(crafter.getProgress() * recipeEnergy);

                            double consumed = 0;
                            if (recipeId.equals(tracker.lastRecipeId)) {
                                if (usedEnergy >= tracker.lastUsedEnergy) {
                                    consumed = usedEnergy - tracker.lastUsedEnergy;
                                } else {
                                    consumed = (tracker.lastRecipeEnergy - tracker.lastUsedEnergy) + usedEnergy;
                                }
                            } else {
                                consumed = Math.max(0, tracker.lastRecipeEnergy - tracker.lastUsedEnergy) + usedEnergy;
                            }

                            if (consumed > 0) {
                                tracker.addEnergy(tick, recipeId, consumed, recipeEnergy);
                            }

                            tracker.lastRecipeId = recipeId;
                            tracker.lastUsedEnergy = usedEnergy;
                            tracker.lastRecipeEnergy = recipeEnergy;
                        }
                        tracker.status = "GREEN";
                    } else {
                        tracker.lastRecipeId = null;
                        tracker.lastUsedEnergy = 0;
                        tracker.lastRecipeEnergy = 0;
                        tracker.status = getMachinePassiveStatus(crafter, level);
                    }
                }
            }
        }
    }

    public static String getMachinePassiveStatus(CrafterComponent crafter, ServerLevel level) {
        if (crafter.hasActiveRecipe()) {
            return "GREEN";
        }

        List<ConfigurableItemStack> itemInputs = crafter.getInventory().getItemInputs();
        List<ConfigurableFluidStack> fluidInputs = crafter.getInventory().getFluidInputs();

        List<RecipeHolder<MachineRecipe>> candidates = CrafterComponent.getRecipes(level, crafter.getBehavior().recipeType(), itemInputs);

        boolean hasInputsForAnyRecipe = false;
        for (RecipeHolder<MachineRecipe> holder : candidates) {
            MachineRecipe recipe = holder.value();
            if (crafter.getBehavior().banRecipe(recipe)) {
                continue;
            }
            if (CrafterComponent.doInputsMatch(itemInputs, fluidInputs, recipe)) {
                hasInputsForAnyRecipe = true;
                break;
            }
        }

        if (hasInputsForAnyRecipe) {
            return "ORANGE"; // Saturating
        } else {
            return "RED"; // Starving
        }
    }

    public static double getExpectedRate(ProductionGoal goal, ResourceLocation resourceId) {
        if (goal.targetId().equals(resourceId)) {
            return goal.rate();
        }
        if (goal.plan().isPresent()) {
            var plan = goal.plan().get();
            for (var flow : plan.intermediateFlows()) {
                if (flow.resourceId().equals(resourceId)) {
                    return flow.rate();
                }
            }
            for (var flow : plan.rawInputs()) {
                if (flow.resourceId().equals(resourceId)) {
                    return flow.rate();
                }
            }
        }
        return 0.0;
    }

    public static Map<ResourceLocation, Double> getActualRates(MachineTracker tracker, ServerLevel level, long currentTick, long windowTicks) {
        Map<ResourceLocation, Double> rates = new HashMap<>();
        Map<ResourceLocation, Double> recipeEnergySum = new HashMap<>();
        Map<ResourceLocation, Double> recipeTotalEnergy = new HashMap<>();

        for (var e : tracker.energyEvents) {
            if (currentTick - e.tick <= windowTicks) {
                recipeEnergySum.merge(e.recipeId, e.energy, Double::sum);
                recipeTotalEnergy.put(e.recipeId, e.totalRecipeEnergy);
            }
        }

        var recipeManager = level.getRecipeManager();
        for (var entry : recipeEnergySum.entrySet()) {
            ResourceLocation recipeId = entry.getKey();
            double energySum = entry.getValue();
            double totalEnergy = recipeTotalEnergy.getOrDefault(recipeId, 1.0);
            double runs = energySum / totalEnergy;

            Optional<RecipeHolder<?>> opt = recipeManager.byKey(recipeId);
            if (opt.isPresent() && opt.get().value() instanceof MachineRecipe recipe) {
                for (var out : recipe.itemOutputs) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(out.variant().getItem());
                    double amt = out.amount() * out.probability() * runs;
                    rates.merge(itemId, amt, Double::sum);
                }
                for (var out : recipe.fluidOutputs) {
                    ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(out.fluid());
                    double amt = out.amount() * out.probability() * runs;
                    rates.merge(fluidId, amt, Double::sum);
                }
            }
        }

        long activeTicks = windowTicks;
        if (!tracker.energyEvents.isEmpty()) {
            long firstTick = tracker.energyEvents.get(0).tick;
            activeTicks = Math.min(windowTicks, Math.max(1, currentTick - firstTick));
        }

        double multiplier = (double) activeTicks;
        Map<ResourceLocation, Double> finalRates = new HashMap<>();
        for (var entry : rates.entrySet()) {
            double ratePerMin = (entry.getValue() / multiplier) * 1200.0;
            finalRates.put(entry.getKey(), ratePerMin);
        }

        return finalRates;
    }
}
