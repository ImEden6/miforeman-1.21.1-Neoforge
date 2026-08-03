package com.mervyn.miforeman.network;

import com.mervyn.miforeman.Config;
import com.mervyn.miforeman.goal.MachineScanner;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraph;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Set;

public class ScanPacketHandlers {
    public static void handleRequest(final ScanRequestPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            ServerLevel level = player.serverLevel();
            ProductionGoal goal = payload.goal();

            RecipeGraph graph = goal.plan().map(ProductionGoal.FactoryPlan::graph).orElse(null);
            if (graph == null) {
                context.reply(new ScanResultPayload(List.of()));
                return;
            }

            Set<ResourceLocation> recipeIndex = MachineScanner.buildRecipeIndex(graph);
            int radius = Config.AUTOLINK_SCAN_RADIUS_CHUNKS.get();
            List<MachineScanner.ScanCandidate> found = MachineScanner.scan(level, player.blockPosition(), radius, recipeIndex);

            List<ScanResultPayload.Candidate> candidates = found.stream()
                    .map(c -> new ScanResultPayload.Candidate(c.pos(), c.machineId(), c.recipeId()))
                    .toList();
            context.reply(new ScanResultPayload(candidates));
        });
    }

    public static void handleResponse(final ScanResultPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> com.mervyn.miforeman.client.ClientAccess.handleScanResult(payload));
    }
}
