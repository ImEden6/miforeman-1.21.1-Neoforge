package com.mervyn.miforeman.network;

import com.mervyn.miforeman.Config;
import com.mervyn.miforeman.goal.MachineScanner;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Set;

public class ScanPacketHandlers {
    // Chunk scans cost more than goal recomputes, so allow one a second.
    private static final PacketRateLimiter LIMITER = new PacketRateLimiter(20);

    public static void handleRequest(final ScanRequestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!LIMITER.tryAcquire(player.getUUID(), level.getGameTime())) {
                return;
            }
            ProductionGoal goal = payload.goal();

            // The plan graph is not sent over the network because it holds live MachineRecipe
            // references, so rebuild it here.
            RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

            Set<ResourceLocation> recipeIndex = MachineScanner.buildRecipeIndex(graph);
            int radius = MachineScanner.effectiveScanRadius(payload.radiusChunks(),
                    Config.AUTOLINK_SCAN_RADIUS_CHUNKS.get(), Config.AUTOLINK_SCAN_MAX_RADIUS_CHUNKS.get());
            List<MachineScanner.ScanCandidate> found = MachineScanner.scan(level, player.blockPosition(), radius, recipeIndex);

            List<ScanResultPayload.Candidate> candidates = found.stream()
                    .map(c -> new ScanResultPayload.Candidate(c.pos(), c.machineId(), c.recipeId()))
                    .toList();
            context.reply(new ScanResultPayload(candidates, radius));
        });
    }

    public static void handleResponse(final ScanResultPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> com.mervyn.miforeman.client.ClientAccess.handleScanResult(payload));
    }
}
