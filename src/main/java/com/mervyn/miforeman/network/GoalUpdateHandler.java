package com.mervyn.miforeman.network;

import aztech.modern_industrialization.machines.MachineBlockEntity;
import com.mervyn.miforeman.Config;
import com.mervyn.miforeman.goal.MachineScanner;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class GoalUpdateHandler {
    // 2 ticks (0.1s) -- these are single-click-driven from the GUI (see MonitoringState's
    // applyLink/applyUnlink/applyReject callbacks), so a real user's fast back-to-back clicks (e.g.
    // link then immediately unlink) must not get silently dropped. Kept short since computePlan's
    // cost is now bounded by the graph's node count rather than its demand-path count (see
    // RecipeGraphTraverser's memoized buildGraph), so this only needs to blunt a packet flood, not
    // rate-limit ordinary use.
    private static final PacketRateLimiter LIMITER = new PacketRateLimiter(2);

    public static void handle(final GoalUpdatePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Player rawPlayer = context.player();
            if (!(rawPlayer instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!LIMITER.tryAcquire(player.getUUID(), level.getGameTime())) {
                return;
            }

            ItemStack stack = player.getItemInHand(payload.hand());
            if (!stack.isEmpty() && stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                ProductionGoal newGoal = payload.goal();
                ProductionGoal.FactoryPlan plan = RecipeGraphTraverser.computePlan(level, newGoal);

                ProductionGoal oldGoal = stack.get(ModComponents.PRODUCTION_GOAL.get());
                List<GlobalPos> validatedLinked = validateLinkedMachines(
                        level, player.blockPosition(), Config.AUTOLINK_SCAN_RADIUS_CHUNKS.get(),
                        newGoal.linkedMachines(), oldGoal != null ? oldGoal.linkedMachines() : null);

                ProductionGoal updatedGoal = newGoal
                        .withPlan(Optional.of(plan))
                        .withLinkedMachines(validatedLinked, newGoal.machineLinkHistory());
                stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
            }
        });
    }

    /**
     * Validates proposed linked machine positions on the server. Previously linked positions pass through,
     * while new positions must be in the current dimension, loaded, within scan radius, and contain a machine block entity.
     */
    private static List<GlobalPos> validateLinkedMachines(
            ServerLevel level, BlockPos playerPos, int radiusChunks,
            List<GlobalPos> newLinked, @Nullable List<GlobalPos> oldLinked) {
        Set<GlobalPos> alreadyKnown = oldLinked != null ? new HashSet<>(oldLinked) : Set.of();
        List<GlobalPos> validated = new ArrayList<>(newLinked.size());
        for (GlobalPos pos : newLinked) {
            if (alreadyKnown.contains(pos)) {
                validated.add(pos);
                continue;
            }
            if (pos.dimension().equals(level.dimension())
                    && level.isLoaded(pos.pos())
                    && MachineScanner.isWithinScanRadius(playerPos, pos.pos(), radiusChunks)
                    && level.getBlockEntity(pos.pos()) instanceof MachineBlockEntity) {
                validated.add(pos);
            }
        }
        return validated;
    }
}
