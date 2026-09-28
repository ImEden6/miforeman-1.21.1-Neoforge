package com.mervyn.miforeman.client.gui;

import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.client.WorldHighlightRenderer;
import com.mervyn.miforeman.client.gui.widget.ReviewListPanel;
import com.mervyn.miforeman.goal.MachineLinkHistory;
import com.mervyn.miforeman.goal.MachineScanner;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import com.mervyn.miforeman.network.ScanResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
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
    /** Radius the player picked for the next scan, or {@link MachineScanner#DEFAULT_RADIUS}.
     *  Lives only as long as this screen session; never written back to config. */
    int scanRadiusChunks = MachineScanner.DEFAULT_RADIUS;
    /** Radius the last scan actually ran at, as the server reported it; 0 before any scan. */
    int lastScanRadiusChunks = 0;
    boolean showRejected = false;
    boolean showInWorldHighlights;
    /** Which hand's clipboard this state belongs to. Set by ClipboardScreen right after
     *  construction so payload updates target the active clipboard. */
    InteractionHand hand = InteractionHand.MAIN_HAND;
    private int tickCount = 0;
    /** The goal's recipe graph, computed once in {@link #fromGoal}. Backs {@link #endProductNames}.
     *  Null when there's no client level yet, or for a {@link #defaults()} state with no goal at all. */
    private @Nullable com.mervyn.miforeman.goal.RecipeGraph graph;

    private MonitoringState() {
        // WorldHighlightRenderer on/off state is static and outlives individual screen lifecycles.
        // Read the live value here so reopened screens reflect current state accurately.
        this.showInWorldHighlights = WorldHighlightRenderer.isEnabled();
    }

    static MonitoringState fromGoal(ProductionGoal goal) {
        MonitoringState state = new MonitoringState();
        state.linkedMachines.addAll(goal.linkedMachines());
        state.machineLinkHistory = goal.machineLinkHistory();
        state.rejectedMachines.addAll(goal.rejectedMachines());

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            state.graph = com.mervyn.miforeman.goal.RecipeGraphTraverser.computeRecipeGraph(mc.level, goal);
        }
        return state;
    }

    static MonitoringState defaults() {
        return new MonitoringState();
    }

    /** Refreshes the cached graph for {@link #endProductNames}. ClipboardScreen constructs
     *  one MonitoringState up front and reuses it across edits, so refreshing avoids stale
     *  graphs when a goal changes. Called from ClipboardScreen.buildStepMonitor() with the
     *  computed graph before list screens open. */
    void refreshGraph(@Nullable com.mervyn.miforeman.goal.RecipeGraph graph) {
        this.graph = graph;
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
            rejectedMachines.remove(result.pos()); // linking always clears a sticky rejection, same as applyLink
        } else {
            linkedMachines.remove(result.pos());
        }
        onChange.run();
    }

    /** Replaces the scan results and returns the candidates worth pointing out in the world:
     *  ones the previous scan didn't find, that aren't linked or rejected already. Rescanning
     *  the same area therefore pings nothing new. */
    List<GlobalPos> setScanResults(List<ScanResultPayload.Candidate> candidates, int radiusChunks) {
        Set<GlobalPos> known = new HashSet<>(linkedMachines);
        known.addAll(rejectedMachines);
        for (ScanResultPayload.Candidate previous : lastScanResults) {
            known.add(previous.pos());
        }
        List<GlobalPos> newlyFound = new ArrayList<>();
        for (ScanResultPayload.Candidate candidate : candidates) {
            if (!known.contains(candidate.pos())) {
                newlyFound.add(candidate.pos());
            }
        }

        this.lastScanResults.clear();
        this.lastScanResults.addAll(candidates);
        this.lastScanRadiusChunks = radiusChunks;
        // The server capped the pick: show the radius that actually ran, not the one asked for.
        if (scanRadiusChunks != MachineScanner.DEFAULT_RADIUS && radiusChunks < scanRadiusChunks) {
            scanRadiusChunks = radiusChunks;
        }
        return newlyFound;
    }

    /** Moves the next scan's radius by {@code delta} chunks. Starting from "default", it steps
     *  from the radius the default last resolved to, or the local config as a best guess before
     *  any scan (exact in singleplayer; a dedicated server may differ, and the scan corrects it). */
    void stepScanRadius(int delta) {
        int from = scanRadiusChunks != MachineScanner.DEFAULT_RADIUS ? scanRadiusChunks
                : lastScanRadiusChunks > 0 ? lastScanRadiusChunks
                : com.mervyn.miforeman.Config.AUTOLINK_SCAN_RADIUS_CHUNKS.get();
        scanRadiusChunks = Math.max(1, Math.min(from + delta, MachineScanner.MAX_SCAN_RADIUS));
    }

    net.minecraft.network.chat.Component scanRadiusLabel() {
        if (scanRadiusChunks != MachineScanner.DEFAULT_RADIUS) {
            return net.minecraft.network.chat.Component.translatable("miforeman.scan.radius", scanRadiusChunks);
        }
        return lastScanRadiusChunks > 0
                ? net.minecraft.network.chat.Component.translatable("miforeman.scan.radius_default_known", lastScanRadiusChunks)
                : net.minecraft.network.chat.Component.translatable("miforeman.scan.radius_default");
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
            ResourceLocation recipeId = live == null ? null : live.recipeId().orElse(null);
            String productLabel = recipeId == null ? null : resolveProductLabel(recipeId);
            if (productLabel == null) {
                ScanResultPayload.Candidate candidate = candidatesByPos.get(pos);
                if (candidate != null) {
                    recipeId = candidate.recipeId();
                    productLabel = resolveProductLabel(recipeId);
                    if (machineId.equals(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "unknown"))) {
                        machineId = candidate.machineId();
                    }
                }
            }
            rows.add(new ReviewListPanel.ReviewRow(pos, machineId, true, rejectedMachines.contains(pos), false,
                    productLabel, recipeId));
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
                    productLabel, candidate.recipeId()));
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

    /** Formatted names of every resource between {@code recipeId}'s machine and this goal's
     *  final target, inclusive. Used for search: see
     *  {@link com.mervyn.miforeman.goal.RecipeGraphTraverser#collectUpstreamResourceIds}. Empty
     *  when the graph hasn't been computed yet, {@code recipeId} is null, or it isn't a machine
     *  node in this goal's plan (e.g. a linked machine crafting something unrelated). */
    List<String> endProductNames(@Nullable ResourceLocation recipeId) {
        if (graph == null || recipeId == null) {
            return List.of();
        }
        return com.mervyn.miforeman.goal.RecipeGraphTraverser.collectUpstreamResourceIds(graph, recipeId).stream()
                .map(DisplayFormat::formatId)
                .toList();
    }

    /** Search text for one row: machine name, immediate product, and every resource between this
     *  machine and the goal's target (see {@link #endProductNames}). Shared across screens to
     *  keep matching behavior consistent. */
    List<String> rowSearchableTexts(ResourceLocation machineId, @Nullable String productLabel, @Nullable ResourceLocation recipeId) {
        List<String> texts = new ArrayList<>();
        texts.add(DisplayFormat.formatId(machineId));
        if (productLabel != null) {
            texts.add(productLabel);
        }
        texts.addAll(endProductNames(recipeId));
        return texts;
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
