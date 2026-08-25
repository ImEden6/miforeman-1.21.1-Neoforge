package com.mervyn.miforeman.goal;

import aztech.modern_industrialization.inventory.ConfigurableFluidStack;
import aztech.modern_industrialization.inventory.ConfigurableItemStack;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.components.CrafterComponent;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.mixin.CrafterComponentAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

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
        /** Recipe a saturating (ORANGE) machine runs once its output clears. Distinct from
         *  lastRecipeId, which only tracks an active craft. */
        public ResourceLocation saturatedRecipeId = null;
        public long lastUsedEnergy = 0;
        public long lastRecipeEnergy = 0;
        public final Deque<EnergyEvent> energyEvents = new ArrayDeque<>();

        public MachineTracker(BlockPos pos) {
            this.pos = pos;
        }

        public void addEnergy(long tick, ResourceLocation recipeId, double energy, double totalEnergy) {
            energyEvents.addLast(new EnergyEvent(tick, recipeId, energy, totalEnergy));
            int windowTicks = getMonitoringWindowTicks();
            while (!energyEvents.isEmpty() && tick - energyEvents.peekFirst().tick > windowTicks) {
                energyEvents.pollFirst();
            }
        }
    }

    public static final Map<GlobalPos, MachineTracker> TRACKERS = new ConcurrentHashMap<>();
    private static long lastPruneTick = Long.MIN_VALUE;

    public static int getMonitoringWindowTicks() {
        try {
            return com.mervyn.miforeman.Config.MONITORING_WINDOW_TICKS.get();
        } catch (Exception e) {
            return 72000;
        }
    }

    public static int getPruneIntervalTicks() {
        try {
            return com.mervyn.miforeman.Config.TRACKER_PRUNE_INTERVAL_TICKS.get();
        } catch (Exception e) {
            return 200;
        }
    }

    /** Dimension-safe map key. Bare BlockPos collides across dimensions. */
    public static GlobalPos key(ServerLevel level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos);
    }

    public static MachineTracker trackerFor(ServerLevel level, BlockPos pos) {
        return TRACKERS.computeIfAbsent(key(level, pos), k -> new MachineTracker(pos));
    }

    /**
     * Removes trackers not linked by any held clipboard. Unlinking and relinking
     * resets a machine energy history window.
     */
    public static void pruneTrackers(Set<GlobalPos> activeKeys) {
        TRACKERS.keySet().removeIf(key -> !activeKeys.contains(key));
    }

    public static RecipeHolder<MachineRecipe> getActiveRecipeHolder(CrafterComponent crafter) {
        return ((CrafterComponentAccessor) crafter).miforeman$getActiveRecipe();
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
        if (event.getServer().getPlayerList().getPlayerCount() == 0) {
            return;
        }

        // Pass 1: collect this tick's monitored set across ALL levels x players x hands. Keys are
        // GlobalPos so same-coordinate machines in different dimensions never share tracker state.
        Map<ServerLevel, Set<BlockPos>> activeByLevel = new HashMap<>();
        Set<GlobalPos> activeKeys = new HashSet<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Set<BlockPos> positions = new HashSet<>();
            for (ServerPlayer player : level.players()) {
                for (var hand : net.minecraft.world.InteractionHand.values()) {
                    ItemStack stack = player.getItemInHand(hand);
                    if (stack.is(com.mervyn.miforeman.registry.ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                        ProductionGoal goal = stack.get(com.mervyn.miforeman.registry.ModComponents.PRODUCTION_GOAL.get());
                        if (goal != null) {
                            positions.addAll(goal.linkedMachines());
                        }
                    }
                }
            }
            if (!positions.isEmpty()) {
                activeByLevel.put(level, positions);
                for (BlockPos pos : positions) {
                    activeKeys.add(key(level, pos));
                }
            }
        }

        // Pass 2: update trackers for active machines.
        for (Map.Entry<ServerLevel, Set<BlockPos>> levelEntry : activeByLevel.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            long tick = level.getGameTime();
            for (BlockPos pos : levelEntry.getValue()) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof MachineBlockEntity machine) {
                    CrafterComponent crafter = getCrafter(machine);
                    if (crafter == null) continue;

                    MachineTracker tracker = trackerFor(level, pos);
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
                        tracker.saturatedRecipeId = null;
                    } else {
                        tracker.lastRecipeId = null;
                        tracker.lastUsedEnergy = 0;
                        tracker.lastRecipeEnergy = 0;
                        PassiveStatus passive = getMachinePassiveStatusDetailed(crafter, level);
                        tracker.status = passive.status();
                        tracker.saturatedRecipeId = passive.matchedRecipeId();
                    }
                }
            }
        }

        // Pass 3: periodically evict trackers nothing links anymore.
        long pruneTick = event.getServer().overworld().getGameTime();
        if (lastPruneTick == Long.MIN_VALUE || pruneTick - lastPruneTick >= getPruneIntervalTicks()) {
            lastPruneTick = pruneTick;
            pruneTrackers(activeKeys);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Static state must not leak across world switches (singleplayer starts a new server per world).
        TRACKERS.clear();
        lastPruneTick = Long.MIN_VALUE;
    }

    public record PassiveStatus(String status, @Nullable ResourceLocation matchedRecipeId) {}

    public static String getMachinePassiveStatus(CrafterComponent crafter, ServerLevel level) {
        return getMachinePassiveStatusDetailed(crafter, level).status();
    }

    /** Evaluates passive status like {@link #getMachinePassiveStatus}, and returns the matching
     *  recipe ID when saturating (ORANGE). */
    public static PassiveStatus getMachinePassiveStatusDetailed(CrafterComponent crafter, ServerLevel level) {
        if (crafter.hasActiveRecipe()) {
            return new PassiveStatus("GREEN", null);
        }

        List<ConfigurableItemStack> itemInputs = crafter.getInventory().getItemInputs();
        List<ConfigurableFluidStack> fluidInputs = crafter.getInventory().getFluidInputs();

        Collection<RecipeHolder<MachineRecipe>> candidates = CrafterComponent.getRecipes(level, crafter.getBehavior().recipeType(), itemInputs);

        for (RecipeHolder<MachineRecipe> holder : candidates) {
            MachineRecipe recipe = holder.value();
            if (crafter.getBehavior().banRecipe(recipe)) {
                continue;
            }
            if (CrafterComponent.doInputsMatch(itemInputs, fluidInputs, recipe)) {
                return new PassiveStatus("ORANGE", holder.id()); // Saturating
            }
        }

        return new PassiveStatus("RED", null); // Starving
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
            long firstTick = tracker.energyEvents.peekFirst().tick;
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
