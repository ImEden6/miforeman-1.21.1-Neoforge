package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.network.GoalUpdatePayload;
import com.mervyn.miforeman.network.RequestMonitoringUpdatePayload;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import com.mervyn.miforeman.client.gui.blockui.DefineGoalWindow;
import com.mervyn.miforeman.client.gui.widget.GraphCanvas;
import com.mervyn.miforeman.client.gui.widget.DetailCard;
import com.mervyn.miforeman.client.gui.widget.ReviewListPanel;
import com.mervyn.miforeman.client.WorldHighlightRenderer;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import com.mervyn.miforeman.goal.GraphLayoutState;
import com.mervyn.miforeman.goal.MachineLinkHistory;
import com.mervyn.miforeman.network.ScanRequestPayload;
import com.mervyn.miforeman.network.ScanResultPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;

import java.util.*;

public class ClipboardScreen extends Screen {
    // --- GUI Texture Resources ---
    private static final ResourceLocation TEX_MAIN = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_main.png");
    private static final ResourceLocation TEX_CLIP = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_clip.png");

    // --- 9-Slice Constants ---
    private static final int MAIN_TEX_SIZE = 64;
    private static final int MAIN_BORDER = 6;

    // --- Clip Accent (rotated to sit on the left edge, like a landscape
    // clipboard's spring clip -- see tools/gen_clipboard_textures.py) ---
    private static final int CLIP_WIDTH = 16;
    private static final int CLIP_HEIGHT = 32;
    private static final int CLIP_OVERHANG = 6;

    // --- Layout Constants ---
    // The wizard fills the window down to SCREEN_MARGIN on each side, never shrinking
    // below the original MIN_GUI_WIDTH/MIN_GUI_HEIGHT design size on tiny windows.
    // Widened/flattened from the old 380x280 so the board reads as a landscape
    // clipboard rather than a stretched portrait one.
    private static final int MIN_GUI_WIDTH = 440;
    private static final int MIN_GUI_HEIGHT = 230;
    private static final int SCREEN_MARGIN = 20;
    private static final int PADDING = 8;

    // --- Text Colors ---
    private static final int COLOR_TITLE = 0xFFDAA520;
    private static final int COLOR_LABEL = 0xFF8B7355;
    private static final int COLOR_ERROR = 0xFFCC3333;
    private static final int COLOR_GREEN = 0xFF2E7D32;
    private static final int COLOR_CYAN = 0xFF006080;
    private static final int COLOR_AMBER = 0xFF9A6C00;
    private static final int COLOR_TEXT = 0xFF3A2A18;
    private static final int COLOR_MUTED = 0xFF8A7A68;

    // --- Step Constants ---
    private static final int STEP_DEFINE_GOAL = 0;
    private static final int STEP_REVIEW_PLAN = 1;
    private static final int STEP_MONITOR = 2;

    // --- Step Indicator Layout ---
    private static final int STEP_INDICATOR_Y = 8;
    private static final int STEP_DOT_RADIUS = 4;
    private static final int STEP_DOT_GAP = 20;

    private int currentStep = STEP_DEFINE_GOAL;
    private boolean hasExistingGoal = false;
    private boolean stepInitialized = false;

    private String goalName;
    private TargetType targetType;
    private String targetIdStr;
    private double rate;
    private final Map<ResourceLocation, ResourceLocation> recipeSelections = new HashMap<>();
    private boolean perHour;
    private double threshold;
    private final List<BlockPos> linkedMachines = new ArrayList<>();
    private GraphLayoutState graphLayout = GraphLayoutState.EMPTY;
    private MachineLinkHistory machineLinkHistory = MachineLinkHistory.EMPTY;
    private final List<BlockPos> rejectedMachines = new ArrayList<>();

    private FactoryPlan currentPlan;
    private String errorMessage;

