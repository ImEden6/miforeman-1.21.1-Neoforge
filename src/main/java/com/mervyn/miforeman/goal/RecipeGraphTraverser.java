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

    private static class SubPlan {
        final Map<ResourceLocation, Double> machineCounts = new HashMap<>();
        final Map<ResourceLocation, Double> rawInputs = new HashMap<>();
        final Map<ResourceLocation, Double> intermediates = new HashMap<>();
        final Map<ResourceLocation, List<ResourceLocation>> ambiguities = new LinkedHashMap<>();
    }

    public static FactoryPlan computePlan(Level level, ProductionGoal goal) {
        RecipeManager recipeManager = level.getRecipeManager();

        // Index all machine recipes by item and fluid outputs
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes = new HashMap<>();
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes = new HashMap<>();
        indexMachineRecipes(recipeManager, itemRecipes, fluidRecipes);

        Map<ResourceLocation, SubPlan> memo = new HashMap<>();
        Set<ResourceLocation> visited = new HashSet<>();

        SubPlan mainPlan = getSubPlan(
                recipeManager,
                itemRecipes,
                fluidRecipes,
                goal.type(),
                goal.targetId(),
                goal.recipeSelections(),
                visited,
                memo);

        // Scale the entire plan by the desired target rate
        double desiredRate = goal.rate();

        List<MachineRequirement> machinesList = mainPlan.machineCounts.entrySet().stream()
                .map(e -> new MachineRequirement(e.getKey(), e.getValue() * desiredRate))
                .toList();

        List<MaterialFlow> rawInputsList = mainPlan.rawInputs.entrySet().stream()
                .map(e -> new MaterialFlow(
                        getItemOrFluidType(e.getKey()),
                        e.getKey(),
                        e.getValue() * desiredRate))
                .toList();

        List<MaterialFlow> intermediatesList = mainPlan.intermediates.entrySet().stream()
                .map(e -> new MaterialFlow(
                        getItemOrFluidType(e.getKey()),
                        e.getKey(),
                        e.getValue() * desiredRate))
                .toList();

        List<Ambiguity> ambiguitiesList = mainPlan.ambiguities.entrySet().stream()
                .map(e -> new Ambiguity(e.getKey(), e.getValue()))
                .toList();

        return new FactoryPlan(machinesList, rawInputsList, intermediatesList, ambiguitiesList);
    }

    private static TargetType getItemOrFluidType(ResourceLocation id) {
        if (BuiltInRegistries.FLUID.containsKey(id)) {
            return TargetType.FLUID;
        }
        return TargetType.ITEM;
    }

    private static SubPlan getSubPlan(
            RecipeManager recipeManager,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes,
            TargetType type,
            ResourceLocation resourceId,
            Map<ResourceLocation, ResourceLocation> selections,
            Set<ResourceLocation> visited,
            Map<ResourceLocation, SubPlan> memo) {
        if (memo.containsKey(resourceId)) {
            return memo.get(resourceId);
        }

        SubPlan plan = new SubPlan();
        if (visited.contains(resourceId)) {
            // Cycle detected: return empty plan to prevent infinite recursion
            return plan;
        }

        visited.add(resourceId);

        List<RecipeHolder<MachineRecipe>> candidates = type == TargetType.ITEM
                ? itemRecipes.getOrDefault(resourceId, Collections.emptyList())
                : fluidRecipes.getOrDefault(resourceId, Collections.emptyList());

        if (candidates.isEmpty()) {
            // Raw input
            plan.rawInputs.put(resourceId, 1.0);
            visited.remove(resourceId);
            memo.put(resourceId, plan);
            return plan;
        }

        // Add itself to intermediates
        plan.intermediates.put(resourceId, 1.0);

        // Resolve chosen recipe
        RecipeHolder<MachineRecipe> chosenHolder = null;
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

            List<ResourceLocation> recipeIds = candidates.stream().map(RecipeHolder::id).toList();
            plan.ambiguities.put(resourceId, recipeIds);
        }

        if (chosenHolder == null) {
            chosenHolder = candidates.get(0);
        }

        MachineRecipe chosenRecipe = chosenHolder.value();
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
            visited.remove(resourceId);
            return plan;
        }

        // Calculations for 1.0 units of resourceId
        double runsPerSecond = 1.0 / (outputAmount * outputProbability);
        double machineCount = (runsPerSecond * chosenRecipe.duration) / 20.0;

        ResourceLocation machineId = BuiltInRegistries.RECIPE_TYPE.getKey(chosenRecipe.getType());
        plan.machineCounts.put(machineId, machineCount);

        // Recurse into item inputs
        for (var input : chosenRecipe.itemInputs) {
            List<Item> inputItems = input.getInputItems();
            if (!inputItems.isEmpty()) {
                Item firstItem = inputItems.get(0);
                ResourceLocation inputItemId = BuiltInRegistries.ITEM.getKey(firstItem);
                double neededInputRate = runsPerSecond * input.amount() * input.probability();

                SubPlan sub = getSubPlan(
                        recipeManager,
                        itemRecipes,
                        fluidRecipes,
                        TargetType.ITEM,
                        inputItemId,
                        selections,
                        visited,
                        memo);
                mergeScaled(plan, sub, neededInputRate);
            }
        }

        // Treat fluid inputs as raw inputs directly (do not recursively traverse them)
        for (var input : chosenRecipe.fluidInputs) {
            List<Fluid> inputFluids = input.getInputFluids();
            if (!inputFluids.isEmpty()) {
                Fluid firstFluid = inputFluids.get(0);
                ResourceLocation inputFluidId = BuiltInRegistries.FLUID.getKey(firstFluid);
                double neededInputRate = runsPerSecond * input.amount() * input.probability();

                plan.rawInputs.merge(inputFluidId, neededInputRate, Double::sum);
            }
        }

        visited.remove(resourceId);
        memo.put(resourceId, plan);
        return plan;
    }

    /**
     * Indexes loaded {@link MachineRecipe} instances by output item and fluid. Scans the vanilla
     * {@link RecipeManager} directly to include addon-defined machine recipe types.
     * <p>
     * Output candidate lists are deduplicated by recipe ID and sorted by ID.
     * Deterministic sorting ensures default recipe selection stays consistent across game loads.
     */
    private static void indexMachineRecipes(
            RecipeManager recipeManager,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes) {
        Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> itemByRecipeId = new HashMap<>();
        Map<ResourceLocation, Map<ResourceLocation, RecipeHolder<MachineRecipe>>> fluidByRecipeId = new HashMap<>();

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
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
                    fluidByRecipeId.computeIfAbsent(fluidId, k -> new HashMap<>()).put(machineHolder.id(), machineHolder);
                }
            }
        }

        sortIntoLists(itemByRecipeId, itemRecipes);
        sortIntoLists(fluidByRecipeId, fluidRecipes);
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
     * {@link RecipeManager} directly to include addon-defined machine recipe types. Used by the
     * recipe-dump command and game tests.
     */
    public static Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> groupMachineRecipesByType(RecipeManager recipeManager) {
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

    private static void mergeScaled(SubPlan target, SubPlan source, double scale) {
        for (var entry : source.machineCounts.entrySet()) {
            target.machineCounts.merge(entry.getKey(), entry.getValue() * scale, Double::sum);
        }
        for (var entry : source.rawInputs.entrySet()) {
            target.rawInputs.merge(entry.getKey(), entry.getValue() * scale, Double::sum);
        }
        for (var entry : source.intermediates.entrySet()) {
            target.intermediates.merge(entry.getKey(), entry.getValue() * scale, Double::sum);
        }
        for (var entry : source.ambiguities.entrySet()) {
            target.ambiguities.put(entry.getKey(), entry.getValue());
        }
    }

    public static RecipeGraph computeRecipeGraph(Level level, ProductionGoal goal) {
        RecipeManager recipeManager = level.getRecipeManager();

        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes = new HashMap<>();
        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes = new HashMap<>();
        indexMachineRecipes(recipeManager, itemRecipes, fluidRecipes);

        // Two phases -- mirrors computePlan()/getSubPlan()'s existing memoization pattern, which
        // buildGraph() previously didn't share (its old `visited` set was only a recursion-stack
        // cycle guard, removed again at every return, so a resourceId reached via more than one
        // demand path had its entire input subtree re-walked from scratch on every occurrence, and
        // its own supply edge got rebuilt with only that occurrence's *partial* rate each time
        // instead of the full accumulated total).
        //
        // Phase 1 resolves the DAG structure (which recipe is chosen, and its input list with
        // per-unit conversion factors) exactly once per resourceId, independent of rate.
        Map<ResourceLocation, StructuralNode> structNodes = new HashMap<>();
        resolveStructure(itemRecipes, fluidRecipes, goal.type(), goal.targetId(),
                goal.recipeSelections(), new HashSet<>(), structNodes);

        // Phase 2 propagates the goal's target rate down through that resolved DAG in topological
        // order, so every node's total rate is fully summed from all of its parents before it's
        // used to derive machine counts or pushed further down to its own children.
        Map<ResourceLocation, RecipeGraphNode> nodes = new HashMap<>();
        Map<EdgeKey, GraphEdge> edges = new LinkedHashMap<>();
        propagateRates(goal.targetId(), goal.rate(), 0, structNodes, nodes, edges);

        // finalizeResourceNode() processes parents before children (topological order), so a
        // child's RecipeGraphNode often doesn't exist yet at the moment its parent would want to
        // record an edge on it. Wiring getInputs()/getOutputs() is instead done here, once, after
        // every node and every (now fully rate-merged) edge exists: an edge's `from` node records
        // it as an output, its `to` node records it as an input -- the same convention the original
        // single-pass buildGraph() used.
        for (GraphEdge edge : edges.values()) {
            RecipeGraphNode fromNode = nodes.get(edge.from());
            if (fromNode != null) fromNode.putOutput(edge);
            RecipeGraphNode toNode = nodes.get(edge.to());
            if (toNode != null) toNode.putInput(edge);
        }

        return new RecipeGraph(goal.targetId(), goal.rate(), nodes, new ArrayList<>(edges.values()));
    }

    private record EdgeKey(ResourceLocation from, ResourceLocation to) {}

    /** One recipe input normalized per 1.0 unit/s of the owning {@link StructuralNode} resource ID. */
    private record StructInputEdge(ResourceLocation childId, double ratePerUnit) {}

    /**
     * Rate-independent graph structure for a resource ID, including the chosen recipe and
     * per-unit normalized inputs. Resolved and memoized in {@link #resolveStructure}, then
     * reused across demand paths in {@link #propagateRates}.
     */
    private record StructuralNode(
            ResourceLocation resourceId,
            @Nullable ResourceLocation recipeId,       // null => RAW (no candidate recipe, or an
                                                        // unproducible chosen recipe -- see below)
            @Nullable ResourceLocation machineTypeId,
            @Nullable MachineRecipe recipe,
            List<ResourceLocation> ambiguityOptions,
            @Nullable ResourceLocation selectedAmbiguity,
            double machineCountPerUnit,
            List<StructInputEdge> itemInputs,          // recurses via resolveStructure
            List<StructInputEdge> fluidInputs          // does NOT recurse -- always a leaf, exactly
                                                        // like the original's fluid-input handling
    ) {}

    private static @Nullable StructuralNode resolveStructure(
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> itemRecipes,
            Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> fluidRecipes,
            TargetType type,
            ResourceLocation resourceId,
            Map<ResourceLocation, ResourceLocation> selections,
            Set<ResourceLocation> visited,
            Map<ResourceLocation, StructuralNode> structMemo) {
        StructuralNode cached = structMemo.get(resourceId);
        if (cached != null) {
            return cached;
        }
        if (visited.contains(resourceId)) {
            // A genuine cycle (a recipe input chain that loops back on itself) -- never memoized,
            // so the caller treats this child as absent and drops the input edge to it entirely.
            return null;
        }
        visited.add(resourceId);

        List<RecipeHolder<MachineRecipe>> candidates = type == TargetType.ITEM
                ? itemRecipes.getOrDefault(resourceId, Collections.emptyList())
                : fluidRecipes.getOrDefault(resourceId, Collections.emptyList());

        StructuralNode result;
        if (candidates.isEmpty()) {
            result = rawStruct(resourceId);
        } else {
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
                result = rawStruct(resourceId);
            } else {
                double runsPerSecond = 1.0 / (outputAmount * outputProbability);
                double machineCountPerUnit = (runsPerSecond * chosenRecipe.duration) / 20.0;
                ResourceLocation machineTypeId = BuiltInRegistries.RECIPE_TYPE.getKey(chosenRecipe.getType());
                ResourceLocation selectedAmbiguity = candidates.size() > 1
                        ? (selections.get(resourceId) != null ? selections.get(resourceId) : chosenHolder.id())
                        : null;

                // Merge duplicate inputs (same recipe listing the same item/fluid in more than one
                // slot) by childId up front, so each structural node contributes at most one edge
                // per distinct child -- exactly the "merge instead of duplicate" fix edges need,
                // applied at the source instead of after the fact.
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
                        fluidRates.merge(inputFluidId, runsPerSecond * input.amount() * input.probability(), Double::sum);
                    }
                }

                List<StructInputEdge> itemInputs = itemRates.entrySet().stream()
                        .map(e -> new StructInputEdge(e.getKey(), e.getValue())).toList();
                List<StructInputEdge> fluidInputs = fluidRates.entrySet().stream()
                        .map(e -> new StructInputEdge(e.getKey(), e.getValue())).toList();

                // Recurse into item inputs now so they're memoized before this node is marked
                // complete -- each distinct resourceId gets walked exactly once, total.
                for (StructInputEdge edge : itemInputs) {
                    resolveStructure(itemRecipes, fluidRecipes, TargetType.ITEM, edge.childId(),
                            selections, visited, structMemo);
                }

                result = new StructuralNode(resourceId, recipeId, machineTypeId, chosenRecipe,
                        ambiguityOptions, selectedAmbiguity, machineCountPerUnit, itemInputs, fluidInputs);
            }
        }

        visited.remove(resourceId);
        structMemo.put(resourceId, result);
        return result;
    }

    private static StructuralNode rawStruct(ResourceLocation resourceId) {
        return new StructuralNode(resourceId, null, null, null, List.of(), null, 0.0, List.of(), List.of());
    }

    /** Propagates rates across the structural DAG built by {@link #resolveStructure} using Kahn's algorithm.
     *  Nodes and edges materialize once all demanding parents contribute, computing rate and depth
     *  in a single pass. */
    private static void propagateRates(
            ResourceLocation rootId, double rootRate, int rootDepth,
            Map<ResourceLocation, StructuralNode> structNodes,
            Map<ResourceLocation, RecipeGraphNode> nodes,
            Map<EdgeKey, GraphEdge> edges) {

        Map<ResourceLocation, Integer> remainingIndegree = new HashMap<>();
        for (StructuralNode node : structNodes.values()) {
            for (StructInputEdge edge : node.itemInputs()) {
                if (structNodes.containsKey(edge.childId())) {
                    remainingIndegree.merge(edge.childId(), 1, Integer::sum);
                }
            }
        }

        Map<ResourceLocation, Double> pendingRate = new HashMap<>();
        Map<ResourceLocation, Integer> pendingDepth = new HashMap<>();
        pendingRate.put(rootId, rootRate);
        pendingDepth.put(rootId, rootDepth);

        Deque<ResourceLocation> ready = new ArrayDeque<>();
        ready.add(rootId);

        while (!ready.isEmpty()) {
            ResourceLocation resourceId = ready.poll();
            StructuralNode structNode = structNodes.get(resourceId);
            double rate = pendingRate.get(resourceId);
            int depth = pendingDepth.get(resourceId);

            finalizeResourceNode(rootId, resourceId, structNode, rate, depth, nodes, edges);

            if (structNode.recipeId() == null) {
                continue; // RAW leaf, nothing further to propagate to
            }

            for (StructInputEdge edge : structNode.itemInputs()) {
                if (!structNodes.containsKey(edge.childId())) {
                    continue; // dropped cyclic child, see resolveStructure
                }

                pendingRate.merge(edge.childId(), rate * edge.ratePerUnit(), Double::sum);
                pendingDepth.merge(edge.childId(), depth + 2, Math::min);

                int remaining = remainingIndegree.merge(edge.childId(), -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(edge.childId());
                }
            }
        }
    }

    private static void finalizeResourceNode(
            ResourceLocation rootId, ResourceLocation resourceId, StructuralNode structNode,
            double rate, int depth,
            Map<ResourceLocation, RecipeGraphNode> nodes, Map<EdgeKey, GraphEdge> edges) {

        if (structNode.recipeId() == null) {
            nodes.put(resourceId, new RecipeGraphNode(
                    resourceId, NodeType.RAW, null, null,
                    rate, 0,
                    List.of(), null, null, depth
            ));
            return;
        }

        NodeType nodeType = resourceId.equals(rootId) ? NodeType.TARGET : NodeType.INTERMEDIATE;
        RecipeGraphNode resNode = new RecipeGraphNode(
                resourceId, nodeType, null, null,
                rate, 0,
                structNode.ambiguityOptions(), structNode.selectedAmbiguity(), resourceId, depth
        );
        nodes.put(resourceId, resNode);

        // A multi-output recipe can be the chosen producer for more than one resourceId (e.g. a
        // Distillation Tower's two outputs, each independently demanded) -- accumulate onto the
        // existing machine node rather than overwriting it, exactly like the original merge branch.
        // Whichever resourceId gets here first permanently owns the MACHINE node's ambiguity list
        // (ambiguityOwnerId is set once at creation and never touched by this merge branch) -- the
        // click handler must resolve the owner from that field rather than guessing an output edge.
        double machineCount = rate * structNode.machineCountPerUnit();
        RecipeGraphNode machNode = nodes.get(structNode.recipeId());
        if (machNode != null) {
            machNode.setRequiredRate(machNode.getRequiredRate() + rate);
            machNode.setMachineCount(machNode.getMachineCount() + machineCount);
            machNode.setDepth(Math.min(machNode.getDepth(), depth + 1));
        } else {
            machNode = new RecipeGraphNode(
                    structNode.recipeId(), NodeType.MACHINE, structNode.machineTypeId(), structNode.recipe(),
                    rate, machineCount,
                    structNode.ambiguityOptions(), structNode.selectedAmbiguity(), resourceId,
                    depth + 1
            );
            nodes.put(structNode.recipeId(), machNode);
        }

        edges.merge(new EdgeKey(structNode.recipeId(), resourceId), new GraphEdge(structNode.recipeId(), resourceId, rate),
                (oldEdge, newEdge) -> new GraphEdge(oldEdge.from(), oldEdge.to(), oldEdge.rate() + newEdge.rate()));

        for (StructInputEdge input : structNode.itemInputs()) {
            double inputRate = rate * input.ratePerUnit();
            edges.merge(new EdgeKey(input.childId(), structNode.recipeId()), new GraphEdge(input.childId(), structNode.recipeId(), inputRate),
                    (oldEdge, newEdge) -> new GraphEdge(oldEdge.from(), oldEdge.to(), oldEdge.rate() + newEdge.rate()));
        }

        // Fluid inputs are always leaves (never recursed into, matching the original), and unlike
        // item resourceIds they aren't routed through propagateRates' topological queue at all --
        // several different machines can each independently need the same fluid, so accumulate
        // directly onto an existing RAW node the same way the original inline handling did.
        for (StructInputEdge input : structNode.fluidInputs()) {
            double inputRate = rate * input.ratePerUnit();
            RecipeGraphNode fluidNode = nodes.get(input.childId());
            if (fluidNode != null) {
                fluidNode.setRequiredRate(fluidNode.getRequiredRate() + inputRate);
                fluidNode.setDepth(Math.min(fluidNode.getDepth(), depth + 2));
            } else {
                fluidNode = new RecipeGraphNode(
                        input.childId(), NodeType.RAW, null, null,
                        inputRate, 0,
                        List.of(), null, null, depth + 2
                );
                nodes.put(input.childId(), fluidNode);
            }

            edges.merge(new EdgeKey(input.childId(), structNode.recipeId()), new GraphEdge(input.childId(), structNode.recipeId(), inputRate),
                    (oldEdge, newEdge) -> new GraphEdge(oldEdge.from(), oldEdge.to(), oldEdge.rate() + newEdge.rate()));
        }
    }

    private RecipeGraphTraverser() {
    }
}
