package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.ProductionGoal.Ambiguity;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.MachineRequirement;
import com.mervyn.miforeman.goal.ProductionGoal.MaterialFlow;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
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

    private static final Map<GraphCacheKey, RecipeGraph> GRAPH_CACHE = new HashMap<>();

    public static void clearGraphCache() {
        GRAPH_CACHE.clear();
    }

    public static RecipeGraph computeRecipeGraph(Level level, ProductionGoal goal) {
        GraphCacheKey key = new GraphCacheKey(goal.type(), goal.targetId(), goal.rate(),
                new HashMap<>(goal.recipeSelections()));
        RecipeGraph cached = GRAPH_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        RecipeManager recipeManager = level.getRecipeManager();

        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes = new HashMap<>();
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes = new HashMap<>();
        indexMachineRecipes(level, recipeManager, itemRecipes, fluidRecipes);

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
        propagateRates(goal.targetId(), goal.rate(), 0, structNodes, nodes, edges);

        for (GraphEdge edge : edges.values()) {
            RecipeGraphNode fromNode = nodes.get(edge.from());
            if (fromNode != null)
                fromNode.putOutput(edge);
            RecipeGraphNode toNode = nodes.get(edge.to());
            if (toNode != null)
                toNode.putInput(edge);
        }

        RecipeGraph result = new RecipeGraph(goal.targetId(), goal.rate(), nodes, new ArrayList<>(edges.values()));
        GRAPH_CACHE.put(key, result);
        return result;
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
            result = new StructuralNode(resourceId, null, null, null, ambiguityOptions, selections.get(resourceId), 0.0, List.of(), List.of());
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
                result = new StructuralNode(resourceId, null, null, null, ambiguityOptions, selections.get(resourceId), 0.0, List.of(), List.of());
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
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes = new HashMap<>();
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes = new HashMap<>();
        indexMachineRecipes(level, recipeManager, itemRecipes, fluidRecipes);
        List<RecipeHolder<MachineRecipe>> list = itemRecipes.get(resourceId);
        if (list != null && !list.isEmpty()) {
            return list;
        }
        return fluidRecipes.getOrDefault(resourceId, List.of());
    }

    private static void propagateRates(
            ResourceLocation rootId, double rootRate, int rootDepth,
            Map<ResourceLocation, StructuralNode> structNodes,
            Map<ResourceLocation, RecipeGraphNode> nodes,
            Map<EdgeKey, GraphEdge> edges) {

        Map<ResourceLocation, Set<ResourceLocation>> forwardEdges = new HashMap<>();
        Map<ResourceLocation, Integer> inDegree = new HashMap<>();
        Set<ResourceLocation> onStack = new HashSet<>();
        Set<ResourceLocation> done = new HashSet<>();
        collectDag(rootId, structNodes, forwardEdges, inDegree, onStack, done);

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
            Set<ResourceLocation> done) {

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
                continue;
            }

            if (forwardEdges.computeIfAbsent(curr, k -> new HashSet<>()).add(child)) {
                inDegree.merge(child, 1, Integer::sum);
            }

            collectDag(child, structNodes, forwardEdges, inDegree, onStack, done);
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