    private ResourceLocation selectedNodeId = null;
    private GraphCanvas graphCanvas;
    private DetailCard detailCard;
    private double cameraX, cameraY;
    private float cameraZoom = 1.0f;
    private boolean detailCardCollapsed = false;
    private Button toggleDetailButton;
    private Button undoLayoutButton, redoLayoutButton;

    private Button nextButton;
    private Button backButton;
    private final List<LiveMonitoringPayload.MachineStatusData> liveData = new ArrayList<>();
    private int tickCount = 0;

    private boolean isMinimized = false;
    private Button toggleModeButton;

    private final List<ScanResultPayload.Candidate> lastScanResults = new ArrayList<>();
    private boolean showRejected = false;
    private boolean showInWorldHighlights = false;
    private ReviewListPanel reviewListPanel;

    public ClipboardScreen(ItemStack stack) {
        super(Component.literal("Clipboard Goal Editor"));
        
        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        this.hasExistingGoal = (goal != null);
        this.currentPlan = (goal != null) ? goal.plan().orElse(null) : null;

        if (goal != null) {
            this.goalName = goal.name();
            this.targetType = goal.type();
            this.targetIdStr = goal.targetId().toString();
            this.rate = goal.rate();
            this.recipeSelections.putAll(goal.recipeSelections());
            this.perHour = goal.perHour();
            this.threshold = goal.threshold();
            this.linkedMachines.addAll(goal.linkedMachines());
            this.graphLayout = goal.graphLayout();
            this.machineLinkHistory = goal.machineLinkHistory();
            this.rejectedMachines.addAll(goal.rejectedMachines());

            // Adjust rate back to per hour for display if perHour is enabled
            if (this.perHour) {
                this.rate = this.rate * 60.0;
            }
        } else {
            this.goalName = "Quantum Production";
            this.targetType = TargetType.ITEM;
            this.targetIdStr = "modern_industrialization:quantum_upgrade";
            this.rate = 1.0;
            this.perHour = false;
            this.threshold = 0.8;
        }
    }

    private int guiWidth() {
        return Math.max(MIN_GUI_WIDTH, this.width - SCREEN_MARGIN * 2);
    }

    private int guiHeight() {
        return Math.max(MIN_GUI_HEIGHT, this.height - SCREEN_MARGIN * 2);
    }

    @Override
    protected void init() {
        super.init();
        if (!stepInitialized) {
            this.currentStep = hasExistingGoal ? STEP_MONITOR : STEP_DEFINE_GOAL;
            // FactoryPlan.graph is deliberately excluded from FactoryPlan.CODEC/STREAM_CODEC
            // (see ProductionGoal.java), so a plan loaded from a saved goal always decodes
            // with graph == null even though currentPlan itself is non-null. Recompute
            // whenever the graph is missing, not just when the whole plan is, or
            // GraphCanvas never gets built when backing up from Monitor into Review Plan.
            if (hasExistingGoal && (this.currentPlan == null || this.currentPlan.graph() == null)) {
                computePlan();
            }
            stepInitialized = true;
        }
        rebuildStep(currentStep);
    }

    private void rebuildStep(int step) {
        this.clearWidgets();
        this.errorMessage = null;

        int guiWidth = guiWidth();
        int guiHeight = guiHeight();
        int left = (this.width - guiWidth) / 2;
        int top = (this.height - guiHeight) / 2;

        toggleModeButton = Button.builder(
            Component.literal(this.isMinimized ? "Edit" : "View"),
            b -> {
                this.isMinimized = !this.isMinimized;
                rebuildStep(this.currentStep);
            }
        ).bounds(left + guiWidth - 54, top + 8, 46, 14).build();
        this.addRenderableWidget(toggleModeButton);

        if (this.isMinimized) {
            return;
        }

        switch (step) {
            case STEP_DEFINE_GOAL -> openDefineGoalWindow();
            case STEP_REVIEW_PLAN -> buildStepReviewPlan();
            case STEP_MONITOR -> buildStepMonitor();
        }
    }

