package com.mervyn.miforeman.client.gui;

import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.client.WorldHighlightRenderer;
import com.mervyn.miforeman.client.gui.widget.ReviewListPanel;
import com.mervyn.miforeman.goal.MachineLinkHistory;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import com.mervyn.miforeman.network.ScanResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State container for machine review and live monitoring operations.
 */
class MonitoringState {
    final List<GlobalPos> linkedMachines = new ArrayList<>();
    final List<GlobalPos> rejectedMachines = new ArrayList<>();
    MachineLinkHistory machineLinkHistory = MachineLinkHistory.EMPTY;
    final List<LiveMonitoringPayload.MachineStatusData> liveData = new ArrayList<>();
    final List<ScanResultPayload.Candidate> lastScanResults = new ArrayList<>();
    boolean showRejected = false;
    boolean showInWorldHighlights;
    private int tickCount = 0;

    private MonitoringState() {
        // WorldHighlightRenderer's on/off state is static and outlives any screen's
        // lifecycle
        // (highlights are meant to keep rendering after the clipboard closes) -- read
        // the live
        // value here instead of hardcoding false, or the button lies about the real
        // state every
        // time the clipboard is reopened.
        this.showInWorldHighlights = WorldHighlightRenderer.isEnabled();
    }

    static MonitoringState fromGoal(ProductionGoal goal) {
        MonitoringState state = new MonitoringState();
        state.linkedMachines.addAll(goal.linkedMachines());
        state.machineLinkHistory = goal.machineLinkHistory();
        state.rejectedMachines.addAll(goal.rejectedMachines());
        return state;
    }

    static MonitoringState defaults() {
        return new MonitoringState();
    }

    void applyLink(GlobalPos pos, Runnable onChange) {
        boolean wasLinked = linkedMachines.contains(pos);
        if (!wasLinked)
            linkedMachines.add(pos);
        rejectedMachines.remove(pos); // linking always clears a sticky rejection
        machineLinkHistory = machineLinkHistory.withToggle(pos, wasLinked, true);
        onChange.run();
    }

    void applyUnlink(GlobalPos pos, Runnable onChange) {
        boolean wasLinked = linkedMachines.contains(pos);
        linkedMachines.remove(pos);
        machineLinkHistory = machineLinkHistory.withToggle(pos, wasLinked, false);
        onChange.run();
    }

    void applyReject(GlobalPos pos, Runnable onChange) {
        if (!rejectedMachines.contains(pos))
            rejectedMachines.add(pos);
        onChange.run();
    }

    void applyUnreject(GlobalPos pos, Runnable onChange) {
        rejectedMachines.remove(pos);
        onChange.run();
    }

    void applyLinkHistoryResult(MachineLinkHistory.UndoResult result, Runnable onChange) {
        machineLinkHistory = result.history();
        if (result.linked()) {
            if (!linkedMachines.contains(result.pos()))
                linkedMachines.add(result.pos());
        } else {
            linkedMachines.remove(result.pos());
        }
        onChange.run();
    }

    void setScanResults(List<ScanResultPayload.Candidate> candidates) {
        this.lastScanResults.clear();
        this.lastScanResults.addAll(candidates);
    }

    void setLiveData(List<LiveMonitoringPayload.MachineStatusData> data) {
        this.liveData.clear();
        this.liveData.addAll(data);
    }

    /**
     * Increments poll counter and returns true every 20 ticks to trigger a
     * monitoring payload request.
     */
    boolean tickAndShouldPoll() {
        tickCount++;
        if (tickCount >= 20) {
            tickCount = 0;
            return true;
        }
        return false;
    }

    List<ReviewListPanel.ReviewRow> buildReviewRows() {
        List<ReviewListPanel.ReviewRow> rows = new ArrayList<>();
        Set<GlobalPos> seen = new HashSet<>();

        Map<GlobalPos, LiveMonitoringPayload.MachineStatusData> liveByPos = new HashMap<>();
        for (LiveMonitoringPayload.MachineStatusData entry : liveData) {
            liveByPos.put(entry.pos(), entry);
        }

        Map<GlobalPos, ScanResultPayload.Candidate> candidatesByPos = new HashMap<>();
        for (ScanResultPayload.Candidate candidate : lastScanResults) {
            candidatesByPos.put(candidate.pos(), candidate);
        }

        for (GlobalPos pos : linkedMachines) {
            ResourceLocation machineId = resolveMachineId(pos);
            LiveMonitoringPayload.MachineStatusData live = liveByPos.get(pos);
            String productLabel = live == null ? null
                    : live.recipeId().map(MonitoringState::resolveProductLabel).orElse(null);
            if (productLabel == null) {
                ScanResultPayload.Candidate candidate = candidatesByPos.get(pos);
                if (candidate != null) {
                    productLabel = resolveProductLabel(candidate.recipeId());
                    if (machineId.equals(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "unknown"))) {
                        machineId = candidate.machineId();
                    }
                }
            }
            rows.add(new ReviewListPanel.ReviewRow(pos, machineId, true, rejectedMachines.contains(pos), false,
                    productLabel));
            seen.add(pos);
        }

        for (ScanResultPayload.Candidate candidate : lastScanResults) {
            if (seen.contains(candidate.pos()))
                continue;
            boolean isRejected = rejectedMachines.contains(candidate.pos());
            if (isRejected && !showRejected)
                continue;
            String productLabel = resolveProductLabel(candidate.recipeId());
            rows.add(new ReviewListPanel.ReviewRow(candidate.pos(), candidate.machineId(), false, isRejected, true,
                    productLabel));
            seen.add(candidate.pos());
        }

        return rows;
    }

    void updateWorldHighlightPositions(List<ReviewListPanel.ReviewRow> rows) {
        List<GlobalPos> linked = new ArrayList<>();
        List<GlobalPos> candidates = new ArrayList<>();
        for (ReviewListPanel.ReviewRow row : rows) {
            if (row.linked())
                linked.add(row.pos());
            else if (row.isNewCandidate())
                candidates.add(row.pos());
        }
        WorldHighlightRenderer.setPositions(linked, candidates);
    }

    private static ResourceLocation resolveMachineId(GlobalPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension().equals(pos.dimension()) && mc.level.isLoaded(pos.pos())) {
            var be = mc.level.getBlockEntity(pos.pos());
            if (be != null) {
                return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos.pos()).getBlock());
            }
        }
        return ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "unknown");
    }

    /**
     * Resolves a recipe ID to formatted product names, or null if the recipe cannot
     * be found.
     */
    static @Nullable String resolveProductLabel(ResourceLocation recipeId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;
        var holder = mc.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof MachineRecipe recipe))
            return null;

        List<String> names = new ArrayList<>();
        for (var output : recipe.itemOutputs) {
            if (output.amount() > 0 && output.probability() > 0) {
                names.add(DisplayFormat.formatId(BuiltInRegistries.ITEM.getKey(output.variant().getItem())));
            }
        }
        for (var output : recipe.fluidOutputs) {
            if (output.amount() > 0 && output.probability() > 0) {
                names.add(DisplayFormat.formatId(BuiltInRegistries.FLUID.getKey(output.fluid())));
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }
}
