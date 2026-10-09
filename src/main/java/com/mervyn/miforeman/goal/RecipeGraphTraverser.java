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

    public static FactoryPlan planFromGraph(RecipeGraph graph) {
        Map<ResourceLocation, MachineRequirementAccumulator> machineMap = new LinkedHashMap<>();
        List<MaterialFlow> rawInputs = new ArrayList<>();
        List<MaterialFlow> intermediateFlows = new ArrayList<>();
        List<Ambiguity> ambiguities = new ArrayList<>();
        Set<ResourceLocation> seenAmbiguityOwners = new HashSet<>();

        for (RecipeGraphNode node : graph.nodes().values()) {
            if (node.getType() == NodeType.MACHINE) {
                ResourceLocation typeId = node.getMachineType();
                if (typeId != null && node.getMachineCount() > 1e-9) {
                    machineMap.computeIfAbsent(typeId, MachineRequirementAccumulator::new)
                            .add(node.getMachineCount(), node.getBaseEuPerTick(), node.getTotalEuPerTick());
                }
            } else if (node.getRequiredRate() <= 1e-9) {
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

    /** Indexes machine recipes by output item and fluid. */
    private static void indexMachineRecipes(
            Level level,
            RecipeManager recipeManager,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes) {
        Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> itemByRecipeId = new HashMap<>();
        Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> fluidByRecipeId = new HashMap<>();

        indexMachineRecipeCollection(recipeManager.getRecipes(), itemByRecipeId, fluidByRecipeId);

        // Addon proxied recipes (e.g. Extended Industrialization).
        if (com.mervyn.miforeman.Config.INCLUDE_PROXIED_RECIPE_TYPES.get()) {
            for (var recipeType : net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE) {
                if (!(recipeType instanceof aztech.modern_industrialization.machines.recipe.ProxyableMachineRecipeType proxyable)) {
                    continue;
                }
                try {
                    var proxied = level instanceof net.minecraft.server.level.ServerLevel serverLevel
                            ? proxyable.getRecipesWithCache(serverLevel)
                            : proxyable.getRecipesWithoutCache(level);
                    indexMachineRecipeCollection(proxied, itemByRecipeId, fluidByRecipeId);
                } catch (Exception e) {
                    ResourceLocation typeId = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE
                            .getKey(recipeType);
                    com.mervyn.miforeman.MIForeman.LOGGER.warn(
                            "Skipping proxied machine recipe type {}: its recipe list threw while building", typeId,
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

    private static final int GRAPH_CACHE_MAX_SIZE = 50;
    private static final Map<GraphCacheKey, RecipeGraph> GRAPH_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<GraphCacheKey, RecipeGraph> eldest) {
            return size() > GRAPH_CACHE_MAX_SIZE;
        }
    };

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

    /** Cache-only lookup of a goal's recycling-loop resource IDs. */
    public static Set<ResourceLocation> peekCyclicResourceIds(ProductionGoal goal) {
        GraphCacheKey key = new GraphCacheKey(goal.type(), goal.targetId(), goal.rate(),
                new HashMap<>(goal.recipeSelections()));
        synchronized (GRAPH_CACHE) {
            RecipeGraph cached = GRAPH_CACHE.get(key);
            return cached != null ? cached.cyclicResourceIds() : Set.of();
        }
    }

    public static @Nullable Set<ResourceLocation> peekMachineNodeIds(ProductionGoal goal) {
        GraphCacheKey key = new GraphCacheKey(goal.type(), goal.targetId(), goal.rate(),
                new HashMap<>(goal.recipeSelections()));
        synchronized (GRAPH_CACHE) {
            RecipeGraph cached = GRAPH_CACHE.get(key);
            if (cached == null) return null;
            Set<ResourceLocation> ids = new java.util.HashSet<>();
            cached.nodes().forEach((id, node) -> {
                if (node.getType() == NodeType.MACHINE) ids.add(id);
            });
            return ids;
        }
    }

    /** Collects all resource IDs between a machine recipe and the target. */
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

    public static Map<ResourceLocation, Double> collectByproductRates(RecipeGraph graph) {
        Map<ResourceLocation, Double> rates = new LinkedHashMap<>();
        graph.surplusRates().forEach((id, rate) -> {
            if (rate > 1e-9)
                rates.put(id, rate);
        });
        return rates;
    }

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

    public enum SolveMode { AUTO, FORCE_LP, PROPAGATION_ONLY, FAIL_LP }

    public static RecipeGraph computeRecipeGraph(Level level, ProductionGoal goal) {
        GraphCacheKey key = new GraphCacheKey(goal.type(), goal.targetId(), goal.rate(),
                new HashMap<>(goal.recipeSelections()));
        synchronized (GRAPH_CACHE) {
            RecipeGraph cached = GRAPH_CACHE.get(key);
            if (cached != null) {
                return cached.copy();
            }
        }
        RecipeGraph result = computeRecipeGraphUncached(level, goal, SolveMode.AUTO);
        synchronized (GRAPH_CACHE) {
            GRAPH_CACHE.put(key, result);
        }
        return result.copy();
    }

    private record Built(Map<ResourceLocation, StructuralNode> structNodes, Map<ResourceLocation, RecipeGraphNode> nodes,
                         Map<EdgeKey, GraphEdge> edges, Set<ResourceLocation> cyclicResourceIds,
                         List<ResourceLocation[]> backEdges, PlanKeys keys, RecipeIndex index,
                         Map<ResourceLocation, ResourceLocation> selections, Set<ResourceLocation> imported) {
        boolean coupled() {
            return !cyclicResourceIds.isEmpty() || keys.hasDemandedByproduct(nodes);
        }

        PlanSolver.Model model(ProductionGoal goal) {
            return keys.model(goal, nodes, backEdges, structNodes);
        }
    }

    private static Built build(Level level, ProductionGoal goal, Map<ResourceLocation, ResourceLocation> autoSelections,
            Set<ResourceLocation> imported) {
        RecipeManager recipeManager = level.getRecipeManager();

        RecipeIndex index = ensureRecipeIndex(level, recipeManager);
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes = index.itemRecipes();
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes = index.fluidRecipes();

        Map<ResourceLocation, ResourceLocation> selections = new HashMap<>(autoSelections);
        selections.putAll(goal.recipeSelections());
        Map<ResourceLocation, StructuralNode> structNodes = new HashMap<>();
        resolveStructure(itemRecipes, fluidRecipes, goal.type(), goal.targetId(),
                selections, new HashSet<>(), structNodes, goal.targetId(), imported);

        Map<ResourceLocation, RecipeGraphNode> nodes = new HashMap<>();
        Map<EdgeKey, GraphEdge> edges = new LinkedHashMap<>();
        DagWalk dag = propagateRates(goal.targetId(), goal.rate(), 0, structNodes, nodes, edges);
        return new Built(structNodes, nodes, edges, dag.cyclicResourceIds, dag.backEdges, PlanKeys.of(goal, nodes),
                index, selections, imported);
    }

    public static PlanSolver.Model planModel(Level level, ProductionGoal goal) {
        return resolve(level, goal, SolveMode.PROPAGATION_ONLY).model();
    }

    public static boolean planIsCoupled(Level level, ProductionGoal goal) {
        return resolve(level, goal, SolveMode.AUTO).built().coupled();
    }

    private static final int MAX_FIX_PASSES = 8;

    private record Attempt(Built built, PlanSolver.Model model, @Nullable PlanSolver.Result result,
                           Map<ResourceLocation, ResourceLocation> autoSelections, Set<ResourceLocation> autoImports,
                           int fixPivots) {
    }

    private static Attempt attempt(Level level, ProductionGoal goal, Map<ResourceLocation, ResourceLocation> auto,
            Set<ResourceLocation> imported) {
        Built built = build(level, goal, auto, imported);
        return new Attempt(built, built.model(goal), null, auto, imported, 0);
    }

    private static boolean lpWanted(SolveMode mode, Built built) {
        return mode == SolveMode.FORCE_LP || (mode == SolveMode.AUTO && built.coupled());
    }

    private static Attempt resolve(Level level, ProductionGoal goal, SolveMode mode) {
        Attempt current = attempt(level, goal, Map.of(), Set.of());
        if (!current.built().cyclicResourceIds().isEmpty()) {
            PlanSolver.Result first = null;
            if (mode != SolveMode.FAIL_LP && lpWanted(mode, current.built())) {
                first = PlanSolver.solve(current.model());
                if (first == null || first.unsourced().isEmpty())
                    return withResult(current, first);
            }
            Attempt fixed = fixDeadLoops(level, goal, current);
            if (fixed.built() == current.built() && first != null)
                return withResult(current, first);
            current = fixed;
        }
        if (mode == SolveMode.FAIL_LP || !lpWanted(mode, current.built()))
            return current;
        return withResult(current, PlanSolver.solve(current.model()));
    }

    private static Attempt withResult(Attempt attempt, @Nullable PlanSolver.Result result) {
        return new Attempt(attempt.built(), attempt.model(), result, attempt.autoSelections(), attempt.autoImports(),
                attempt.fixPivots());
    }

    private static Attempt fixDeadLoops(Level level, ProductionGoal goal, Attempt current) {
        PlanSolver.LoopSupply found = PlanSolver.loopSupply(current.model());
        Map<PlanSolver.BackEdge, Double> dead = found == null ? null : found.supply();
        int pivots = found == null ? 0 : found.pivots();
        boolean allowPicks = true;
        for (int pass = 0; pass < MAX_FIX_PASSES && dead != null && !dead.isEmpty(); pass++) {
            Map<ResourceLocation, ResourceLocation> auto = new TreeMap<>(current.autoSelections());
            Set<ResourceLocation> imported = new TreeSet<>(current.autoImports());
            if (!decideFixes(goal, current, dead.keySet(), auto, imported, allowPicks))
                break;
            Attempt next = attempt(level, goal, auto, imported);
            PlanSolver.LoopSupply nextFound = PlanSolver.loopSupply(next.model());
            Map<PlanSolver.BackEdge, Double> nextDead = nextFound == null ? null : nextFound.supply();
            pivots += nextFound == null ? 0 : nextFound.pivots();
            if (nextDead == null || nextDead.size() > dead.size()
                    || (nextDead.size() == dead.size() && PlanSolver.loopCost(nextDead) >= PlanSolver.loopCost(dead) - 1e-9)) {
                Map<ResourceLocation, ResourceLocation> before = current.autoSelections();
                boolean picked = auto.keySet().stream().anyMatch(k -> !before.containsKey(k));
                if (!allowPicks || !picked)
                    break;
                allowPicks = false;
                continue;
            }
            current = next;
            dead = nextDead;
            allowPicks = true;
        }

        Map<ResourceLocation, ResourceLocation> kept = new TreeMap<>();
        for (var entry : current.autoSelections().entrySet()) {
            RecipeGraphNode node = current.built().nodes().get(entry.getKey());
            if (node != null && node.getType() != NodeType.RAW)
                kept.put(entry.getKey(), entry.getValue());
        }
        if (pivots == 0 && kept.equals(current.autoSelections()))
            return current;
        Attempt result = kept.equals(current.autoSelections()) ? current : attempt(level, goal, kept, current.autoImports());
        return new Attempt(result.built(), result.model(), result.result(), result.autoSelections(), result.autoImports(), pivots);
    }

    public static int deadLoopFixPivots(Level level, ProductionGoal goal) {
        return resolve(level, goal, SolveMode.PROPAGATION_ONLY).fixPivots();
    }

    private record DeadLoop(PlanSolver.BackEdge edge, ResourceLocation looped, ResourceLocation consumer,
                            List<ResourceLocation> members) {
    }

    private static boolean decideFixes(ProductionGoal goal, Attempt attempt, Set<PlanSolver.BackEdge> dead,
            Map<ResourceLocation, ResourceLocation> auto, Set<ResourceLocation> imported, boolean allowPicks) {
        Built built = attempt.built();
        Map<PlanSolver.BackEdge, ResourceLocation> consumerOf = new HashMap<>();
        for (ResourceLocation[] pair : built.backEdges()) {
            StructuralNode node = built.structNodes().get(pair[0]);
            String key = built.keys().keyOf().get(pair[1]);
            if (node != null && node.recipeId() != null && key != null)
                consumerOf.putIfAbsent(new PlanSolver.BackEdge(node.recipeId().toString(), key), pair[0]);
        }
        List<PlanSolver.BackEdge> sorted = new ArrayList<>(dead);
        sorted.sort(Comparator.comparing(PlanSolver.BackEdge::resource).thenComparing(PlanSolver.BackEdge::recipeId));
        List<DeadLoop> loops = new ArrayList<>();
        for (PlanSolver.BackEdge edge : sorted) {
            ResourceLocation consumer = consumerOf.get(edge);
            if (consumer == null)
                continue;
            ResourceLocation looped = idOfKey(edge.resource());
            List<ResourceLocation> members = new ArrayList<>(loopMembers(built.structNodes(), looped, consumer));
            members.sort(Comparator.comparingInt((ResourceLocation id) -> {
                RecipeGraphNode node = built.nodes().get(id);
                return node != null ? node.getDepth() : Integer.MAX_VALUE;
            }).thenComparing(ResourceLocation::toString));
            loops.add(new DeadLoop(edge, looped, consumer, members));
        }

        List<PlanSolver.BackEdge> healthy = new ArrayList<>(attempt.model().backEdges());
        healthy.removeAll(dead);
        CandidateCheck check = new CandidateCheck(goal, built, attempt.model());
        Set<ResourceLocation> handled = new HashSet<>();
        for (DeadLoop loop : loops) {
            if (!allowPicks || loop.members().stream().anyMatch(handled::contains))
                continue;
            for (ResourceLocation member : loop.members()) {
                if (goal.recipeSelections().containsKey(member) || auto.containsKey(member))
                    continue;
                ResourceLocation pick = check.firstWorking(member, healthy);
                if (pick != null) {
                    auto.put(member, pick);
                    handled.addAll(loop.members());
                    break;
                }
            }
        }
        if (!handled.isEmpty())
            return true;

        List<DeadLoop> waiting = new ArrayList<>();
        boolean changed = false;
        for (DeadLoop loop : loops) {
            if (loop.members().stream().anyMatch(handled::contains))
                continue;
            List<PlanSolver.BackEdge> others = new ArrayList<>(healthy);
            for (DeadLoop other : loops)
                if (other.members().stream().noneMatch(loop.members()::contains))
                    others.add(other.edge());
            boolean fixable = false;
            for (ResourceLocation member : allowPicks ? loop.members() : List.<ResourceLocation>of()) {
                if (!goal.recipeSelections().containsKey(member) && !auto.containsKey(member)
                        && check.firstWorking(member, others) != null) {
                    fixable = true;
                    break;
                }
            }
            if (fixable) {
                waiting.add(loop);
            } else if (importLoop(goal, loop, auto, imported)) {
                handled.addAll(loop.members());
                changed = true;
            }
        }
        for (int i = 0; !changed && i < waiting.size(); i++)
            changed = importLoop(goal, waiting.get(i), auto, imported);
        return changed;
    }

    private static boolean importLoop(ProductionGoal goal, DeadLoop loop, Map<ResourceLocation, ResourceLocation> auto,
            Set<ResourceLocation> imported) {
        ResourceLocation point = loop.looped().equals(goal.targetId()) ? loop.consumer() : loop.looped();
        if (point.equals(goal.targetId()) || goal.recipeSelections().containsKey(point) || !imported.add(point))
            return false;
        auto.remove(point);
        return true;
    }

    private static final class CandidateCheck {
        private final ProductionGoal goal;
        private final Built built;
        private final PlanSolver.Model model;
        private final Map<ResourceLocation, StructuralNode> memo;
        private final Map<String, PlanSolver.Recipe> extra = new LinkedHashMap<>();
        private final Set<String> raw;

        CandidateCheck(ProductionGoal goal, Built built, PlanSolver.Model model) {
            this.goal = goal;
            this.built = built;
            this.model = model;
            this.memo = new HashMap<>(built.structNodes());
            this.raw = new HashSet<>(model.raw());
        }

        @Nullable ResourceLocation firstWorking(ResourceLocation member, List<PlanSolver.BackEdge> free) {
            StructuralNode node = built.structNodes().get(member);
            String key = built.keys().keyOf().get(member);
            if (node == null || node.recipeId() == null || key == null)
                return null;
            List<RecipeHolder<MachineRecipe>> holders = (key.startsWith("fluid:") ? built.index().fluidRecipes()
                    : built.index().itemRecipes()).getOrDefault(member, List.of());
            for (RecipeHolder<MachineRecipe> holder : holders) {
                if (holder.id().equals(node.recipeId()))
                    continue;
                MachineRecipe recipe = holder.value();
                PlanSolver.Recipe candidate = new PlanSolver.Recipe(holder.id().toString(), recipe.duration,
                        inputsOf(recipe), outputsOf(recipe));
                if (!PlanSolver.makesOnly(candidate, key))
                    continue;
                resolveInputs(candidate);
                List<PlanSolver.Recipe> recipes = new ArrayList<>();
                for (PlanSolver.Recipe r : model.recipes())
                    if (!r.id().equals(node.recipeId().toString()))
                        recipes.add(r);
                recipes.addAll(extra.values());
                recipes.add(candidate);
                Set<String> made = PlanSolver.obtainable(recipes, raw, free);
                if (candidate.inputs().entrySet().stream().allMatch(e -> e.getValue() <= 0 || made.contains(e.getKey())))
                    return holder.id();
            }
            return null;
        }

        private void resolveInputs(PlanSolver.Recipe candidate) {
            int before = memo.size();
            for (String input : candidate.inputs().keySet()) {
                resolveStructure(built.index().itemRecipes(), built.index().fluidRecipes(),
                        input.startsWith("fluid:") ? TargetType.FLUID : TargetType.ITEM, idOfKey(input),
                        built.selections(), new HashSet<>(), memo, goal.targetId(), built.imported());
            }
            if (memo.size() != before) {
                for (var entry : memo.entrySet()) {
                    StructuralNode node = entry.getValue();
                    if (built.structNodes().containsKey(entry.getKey()) || node.recipeId() == null || node.recipe() == null)
                        continue;
                    extra.computeIfAbsent(node.recipeId().toString(), id -> new PlanSolver.Recipe(id,
                            node.recipe().duration, inputsOf(node.recipe()), outputsOf(node.recipe())));
                }
            }
            List<PlanSolver.Recipe> touched = new ArrayList<>(extra.values());
            touched.add(candidate);
            for (PlanSolver.Recipe recipe : touched) {
                for (String input : recipe.inputs().keySet()) {
                    StructuralNode node = memo.get(idOfKey(input));
                    if (node != null && node.recipeId() == null)
                        raw.add(input);
                }
            }
        }
    }

    private static Set<ResourceLocation> loopMembers(Map<ResourceLocation, StructuralNode> structNodes,
            ResourceLocation looped, ResourceLocation consumer) {
        Map<ResourceLocation, List<ResourceLocation>> consumers = new HashMap<>();
        structNodes.forEach((id, node) -> {
            for (StructInputEdge in : node.itemInputs())
                consumers.computeIfAbsent(in.childId(), k -> new ArrayList<>()).add(id);
            for (StructInputEdge in : node.fluidInputs())
                consumers.computeIfAbsent(in.childId(), k -> new ArrayList<>()).add(id);
        });
        Set<ResourceLocation> below = new HashSet<>(List.of(looped));
        Deque<ResourceLocation> queue = new ArrayDeque<>(List.of(looped));
        while (!queue.isEmpty()) {
            StructuralNode node = structNodes.get(queue.poll());
            if (node == null)
                continue;
            for (StructInputEdge in : node.itemInputs())
                if (below.add(in.childId()))
                    queue.add(in.childId());
            for (StructInputEdge in : node.fluidInputs())
                if (below.add(in.childId()))
                    queue.add(in.childId());
        }
        Set<ResourceLocation> members = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        members.add(looped);
        members.add(consumer);
        Set<ResourceLocation> above = new HashSet<>(List.of(consumer));
        queue.add(consumer);
        while (!queue.isEmpty()) {
            for (ResourceLocation next : consumers.getOrDefault(queue.poll(), List.of())) {
                if (above.add(next)) {
                    queue.add(next);
                    if (below.contains(next))
                        members.add(next);
                }
            }
        }
        return members;
    }

    public static RecipeGraph computeRecipeGraphUncached(Level level, ProductionGoal goal, SolveMode mode) {
        Attempt resolved = resolve(level, goal, mode);
        Built built = resolved.built();
        Map<ResourceLocation, RecipeGraphNode> nodes = built.nodes();
        Map<EdgeKey, GraphEdge> edges = built.edges();
        Set<ResourceLocation> cyclicResourceIds = built.cyclicResourceIds();
        PlanKeys keys = built.keys();
        Map<ResourceLocation, Double> surplusRates = propagationSurplus(nodes);
        Set<ResourceLocation> unsourced = Set.of();
        PlanSolver.Model model = resolved.model();
        boolean lpWanted = mode == SolveMode.FORCE_LP || mode == SolveMode.FAIL_LP || (mode == SolveMode.AUTO && built.coupled());
        if (lpWanted) {
            PlanSolver.Result solved = mode == SolveMode.FAIL_LP ? null : resolved.result();
            if (solved != null) {
                applyPlanSolution(goal, keys, solved, nodes, edges);
                surplusRates = keys.toIds(solved.surplus());
                unsourced = keys.toIds(solved.unsourced());
            } else if (mode != SolveMode.FAIL_LP) {
                com.mervyn.miforeman.MIForeman.LOGGER.warn(
                        "Plan LP for {} found no solution; keeping the propagated rates.", goal.targetId());
            }
        }

        for (GraphEdge edge : edges.values()) {
            RecipeGraphNode fromNode = nodes.get(edge.from());
            if (fromNode != null)
                fromNode.putOutput(edge);
            RecipeGraphNode toNode = nodes.get(edge.to());
            if (toNode != null)
                toNode.putInput(edge);
        }

        return new RecipeGraph(goal.targetId(), goal.rate(), nodes, new ArrayList<>(edges.values()), cyclicResourceIds,
                surplusRates, unsourced, model, Map.copyOf(resolved.autoSelections()), Set.copyOf(resolved.autoImports()));
    }

    private record PlanKeys(Map<ResourceLocation, String> keyOf, Map<String, ResourceLocation> idOf) {
        static PlanKeys of(ProductionGoal goal, Map<ResourceLocation, RecipeGraphNode> nodes) {
            Map<ResourceLocation, String> keyOf = new HashMap<>();
            Map<String, ResourceLocation> idOf = new HashMap<>();
            keyOf.put(goal.targetId(), typed(goal.type(), goal.targetId()));
            for (RecipeGraphNode node : nodes.values()) {
                if (node.getType() != NodeType.MACHINE || node.getRecipe() == null)
                    continue;
                for (var e : inputsOf(node.getRecipe()).keySet())
                    keyOf.putIfAbsent(idOfKey(e), e);
                for (var e : outputsOf(node.getRecipe()).keySet()) {
                    ResourceLocation id = idOfKey(e);
                    if (nodes.containsKey(id) && nodes.get(id).getType() != NodeType.MACHINE && id.equals(node.getAmbiguityOwnerId()))
                        keyOf.putIfAbsent(id, e);
                }
            }
            keyOf.forEach((id, key) -> idOf.put(key, id));
            return new PlanKeys(keyOf, idOf);
        }

        boolean isGraphResource(String key, Map<ResourceLocation, RecipeGraphNode> nodes) {
            ResourceLocation id = idOf.get(key);
            return id != null && nodes.containsKey(id) && nodes.get(id).getType() != NodeType.MACHINE;
        }

        boolean hasDemandedByproduct(Map<ResourceLocation, RecipeGraphNode> nodes) {
            for (RecipeGraphNode node : nodes.values()) {
                if (node.getType() != NodeType.MACHINE || node.getRecipe() == null)
                    continue;
                for (String out : outputsOf(node.getRecipe()).keySet()) {
                    if (isGraphResource(out, nodes) && !idOfKey(out).equals(node.getAmbiguityOwnerId()))
                        return true;
                }
            }
            return false;
        }

        PlanSolver.Model model(ProductionGoal goal, Map<ResourceLocation, RecipeGraphNode> nodes,
                List<ResourceLocation[]> backEdges, Map<ResourceLocation, StructuralNode> structNodes) {
            List<PlanSolver.Recipe> recipes = new ArrayList<>();
            Set<String> raw = new HashSet<>();
            for (RecipeGraphNode node : nodes.values()) {
                if (node.getType() == NodeType.MACHINE && node.getRecipe() != null) {
                    MachineRecipe recipe = node.getRecipe();
                    recipes.add(new PlanSolver.Recipe(node.getId().toString(), recipe.duration,
                            inputsOf(recipe), outputsOf(recipe)));
                } else if (node.getType() == NodeType.RAW && keyOf.containsKey(node.getId())) {
                    raw.add(keyOf.get(node.getId()));
                }
            }
            List<PlanSolver.BackEdge> loops = new ArrayList<>();
            for (ResourceLocation[] edge : backEdges) {
                StructuralNode consumer = structNodes.get(edge[0]);
                if (consumer != null && consumer.recipeId() != null && keyOf.containsKey(edge[1]))
                    loops.add(new PlanSolver.BackEdge(consumer.recipeId().toString(), keyOf.get(edge[1])));
            }
            return new PlanSolver.Model(recipes, raw, keyOf.get(goal.targetId()), goal.rate(), loops);
        }

        Map<ResourceLocation, Double> toIds(Map<String, Double> byKey) {
            Map<ResourceLocation, Double> result = new LinkedHashMap<>();
            byKey.forEach((key, value) -> {
                if (value > 0)
                    result.merge(idOfKey(key), value, Double::sum);
            });
            return result;
        }

        Set<ResourceLocation> toIds(Set<String> keys) {
            Set<ResourceLocation> result = new HashSet<>();
            for (String key : keys)
                result.add(idOfKey(key));
            return result;
        }
    }

    private static String typed(TargetType type, ResourceLocation id) {
        return (type == TargetType.FLUID ? "fluid:" : "item:") + id;
    }

    private static ResourceLocation idOfKey(String key) {
        return ResourceLocation.parse(key.substring(key.indexOf(':') + 1));
    }

    private static Map<String, Double> inputsOf(MachineRecipe recipe) {
        Map<String, Double> inputs = new TreeMap<>();
        for (var input : recipe.itemInputs) {
            List<Item> items = input.getInputItems();
            if (!items.isEmpty())
                inputs.merge(typed(TargetType.ITEM, BuiltInRegistries.ITEM.getKey(items.get(0))),
                        (double) input.amount() * input.probability(), Double::sum);
        }
        for (var input : recipe.fluidInputs) {
            List<Fluid> fluids = input.getInputFluids();
            if (!fluids.isEmpty())
                inputs.merge(typed(TargetType.FLUID, BuiltInRegistries.FLUID.getKey(fluids.get(0))),
                        (double) input.amount() * input.probability(), Double::sum);
        }
        return inputs;
    }

    private static Map<String, Double> outputsOf(MachineRecipe recipe) {
        Map<String, Double> outputs = new TreeMap<>();
        for (var out : recipe.itemOutputs)
            outputs.merge(typed(TargetType.ITEM, BuiltInRegistries.ITEM.getKey(out.variant().getItem())),
                    (double) out.amount() * out.probability(), Double::sum);
        for (var out : recipe.fluidOutputs)
            outputs.merge(typed(TargetType.FLUID, BuiltInRegistries.FLUID.getKey(out.fluid())),
                    (double) out.amount() * out.probability(), Double::sum);
        return outputs;
    }

    private static Map<ResourceLocation, Double> propagationSurplus(Map<ResourceLocation, RecipeGraphNode> nodes) {
        Map<ResourceLocation, Double> surplus = new LinkedHashMap<>();
        for (RecipeGraphNode machine : nodes.values()) {
            if (machine.getType() != NodeType.MACHINE)
                continue;
            MachineRecipe recipe = machine.getRecipe();
            double machineCount = machine.getMachineCount();
            if (recipe == null || machineCount <= 0 || recipe.duration <= 0)
                continue;
            double runsPerMinute = machineCount * 1200.0 / recipe.duration;
            outputsOf(recipe).forEach((key, perRun) -> {
                ResourceLocation id = idOfKey(key);
                if (!nodes.containsKey(id))
                    surplus.merge(id, runsPerMinute * perRun, Double::sum);
            });
        }
        return surplus;
    }

    private static void applyPlanSolution(ProductionGoal goal, PlanKeys keys, PlanSolver.Result solved,
            Map<ResourceLocation, RecipeGraphNode> nodes, Map<EdgeKey, GraphEdge> edges) {
        Map<ResourceLocation, Double> consumption = new HashMap<>();
        for (RecipeGraphNode machine : nodes.values()) {
            if (machine.getType() != NodeType.MACHINE || machine.getRecipe() == null)
                continue;
            MachineRecipe recipe = machine.getRecipe();
            double runs = solved.runs().getOrDefault(machine.getId().toString(), 0.0);
            double machineCount = runs * recipe.duration / 1200.0;
            machine.setMachineCount(machineCount);
            machine.setTotalEuPerTick((long) Math.ceil(machineCount * machine.getBaseEuPerTick()));
            Map<String, Double> outputs = outputsOf(recipe);
            String owner = keys.keyOf().get(machine.getAmbiguityOwnerId());
            machine.setRequiredRate(owner != null ? runs * outputs.getOrDefault(owner, 0.0) : 0.0);

            inputsOf(recipe).forEach((key, perRun) -> {
                ResourceLocation id = idOfKey(key);
                EdgeKey edgeKey = new EdgeKey(id, machine.getId());
                if (edges.containsKey(edgeKey))
                    edges.put(edgeKey, new GraphEdge(id, machine.getId(), runs * perRun));
                consumption.merge(id, runs * perRun, Double::sum);
            });
            outputs.forEach((key, perRun) -> {
                ResourceLocation id = idOfKey(key);
                EdgeKey edgeKey = new EdgeKey(machine.getId(), id);
                double flow = runs * perRun;
                if (edges.containsKey(edgeKey))
                    edges.put(edgeKey, new GraphEdge(machine.getId(), id, flow));
                else if (flow > 1e-9 && keys.isGraphResource(key, nodes))
                    edges.put(edgeKey, new GraphEdge(machine.getId(), id, flow));
            });
        }
        for (RecipeGraphNode node : nodes.values()) {
            if (node.getType() == NodeType.MACHINE)
                continue;
            if (node.getType() == NodeType.RAW) {
                String key = keys.keyOf().get(node.getId());
                node.setRequiredRate(key != null ? solved.imports().getOrDefault(key, 0.0) : 0.0);
            } else {
                double demand = node.getId().equals(goal.targetId()) ? goal.rate() : 0.0;
                node.setRequiredRate(consumption.getOrDefault(node.getId(), 0.0) + demand);
            }
        }
    }

    private record EdgeKey(ResourceLocation from, ResourceLocation to) {
    }

    private record StructInputEdge(ResourceLocation childId, double ratePerUnit) {
    }

    private record StructuralNode(
            ResourceLocation resourceId,
            @Nullable ResourceLocation recipeId,
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
            ResourceLocation rootTargetId,
            Set<ResourceLocation> imported) {
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

        boolean shouldExpand = (type == TargetType.ITEM || resourceId.equals(rootTargetId) || selections.containsKey(resourceId))
                && (resourceId.equals(rootTargetId) || !imported.contains(resourceId));

        StructuralNode result;
        if (!shouldExpand || allCandidates.isEmpty()) {
            List<ResourceLocation> ambiguityOptions = allCandidates.size() > 1
                    ? allCandidates.stream().map(RecipeHolder::id).toList()
                    : (allCandidates.size() == 1 ? List.of(allCandidates.get(0).id()) : List.of());
            ResourceLocation selectedAmbiguity = ambiguityOptions.isEmpty() || imported.contains(resourceId)
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
                double runsPerUnit = 1.0 / (outputAmount * outputProbability);
                double machineCountPerUnit = (runsPerUnit * chosenRecipe.duration) / 1200.0;
                ResourceLocation machineTypeId = BuiltInRegistries.RECIPE_TYPE.getKey(chosenRecipe.getType());
                ResourceLocation selectedAmbiguity = candidates.size() > 1
                        ? (selections.get(resourceId) != null ? selections.get(resourceId) : chosenHolder.id())
                        : null;

                Map<ResourceLocation, Double> itemRates = new LinkedHashMap<>();
                for (var input : chosenRecipe.itemInputs) {
                    List<Item> inputItems = input.getInputItems();
                    if (!inputItems.isEmpty()) {
                        ResourceLocation inputItemId = BuiltInRegistries.ITEM.getKey(inputItems.get(0));
                        itemRates.merge(inputItemId, runsPerUnit * input.amount() * input.probability(), Double::sum);
                    }
                }
                Map<ResourceLocation, Double> fluidRates = new LinkedHashMap<>();
                for (var input : chosenRecipe.fluidInputs) {
                    List<Fluid> inputFluids = input.getInputFluids();
                    if (!inputFluids.isEmpty()) {
                        ResourceLocation inputFluidId = BuiltInRegistries.FLUID.getKey(inputFluids.get(0));
                        fluidRates.merge(inputFluidId, runsPerUnit * input.amount() * input.probability(),
                                Double::sum);
                    }
                }

                List<StructInputEdge> itemInputs = itemRates.entrySet().stream()
                        .map(e -> new StructInputEdge(e.getKey(), e.getValue())).toList();
                List<StructInputEdge> fluidInputs = fluidRates.entrySet().stream()
                        .map(e -> new StructInputEdge(e.getKey(), e.getValue())).toList();

                for (StructInputEdge edge : itemInputs) {
                    resolveStructure(itemRecipes, fluidRecipes, TargetType.ITEM, edge.childId(),
                            selections, visited, structMemo, rootTargetId, imported);
                }
                for (StructInputEdge edge : fluidInputs) {
                    resolveStructure(itemRecipes, fluidRecipes, TargetType.FLUID, edge.childId(),
                            selections, visited, structMemo, rootTargetId, imported);
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

    private static DagWalk propagateRates(
            ResourceLocation rootId, double rootRate, int rootDepth,
            Map<ResourceLocation, StructuralNode> structNodes,
            Map<ResourceLocation, RecipeGraphNode> nodes,
            Map<EdgeKey, GraphEdge> edges) {

        DagWalk dag = new DagWalk(structNodes);
        dag.visit(rootId);
        Map<ResourceLocation, Set<ResourceLocation>> forwardEdges = dag.forwardEdges;
        Map<ResourceLocation, Integer> inDegree = dag.inDegree;

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
        return dag;
    }

    private static final class DagWalk {
        private final Map<ResourceLocation, StructuralNode> structNodes;
        final Map<ResourceLocation, Set<ResourceLocation>> forwardEdges = new HashMap<>();
        final Map<ResourceLocation, Integer> inDegree = new HashMap<>();
        final Set<ResourceLocation> cyclicResourceIds = new HashSet<>();
        final List<ResourceLocation[]> backEdges = new ArrayList<>();
        private final Set<ResourceLocation> onStack = new HashSet<>();
        private final Set<ResourceLocation> done = new HashSet<>();

        DagWalk(Map<ResourceLocation, StructuralNode> structNodes) {
            this.structNodes = structNodes;
        }

        void visit(ResourceLocation curr) {
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
                    // Recycling loop back-edge.
                    cyclicResourceIds.add(curr);
                    cyclicResourceIds.add(child);
                    backEdges.add(new ResourceLocation[] { curr, child });
                    continue;
                }

                if (forwardEdges.computeIfAbsent(curr, k -> new HashSet<>()).add(child)) {
                    inDegree.merge(child, 1, Integer::sum);
                }

                visit(child);
            }
            onStack.remove(curr);
            done.add(curr);
        }
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
