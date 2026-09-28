package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.network.LiveMonitoringPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live status of every linked machine working one recipe, folded into what a single graph
 * node can show: the worst status among them, and how many are running.
 *
 * <p>Worst rather than majority, because the graph's job is surfacing problems: one starved
 * machine among four should not hide behind three green ones. {@link #running}/{@link #total}
 * is what explains a red node whose output still looks fine (e.g. a spare machine idling).
 */
public record RecipeLiveSummary(MachineStatus worst, int running, int total) {

    /** Groups {@code data} by recipe id. Machines with no recipe id are left out: they have
     *  no node to belong to. */
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

    /** GREEN and YELLOW both have an active craft; YELLOW is only running below the plan's rate. */
    private static boolean isRunning(MachineStatus status) {
        return status == MachineStatus.GREEN || status == MachineStatus.YELLOW;
    }

    private RecipeLiveSummary combine(RecipeLiveSummary other) {
        // MachineStatus is declared worst-first, so the lower ordinal wins.
        MachineStatus combined = worst.ordinal() <= other.worst.ordinal() ? worst : other.worst;
        return new RecipeLiveSummary(combined, running + other.running, total + other.total);
    }
}
