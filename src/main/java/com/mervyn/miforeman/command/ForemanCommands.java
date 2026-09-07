package com.mervyn.miforeman.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class ForemanCommands {
    public static void init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {
            event.getDispatcher().register(literal("miforeman")
                    .then(literal("goal")
                            .then(literal("create")
                                    .then(argument("name", StringArgumentType.word())
                                            .then(argument("type", StringArgumentType.word())
                                                    .then(argument("target", ResourceLocationArgument.id())
                                                            .then(argument("rate", DoubleArgumentType.doubleArg(0.0))
                                                                    .executes(context -> createGoal(
                                                                            context.getSource(),
                                                                            StringArgumentType.getString(context, "name"),
                                                                            StringArgumentType.getString(context, "type"),
                                                                            ResourceLocationArgument.getId(context, "target"),
                                                                            DoubleArgumentType.getDouble(context, "rate")
                                                                    ))
                                                            )
                                                    )
                                            )
                                    )
                            )
                            .then(literal("print")
                                    .executes(context -> printGoal(context.getSource()))
                            )
                            .then(literal("plan")
                                    .executes(context -> runPlan(context.getSource()))
                            )
                            .then(literal("select")
                                    .then(argument("target", ResourceLocationArgument.id())
                                            .then(argument("recipe", ResourceLocationArgument.id())
                                                    .executes(context -> selectRecipe(
                                                            context.getSource(),
                                                            ResourceLocationArgument.getId(context, "target"),
                                                            ResourceLocationArgument.getId(context, "recipe")
                                                    ))
                                            )
                                    )
                            )
                    )
                    .then(literal("recipes")
                            .then(literal("print")
                                    .executes(context -> printMIRecipes(context.getSource()))
                            )
                    )
            );
        });
    }

    private static int createGoal(CommandSourceStack source, String name, String typeStr, ResourceLocation target, double rate) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("miforeman.command.error.players_only"));
            return 0;
        }

        ProductionGoal.TargetType type;
        try {
            type = ProductionGoal.TargetType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("miforeman.command.error.invalid_target_type"));
            return 0;
        }

        ProductionGoal goal = new ProductionGoal(name, type, target, rate, new HashMap<>(), Optional.empty());
        ItemStack stack = new ItemStack(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
        stack.set(ModComponents.PRODUCTION_GOAL.get(), goal);

        ItemStack existing = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!existing.isEmpty() && !player.getInventory().add(existing)) {
            player.drop(existing, false);
        }

        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        source.sendSuccess(() -> Component.translatable("miforeman.command.set_goal.success", name), true);

        return Command.SINGLE_SUCCESS;
    }

    private static int printGoal(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("miforeman.command.error.players_only"));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
            source.sendFailure(Component.translatable("miforeman.command.error.not_holding_clipboard"));
            return 0;
        }

        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal == null) {
            source.sendSuccess(() -> Component.translatable("miforeman.command.error.no_goal_defined"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("miforeman.command.show_goal",
                    goal.name(), goal.type(), goal.targetId(), goal.rate()), false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int runPlan(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("miforeman.command.error.players_only"));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
            source.sendFailure(Component.translatable("miforeman.command.error.not_holding_clipboard"));
            return 0;
        }

        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal == null) {
            source.sendFailure(Component.translatable("miforeman.command.error.no_goal_defined"));
            return 0;
        }

        FactoryPlan plan = RecipeGraphTraverser.computePlan(source.getLevel(), goal);
        ProductionGoal updatedGoal = goal.withPlan(Optional.of(plan));
        stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.success", goal.name()), true);

        source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.machines_header"), false);
        for (var req : plan.machines()) {
            source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.machine_row",
                    req.machineId(), String.format(Locale.ROOT, "%.2f", req.count())), false);
        }

        source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.inputs_header"), false);
        for (var flow : plan.rawInputs()) {
            source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.input_row",
                    flow.resourceId(), String.format(Locale.ROOT, "%.2f", flow.rate()), flow.type()), false);
        }

        if (!plan.ambiguities().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.ambiguity_warning"), false);
            for (var amb : plan.ambiguities()) {
                boolean hasSelection = goal.recipeSelections().containsKey(amb.resourceId());
                source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.ambiguity_row",
                        amb.resourceId(),
                        hasSelection ? goal.recipeSelections().get(amb.resourceId()) : "none"
                ), false);
                for (var opt : amb.recipeIds()) {
                    source.sendSuccess(() -> Component.translatable("miforeman.command.calculate.ambiguity_option", opt), false);
                }
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int selectRecipe(CommandSourceStack source, ResourceLocation target, ResourceLocation recipe) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("miforeman.command.error.players_only"));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
            source.sendFailure(Component.translatable("miforeman.command.error.not_holding_clipboard"));
            return 0;
        }

        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal == null) {
            source.sendFailure(Component.translatable("miforeman.command.error.no_goal_defined"));
            return 0;
        }

        Map<ResourceLocation, ResourceLocation> updatedSelections = new HashMap<>(goal.recipeSelections());
        updatedSelections.put(target, recipe);

        ProductionGoal tempGoal = goal.withRecipeSelections(updatedSelections);
        FactoryPlan newPlan = RecipeGraphTraverser.computePlan(source.getLevel(), tempGoal);

        ProductionGoal updatedGoal = tempGoal.withPlan(Optional.of(newPlan));

        stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        source.sendSuccess(() -> Component.translatable("miforeman.command.select_recipe.success", recipe, target), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int printMIRecipes(CommandSourceStack source) {
        var level = source.getLevel();
        var recipeManager = level.getRecipeManager();
        int totalRecipes = 0;

        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> byType = RecipeGraphTraverser.groupMachineRecipesByType(recipeManager);

        for (var entry : byType.entrySet()) {
            var recipes = entry.getValue();
            source.sendSuccess(() -> Component.translatable("miforeman.command.list_recipes.type_header", entry.getKey(), recipes.size()), false);
            totalRecipes += recipes.size();
            for (var recipeHolder : recipes) {
                var recipe = recipeHolder.value();
                source.sendSuccess(() -> Component.translatable("miforeman.command.list_recipes.row", recipeHolder.id(), recipe.duration, recipe.eu), false);
            }
        }

        int finalTotal = totalRecipes;
        source.sendSuccess(() -> Component.translatable("miforeman.command.list_recipes.total", finalTotal), true);
        return Command.SINGLE_SUCCESS;
    }
}