    /**
     * BlockUI trial for this step (see .scratch/blockui-define-goal-trial/map.md) --
     * pushed as a layer on top of this still-alive Screen rather than built as widgets here.
     * ClipboardScreen doesn't receive input again until the layer is popped (Cancel/Next),
     * since Minecraft only routes input to the current top-of-stack screen.
     */
    private void openDefineGoalWindow() {
        openDefineGoalWindow(null);
    }

    /**
     * Reopens the window with a server/traversal-side error already showing -- used when
     * {@link #computePlan()} fails for a reason {@link GoalFormValidation} can't catch client-side
     * (e.g. the target item exists but has no reachable recipe path). Mirrors the vanilla form's old
     * recovery path: the resubmitted values already pass {@link GoalFormValidation}, so Next stays
     * enabled and the player can just press it again (or edit a field) to retry.
     */
    private void openDefineGoalWindow(String initialError) {
        DefineGoalWindow window = new DefineGoalWindow(
                guiWidth(), guiHeight(),
                this.goalName, this.targetType, this.targetIdStr, this.rate, this.perHour, this.threshold, initialError,
                result -> {
                    this.goalName = result.goalName();
                    this.targetType = result.targetType();
                    this.targetIdStr = result.targetIdStr();
                    this.rate = result.rate();
                    this.perHour = result.perHour();
                    this.threshold = result.threshold();
                    computePlan();
                    if (this.errorMessage == null && this.currentPlan != null) {
                        this.selectedNodeId = null;
                        goToStep(STEP_REVIEW_PLAN);
                    } else {
                        openDefineGoalWindow(this.errorMessage);
                    }
                },
                this::onClose
        );
        window.openAsLayer();
    }

    private void goToStep(int step) {
        this.currentStep = step;
        rebuildStep(step);
    }

