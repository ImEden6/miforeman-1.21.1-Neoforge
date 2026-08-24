package com.mervyn.miforeman.network;

import aztech.modern_industrialization.machines.MachineBlockEntity;
import com.mervyn.miforeman.Config;
import com.mervyn.miforeman.goal.MachineScanner;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
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

            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (stack.isEmpty() || !stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                stack = player.getItemInHand(InteractionHand.OFF_HAND);
            }
            if (!stack.isEmpty() && stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get())) {
                ProductionGoal newGoal = payload.goal();
                ProductionGoal.FactoryPlan plan = RecipeGraphTraverser.computePlan(level, newGoal);

                ProductionGoal oldGoal = stack.get(ModComponents.PRODUCTION_GOAL.get());
                List<BlockPos> validatedLinked = validateLinkedMachines(
                        level, player.blockPosition(), Config.AUTOLINK_SCAN_RADIUS_CHUNKS.get(),
                        newGoal.linkedMachines(), oldGoal != null ? oldGoal.linkedMachines() : null);

                ProductionGoal updatedGoal = new ProductionGoal(
                        newGoal.name(),
                        newGoal.type(),
                        newGoal.targetId(),
                        newGoal.rate(),
                        newGoal.recipeSelections(),
                        Optional.of(plan),
                        newGoal.perHour(),
                        newGoal.threshold(),
                        validatedLinked,
                        newGoal.graphLayout(),
                        newGoal.machineLinkHistory(),
                        newGoal.rejectedMachines(),
                        newGoal.uiState()
                );
                stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
            }
        });
    }

    /**
     * Filters {@code newLinked} down to positions the server can actually vouch for, since
     * {@link GoalUpdatePayload} is a whole-object client-to-server sync and the client could
     * otherwise inject arbitrary positions (letting {@link MonitoringPacketHandlers} report back
     * machine data for anywhere, bypassing the scan's proximity gate). A position already present
     * in {@code oldLinked} was already accepted on a prior sync and passes through unconditionally;
     * a newly-added position is kept only if it's loaded, within the same scan-radius bound
     * {@link MachineScanner#scan} enforces, and actually a machine.
     */
    private static List<BlockPos> validateLinkedMachines(
            ServerLevel level, BlockPos playerPos, int radiusChunks,
            List<BlockPos> newLinked, @Nullable List<BlockPos> oldLinked) {
        Set<BlockPos> alreadyKnown = oldLinked != null ? new HashSet<>(oldLinked) : Set.of();
        List<BlockPos> validated = new ArrayList<>(newLinked.size());
        for (BlockPos pos : newLinked) {
            if (alreadyKnown.contains(pos)) {
                validated.add(pos);
                continue;
            }
            if (level.isLoaded(pos)
                    && MachineScanner.isWithinScanRadius(playerPos, pos, radiusChunks)
                    && level.getBlockEntity(pos) instanceof MachineBlockEntity) {
                validated.add(pos);
            }
        }
        return validated;
    }
}
