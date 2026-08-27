package com.mervyn.miforeman.network;

import aztech.modern_industrialization.machines.MachineBlockEntity;
import com.mervyn.miforeman.goal.MachineStatus;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.ServerMonitoringManager;
import com.mervyn.miforeman.goal.ServerMonitoringManager.MachineTracker;
import com.mervyn.miforeman.registry.ModItems;
import com.mervyn.miforeman.registry.ModComponents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

public class MonitoringPacketHandlers {

    public static void handleRequest(final RequestMonitoringUpdatePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = player.getItemInHand(payload.hand());
            if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                return;
            }

            ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
            if (goal == null) {
                return;
            }

            long windowTicks = goal.perHour() ? 72000 : 1200;

            List<LiveMonitoringPayload.MachineStatusData> list = new ArrayList<>();

            // 1. Calculate actual rate maps for all linked machines
            Map<GlobalPos, Map<ResourceLocation, Double>> machineRates = new HashMap<>();
            Map<ResourceLocation, Double> totalRates = new HashMap<>();

            for (GlobalPos pos : goal.linkedMachines()) {
                ServerLevel machineLevel = player.server.getLevel(pos.dimension());
                if (machineLevel == null || !machineLevel.isLoaded(pos.pos())) {
                    continue;
                }
                BlockEntity be = machineLevel.getBlockEntity(pos.pos());
                if (be instanceof MachineBlockEntity) {
                    MachineTracker tracker = ServerMonitoringManager.trackerFor(pos);
                    Map<ResourceLocation, Double> rates = ServerMonitoringManager.getActualRates(tracker, machineLevel, machineLevel.getGameTime(), windowTicks);
                    machineRates.put(pos, rates);
                    for (var entry : rates.entrySet()) {
                        totalRates.merge(entry.getKey(), entry.getValue(), Double::sum);
                    }
                }
            }

            // 2. Compute final statuses and package data
            for (GlobalPos pos : goal.linkedMachines()) {
                ServerLevel machineLevel = player.server.getLevel(pos.dimension());
                if (machineLevel == null || !machineLevel.isLoaded(pos.pos())) {
                    continue;
                }
                BlockEntity be = machineLevel.getBlockEntity(pos.pos());
                if (be instanceof MachineBlockEntity machine) {
                    MachineTracker tracker = ServerMonitoringManager.trackerFor(pos);
                    MachineStatus status = tracker.status;

                    Map<ResourceLocation, Double> rates = machineRates.getOrDefault(pos, Map.of());
                    ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(machine.getBlockState().getBlock());

                    // If active (GREEN), check for underperformance (YELLOW)
                    if (status == MachineStatus.GREEN) {
                        boolean isUnderperforming = false;
                        for (var entry : rates.entrySet()) {
                            ResourceLocation resourceId = entry.getKey();
                            double actualTotal = totalRates.getOrDefault(resourceId, 0.0);
                            double expected = ServerMonitoringManager.getExpectedRate(goal, resourceId);
                            if (expected > 0.0 && actualTotal < expected * goal.threshold()) {
                                isUnderperforming = true;
                                break;
                            }
                        }
                        if (isUnderperforming) {
                            status = MachineStatus.YELLOW;
                        }
                    }

                    // Determine primary output rate to display
                    double primaryRate = 0.0;
                    for (var entry : rates.entrySet()) {
                        ResourceLocation res = entry.getKey();
                        double expected = ServerMonitoringManager.getExpectedRate(goal, res);
                        if (expected > 0.0 || goal.targetId().equals(res)) {
                            primaryRate = entry.getValue(); // Display the rate of the planned output
                            break;
                        }
                    }
                    if (primaryRate == 0.0 && !rates.isEmpty()) {
                        primaryRate = rates.values().iterator().next(); // Fallback to first output
                    }

                    // lastRecipeId (actually crafting) and saturatedRecipeId (blocked, would craft
                    // once its output clears) are mutually exclusive -- whichever is set is the
                    // recipe worth showing the player.
                    ResourceLocation displayRecipeId = tracker.lastRecipeId != null ? tracker.lastRecipeId : tracker.saturatedRecipeId;
                    list.add(new LiveMonitoringPayload.MachineStatusData(pos, status, primaryRate, blockId, Optional.ofNullable(displayRecipeId)));
                }
            }

            // Send response back to player
            context.reply(new LiveMonitoringPayload(list));
        });
    }

    public static void handleResponse(final LiveMonitoringPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            com.mervyn.miforeman.client.ClientAccess.handleLiveMonitoring(payload);
        });
    }
}
