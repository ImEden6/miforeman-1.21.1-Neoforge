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
        /** Recipe a saturating (ORANGE) machine runs once its output clears. Distinct from
         *  lastRecipeId, which only tracks an active craft. */
        public ResourceLocation saturatedRecipeId = null;
        /** Last recipe actually seen running. Unlike lastRecipeId this is never cleared when the
         *  machine goes idle, so it can recognize a dead-loop (was producing/consuming a cyclic
         *  resource, now starved) instead of ordinary starvation (never ran at all). */
        public ResourceLocation lastKnownRecipeId = null;
        public long lastUsedEnergy = 0;
        public long lastRecipeEnergy = 0;
        /** How full this machine's fullest output slot is, 0.0 (empty) to 1.0 (full/backed up).
         *  Refreshed every tick regardless of active/passive status -- see
         *  {@link ServerMonitoringManager#computeDisposalRatio}. */
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

    /** Dimension-safe map key. Bare BlockPos collides across dimensions. */
    public static GlobalPos key(ServerLevel level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos);
    }

    public static MachineTracker trackerFor(GlobalPos key) {
        return TRACKERS.computeIfAbsent(key, k -> new MachineTracker(k.pos()));
    }

    public static MachineTracker trackerFor(ServerLevel level, BlockPos pos) {
        return trackerFor(key(level, pos));
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

    public static RecipeHolder<MachineRecipe> getActiveRecipeHolder(UnifiedCrafter crafter) {
        return crafter.getActiveRecipe();
    }

    public static UnifiedCrafter getCrafter(MachineBlockEntity machine) {
        return UnifiedCrafter.from(machine);
    }

    /** actual/expected ratio at or above this marks a running (GREEN) but underperforming
     *  machine as {@link FailureReason#DISPOSAL_THROTTLED} rather than plain underperformance --
     *  see {@link #classifyLiveStatus}. */
    private static final double DISPOSAL_THROTTLE_THRESHOLD = 0.85;

    /** How full this machine's fullest output slot is, 0.0 (empty) to 1.0 (full/backed up).
     *  Uses {@code getCapacity()}, not {@code getAdjustedCapacity()} -- for a non-64-stackable
     *  item output, the adjusted capacity alone ignores the item's own max stack size and would
     *  understate how close the slot actually is to full. See maybe.md's "disposal ratio" note. */
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

    /** Recipe id worth showing the player for this machine: {@code lastRecipeId} (actually
     *  crafting) and {@code saturatedRecipeId} (blocked, would craft once its output clears) are
     *  mutually exclusive -- whichever is set wins. Neither is set for a RED (STARVED/DEAD_LOOP)
     *  machine, so falls back to {@code lastKnownRecipeId} -- history the tracker already keeps
     *  -- so RED machines still get a product label/graph-node match instead of silently having
     *  none. Extracted from {@code MonitoringPacketHandlers.handleRequest} so it's unit-testable. */
    public static @Nullable ResourceLocation resolveDisplayRecipeId(MachineTracker tracker) {
        if (tracker.lastRecipeId != null) return tracker.lastRecipeId;
        if (tracker.saturatedRecipeId != null) return tracker.saturatedRecipeId;
        return tracker.lastKnownRecipeId;
    }

    /** Unions every linking goal's cyclic-resource set: a resource counts as "on a recycling
     *  loop" for a shared machine if any goal that links it says so, rather than picking one
     *  goal's context arbitrarily. Extracted from {@link #onServerTick} so it's unit-testable
     *  without a real server tick/player. */
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

        // Pass 1: collect this tick's monitored set across all players and hands, and remember
        // every goal that links each position -- used below for cheap (cache-only) dead-loop
        // detection. A machine can be linked by more than one goal at once (e.g. two players);
        // keep all of them per position rather than letting the last one iterated silently win,
        // so dead-loop classification doesn't flip nondeterministically with player/hand order.
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

        // Pass 2: group active keys by dimension and update trackers for loaded machines.
        Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, List<BlockPos>> byDimension = new HashMap<>();
        for (GlobalPos gp : activeKeys) {
            byDimension.computeIfAbsent(gp.dimension(), k -> new ArrayList<>()).add(gp.pos());
        }

        for (Map.Entry<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, List<BlockPos>> entry : byDimension.entrySet()) {
            ServerLevel level = event.getServer().getLevel(entry.getKey());
            if (level == null) continue;
            long tick = level.getGameTime();
            for (BlockPos pos : entry.getValue()) {
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof MachineBlockEntity machine) {
                    UnifiedCrafter crafter = getCrafter(machine);
                    if (crafter == null) continue;

                    MachineTracker tracker = trackerFor(GlobalPos.of(entry.getKey(), pos));
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

                            tracker.lastRecipeId = recipeId;
                            tracker.lastKnownRecipeId = recipeId;
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
                        List<ProductionGoal> linkingGoals = goalsByPos.getOrDefault(GlobalPos.of(entry.getKey(), pos), List.of());
                        Set<ResourceLocation> cyclicResourceIds = unionCyclicResourceIds(linkingGoals);
                        PassiveStatus passive = getMachinePassiveStatusDetailed(crafter, level, cyclicResourceIds,
                                tracker.lastKnownRecipeId);
                        tracker.status = passive.status();
                        tracker.failureReason = passive.reason();
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

    /** Evaluates passive status like {@link #getMachinePassiveStatus}, with no recycling-loop
     *  awareness. A starving machine is always reported as {@link FailureReason#STARVED}. Use
     *  the overload taking {@code cyclicResourceIds}/{@code lastKnownRecipeId} when a goal's
     *  graph and this machine's tracker are available. */
    public static PassiveStatus getMachinePassiveStatusDetailed(UnifiedCrafter crafter, ServerLevel level) {
        return getMachinePassiveStatusDetailed(crafter, level, Set.of(), null);
    }

    /** Evaluates passive status like {@link #getMachinePassiveStatus}, returns the matching
     *  recipe ID when saturating (ORANGE), and classifies *why* the machine isn't running:
     *  {@link FailureReason#CLOG_LOCK} (ORANGE, own output is full), {@link FailureReason#DEAD_LOOP}
     *  (RED, and {@code lastKnownRecipeId}, the last recipe this machine was actually seen
     *  running, has an input or output resource on a recycling loop in
     *  {@code cyclicResourceIds}), or plain {@link FailureReason#STARVED} (RED otherwise, including
     *  a machine that has simply never run yet).
     *  <p>Deliberately doesn't derive dead-loop-ness from the crafter's current input slots. MI's
     *  {@code ConfigurableItemStack}/{@code ConfigurableFluidStack} reset a slot's configured
     *  resource type back to blank the moment its amount drains to zero, so a genuinely starved
     *  slot, exactly the state this exists to classify, carries no live resource-type
     *  information to check. {@code lastKnownRecipeId} is history the tracker already keeps, and
     *  survives that reset. */
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
                return new PassiveStatus(MachineStatus.ORANGE, holder.id(), FailureReason.CLOG_LOCK); // Saturating
            }
        }

        // Starving. If this machine was never seen running a recipe touching a recycling loop,
        // it's ordinary starvation (fix: increase upstream supply); otherwise it's a dead-loop
        // (fix: wire in a source) -- see FailureReason.
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
        for (var in : recipe.itemInputs) {
            for (var item : in.getInputItems()) {
                if (cyclicResourceIds.contains(BuiltInRegistries.ITEM.getKey(item))) {
                    return true;
                }
            }
        }
        for (var in : recipe.fluidInputs) {
            for (var fluid : in.getInputFluids()) {
                if (cyclicResourceIds.contains(BuiltInRegistries.FLUID.getKey(fluid))) {
                    return true;
                }
            }
        }
        for (var out : recipe.itemOutputs) {
            if (cyclicResourceIds.contains(BuiltInRegistries.ITEM.getKey(out.variant().getItem()))) {
                return true;
            }
        }
        for (var out : recipe.fluidOutputs) {
            if (cyclicResourceIds.contains(BuiltInRegistries.FLUID.getKey(out.fluid()))) {
                return true;
            }
        }
        return false;
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

    /** Result of {@link #classifyLiveStatus}: the machine's status possibly upgraded to YELLOW,
     *  and the reason to show alongside it. */
    public record LiveStatus(MachineStatus status, FailureReason reason) {}

    /** Given a machine's passively-tracked status/reason and its live actual-vs-total rate maps
     *  (as computed by {@link #getActualRates} and summed across all machines producing the same
     *  resource), decides whether a GREEN machine should read as YELLOW (underperforming), and if
     *  so whether the shortfall traces to a resource on a recycling loop ({@link FailureReason#DEAD_LOOP})
     *  or not ({@link FailureReason#NONE}, still actively crafting, so it isn't itself
     *  clog-locked). Non-GREEN machines pass through unchanged. Extracted from
     *  {@code MonitoringPacketHandlers.handleRequest} so this branch is unit-testable without a
     *  real network {@code IPayloadContext}. */
    public static LiveStatus classifyLiveStatus(ProductionGoal goal, MachineTracker tracker,
                                                 Map<ResourceLocation, Double> rates,
                                                 Map<ResourceLocation, Double> totalRates) {
        MachineStatus status = tracker.status;
        FailureReason reason = tracker.failureReason;
        if (status == MachineStatus.GREEN) {
            // rates.entrySet() has no guaranteed order, so if a machine underperforms on more
            // than one resource at once, scan for a cyclic one first rather than breaking on
            // whichever resource the HashMap iterates first -- a real dead-loop must never be
            // hidden behind an arbitrarily-chosen ordinary shortfall.
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
