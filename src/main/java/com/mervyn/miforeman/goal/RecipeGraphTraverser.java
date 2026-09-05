package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.ProductionGoal.Ambiguity;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.MachineRequirement;
import com.mervyn.miforeman.goal.ProductionGoal.MaterialFlow;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class RecipeGraphTraverser {

    public static FactoryPlan computePlan(Level level, ProductionGoal goal) {
        RecipeGraph graph = computeRecipeGraph(level, goal);
        return planFromGraph(graph);
    }

    /**
     * Derives a {@link FactoryPlan} directly from a resolved {@link RecipeGraph}.
     */
    public static FactoryPlan planFromGraph(RecipeGraph graph) {
        Map<ResourceLocation, MachineRequirementAccumulator> machineMap = new LinkedHashMap<>();
        List<MaterialFlow> rawInputs = new ArrayList<>();
        List<MaterialFlow> intermediateFlows = new ArrayList<>();
        List<Ambiguity> ambiguities = new ArrayList<>();
        Set<ResourceLocation> seenAmbiguityOwners = new HashSet<>();

        for (RecipeGraphNode node : graph.nodes().values()) {
            if (node.getType() == NodeType.MACHINE) {
                ResourceLocation typeId = node.getMachineType();
                if (typeId != null) {
                    machineMap.computeIfAbsent(typeId, MachineRequirementAccumulator::new)
                            .add(node.getMachineCount(), node.getBaseEuPerTick(), node.getTotalEuPerTick());
                }
            } else if (node.getType() == NodeType.RAW) {
                rawInputs.add(new MaterialFlow(getItemOrFluidType(node.getId()), node.getId(), node.getRequiredRate()));
            } else {
                intermediateFlows
                        .add(new MaterialFlow(getItemOrFluidType(node.getId()), node.getId(), node.getRequiredRate()));
            }

            if (node.getType() != NodeType.MACHINE && node.getAmbiguityOptions().size() > 1) {
                ResourceLocation ownerId = node.getAmbiguityOwnerId() != null ? node.getAmbiguityOwnerId()
                        : node.getId();
                if (seenAmbiguityOwners.add(ownerId)) {
                    ambiguities.add(new Ambiguity(ownerId, node.getAmbiguityOptions()));
                }
            }
        }

        List<MachineRequirement> machines = machineMap.values().stream()
                .map(MachineRequirementAccumulator::toRequirement)
                .toList();

        return new FactoryPlan(machines, rawInputs, intermediateFlows, ambiguities, graph);
    }

    private static class MachineRequirementAccumulator {
        final ResourceLocation machineId;
        double count;
        long baseEu;
        long totalEu;

        MachineRequirementAccumulator(ResourceLocation machineId) {
            this.machineId = machineId;
        }

        void add(double machineCount, long baseEu, long totalEu) {
            this.count += machineCount;
            this.baseEu = Math.max(this.baseEu, baseEu);
            this.totalEu += totalEu;
        }

        MachineRequirement toRequirement() {
            return new MachineRequirement(machineId, count, baseEu, totalEu);
        }
    }

    public static TargetType getItemOrFluidType(ResourceLocation id) {
        if (BuiltInRegistries.FLUID.containsKey(id)) {
            return TargetType.FLUID;
        }
        return TargetType.ITEM;
    }

    /**
     * Indexes loaded {@link MachineRecipe} instances by output item and fluid.
     * Scans the vanilla
     * {@link RecipeManager} directly to include addon-defined machine recipe types.
     * <p>
     * Output candidate lists are deduplicated by recipe ID and sorted by ID.
     * Deterministic sorting ensures default recipe selection stays consistent
     * across game loads.
     */
    private static void indexMachineRecipes(
            Level level,
            RecipeManager recipeManager,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes) {
        Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> itemByRecipeId = new HashMap<>();
        Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> fluidByRecipeId = new HashMap<>();

        indexMachineRecipeCollection(recipeManager.getRecipes(), itemByRecipeId, fluidByRecipeId);

        // Addons can supply recipes via a ProxyableMachineRecipeType (e.g. Extended
        // Industrialization's
        // runtime-generated canning/bucket recipes) that never register through
        // RecipeManager at all --
        // recipeManager.getRecipes() above can't see them. Off by default: see Config's
        // comment for why
        // (ClipboardScreen's client-side preview can't reflect this even when enabled).
        if (com.mervyn.miforeman.Config.INCLUDE_PROXIED_RECIPE_TYPES.get()
                && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (var recipeType : net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE) {
                if (!(recipeType instanceof aztech.modern_industrialization.machines.recipe.ProxyableMachineRecipeType proxyable)) {
                    continue;
                }
                try {
                    indexMachineRecipeCollection(proxyable.getRecipesWithCache(serverLevel), itemByRecipeId,
                            fluidByRecipeId);
                } catch (Exception e) {
                    ResourceLocation typeId = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE
                            .getKey(recipeType);
                    com.mervyn.miforeman.MIForeman.LOGGER.warn(
                            "Skipping proxied machine recipe type {} -- its recipe list threw while building", typeId,
                            e);
                }
            }
        }

        sortIntoLists(itemByRecipeId, itemRecipes);
        sortIntoLists(fluidByRecipeId, fluidRecipes);
    }

    private static void indexMachineRecipeCollection(
            Collection<? extends RecipeHolder<?>> recipes,
            Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> itemByRecipeId,
            Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> fluidByRecipeId) {
        for (RecipeHolder<?> holder : recipes) {
            if (!(holder.value() instanceof MachineRecipe recipe)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            RecipeHolder<MachineRecipe> machineHolder = (RecipeHolder<MachineRecipe>) holder;

            for (var output : recipe.itemOutputs) {
                if (output.amount() > 0 && output.probability() > 0) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(output.variant().getItem());
                    itemByRecipeId.computeIfAbsent(itemId, k -> new HashMap<>()).put(machineHolder.id(), machineHolder);
                }
            }
            for (var output : recipe.fluidOutputs) {
                if (output.amount() > 0 && output.probability() > 0) {
                    ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(output.fluid());
                    fluidByRecipeId.computeIfAbsent(fluidId, k -> new HashMap<>()).put(machineHolder.id(),
                            machineHolder);
                }
            }
        }
    }

    private static void sortIntoLists(
            Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> byRecipeId,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> target) {
        for (var entry : byRecipeId.entrySet()) {
            List<RecipeHolder<MachineRecipe>> sorted = new ArrayList<>(entry.getValue().values());
            sorted.sort(Comparator.comparing(RecipeHolder::id));
            target.put(entry.getKey(), sorted);
        }
    }

    /**
     * Groups loaded {@link MachineRecipe} instances by {@code RecipeType}. Scans
     * {@link RecipeManager} directly to include addon-defined machine recipe types.
     * Used by the
     * recipe-dump command and game tests.
     */
    public static Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> groupMachineRecipesByType(
            RecipeManager recipeManager) {
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> byType = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (!(holder.value() instanceof MachineRecipe recipe)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            RecipeHolder<MachineRecipe> machineHolder = (RecipeHolder<MachineRecipe>) holder;
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            byType.computeIfAbsent(typeId, k -> new ArrayList<>()).add(machineHolder);
        }
        return byType;
    }

    private record GraphCacheKey(
            TargetType type,
            ResourceLocation targetId,
            double rate,
            Map<ResourceLocation, ResourceLocation> selections) {
    }

    // Bounded (LRU-evicted) since every distinct (target, rate, selections) combination is a
    // separate key -- unbounded would grow forever across a long session of slider drags.
    // Not a ConcurrentHashMap. LinkedHashMap's removeEldestEntry hook needs external
    // synchronization anyway (get-then-put isn't atomic), so every access below is wrapped in
    // synchronized(GRAPH_CACHE) instead. This cache is genuinely reached from two different
    // threads in singleplayer, the client render thread (ClipboardScreen -> GoalDraft.computePlan)
    // and the integrated/dedicated server thread (GoalUpdateHandler, ForemanCommands).
    private static final int GRAPH_CACHE_MAX_SIZE = 50;
    private static final Map<GraphCacheKey, RecipeGraph> GRAPH_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<GraphCacheKey, RecipeGraph> eldest) {
            return size() > GRAPH_CACHE_MAX_SIZE;
        }
    };

    /**
     * Immutable snapshot of the indexed item/fluid recipe lookup, keyed on both the
     * {@link RecipeManager} instance and the dimension it was built for -- a single
     * {@link RecipeManager} is shared server-wide across dimensions, but the index can
     * still be dimension-specific (see {@link #indexMachineRecipes}'s proxied-recipe
     * branch). Held behind one {@code volatile} reference so readers on the client
     * render thread and the integrated-server thread always see a fully-consistent
     * snapshot together, never a torn mix of an old map with a new manager/dimension.
     */
    private record RecipeIndex(
            RecipeManager recipeManager,
            ResourceKey<Level> dimension,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes) {
    }

    private static volatile RecipeIndex cachedIndex;

    public static void clearGraphCache() {
        synchronized (GRAPH_CACHE) {
            GRAPH_CACHE.clear();
        }
        cachedIndex = null;
    }

    /**
     * Rebuilds the item/fluid recipe index only when the {@link RecipeManager}
     * instance or the dimension has changed since the last call (e.g. a datapack
     * reload swaps in a new instance, or the caller moved to a different dimension),
     * instead of re-scanning {@code RecipeManager.getRecipes()} on every call.
     */
    private static RecipeIndex ensureRecipeIndex(Level level, RecipeManager recipeManager) {
        RecipeIndex local = cachedIndex;
        if (local != null && local.recipeManager() == recipeManager && local.dimension().equals(level.dimension())) {
            return local;
        }
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes = new HashMap<>();
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes = new HashMap<>();
        indexMachineRecipes(level, recipeManager, itemRecipes, fluidRecipes);
        RecipeIndex fresh = new RecipeIndex(recipeManager, level.dimension(), itemRecipes, fluidRecipes);
        cachedIndex = fresh;
        return fresh;
    }

    /** Cache-only lookup of a goal's recycling-loop resource IDs, for cheap use from the server
     *  tick loop. Never triggers {@link #computeRecipeGraph}'s comparatively expensive structure
     *  resolution or deep copy. Returns an empty set until something else (opening the clipboard
     *  screen, a plan recompute) has already populated the graph cache for this goal. */
    public static Set<ResourceLocation> peekCyclicResourceIds(ProductionGoal goal) {
        GraphCacheKey key = new GraphCacheKey(goal.type(), goal.targetId(), goal.rate(),
                new HashMap<>(goal.recipeSelections()));
        synchronized (GRAPH_CACHE) {
            RecipeGraph cached = GRAPH_CACHE.get(key);
            return cached != null ? cached.cyclicResourceIds() : Set.of();
        }
    }

    /** Every resource id between {@code recipeId}'s machine node and {@code graph}'s target,
     *  inclusive of both ends. Found by walking forward through {@link RecipeGraphNode#getOutputs()}
     *  edges, which alternate resource -> machine -> resource all the way to the {@code TARGET}
     *  node (see the node-id scheme in {@link #finalizeResourceNode}: resource nodes are keyed by
     *  resource id, machine nodes are keyed by recipe id). Used to answer "does this machine
     *  contribute anywhere to producing X", not just "is X this machine's own immediate output".
     *  <p>Returns an empty set if {@code recipeId} isn't a machine node in this graph, e.g. a
     *  linked machine crafting something unrelated to the current goal. No cycle risk beyond the
     *  visited-node guard: {@code getOutputs()} reflects the already-cycle-cut DAG used for rate
     *  propagation (see {@link #collectDag}), so this terminates even when
     *  {@code graph.cyclicResourceIds()} is non-empty. */
    public static Set<ResourceLocation> collectUpstreamResourceIds(RecipeGraph graph, @Nullable ResourceLocation recipeId) {
        RecipeGraphNode start = recipeId == null ? null : graph.nodes().get(recipeId);
        if (start == null) {
            return Set.of();
        }

        Set<ResourceLocation> visitedNodeIds = new HashSet<>();
        Set<ResourceLocation> resourceIds = new HashSet<>();
        Deque<RecipeGraphNode> queue = new ArrayDeque<>(List.of(start));

        while (!queue.isEmpty()) {
            RecipeGraphNode node = queue.poll();
            if (!visitedNodeIds.add(node.getId())) {
                continue;
            }
            if (node.getType() != NodeType.MACHINE) {
                resourceIds.add(node.getId());
            }
            for (GraphEdge edge : node.getOutputs()) {
                RecipeGraphNode next = graph.nodes().get(edge.to());
                if (next != null) {
                    queue.add(next);
                }
            }
        }

        return resourceIds;
    }

    /** Byproducts: outputs a machine's real recipe produces beyond what the plan demanded from
     *  it, aggregated across every MACHINE node and returned as resourceId -> rate (units/minute,
     *  matching this codebase's rate convention). This graph is a pure top-down demand tree (see
     *  {@link #finalizeResourceNode}): every non-target resource node exists because it was
     *  specifically demanded, and gets exactly one output edge, to whatever demanded it, the
     *  moment it's created. A recipe's other outputs, the ones nobody asked for, are never
     *  modeled as graph nodes. So this reads the real recipe data directly: for each MACHINE
     *  node, its {@code itemOutputs}/{@code fluidOutputs}, with each rate computed the same way
     *  {@code ServerMonitoringManager.getActualRates} does (machines &times; amount &times;
     *  probability per craft, scaled by the recipe's duration). A resourceId already tracked
     *  elsewhere in the graph is excluded -- the plan already relies on it, so it isn't excess
     *  production even if this machine also produces some as a side effect. */
    public static Map<ResourceLocation, Double> collectByproductRates(RecipeGraph graph) {
        Map<ResourceLocation, Double> byproductRates = new LinkedHashMap<>();
        for (RecipeGraphNode machineNode : graph.nodes().values()) {
            if (machineNode.getType() != NodeType.MACHINE) continue;
            MachineRecipe recipe = machineNode.getRecipe();
            double machineCount = machineNode.getMachineCount();
            if (recipe == null || machineCount <= 0 || recipe.duration <= 0) continue;

            // 1200 ticks/minute -- same conversion constant ServerMonitoringManager.getActualRates uses.
            double runsPerMinute = machineCount * 1200.0 / recipe.duration;

            for (var out : recipe.itemOutputs) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(out.variant().getItem());
                if (graph.nodes().containsKey(itemId)) continue;
                byproductRates.merge(itemId, runsPerMinute * out.amount() * out.probability(), Double::sum);
            }
            for (var out : recipe.fluidOutputs) {
                ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(out.fluid());
                if (graph.nodes().containsKey(fluidId)) continue;
                byproductRates.merge(fluidId, runsPerMinute * out.amount() * out.probability(), Double::sum);
            }
        }
        return byproductRates;
    }

    /** Every item/fluid resource id a recipe touches, inputs and outputs combined, ignoring rates
     *  and probabilities -- used where only "does this recipe touch resource X at all" matters
     *  (e.g. {@code ServerMonitoringManager.recipeTouchesCycle}), as opposed to the rate-aware
     *  walks elsewhere in this class. */
    public static Set<ResourceLocation> recipeResourceIds(MachineRecipe recipe) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (var in : recipe.itemInputs) {
            for (var item : in.getInputItems()) {
                ids.add(BuiltInRegistries.ITEM.getKey(item));
            }
        }
        for (var in : recipe.fluidInputs) {
            for (var fluid : in.getInputFluids()) {
                ids.add(BuiltInRegistries.FLUID.getKey(fluid));
            }
        }
        for (var out : recipe.itemOutputs) {
            ids.add(BuiltInRegistries.ITEM.getKey(out.variant().getItem()));
        }
        for (var out : recipe.fluidOutputs) {
            ids.add(BuiltInRegistries.FLUID.getKey(out.fluid()));
        }
        return ids;
    }

    public static RecipeGraph computeRecipeGraph(Level level, ProductionGoal goal) {
        GraphCacheKey key = new GraphCacheKey(goal.type(), goal.targetId(), goal.rate(),
                new HashMap<>(goal.recipeSelections()));
        synchronized (GRAPH_CACHE) {
            RecipeGraph cached = GRAPH_CACHE.get(key);
            if (cached != null) {
                // Never hand out the cached instance itself -- its nodes have public setters
                // (setExpanded, setSelectedAmbiguity, ...) that UI code calls directly, which
                // would otherwise mutate the shared cache entry in place.
                return cached.copy();
            }
        }

        RecipeManager recipeManager = level.getRecipeManager();

        RecipeIndex index = ensureRecipeIndex(level, recipeManager);
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes = index.itemRecipes();
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes = index.fluidRecipes();

        // Two phases -- mirrors computePlan()/getSubPlan()'s existing memoization
        // pattern, which
        // buildGraph() previously didn't share (its old `visited` set was only a
        // recursion-stack
        // cycle guard, removed again at every return, so a resourceId reached via more
        // than one
        // demand path had its entire input subtree re-walked from scratch on every
        // occurrence, and
        // its own supply edge got rebuilt with only that occurrence's *partial* rate
        // each time
        // instead of the full accumulated total).
        //
        // Phase 1 resolves the DAG structure (which recipe is chosen, and its input
        Map<ResourceLocation, StructuralNode> structNodes = new HashMap<>();
        resolveStructure(itemRecipes, fluidRecipes, goal.type(), goal.targetId(),
                goal.recipeSelections(), new HashSet<>(), structNodes, goal.targetId());

        Map<ResourceLocation, RecipeGraphNode> nodes = new HashMap<>();
        Map<EdgeKey, GraphEdge> edges = new LinkedHashMap<>();
        Set<ResourceLocation> cyclicResourceIds = new HashSet<>();
        propagateRates(goal.targetId(), goal.rate(), 0, structNodes, nodes, edges, cyclicResourceIds);

        for (GraphEdge edge : edges.values()) {
            RecipeGraphNode fromNode = nodes.get(edge.from());
            if (fromNode != null)
                fromNode.putOutput(edge);
            RecipeGraphNode toNode = nodes.get(edge.to());
            if (toNode != null)
                toNode.putInput(edge);
        }

        RecipeGraph result = new RecipeGraph(goal.targetId(), goal.rate(), nodes, new ArrayList<>(edges.values()), cyclicResourceIds);
        synchronized (GRAPH_CACHE) {
            GRAPH_CACHE.put(key, result);
        }
        return result.copy();
    }

    private record EdgeKey(ResourceLocation from, ResourceLocation to) {
    }

    /**
     * One recipe input normalized per 1.0 unit/s of the owning
     * {@link StructuralNode} resource ID.
     */
    private record StructInputEdge(ResourceLocation childId, double ratePerUnit) {
    }

    /**
     * Rate-independent graph structure for a resource ID, including the chosen
     * recipe and
     * per-unit normalized inputs. Resolved and memoized in
     * {@link #resolveStructure}, then
     * reused across demand paths in {@link #propagateRates}.
     */
    private record StructuralNode(
            ResourceLocation resourceId,
            @Nullable ResourceLocation recipeId, // null => RAW (no candidate recipe, or an
                                                 // unproducible chosen recipe -- see below)
            @Nullable ResourceLocation machineTypeId,
            @Nullable MachineRecipe recipe,
            List<ResourceLocation> ambiguityOptions,
            @Nullable ResourceLocation selectedAmbiguity,
            double machineCountPerUnit,
            List<StructInputEdge> itemInputs, 
            List<StructInputEdge> fluidInputs 
    ) {
    }

    private static @Nullable StructuralNode resolveStructure(
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes,
            TargetType type,
            ResourceLocation resourceId,
            Map<ResourceLocation, ResourceLocation> selections,
            Set<ResourceLocation> visited,
            Map<ResourceLocation, StructuralNode> structMemo,
            ResourceLocation rootTargetId) {
        StructuralNode cached = structMemo.get(resourceId);
        if (cached != null) {
            return cached;
        }
        if (visited.contains(resourceId)) {
            return null;
        }
        visited.add(resourceId);

        List<RecipeHolder<MachineRecipe>> allCandidates = type == TargetType.ITEM
                ? itemRecipes.getOrDefault(resourceId, Collections.emptyList())
                : fluidRecipes.getOrDefault(resourceId, Collections.emptyList());

        boolean shouldExpand = type == TargetType.ITEM || resourceId.equals(rootTargetId) || selections.containsKey(resourceId);

        StructuralNode result;
        if (!shouldExpand || allCandidates.isEmpty()) {
            List<ResourceLocation> ambiguityOptions = allCandidates.size() > 1
                    ? allCandidates.stream().map(RecipeHolder::id).toList()
                    : (allCandidates.size() == 1 ? List.of(allCandidates.get(0).id()) : List.of());
            ResourceLocation selectedAmbiguity = ambiguityOptions.isEmpty()
                    ? null
                    : (selections.get(resourceId) != null ? selections.get(resourceId) : ambiguityOptions.get(0));
            result = new StructuralNode(resourceId, null, null, null, ambiguityOptions, selectedAmbiguity, 0.0, List.of(), List.of());
        } else {
            List<RecipeHolder<MachineRecipe>> candidates = allCandidates;
            RecipeHolder<MachineRecipe> chosenHolder = null;
            List<ResourceLocation> ambiguityOptions = List.of();
            if (candidates.size() > 1) {
                ResourceLocation selectedRecipeId = selections.get(resourceId);
                if (selectedRecipeId != null) {
                    for (var candidate : candidates) {
                        if (candidate.id().equals(selectedRecipeId)) {
                            chosenHolder = candidate;
                            break;
                        }
                    }
                }
                ambiguityOptions = candidates.stream().map(RecipeHolder::id).toList();
            }
            if (chosenHolder == null) {
                chosenHolder = candidates.get(0);
            }

            MachineRecipe chosenRecipe = chosenHolder.value();
            ResourceLocation recipeId = chosenHolder.id();

            double outputAmount = 0.0;
            double outputProbability = 1.0;
            if (type == TargetType.ITEM) {
                for (var output : chosenRecipe.itemOutputs) {
                    if (BuiltInRegistries.ITEM.getKey(output.variant().getItem()).equals(resourceId)) {
                        outputAmount = output.amount();
                        outputProbability = output.probability();
                        break;
                    }
                }
            } else if (type == TargetType.FLUID) {
                for (var output : chosenRecipe.fluidOutputs) {
                    if (BuiltInRegistries.FLUID.getKey(output.fluid()).equals(resourceId)) {
                        outputAmount = output.amount();
                        outputProbability = output.probability();
                        break;
                    }
                }
            }

            if (outputAmount <= 0.0 || outputProbability <= 0.0) {
                ResourceLocation selectedAmbiguity = ambiguityOptions.isEmpty()
                        ? null
                        : (selections.get(resourceId) != null ? selections.get(resourceId) : ambiguityOptions.get(0));
                result = new StructuralNode(resourceId, null, null, null, ambiguityOptions, selectedAmbiguity, 0.0, List.of(), List.of());
            } else {
                double runsPerSecond = 1.0 / (outputAmount * outputProbability);
                double machineCountPerUnit = (runsPerSecond * chosenRecipe.duration) / 20.0;
                ResourceLocation machineTypeId = BuiltInRegistries.RECIPE_TYPE.getKey(chosenRecipe.getType());
                ResourceLocation selectedAmbiguity = candidates.size() > 1
                        ? (selections.get(resourceId) != null ? selections.get(resourceId) : chosenHolder.id())
                        : null;

                Map<ResourceLocation, Double> itemRates = new LinkedHashMap<>();
                for (var input : chosenRecipe.itemInputs) {
                    List<Item> inputItems = input.getInputItems();
                    if (!inputItems.isEmpty()) {
                        ResourceLocation inputItemId = BuiltInRegistries.ITEM.getKey(inputItems.get(0));
                        itemRates.merge(inputItemId, runsPerSecond * input.amount() * input.probability(), Double::sum);
                    }
                }
                Map<ResourceLocation, Double> fluidRates = new LinkedHashMap<>();
                for (var input : chosenRecipe.fluidInputs) {
                    List<Fluid> inputFluids = input.getInputFluids();
                    if (!inputFluids.isEmpty()) {
                        ResourceLocation inputFluidId = BuiltInRegistries.FLUID.getKey(inputFluids.get(0));
                        fluidRates.merge(inputFluidId, runsPerSecond * input.amount() * input.probability(),
                                Double::sum);
                    }
                }

                List<StructInputEdge> itemInputs = itemRates.entrySet().stream()
                        .map(e -> new StructInputEdge(e.getKey(), e.getValue())).toList();
                List<StructInputEdge> fluidInputs = fluidRates.entrySet().stream()
                        .map(e -> new StructInputEdge(e.getKey(), e.getValue())).toList();

                for (StructInputEdge edge : itemInputs) {
                    resolveStructure(itemRecipes, fluidRecipes, TargetType.ITEM, edge.childId(),
                            selections, visited, structMemo, rootTargetId);
                }
                for (StructInputEdge edge : fluidInputs) {
                    resolveStructure(itemRecipes, fluidRecipes, TargetType.FLUID, edge.childId(),
                            selections, visited, structMemo, rootTargetId);
                }

                result = new StructuralNode(resourceId, recipeId, machineTypeId, chosenRecipe,
                        ambiguityOptions, selectedAmbiguity, machineCountPerUnit, itemInputs, fluidInputs);
            }
        }

        visited.remove(resourceId);
        structMemo.put(resourceId, result);
        return result;
    }

    public static List<RecipeHolder<MachineRecipe>> getCandidateRecipes(Level level, ResourceLocation resourceId) {
        var recipeManager = level.getRecipeManager();
        RecipeIndex index = ensureRecipeIndex(level, recipeManager);
        List<RecipeHolder<MachineRecipe>> list = index.itemRecipes().get(resourceId);
        if (list != null && !list.isEmpty()) {
            return list;
        }
        return index.fluidRecipes().getOrDefault(resourceId, List.of());
    }

    private static void propagateRates(
            ResourceLocation rootId, double rootRate, int rootDepth,
            Map<ResourceLocation, StructuralNode> structNodes,
            Map<ResourceLocation, RecipeGraphNode> nodes,
            Map<EdgeKey, GraphEdge> edges,
            Set<ResourceLocation> cyclicResourceIds) {

        Map<ResourceLocation, Set<ResourceLocation>> forwardEdges = new HashMap<>();
        Map<ResourceLocation, Integer> inDegree = new HashMap<>();
        Set<ResourceLocation> onStack = new HashSet<>();
        Set<ResourceLocation> done = new HashSet<>();
        collectDag(rootId, structNodes, forwardEdges, inDegree, onStack, done, cyclicResourceIds);

        Map<ResourceLocation, Double> totalRate = new HashMap<>();
        Map<ResourceLocation, Integer> minDepth = new HashMap<>();
        totalRate.put(rootId, rootRate);
        minDepth.put(rootId, rootDepth);

        Deque<ResourceLocation> ready = new ArrayDeque<>();
        ready.add(rootId);

        while (!ready.isEmpty()) {
            ResourceLocation curr = ready.poll();
            StructuralNode structNode = structNodes.get(curr);
            if (structNode == null) {
                continue;
            }
            double rate = totalRate.getOrDefault(curr, 0.0);
            int depth = minDepth.getOrDefault(curr, 0);

            finalizeResourceNode(rootId, curr, structNode, rate, depth, nodes, edges);

            if (structNode.recipeId() == null) {
                continue;
            }

            Set<ResourceLocation> children = forwardEdges.getOrDefault(curr, Collections.emptySet());
            List<StructInputEdge> allInputs = new ArrayList<>(structNode.itemInputs().size() + structNode.fluidInputs().size());
            allInputs.addAll(structNode.itemInputs());
            allInputs.addAll(structNode.fluidInputs());

            for (StructInputEdge input : allInputs) {
                if (!children.contains(input.childId())) {
                    continue;
                }
                ResourceLocation child = input.childId();
                totalRate.merge(child, rate * input.ratePerUnit(), Double::sum);
                minDepth.merge(child, depth + 2, Math::min);

                int remaining = inDegree.merge(child, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(child);
                }
            }
        }
    }

    private static void collectDag(
            ResourceLocation curr,
            Map<ResourceLocation, StructuralNode> structNodes,
            Map<ResourceLocation, Set<ResourceLocation>> forwardEdges,
            Map<ResourceLocation, Integer> inDegree,
            Set<ResourceLocation> onStack,
            Set<ResourceLocation> done,
            Set<ResourceLocation> cyclicResourceIds) {

        if (done.contains(curr))
            return;

        StructuralNode node = structNodes.get(curr);
        if (node == null)
            return;

        onStack.add(curr);
        List<StructInputEdge> allInputs = new ArrayList<>(node.itemInputs().size() + node.fluidInputs().size());
        allInputs.addAll(node.itemInputs());
        allInputs.addAll(node.fluidInputs());

        for (StructInputEdge edge : allInputs) {
            ResourceLocation child = edge.childId();
            if (!structNodes.containsKey(child))
                continue;

            if (onStack.contains(child)) {
                // Back-edge: curr ... child forms a recycling loop. Not solved (rate propagation
                // still treats this edge as absent, same as before), but its membership is
                // recorded so passive-status checks can tell a starved loop member ("dead-loop")
                // apart from ordinary starvation.
                cyclicResourceIds.add(curr);
                cyclicResourceIds.add(child);
                continue;
            }

            if (forwardEdges.computeIfAbsent(curr, k -> new HashSet<>()).add(child)) {
                inDegree.merge(child, 1, Integer::sum);
            }

            collectDag(child, structNodes, forwardEdges, inDegree, onStack, done, cyclicResourceIds);
        }
        onStack.remove(curr);
        done.add(curr);
    }

    private static void finalizeResourceNode(
            ResourceLocation rootId, ResourceLocation resourceId, StructuralNode structNode,
            double rate, int depth,
            Map<ResourceLocation, RecipeGraphNode> nodes, Map<EdgeKey, GraphEdge> edges) {

        if (structNode.recipeId() == null) {
            RecipeGraphNode rawNode = nodes.get(resourceId);
            if (rawNode != null) {
                rawNode.setRequiredRate(rawNode.getRequiredRate() + rate);
                rawNode.setDepth(Math.min(rawNode.getDepth(), depth));
            } else {
                nodes.put(resourceId, new RecipeGraphNode(
                        resourceId, NodeType.RAW, null, null,
                        rate, 0,
                        structNode.ambiguityOptions(), structNode.selectedAmbiguity(), resourceId, depth));
            }
            return;
        }

        NodeType nodeType = resourceId.equals(rootId) ? NodeType.TARGET : NodeType.INTERMEDIATE;
        RecipeGraphNode resNode = nodes.get(resourceId);
        if (resNode != null) {
            resNode.setRequiredRate(resNode.getRequiredRate() + rate);
            resNode.setDepth(Math.min(resNode.getDepth(), depth));
        } else {
            resNode = new RecipeGraphNode(
                    resourceId, nodeType, null, null,
                    rate, 0,
                    structNode.ambiguityOptions(), structNode.selectedAmbiguity(), resourceId, depth);
            nodes.put(resourceId, resNode);
        }

        double machineCount = rate * structNode.machineCountPerUnit();
        long baseEu = structNode.recipe() != null ? structNode.recipe().eu : 0;
        long totalEu = (long) Math.ceil(machineCount * baseEu);
        RecipeGraphNode machNode = nodes.get(structNode.recipeId());
        if (machNode != null) {
            machNode.setRequiredRate(machNode.getRequiredRate() + rate);
            machNode.setMachineCount(machNode.getMachineCount() + machineCount);
            machNode.setBaseEuPerTick(Math.max(machNode.getBaseEuPerTick(), baseEu));
            machNode.setTotalEuPerTick(machNode.getTotalEuPerTick() + totalEu);
            machNode.setDepth(Math.min(machNode.getDepth(), depth + 1));
        } else {
            machNode = new RecipeGraphNode(
                    structNode.recipeId(), NodeType.MACHINE, structNode.machineTypeId(), structNode.recipe(),
                    rate, machineCount,
                    structNode.ambiguityOptions(), structNode.selectedAmbiguity(), resourceId,
                    depth + 1);
            machNode.setBaseEuPerTick(baseEu);
            machNode.setTotalEuPerTick(totalEu);
            nodes.put(structNode.recipeId(), machNode);
        }

        edges.merge(new EdgeKey(structNode.recipeId(), resourceId),
                new GraphEdge(structNode.recipeId(), resourceId, rate),
                (oldEdge, newEdge) -> new GraphEdge(oldEdge.from(), oldEdge.to(), oldEdge.rate() + newEdge.rate()));

        for (StructInputEdge input : structNode.itemInputs()) {
            double inputRate = rate * input.ratePerUnit();
            edges.merge(new EdgeKey(input.childId(), structNode.recipeId()),
                    new GraphEdge(input.childId(), structNode.recipeId(), inputRate),
                    (oldEdge, newEdge) -> new GraphEdge(oldEdge.from(), oldEdge.to(), oldEdge.rate() + newEdge.rate()));
        }

        for (StructInputEdge input : structNode.fluidInputs()) {
            double inputRate = rate * input.ratePerUnit();
            edges.merge(new EdgeKey(input.childId(), structNode.recipeId()),
                    new GraphEdge(input.childId(), structNode.recipeId(), inputRate),
                    (oldEdge, newEdge) -> new GraphEdge(oldEdge.from(), oldEdge.to(), oldEdge.rate() + newEdge.rate()));
        }
    }

    private RecipeGraphTraverser() {
    }
}