    private void buildStepReviewPlan() {
        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;

        int contentX = left + PADDING + MAIN_BORDER + 2;
        int contentY = top + PADDING + MAIN_BORDER + 18;
        int contentW = guiWidth() - (PADDING + MAIN_BORDER) * 2 - 4;

        // Top-down layout, derived from actual row heights rather than hardcoded offsets that can
        // drift out of sync (title/button-row and button-row/canvas overlaps both traced back to
        // exactly that -- see .claude/plans/3-things-define-goal-flickering-starlight.md).
        int titleRowHeight = 16; // matches the "+16" gap already used after the title in renderStepMonitor
        int topButtonRowHeight = 12;
        int rowGap = 4;

        int topButtonRowY = contentY + titleRowHeight;
        int canvasY = topButtonRowY + topButtonRowHeight + rowGap;
        int btnY = top + guiHeight() - PADDING - MAIN_BORDER - 22;
        int contentH = btnY - rowGap - canvasY;

        if (this.currentPlan != null && this.currentPlan.graph() != null) {
            int detailWidth = detailCardCollapsed ? 0 : (int) (contentW * 0.42);
            int canvasWidth = contentW - (detailCardCollapsed ? 0 : detailWidth + 6);

            graphCanvas = new GraphCanvas(contentX, canvasY, canvasWidth, contentH,
                    this.currentPlan.graph(), this.graphLayout, cameraX, cameraY, cameraZoom,
                    selectedNodeId,
                    nodeId -> {
                        selectedNodeId = nodeId;
                        if (detailCard != null) {
                            detailCard.setNode(this.currentPlan.graph().node(nodeId));
                        }
                    },
                    layout -> {
                        this.graphLayout = layout;
                        if (undoLayoutButton != null) undoLayoutButton.active = graphCanvas.canUndo();
                        if (redoLayoutButton != null) redoLayoutButton.active = graphCanvas.canRedo();
                    },
                    (panX, panY, zoomLevel) -> {
                        this.cameraX = panX;
                        this.cameraY = panY;
                        this.cameraZoom = zoomLevel;
                    }
            );
            this.addRenderableWidget(graphCanvas);

            if (!detailCardCollapsed) {
                RecipeGraphNode selectedNode = selectedNodeId != null ? this.currentPlan.graph().node(selectedNodeId) : null;
                detailCard = new DetailCard(contentX + canvasWidth + 6, canvasY, detailWidth, contentH, selectedNode, this.currentPlan, this.perHour, (resId, choiceRecipeId) -> {
                    this.recipeSelections.put(resId, choiceRecipeId);
                    computePlan();
                    if (selectedNodeId != null && this.currentPlan.graph().node(selectedNodeId) == null) {
                        selectedNodeId = null;
                    }
                    rebuildStep(STEP_REVIEW_PLAN);
                });
                this.addRenderableWidget(detailCard);
            } else {
                detailCard = null;
            }

            toggleDetailButton = Button.builder(Component.literal(detailCardCollapsed ? "Show Details" : "Hide Details"),
                    b -> {
                        detailCardCollapsed = !detailCardCollapsed;
                        rebuildStep(STEP_REVIEW_PLAN);
                    }).bounds(contentX + contentW - 90, topButtonRowY, 90, 12).build();
            this.addRenderableWidget(toggleDetailButton);

            undoLayoutButton = Button.builder(Component.literal("Undo"), b -> graphCanvas.undo())
                    .bounds(contentX, topButtonRowY, 40, 12).build();
            undoLayoutButton.active = graphCanvas.canUndo();
            this.addRenderableWidget(undoLayoutButton);

            redoLayoutButton = Button.builder(Component.literal("Redo"), b -> graphCanvas.redo())
                    .bounds(contentX + 44, topButtonRowY, 40, 12).build();
            redoLayoutButton.active = graphCanvas.canRedo();
            this.addRenderableWidget(redoLayoutButton);
        }

        backButton = Button.builder(Component.literal("<- Back"), b -> goToStep(STEP_DEFINE_GOAL))
                .bounds(contentX, btnY, 80, 16).build();
        this.addRenderableWidget(backButton);

        nextButton = Button.builder(Component.literal("Save & Monitor"), b -> {
            save();
            goToStep(STEP_MONITOR);
        }).bounds(contentX + contentW - 110, btnY, 110, 16).build();
        this.addRenderableWidget(nextButton);
    }

    private void buildStepMonitor() {
        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;

        int contentX = left + PADDING + MAIN_BORDER + 2;
        int contentY = top + PADDING + MAIN_BORDER + 18;
        int contentW = guiWidth() - (PADDING + MAIN_BORDER) * 2 - 4;
        int contentH = guiHeight() - (PADDING + MAIN_BORDER) * 2 - 40;

        Button scanButton = Button.builder(Component.literal("Scan Nearby"), b -> triggerScan())
                .bounds(contentX, contentY + 44, 90, 14).build();
        this.addRenderableWidget(scanButton);

        Button showRejectedButton = Button.builder(Component.literal(showRejected ? "Hide Rejected" : "Show Rejected"),
                b -> {
                    showRejected = !showRejected;
                    rebuildStep(STEP_MONITOR);
                }).bounds(contentX + 94, contentY + 44, 90, 14).build();
        this.addRenderableWidget(showRejectedButton);

        Button undoLinkButton = Button.builder(Component.literal("Undo"), b -> {
            MachineLinkHistory.UndoResult result = machineLinkHistory.undo();
            if (result != null) applyLinkHistoryResult(result);
        }).bounds(contentX + 188, contentY + 44, 40, 14).build();
        undoLinkButton.active = !machineLinkHistory.undoStack().isEmpty();
        this.addRenderableWidget(undoLinkButton);

        Button redoLinkButton = Button.builder(Component.literal("Redo"), b -> {
            MachineLinkHistory.UndoResult result = machineLinkHistory.redo();
            if (result != null) applyLinkHistoryResult(result);
        }).bounds(contentX + 232, contentY + 44, 40, 14).build();
        redoLinkButton.active = !machineLinkHistory.redoStack().isEmpty();
        this.addRenderableWidget(redoLinkButton);

        Button highlightsButton = Button.builder(
                Component.literal(showInWorldHighlights ? "Highlights: On" : "Highlights: Off"),
                b -> {
                    showInWorldHighlights = !showInWorldHighlights;
                    WorldHighlightRenderer.setEnabled(showInWorldHighlights);
                    rebuildStep(STEP_MONITOR);
                }).bounds(contentX + 276, contentY + 44, 90, 14).build();
        this.addRenderableWidget(highlightsButton);

        List<ReviewListPanel.ReviewRow> rows = buildReviewRows();
        reviewListPanel = new ReviewListPanel(contentX, contentY + 62, contentW, contentH - 62, rows,
                this::handleReviewToggle, this::handleRejectCandidateRequest, this::handleUnreject);
        this.addRenderableWidget(reviewListPanel);
        updateWorldHighlightPositions(rows);

        int btnY = top + guiHeight() - PADDING - MAIN_BORDER - 22;
        backButton = Button.builder(Component.literal("<- Back"), b -> goToStep(STEP_REVIEW_PLAN))
                .bounds(contentX, btnY, 80, 16).build();
        this.addRenderableWidget(backButton);

        nextButton = Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(contentX + contentW - 80, btnY, 80, 16).build();
        this.addRenderableWidget(nextButton);

        new RequestMonitoringUpdatePayload().sendToServer();
    }

