package com.mervyn.miforeman.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.HashMap;
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
            source.sendFailure(Component.literal("Only players can execute this command."));
            return 0;
        }

        ProductionGoal.TargetType type;
        try {
            type = ProductionGoal.TargetType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid target type: must be 'item' or 'fluid'."));
            return 0;
        }

        ProductionGoal goal = new ProductionGoal(name, type, target, rate, new HashMap<>(), Optional.empty());
        ItemStack stack = new ItemStack(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
        stack.set(ModComponents.PRODUCTION_GOAL.get(), goal);

        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        source.sendSuccess(() -> Component.literal("Successfully created and equipped MI Foreman's Clipboard with goal: " + name), true);

        return Command.SINGLE_SUCCESS;
    }

    private static int printGoal(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can execute this command."));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
            source.sendFailure(Component.literal("You must be holding a Foreman's Clipboard in your main hand."));
            return 0;
        }

        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal == null) {
            source.sendSuccess(() -> Component.literal("The held Foreman's Clipboard has no production goal defined."), false);
        } else {
            source.sendSuccess(() -> Component.literal("Held Foreman's Clipboard Production Goal:\n- Name: " + goal.name() + "\n- Target Type: " + goal.type() + "\n- Target ID: " + goal.targetId() + "\n- Rate: " + goal.rate()), false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int runPlan(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Only players can execute this command."));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
            source.sendFailure(Component.literal("You must be holding a Foreman's Clipboard in your main hand."));
            return 0;
        }

        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal == null) {
            source.sendFailure(Component.literal("The held Foreman's Clipboard has no production goal defined."));
            return 0;
        }

        FactoryPlan plan = RecipeGraphTraverser.computePlan(source.getLevel(), goal);
        ProductionGoal updatedGoal = new ProductionGoal(
                goal.name(),
                goal.type(),
                goal.targetId(),
                goal.rate(),
                goal.recipeSelections(),
                Optional.of(plan)
        );
        stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        source.sendSuccess(() -> Component.literal("Successfully calculated production plan for: " + goal.name()), true);

        source.sendSuccess(() -> Component.literal("Machines required:"), false);
        for (var req : plan.machines()) {
            source.sendSuccess(() -> Component.literal(String.format("  - %s: %.2f", req.machineId(), req.count())), false);
        }

        source.sendSuccess(() -> Component.literal("Raw inputs needed:"), false);
        for (var flow : plan.rawInputs()) {
            source.sendSuccess(() -> Component.literal(String.format("  - %s: %.2f/s (%s)", flow.resourceId(), flow.rate(), flow.type())), false);
        }

        if (!plan.ambiguities().isEmpty()) {
            source.sendSuccess(() -> Component.literal("WARNING: Unresolved ambiguities found!"), false);
            for (var amb : plan.ambiguities()) {
                boolean hasSelection = goal.recipeSelections().containsKey(amb.resourceId());
                source.sendSuccess(() -> Component.literal(String.format("  - %s (Selection: %s). Options:",
                        amb.resourceId(),
                        hasSelection ? goal.recipeSelections().get(amb.resourceId()) : "none"
                )), false);
                for (var opt : amb.recipeIds()) {
                    source.sendSuccess(() -> Component.literal("    - " + opt), false);
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
            source.sendFailure(Component.literal("Only players can execute this command."));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
            source.sendFailure(Component.literal("You must be holding a Foreman's Clipboard in your main hand."));
            return 0;
        }

        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal == null) {
            source.sendFailure(Component.literal("The held Foreman's Clipboard has no production goal defined."));
            return 0;
        }

        Map<ResourceLocation, ResourceLocation> updatedSelections = new HashMap<>(goal.recipeSelections());
        updatedSelections.put(target, recipe);

        ProductionGoal tempGoal = new ProductionGoal(
                goal.name(),
                goal.type(),
                goal.targetId(),
                goal.rate(),
                updatedSelections,
                Optional.empty()
        );
        FactoryPlan newPlan = RecipeGraphTraverser.computePlan(source.getLevel(), tempGoal);

        ProductionGoal updatedGoal = new ProductionGoal(
                goal.name(),
                goal.type(),
                goal.targetId(),
                goal.rate(),
                updatedSelections,
                Optional.of(newPlan)
        );

        stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        source.sendSuccess(() -> Component.literal(String.format("Successfully selected recipe %s for %s", recipe, target)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int printMIRecipes(CommandSourceStack source) {
        var level = source.getLevel();
        var recipeManager = level.getRecipeManager();
        int totalRecipes = 0;

        for (var recipeType : aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.getRecipeTypes()) {
            var recipes = recipeManager.getAllRecipesFor(recipeType);
            source.sendSuccess(() -> Component.literal("Recipe Type: " + recipeType.getId() + " - " + recipes.size() + " recipes"), false);
            totalRecipes += recipes.size();
            for (var recipeHolder : recipes) {
                var recipe = recipeHolder.value();
                source.sendSuccess(() -> Component.literal("  - " + recipeHolder.id() + " (" + recipe.duration + " ticks, " + recipe.eu + " EU/t)"), false);
            }
        }

        int finalTotal = totalRecipes;
        source.sendSuccess(() -> Component.literal("Total MI Recipes read: " + finalTotal), true);
        return Command.SINGLE_SUCCESS;
    }
}
