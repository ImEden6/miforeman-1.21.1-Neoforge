package com.mervyn.miforeman.network;

import aztech.modern_industrialization.machines.MachineBlockEntity;
import com.mervyn.miforeman.Config;
import com.mervyn.miforeman.goal.MachineScanner;
import com.mervyn.miforeman.goal.PlanWorker;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@EventBusSubscriber(modid = com.mervyn.miforeman.MIForeman.MODID)
public class GoalUpdateHandler {
    // 2 ticks. These handlers run from GUI clicks, so fast clicks must not drop.
    private static final PacketRateLimiter LIMITER = new PacketRateLimiter(2);
    private static final Executor PLAN_THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MI Foreman server plan");
        thread.setDaemon(true);
        return thread;
    });
    /** Server thread only. One per player, so a newer save drops that player's older pending plan. */
    private static final Map<UUID, PlanWorker> WORKERS = new HashMap<>();

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

            update(player, payload, PLAN_THREAD, player.server);
        });
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PlanWorker worker = WORKERS.remove(event.getEntity().getUUID());
        if (worker != null)
            worker.cancel();
    }

    /** Saves the goal at once. An uncached plan is computed on {@code background} and patched in on
     *  the server thread later, so a big plan doesn't stall the server or overwrite newer writes. */
    public static void update(ServerPlayer player, GoalUpdatePayload payload, Executor background, Executor main) {
        if (!holdsClipboard(player, payload)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        ProductionGoal newGoal = payload.goal();
        PlanWorker worker = WORKERS.computeIfAbsent(player.getUUID(), id -> new PlanWorker(background, main));
        ProductionGoal.FactoryPlan cached = RecipeGraphTraverser.cachedPlan(newGoal);
        if (cached != null) {
            worker.cancel();
            apply(player, payload, cached);
            return;
        }
        apply(player, payload, null);
        RecipeGraphTraverser.prepareRecipeIndex(level);
        worker.submit(() -> RecipeGraphTraverser.computePlan(level, newGoal), (plan, error) -> {
            if (error != null) {
                com.mervyn.miforeman.MIForeman.LOGGER.warn("Couldn't calculate the plan for {}", newGoal.targetId(), error);
                return;
            }
            if (!player.hasDisconnected())
                patchPlan(player, payload, plan);
        });
    }

    /** Applies a finished plan only if the goal still asks for it. */
    private static void patchPlan(ServerPlayer player, GoalUpdatePayload payload, ProductionGoal.FactoryPlan plan) {
        if (!holdsClipboard(player, payload)) {
            return;
        }
        ItemStack stack = player.getItemInHand(payload.hand());
        ProductionGoal current = stack.get(ModComponents.PRODUCTION_GOAL.get());
        ProductionGoal planned = payload.goal();
        if (current != null && current.type() == planned.type() && current.targetId().equals(planned.targetId())
                && current.rate() == planned.rate() && current.recipeSelections().equals(planned.recipeSelections())) {
            stack.set(ModComponents.PRODUCTION_GOAL.get(), current.withPlan(Optional.of(plan)));
        }
    }

    private static boolean holdsClipboard(ServerPlayer player, GoalUpdatePayload payload) {
        ItemStack stack = player.getItemInHand(payload.hand());
        return !stack.isEmpty() && stack.is(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
    }

    /** A null plan keeps the clipboard's current one until the new plan is ready. */
    private static void apply(ServerPlayer player, GoalUpdatePayload payload, @Nullable ProductionGoal.FactoryPlan plan) {
        if (!holdsClipboard(player, payload)) {
            return;
        }
        ItemStack stack = player.getItemInHand(payload.hand());
        ServerLevel level = player.serverLevel();
        ProductionGoal newGoal = payload.goal();
        ProductionGoal oldGoal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        List<GlobalPos> validatedLinked = validateLinkedMachines(
                level, player.blockPosition(), MachineScanner.linkValidationRadius(
                        Config.AUTOLINK_SCAN_RADIUS_CHUNKS.get(), Config.AUTOLINK_SCAN_MAX_RADIUS_CHUNKS.get()),
                newGoal.linkedMachines(), oldGoal != null ? oldGoal.linkedMachines() : null);

        Optional<ProductionGoal.FactoryPlan> keptPlan = oldGoal != null ? oldGoal.plan() : Optional.empty();
        ProductionGoal updatedGoal = newGoal
                .withPlan(plan != null ? Optional.of(plan) : keptPlan)
                .withLinkedMachines(validatedLinked, newGoal.machineLinkHistory());
        stack.set(ModComponents.PRODUCTION_GOAL.get(), updatedGoal);
    }

    /** Already-linked positions pass. New ones must be in this dimension, loaded, in scan radius, and hold a machine. */
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