    private List<ReviewListPanel.ReviewRow> buildReviewRows() {
        List<ReviewListPanel.ReviewRow> rows = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();

        for (BlockPos pos : linkedMachines) {
            ResourceLocation machineId = resolveMachineId(pos);
            rows.add(new ReviewListPanel.ReviewRow(pos, machineId, true, rejectedMachines.contains(pos), false));
            seen.add(pos);
        }

        for (ScanResultPayload.Candidate candidate : lastScanResults) {
            if (seen.contains(candidate.pos())) continue;
            boolean isRejected = rejectedMachines.contains(candidate.pos());
            if (isRejected && !showRejected) continue;
            rows.add(new ReviewListPanel.ReviewRow(candidate.pos(), candidate.machineId(), false, isRejected, true));
            seen.add(candidate.pos());
        }

        return rows;
    }

    private ResourceLocation resolveMachineId(BlockPos pos) {
        if (this.minecraft != null && this.minecraft.level != null && this.minecraft.level.isLoaded(pos)) {
            var be = this.minecraft.level.getBlockEntity(pos);
            if (be != null) {
                return BuiltInRegistries.BLOCK.getKey(this.minecraft.level.getBlockState(pos).getBlock());
            }
        }
        return ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "unknown");
    }

    private void triggerScan() {
        if (this.currentPlan == null) return;
        ProductionGoal goal = buildCurrentGoal();
        new ScanRequestPayload(goal).sendToServer();
    }

    public void updateScanResults(List<ScanResultPayload.Candidate> candidates) {
        this.lastScanResults.clear();
        this.lastScanResults.addAll(candidates);
        if (currentStep == STEP_MONITOR) rebuildStep(STEP_MONITOR);
    }

    private void handleReviewToggle(ReviewListPanel.ReviewRow row) {
        if (row.linked()) {
            applyUnlink(row.pos());
        } else {
            applyLink(row.pos());
        }
    }

    private void handleRejectCandidateRequest(ReviewListPanel.ReviewRow row) {
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                confirmed -> {
                    Minecraft.getInstance().setScreen(this);
                    if (confirmed) applyReject(row.pos());
                },
                Component.literal("Reject this machine?"),
                Component.literal("It will be hidden from future scans until un-rejected. Confirm?")
        ));
    }

    private void handleUnreject(ReviewListPanel.ReviewRow row) {
        applyUnreject(row.pos());
    }

    private void applyLink(BlockPos pos) {
        boolean wasLinked = linkedMachines.contains(pos);
        if (!wasLinked) linkedMachines.add(pos);
        rejectedMachines.remove(pos); // linking always clears a sticky rejection
        machineLinkHistory = machineLinkHistory.withToggle(pos, wasLinked, true);
        syncGoal();
        rebuildStep(STEP_MONITOR);
    }

    private void applyUnlink(BlockPos pos) {
        boolean wasLinked = linkedMachines.contains(pos);
        linkedMachines.remove(pos);
        machineLinkHistory = machineLinkHistory.withToggle(pos, wasLinked, false);
        syncGoal();
        rebuildStep(STEP_MONITOR);
    }

    private void applyReject(BlockPos pos) {
        if (!rejectedMachines.contains(pos)) rejectedMachines.add(pos);
        syncGoal();
        rebuildStep(STEP_MONITOR);
    }

    private void applyUnreject(BlockPos pos) {
        rejectedMachines.remove(pos);
        syncGoal();
        rebuildStep(STEP_MONITOR);
    }

    private void applyLinkHistoryResult(MachineLinkHistory.UndoResult result) {
        machineLinkHistory = result.history();
        if (result.linked()) {
            if (!linkedMachines.contains(result.pos())) linkedMachines.add(result.pos());
        } else {
            linkedMachines.remove(result.pos());
        }
        syncGoal();
        rebuildStep(STEP_MONITOR);
    }

    private void updateWorldHighlightPositions(List<ReviewListPanel.ReviewRow> rows) {
        List<BlockPos> linked = new ArrayList<>();
        List<BlockPos> candidates = new ArrayList<>();
        for (ReviewListPanel.ReviewRow row : rows) {
            if (row.linked()) linked.add(row.pos());
            else if (row.isNewCandidate()) candidates.add(row.pos());
        }
        WorldHighlightRenderer.setPositions(linked, candidates);
    }

    private ProductionGoal buildCurrentGoal() {
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        double adjustedRate = this.rate / (this.perHour ? 60.0 : 1.0);
        return new ProductionGoal(
                this.goalName, this.targetType, targetRes, adjustedRate, this.recipeSelections,
                this.currentPlan != null ? Optional.of(this.currentPlan) : Optional.empty(),
                this.perHour, this.threshold, this.linkedMachines, this.graphLayout,
                this.machineLinkHistory, this.rejectedMachines
        );
    }

    private void syncGoal() {
        if (this.errorMessage != null || this.goalName == null || this.goalName.isEmpty()
                || this.targetIdStr == null || this.targetIdStr.isEmpty()) return;
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        if (targetRes == null) return;
        new GoalUpdatePayload(buildCurrentGoal()).sendToServer();
    }

    private void computePlan() {
        this.errorMessage = null;
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        if (targetRes == null) {
            this.errorMessage = "Invalid Target ID format";
            return;
        }

        double adjustedRate = this.rate / (this.perHour ? 60.0 : 1.0); // Compute plan internally using base rate/min
        try {
            ProductionGoal tempGoal = new ProductionGoal(
                this.goalName,
                this.targetType,
                targetRes,
                adjustedRate,
                this.recipeSelections,
                Optional.empty(),
                this.perHour,
                this.threshold,
                this.linkedMachines
            );
            this.currentPlan = RecipeGraphTraverser.computePlan(this.minecraft.level, tempGoal);
            if (this.currentPlan != null) {
                RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(this.minecraft.level, tempGoal);
                this.currentPlan = this.currentPlan.withGraph(graph);
                this.graphLayout = this.graphLayout.prunedTo(graph);
            }
        } catch (Exception e) {
            this.errorMessage = "Error calculating plan: " + e.getMessage();
            this.currentPlan = null;
        }
    }

    private void drawStepIndicator(GuiGraphics guiGraphics, int left, int top) {
        int cx = left + guiWidth() / 2;
        int dotY = top + STEP_INDICATOR_Y + 4;
        int startX = cx - STEP_DOT_GAP;

        for (int i = 0; i < 3; i++) {
            int dx = startX + i * STEP_DOT_GAP;
            int dotColor = (i == currentStep) ? COLOR_TITLE : COLOR_MUTED;
            guiGraphics.fill(dx - STEP_DOT_RADIUS, dotY - STEP_DOT_RADIUS,
                             dx + STEP_DOT_RADIUS, dotY + STEP_DOT_RADIUS, dotColor);
        }
    }

    private String formatId(ResourceLocation id) {
        String path = id.getPath();
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1))
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }

    @Override
    public void tick() {
        super.tick();
        if (currentStep == STEP_MONITOR) {
            tickCount++;
            if (tickCount >= 20) {
                tickCount = 0;
                new RequestMonitoringUpdatePayload().sendToServer();
            }
        }
    }

    public void updateLiveMonitoring(List<LiveMonitoringPayload.MachineStatusData> data) {
        this.liveData.clear();
        this.liveData.addAll(data);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately no dimming/vignette here. renderTransparentBackground draws a full-window
        // dark gradient (see vanilla Screen.renderTransparentBackground) -- the same darkening
        // technique the pause/options menus use -- which read as "everything looks like the esc
        // menu" rather than a lightweight tool held up over the still-visible game world.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int guiWidth = guiWidth();
        int guiHeight = guiHeight();
        int left = (this.width - guiWidth) / 2;
        int top = (this.height - guiHeight) / 2;

        // --- Draw main clipboard background (9-slice) ---
        NineSliceTexture.blit(guiGraphics, TEX_MAIN, left, top, guiWidth, guiHeight, MAIN_BORDER, MAIN_TEX_SIZE);

        // --- Draw metallic clip accent on the left edge (landscape orientation) ---
        int clipX = left - CLIP_OVERHANG;
        int clipY = top + (guiHeight - CLIP_HEIGHT) / 2;
        guiGraphics.blit(TEX_CLIP, clipX, clipY, 0, 0, CLIP_WIDTH, CLIP_HEIGHT, CLIP_WIDTH, CLIP_HEIGHT);

        // --- Draw step indicator ---
        if (!isMinimized) {
            drawStepIndicator(guiGraphics, left, top);
        }

        // --- Draw step-specific content ---
        if (isMinimized) {
            renderViewMode(guiGraphics, left, top);
        } else {
            switch (currentStep) {
                case STEP_REVIEW_PLAN -> renderStepReviewPlan(guiGraphics, left, top);
                case STEP_MONITOR -> renderStepMonitor(guiGraphics, left, top);
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderViewMode(GuiGraphics guiGraphics, int left, int top) {
        int contentX = left + PADDING + MAIN_BORDER + 2;
        int contentY = top + PADDING + MAIN_BORDER + 18;

        guiGraphics.drawString(this.font, Component.literal("Production Goal Summary"), contentX, contentY, COLOR_TITLE);

        int currentY = contentY + 20;

        guiGraphics.drawString(this.font, Component.literal("Goal Name:"), contentX, currentY, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal(this.goalName), contentX + 80, currentY, COLOR_TEXT);
        currentY += 15;

        guiGraphics.drawString(this.font, Component.literal("Target ID:"), contentX, currentY, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal(this.targetIdStr), contentX + 80, currentY, COLOR_TEXT);
        currentY += 15;

        guiGraphics.drawString(this.font, Component.literal("Target Type:"), contentX, currentY, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal(this.targetType.name()), contentX + 80, currentY, COLOR_TEXT);
        currentY += 15;

        String rateStr = String.format("%.2f units/%s", this.rate, this.perHour ? "hour" : "min");
        guiGraphics.drawString(this.font, Component.literal("Desired Rate:"), contentX, currentY, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal(rateStr), contentX + 80, currentY, COLOR_TEXT);
        currentY += 15;

        String thresholdStr = String.format("%d%%", (int) (this.threshold * 100));
        guiGraphics.drawString(this.font, Component.literal("Threshold:"), contentX, currentY, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal(thresholdStr), contentX + 80, currentY, COLOR_TEXT);
        currentY += 20;

        String statusStr = "Status: Planning Complete";
        int statusColor = COLOR_GREEN;
        if (currentStep == STEP_MONITOR) {
            statusStr = "Status: Live Monitoring Active (" + this.liveData.size() + " nodes)";
            statusColor = COLOR_CYAN;
        } else if (currentStep == STEP_DEFINE_GOAL) {
            statusStr = "Status: Goal Definition Draft";
            statusColor = COLOR_AMBER;
        }
        guiGraphics.drawString(this.font, Component.literal(statusStr), contentX, currentY, statusColor);
    }

    private void renderStepReviewPlan(GuiGraphics guiGraphics, int left, int top) {
        int contentX = left + PADDING + MAIN_BORDER + 2;
        int contentY = top + PADDING + MAIN_BORDER + 18;
        guiGraphics.drawString(this.font, Component.literal("Factory Plan"), contentX, contentY, COLOR_TITLE);
    }

    private void renderStepMonitor(GuiGraphics guiGraphics, int left, int top) {
        int contentX = left + PADDING + MAIN_BORDER + 2;
        int contentY = top + PADDING + MAIN_BORDER + 18;

        guiGraphics.drawString(this.font, Component.literal("Factory Monitoring"), contentX, contentY, COLOR_TITLE);

        int currentY = contentY + 16;
        if (this.liveData.isEmpty()) {
            guiGraphics.drawString(this.font, Component.literal(" - None linked yet"), contentX + 4, currentY, COLOR_MUTED);
        } else {
            int displayed = Math.min(this.liveData.size(), 3);
            for (int i = 0; i < displayed; i++) {
                var machine = this.liveData.get(i);
                int color = COLOR_MUTED;
                if ("GREEN".equals(machine.status())) color = COLOR_GREEN;
                else if ("YELLOW".equals(machine.status())) color = COLOR_AMBER;
                else if ("RED".equals(machine.status())) color = COLOR_ERROR;
                else if ("ORANGE".equals(machine.status())) color = 0xFFE67700;

                guiGraphics.fill(contentX + 2, currentY + 3, contentX + 6, currentY + 7, color);

                double rateVal = machine.actualRate() * (this.perHour ? 60.0 : 1.0);
                String text = String.format("%s: %.2f/%s (%s)", formatId(machine.machineId()), rateVal, this.perHour ? "hr" : "min", machine.status());
                guiGraphics.drawString(this.font, Component.literal(text), contentX + 10, currentY, color);
                currentY += 12;
            }
            if (this.liveData.size() > 3) {
                guiGraphics.drawString(this.font, Component.literal(" - ... and " + (this.liveData.size() - 3) + " more"), contentX + 4, currentY, COLOR_MUTED);
            }
        }
    }

    private void save() {
        if (this.errorMessage != null || this.goalName.isEmpty() || this.targetIdStr.isEmpty()) {
            return;
        }
        ResourceLocation targetRes = ResourceLocation.tryParse(this.targetIdStr);
        if (targetRes == null) {
            return;
        }

        double adjustedRate = this.rate / (this.perHour ? 60.0 : 1.0); // Save internally as rate/min

        ProductionGoal finalGoal = new ProductionGoal(
            this.goalName,
            this.targetType,
            targetRes,
            adjustedRate,
            this.recipeSelections,
            this.currentPlan != null ? Optional.of(this.currentPlan) : Optional.empty(),
            this.perHour,
            this.threshold,
            this.linkedMachines,
            this.graphLayout,
            this.machineLinkHistory,
            this.rejectedMachines
        );

        new GoalUpdatePayload(finalGoal).sendToServer();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
