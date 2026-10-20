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
        public MachineStatus status = MachineStatus.RED;
        public FailureReason failureReason = FailureReason.NONE;
        public ResourceLocation lastRecipeId = null;
        /** Recipe an ORANGE machine will run once its output clears. */
        public ResourceLocation saturatedRecipeId = null;
        /** Last recipe seen running. Never cleared on idle, so a starved machine that once ran
         *  can be classified as a dead loop. */
        public ResourceLocation lastKnownRecipeId = null;
        public long lastUsedEnergy = 0;
        public long lastRecipeEnergy = 0;
        /** Fill of the fullest output slot, 0 to 1. Refreshed every tick. */
        public double disposalRatio = 0.0;
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

    /** Bare BlockPos collides across dimensions. */
    public static GlobalPos key(ServerLevel level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos);
    }

    public static MachineTracker trackerFor(GlobalPos key) {
        return TRACKERS.computeIfAbsent(key, k -> new MachineTracker(k.pos()));
    }

    public static MachineTracker trackerFor(ServerLevel level, BlockPos pos) {
        return trackerFor(key(level, pos));
    }

    /** Removes trackers no held clipboard links. Unlinking and relinking resets a machine's
     *  energy history. */
    public static void pruneTrackers(Set<GlobalPos> activeKeys) {
        TRACKERS.keySet().removeIf(key -> !activeKeys.contains(key));
    }

    public static RecipeHolder<MachineRecipe> getActiveRecipeHolder(CrafterComponent crafter) {
        return ((CrafterComponentAccessor) crafter).miforeman$getActiveRecipe();
    }

    public static RecipeHolder<MachineRecipe> getActiveRecipeHolder(UnifiedCrafter crafter) {
        return crafter.getActiveRecipe();
    }

    public static UnifiedCrafter getCrafter(MachineBlockEntity machine) {
        return UnifiedCrafter.from(machine);
    }

    /** A GREEN machine underperforming at or above this actual/expected ratio is marked
     *  DISPOSAL_THROTTLED. */
    private static final double DISPOSAL_THROTTLE_THRESHOLD = 0.85;

    /** Fill of the fullest output slot, 0 to 1. Uses getCapacity, not getAdjustedCapacity, so
     *  items that stack below 64 count their max stack size. */
    public static double computeDisposalRatio(UnifiedCrafter crafter) {
        double maxRatio = 0.0;
        for (ConfigurableItemStack stack : crafter.getItemOutputs()) {
            maxRatio = Math.max(maxRatio, ratioIfPositive(stack.getAmount(), stack.getCapacity()));
        }
        for (ConfigurableFluidStack stack : crafter.getFluidOutputs()) {
            maxRatio = Math.max(maxRatio, ratioIfPositive(stack.getAmount(), stack.getCapacity()));
        }
        return maxRatio;
    }

    private static double ratioIfPositive(long amount, long capacity) {
        return capacity > 0 ? (double) amount / capacity : 0.0;
    }

    public static void recordActiveRecipe(MachineTracker tracker, ResourceLocation recipeId) {
        tracker.lastRecipeId = recipeId;
        tracker.lastKnownRecipeId = recipeId;
    }

    /** Restores a fresh tracker's last-run recipe from MachineRecipeHistory. The entry is dropped
     *  when stillValid fails, so a swapped machine or removed recipe can't pin the machine to a
     *  node it can't run. */
    public static void seedLastKnownRecipe(MachineTracker tracker, MachineRecipeHistory history, GlobalPos key,
                                           java.util.function.Predicate<ResourceLocation> stillValid) {
        if (tracker.lastKnownRecipeId != null) return;
        ResourceLocation saved = history.get(key);
        if (saved == null) return;
        if (!stillValid.test(saved)) {
            history.forget(key);
            return;
        }
        tracker.lastKnownRecipeId = saved;
    }

    /** Recipe to show for this machine. RED machines fall back to lastKnownRecipeId so their
     *  nodes keep product labels. */
    public static @Nullable ResourceLocation resolveDisplayRecipeId(MachineTracker tracker) {
        if (tracker.lastRecipeId != null) return tracker.lastRecipeId;
        if (tracker.saturatedRecipeId != null) return tracker.saturatedRecipeId;
        return tracker.lastKnownRecipeId;
    }

    /** The machine's recipe type as a RECIPE_TYPE registry key, the same id graph MACHINE nodes
     *  carry. Empty when the crafter's type can't be read, as with some modular addon machines. */
    public static java.util.Optional<ResourceLocation> recipeTypeIdOf(MachineBlockEntity machine) {
        UnifiedCrafter crafter = getCrafter(machine);
        var recipeType = crafter != null ? crafter.getRecipeType() : null;
        return recipeType != null
                ? java.util.Optional.ofNullable(net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipeType))
                : java.util.Optional.empty();
    }

    /** What a machine shows under on the graph, and whether a player placed it there. */
    public record DisplayRecipe(@Nullable ResourceLocation recipeId, boolean assigned) {}

    public static DisplayRecipe resolveDisplayRecipe(MachineTracker tracker, @Nullable ResourceLocation assignment,
                                                     @Nullable Set<ResourceLocation> machineNodeIds) {
        ResourceLocation live = tracker.lastRecipeId != null ? tracker.lastRecipeId : tracker.saturatedRecipeId;
        if (live != null) return new DisplayRecipe(live, false);
        ResourceLocation history = tracker.lastKnownRecipeId;
        boolean historyOffPlan = history != null && machineNodeIds != null && !machineNodeIds.contains(history);
        if (assignment != null && (history == null || historyOffPlan)) return new DisplayRecipe(assignment, true);
        return new DisplayRecipe(history, false);
    }

    /** A resource is on a recycling loop for a shared machine if any goal linking it says so. */
    public static Set<ResourceLocation> unionCyclicResourceIds(List<ProductionGoal> linkingGoals) {
        Set<ResourceLocation> cyclicResourceIds = new HashSet<>();
        for (ProductionGoal linkingGoal : linkingGoals) {
            cyclicResourceIds.addAll(RecipeGraphTraverser.peekCyclicResourceIds(linkingGoal));
        }
        return cyclicResourceIds;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getPlayerList().getPlayerCount() == 0) {
            return;
        }

        // Keep every goal per position, so iteration order can't flip a status.
        Set<GlobalPos> activeKeys = new HashSet<>();
        Map<GlobalPos, List<ProductionGoal>> goalsByPos = new HashMap<>();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (stack.is(com.mervyn.miforeman.registry.ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                    ProductionGoal goal = stack.get(com.mervyn.miforeman.registry.ModComponents.PRODUCTION_GOAL.get());
                    if (goal != null) {
                        activeKeys.addAll(goal.linkedMachines());
                        for (GlobalPos pos : goal.linkedMachines()) {
                            goalsByPos.computeIfAbsent(pos, k -> new ArrayList<>()).add(goal);
                        }
                    }
                }
            }
        }

        Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, List<BlockPos>> byDimension = new HashMap<>();
        for (GlobalPos gp : activeKeys) {
            byDimension.computeIfAbsent(gp.dimension(), k -> new ArrayList<>()).add(gp.pos());
        }

        MachineRecipeHistory history = activeKeys.isEmpty() ? null : MachineRecipeHistory.get(event.getServer());
        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, List<BlockPos>> entry : byDimension.entrySet()) {
            ServerLevel level = event.getServer().getLevel(entry.getKey());
            if (level == null) continue;
            for (BlockPos pos : entry.getValue()) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                GlobalPos key = GlobalPos.of(entry.getKey(), pos);
                updateMachine(level, key, history, goalsByPos.getOrDefault(key, List.of()));
            }
        }

        long pruneTick = event.getServer().overworld().getGameTime();
        if (lastPruneTick == Long.MIN_VALUE || pruneTick - lastPruneTick >= getPruneIntervalTicks()) {
            lastPruneTick = pruneTick;
            pruneTrackers(activeKeys);
        }
    }

    /** Separate from onServerTick, which only runs with a player online, so gametests can drive
     *  it. */
    public static void updateMachine(ServerLevel level, GlobalPos key, MachineRecipeHistory history,
                                     List<ProductionGoal> linkingGoals) {
        long tick = level.getGameTime();
        BlockEntity be = level.getBlockEntity(key.pos());
        if (be instanceof MachineBlockEntity machine) {
            UnifiedCrafter crafter = getCrafter(machine);
            if (crafter == null) return;

            MachineTracker tracker = trackerFor(key);
            // A crafter whose recipe type can't be read is trusted, since an unverifiable entry beats
            // no node at all.
            var recipeType = crafter.getRecipeType();
            seedLastKnownRecipe(tracker, history, key,
                    id -> recipeType == null || recipeType.getRecipe(level, id) != null);
            tracker.disposalRatio = computeDisposalRatio(crafter);
            boolean hasActive = crafter.hasActiveRecipe();

            if (hasActive) {
                var activeHolder = crafter.getActiveRecipe();
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

                    recordActiveRecipe(tracker, recipeId);
                    history.record(key, recipeId);
                    tracker.lastUsedEnergy = usedEnergy;
                    tracker.lastRecipeEnergy = recipeEnergy;
                }
                tracker.status = MachineStatus.GREEN;
                tracker.failureReason = FailureReason.NONE;
                tracker.saturatedRecipeId = null;
            } else {
                tracker.lastRecipeId = null;
                tracker.lastUsedEnergy = 0;
                tracker.lastRecipeEnergy = 0;
                Set<ResourceLocation> cyclicResourceIds = unionCyclicResourceIds(linkingGoals);
                PassiveStatus passive = getMachinePassiveStatusDetailed(crafter, level, cyclicResourceIds,
                        tracker.lastKnownRecipeId);
                tracker.status = passive.status();
                tracker.failureReason = passive.reason();
                tracker.saturatedRecipeId = passive.matchedRecipeId();
            }
        } else {
            // No machine here anymore. Forget its recipe so a machine placed later starts clean.
            history.forget(key);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Static state must not leak across worlds. Singleplayer starts a new server per world.
        TRACKERS.clear();
        lastPruneTick = Long.MIN_VALUE;
    }

    public record PassiveStatus(MachineStatus status, @Nullable ResourceLocation matchedRecipeId,
                                 FailureReason reason) {}

    public static MachineStatus getMachinePassiveStatus(UnifiedCrafter crafter, ServerLevel level) {
        return getMachinePassiveStatusDetailed(crafter, level).status();
    }

    public static MachineStatus getMachinePassiveStatus(CrafterComponent crafter, ServerLevel level) {
        return getMachinePassiveStatusDetailed(UnifiedCrafter.from(crafter), level).status();
    }

    public static PassiveStatus getMachinePassiveStatusDetailed(CrafterComponent crafter, ServerLevel level) {
        return getMachinePassiveStatusDetailed(UnifiedCrafter.from(crafter), level);
    }

    /** Passive status with no recycling-loop awareness, so a starving machine is always STARVED. */
    public static PassiveStatus getMachinePassiveStatusDetailed(UnifiedCrafter crafter, ServerLevel level) {
        return getMachinePassiveStatusDetailed(crafter, level, Set.of(), null);
    }

    /** Passive status plus a failure reason: CLOG_LOCK when the output is full, DEAD_LOOP when a
     *  starved machine's lastKnownRecipeId touches a resource in cyclicResourceIds, else STARVED.
     *  <p>Input slots can't answer this. MI resets a drained ConfigurableItemStack or
     *  ConfigurableFluidStack to blank, so a starved slot carries no resource type. */
    public static PassiveStatus getMachinePassiveStatusDetailed(UnifiedCrafter crafter, ServerLevel level,
                                                                  Set<ResourceLocation> cyclicResourceIds,
                                                                  @Nullable ResourceLocation lastKnownRecipeId) {
        if (crafter.hasActiveRecipe()) {
            return new PassiveStatus(MachineStatus.GREEN, null, FailureReason.NONE);
        }

        var recipeType = crafter.getRecipeType();
        if (recipeType == null) {
            return new PassiveStatus(MachineStatus.RED, null, FailureReason.STARVED);
        }

        List<ConfigurableItemStack> itemInputs = crafter.getItemInputs();
        List<ConfigurableFluidStack> fluidInputs = crafter.getFluidInputs();

        Collection<RecipeHolder<MachineRecipe>> candidates = CrafterComponent.getRecipes(level, recipeType, itemInputs);

        for (RecipeHolder<MachineRecipe> holder : candidates) {
            MachineRecipe recipe = holder.value();
            if (crafter.banRecipe(recipe)) {
                continue;
            }
            if (CrafterComponent.doInputsMatch(itemInputs, fluidInputs, recipe)) {
                return new PassiveStatus(MachineStatus.ORANGE, holder.id(), FailureReason.CLOG_LOCK);
            }
        }

        boolean touchesCycle = recipeTouchesCycle(level, lastKnownRecipeId, cyclicResourceIds);
        return new PassiveStatus(MachineStatus.RED, null, touchesCycle ? FailureReason.DEAD_LOOP : FailureReason.STARVED);
    }

    private static boolean recipeTouchesCycle(ServerLevel level, @Nullable ResourceLocation recipeId,
                                               Set<ResourceLocation> cyclicResourceIds) {
        if (recipeId == null || cyclicResourceIds.isEmpty()) {
            return false;
        }
        var holder = level.getRecipeManager().byKey(recipeId);
        if (holder.isEmpty() || !(holder.get().value() instanceof MachineRecipe recipe)) {
            return false;
        }
        return !Collections.disjoint(RecipeGraphTraverser.recipeResourceIds(recipe), cyclicResourceIds);
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

    public record LiveStatus(MachineStatus status, FailureReason reason) {}

    /** Decides from its actual and total rate maps whether a GREEN machine reads as YELLOW, and
     *  whether the shortfall traces to a recycling-loop resource. Other statuses pass through. */
    public static LiveStatus classifyLiveStatus(ProductionGoal goal, MachineTracker tracker,
                                                 Map<ResourceLocation, Double> rates,
                                                 Map<ResourceLocation, Double> totalRates) {
        MachineStatus status = tracker.status;
        FailureReason reason = tracker.failureReason;
        if (status == MachineStatus.GREEN) {
            // Check cyclic resources first so a dead loop isn't masked by iteration order.
            Set<ResourceLocation> cyclicIds = RecipeGraphTraverser.peekCyclicResourceIds(goal);
            ResourceLocation underperformingResource = null;
            for (var entry : rates.entrySet()) {
                ResourceLocation resourceId = entry.getKey();
                double actualTotal = totalRates.getOrDefault(resourceId, 0.0);
                double expected = getExpectedRate(goal, resourceId);
                if (expected > 0.0 && actualTotal < expected * goal.threshold()) {
                    if (underperformingResource == null) {
                        underperformingResource = resourceId;
                    }
                    if (cyclicIds.contains(resourceId)) {
                        underperformingResource = resourceId;
                        break;
                    }
                }
            }
            if (underperformingResource != null) {
                status = MachineStatus.YELLOW;
                if (cyclicIds.contains(underperformingResource)) {
                    reason = FailureReason.DEAD_LOOP;
                } else if (tracker.disposalRatio >= DISPOSAL_THROTTLE_THRESHOLD) {
                    reason = FailureReason.DISPOSAL_THROTTLED;
                } else {
                    reason = FailureReason.NONE;
                }
            }
        }
        return new LiveStatus(status, reason);
    }
}
