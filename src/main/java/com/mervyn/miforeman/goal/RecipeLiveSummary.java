package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.network.LiveMonitoringPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live status of the machines working one recipe, as one graph node shows it. The worst status
 * wins, so one starved machine can't hide behind green ones.
 */
public record RecipeLiveSummary(MachineStatus worst, int running, int total) {

    /** Machines with no recipe id are left out, since they have no node. */
    public static Map<ResourceLocation, RecipeLiveSummary> byRecipe(List<LiveMonitoringPayload.MachineStatusData> data) {
        Map<ResourceLocation, RecipeLiveSummary> result = new HashMap<>();
        for (LiveMonitoringPayload.MachineStatusData entry : data) {
            entry.recipeId().ifPresent(recipeId -> result.merge(recipeId, of(entry.status()), RecipeLiveSummary::combine));
        }
        return result;
    }

    private static RecipeLiveSummary of(MachineStatus status) {
        return new RecipeLiveSummary(status, isRunning(status) ? 1 : 0, 1);
    }

    /** YELLOW still has an active craft, only below the plan's rate. */
    private static boolean isRunning(MachineStatus status) {
        return status == MachineStatus.GREEN || status == MachineStatus.YELLOW;
    }

    private RecipeLiveSummary combine(RecipeLiveSummary other) {
        // MachineStatus is declared worst-first, so the lower ordinal wins.
        MachineStatus combined = worst.ordinal() <= other.worst.ordinal() ? worst : other.worst;
        return new RecipeLiveSummary(combined, running + other.running, total + other.total);
    }
}
