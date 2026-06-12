package com.mervyn.miforeman.network;

import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Optional;

public class GoalUpdateHandler {
    public static void handle(final GoalUpdatePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                stack = player.getItemInHand(InteractionHand.OFF_HAND);
            }
            if (!stack.isEmpty() && stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                ProductionGoal newGoal = payload.goal();
                ProductionGoal.FactoryPlan plan = RecipeGraphTraverser.computePlan(player.level(), newGoal);
                ProductionGoal updatedGoal = new ProductionGoal(
                        newGoal.name(),
                        newGoal.type(),
                        newGoal.targetId(),
                        newGoal.rate(),
                        newGoal.recipeSelections(),
                        Optional.of(plan),
                        newGoal.perHour(),
                        newGoal.threshold(),
                        newGoal.linkedMachines()
                );
                stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
            }
        });
    }
}
