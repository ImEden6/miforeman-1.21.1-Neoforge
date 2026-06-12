package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.goal.ProductionGoal.Ambiguity;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.MachineRequirement;
import com.mervyn.miforeman.goal.ProductionGoal.MaterialFlow;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import aztech.modern_industrialization.machines.init.MIMachineRecipeTypes;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

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

        for (var recipeType : MIMachineRecipeTypes.getRecipeTypes()) {
            for (var holder : recipeManager.getAllRecipesFor(recipeType)) {
                MachineRecipe recipe = holder.value();
                for (var output : recipe.itemOutputs) {
                    if (output.amount() > 0 && output.probability() > 0) {
                        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(output.variant().getItem());
                        itemRecipes.computeIfAbsent(itemId, k -> new ArrayList<>()).add(holder);
                    }
                }
                for (var output : recipe.fluidOutputs) {
                    if (output.amount() > 0 && output.probability() > 0) {
                        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(output.fluid());
                        fluidRecipes.computeIfAbsent(fluidId, k -> new ArrayList<>()).add(holder);
                    }
                }
            }
        }

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

    private RecipeGraphTraverser() {
    }
}
