package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.compat.emi.EmiCompat;
import com.mervyn.miforeman.goal.ClipboardCloseSync;
import com.mervyn.miforeman.goal.ClipboardUiState;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.network.GoalUpdatePayload;
import com.mervyn.miforeman.network.RequestMonitoringUpdatePayload;
import com.mervyn.miforeman.network.LiveMonitoringPayload;
import com.mervyn.miforeman.client.gui.widget.ClipboardButton;
import com.mervyn.miforeman.client.gui.widget.GraphCanvas;
import com.mervyn.miforeman.client.gui.widget.DetailCard;
import com.mervyn.miforeman.client.gui.widget.ReviewListPanel;
import com.mervyn.miforeman.client.WorldHighlightRenderer;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import com.mervyn.miforeman.goal.NodeType;
import com.mervyn.miforeman.network.ScanRequestPayload;
import com.mervyn.miforeman.network.ScanResultPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public class ClipboardScreen extends Screen {
    // --- Layout Constants ---
    // The wizard fills the window down to ClipboardChrome.SCREEN_MARGIN on each
    // side, never
    // shrinking below the original MIN_GUI_WIDTH/MIN_GUI_HEIGHT design size on tiny
    // windows.
    // Widened/flattened from the old 380x280 so the board reads as a landscape
    // clipboard rather than a stretched portrait one.
    private static final int MIN_GUI_WIDTH = 440;
    private static final int MIN_GUI_HEIGHT = 230;
    private static final int PADDING = 8;
    private static final int FIELD_HEIGHT = 14;

    // --- "From EMI" button, shown next to the target-ID field only when
    // EmiCompat.isLoaded() ---
    private static final int EMI_PICK_BUTTON_WIDTH = 70;
    private static final int EMI_PICK_BUTTON_GAP = 4;

    // --- Text Colours ---
    private static final int COLOUR_TITLE = 0xFFDAA520;
    private static final int COLOUR_LABEL = 0xFF8B7355;
    private static final int COLOUR_ERROR = 0xFFCC3333;
    private static final int COLOUR_GREEN = 0xFF2E7D32;
    private static final int COLOUR_CYAN = 0xFF006080;
    private static final int COLOUR_AMBER = 0xFF9A6C00;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;

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

    private final GoalDraft goalDraft;
    private final MonitoringState monitoringState;

    private ResourceLocation selectedNodeId = null;
    private GraphCanvas graphCanvas;
    private DetailCard detailCard;
    private double cameraX, cameraY;
    private float cameraZoom;
    private int detailScrollOffset = 0;
    private boolean detailCardCollapsed;
    private com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode graphViewMode;
    private boolean graphDragEnabled;
    private boolean showMachineNumbers;
    private Button toggleDetailButton;
    private Button toggleMachineViewButton;
    private Button toggleDragModeButton;
    private Button undoLayoutButton, redoLayoutButton, resetLayoutButton;

    private Button nextButton;
    private Button backButton;
    private Button defineNextButton;

    private boolean isMinimized;
    private Button toggleModeButton;

    /**
     * Goal state at screen opening. Used by {@link #init()} to determine initial
     * wizard step.
     */
    private final ProductionGoal openedGoal;

    /** The most recent goal state synchronized to the server. */
    private ProductionGoal lastSyncedGoal;

    public ClipboardScreen(ItemStack stack) {
        super(Component.literal("Clipboard Goal Editor"));

        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        this.openedGoal = goal;
        this.lastSyncedGoal = goal;
        this.hasExistingGoal = (goal != null);
        this.goalDraft = (goal != null) ? GoalDraft.fromGoal(goal) : GoalDraft.defaults();
        this.monitoringState = (goal != null) ? MonitoringState.fromGoal(goal) : MonitoringState.defaults();

        ClipboardUiState ui = (goal != null) ? goal.uiState() : ClipboardUiState.EMPTY;
        this.cameraX = ui.cameraX();
        this.cameraY = ui.cameraY();
        this.cameraZoom = ui.cameraZoom();
        this.graphViewMode = ui.graphViewMode();
        this.graphDragEnabled = ui.graphDragEnabled();
        this.detailCardCollapsed = ui.detailCardCollapsed();
        this.isMinimized = ui.isMinimized();
        this.showMachineNumbers = ui.showMachineNumbers();
    }

    @Override
    public void removed() {
        super.removed();
        ClipboardUiState snapshot = new ClipboardUiState(
                currentStep, cameraX, cameraY, cameraZoom,
                graphViewMode, graphDragEnabled, detailCardCollapsed, isMinimized, showMachineNumbers);
        // goalDraft.graphLayout tracks node drags live (see the onLayoutChange callback
        // in
        // buildStepReviewPlan) but is otherwise only sent to the server via an explicit
        // save
        // action -- layer it on here too so a drag survives a plain close, same as the
        // ui state.
        ClipboardCloseSync.computeCloseSyncGoal(lastSyncedGoal, snapshot, goalDraft.graphLayout)
                .ifPresent(toPersist -> new GoalUpdatePayload(toPersist).sendToServer());
    }

    private int guiWidth() {
        return ClipboardChrome.guiWidth(this.width, MIN_GUI_WIDTH);
    }

    private int guiHeight() {
        return ClipboardChrome.guiHeight(this.height, MIN_GUI_HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        if (!stepInitialized) {
            this.currentStep = hasExistingGoal
                    ? Math.max(STEP_DEFINE_GOAL, Math.min(STEP_MONITOR, openedGoal.uiState().lastStep()))
                    : STEP_DEFINE_GOAL;
            // FactoryPlan.graph is deliberately excluded from
            // FactoryPlan.CODEC/STREAM_CODEC
            // (see ProductionGoal.java), so a plan loaded from a saved goal always decodes
            // with graph == null even though currentPlan itself is non-null. Recompute
            // whenever the graph is missing, not just when the whole plan is, or
            // GraphCanvas never gets built when backing up from Monitor into Review Plan.
            if (hasExistingGoal && (this.goalDraft.currentPlan == null || this.goalDraft.currentPlan.graph() == null)) {
                computePlan();
            }
            stepInitialized = true;
        }
        rebuildStep(currentStep);
    }

    private void rebuildStep(int step) {
        this.clearWidgets();
        this.goalDraft.errorMessage = null;

        int guiWidth = guiWidth();
        int guiHeight = guiHeight();
        int left = (this.width - guiWidth) / 2;
        int top = (this.height - guiHeight) / 2;

        toggleModeButton = new ClipboardButton(left + guiWidth - 54, top + 8, 46, 14,
                Component.literal(this.isMinimized ? "Edit" : "View"),
                b -> {
                    this.isMinimized = !this.isMinimized;
                    rebuildStep(this.currentStep);
                });
        this.addRenderableWidget(toggleModeButton);

        Button coloursButton = new ClipboardButton(left + guiWidth - 54 - 54, top + 8, 50, 14,
                Component.literal("Colours"), b -> Minecraft.getInstance().setScreen(new ColourPickerScreen(this)));
        this.addRenderableWidget(coloursButton);

        if (this.isMinimized) {
            return;
        }

        switch (step) {
            case STEP_DEFINE_GOAL -> buildStepDefineGoal();
            case STEP_REVIEW_PLAN -> buildStepReviewPlan();
            case STEP_MONITOR -> buildStepMonitor();
        }
    }

    private void buildStepDefineGoal() {
        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;
        int contentW = guiWidth() - (PADDING + ClipboardChrome.MAIN_BORDER) * 2 - 4;

        int y = contentY + 16;

        EditBox nameField = new EditBox(this.font, contentX, y + 10, contentW, FIELD_HEIGHT,
                Component.literal("Goal Name"));
        nameField.setMaxLength(64);
        nameField.setValue(this.goalDraft.goalName == null ? "" : this.goalDraft.goalName);
        nameField.setResponder(val -> {
            this.goalDraft.goalName = val;
            revalidateDefineGoal();
        });
        this.addRenderableWidget(nameField);
        y += 10 + FIELD_HEIGHT + 8;

        Button typeButton = new ClipboardButton(contentX, y + 10, 55, FIELD_HEIGHT,
                Component.literal(this.goalDraft.targetType.name()), b -> {
                    this.goalDraft.targetType = this.goalDraft.targetType == TargetType.ITEM ? TargetType.FLUID
                            : TargetType.ITEM;
                    b.setMessage(Component.literal(this.goalDraft.targetType.name()));
                    revalidateDefineGoal();
                });
        this.addRenderableWidget(typeButton);

        boolean emiLoaded = EmiCompat.isLoaded();
        int emiButtonWidth = emiLoaded ? EMI_PICK_BUTTON_WIDTH + EMI_PICK_BUTTON_GAP : 0;

        int targetIdWidth = contentW - 60 - emiButtonWidth;
        EditBox targetIdField = new EditBox(this.font, contentX + 60, y + 10, targetIdWidth, FIELD_HEIGHT,
                Component.literal("Target ID"));
        targetIdField.setMaxLength(256);
        targetIdField.setValue(this.goalDraft.targetIdStr == null ? "" : this.goalDraft.targetIdStr);
        targetIdField.setResponder(val -> {
            this.goalDraft.targetIdStr = val;
            revalidateDefineGoal();
        });
        this.addRenderableWidget(targetIdField);

        if (emiLoaded) {
            Button pickFromEmiButton = new ClipboardButton(contentX + 60 + targetIdWidth + EMI_PICK_BUTTON_GAP, y + 10,
                    EMI_PICK_BUTTON_WIDTH, FIELD_HEIGHT, Component.literal("From EMI"),
                    b -> Minecraft.getInstance().setScreen(new EmiTargetPickerScreen(this)));
            this.addRenderableWidget(pickFromEmiButton);
        }
        y += 10 + FIELD_HEIGHT + 8;

        EditBox rateField = new EditBox(this.font, contentX, y + 10, contentW - 80, FIELD_HEIGHT,
                Component.literal("Rate"));
        rateField.setValue(String.valueOf(this.goalDraft.rate));
        rateField.setResponder(val -> {
            try {
                this.goalDraft.rate = Double.parseDouble(val);
            } catch (NumberFormatException e) {
                // handled by revalidateDefineGoal()'s error message
            }
            revalidateDefineGoal();
        });
        this.addRenderableWidget(rateField);

        Button unitButton = new ClipboardButton(contentX + contentW - 70, y + 10, 70, FIELD_HEIGHT,
                Component.literal(this.goalDraft.perHour ? "Per Hour" : "Per Min"), b -> {
                    this.goalDraft.perHour = !this.goalDraft.perHour;
                    b.setMessage(Component.literal(this.goalDraft.perHour ? "Per Hour" : "Per Min"));
                    revalidateDefineGoal();
                });
        this.addRenderableWidget(unitButton);
        y += 10 + FIELD_HEIGHT + 8;

        EditBox thresholdField = new EditBox(this.font, contentX, y + 10, contentW, FIELD_HEIGHT,
                Component.literal("Threshold"));
        thresholdField.setValue(String.valueOf((int) (this.goalDraft.threshold * 100)));
        thresholdField.setResponder(val -> {
            try {
                double pct = Double.parseDouble(val);
                this.goalDraft.threshold = pct / 100.0;
            } catch (NumberFormatException e) {
                // handled by revalidateDefineGoal()'s error message
            }
            revalidateDefineGoal();
        });
        this.addRenderableWidget(thresholdField);

        int btnY = top + guiHeight() - PADDING - ClipboardChrome.MAIN_BORDER - 22;

        Button cancelButton = new ClipboardButton(contentX, btnY, 80, 16, Component.literal("Cancel"),
                b -> this.onClose());
        this.addRenderableWidget(cancelButton);

        defineNextButton = new ClipboardButton(contentX + contentW - 80, btnY, 80, 16, Component.literal("Next ->"),
                b -> {
                    GoalFormResult result = new GoalFormResult(
                            this.goalDraft.goalName, this.goalDraft.targetType, this.goalDraft.targetIdStr,
                            this.goalDraft.rate, this.goalDraft.perHour, this.goalDraft.threshold);
                    this.goalDraft.applyFormResult(result);
                    computePlan();
                    if (this.goalDraft.errorMessage == null && this.goalDraft.currentPlan != null) {
                        this.selectedNodeId = null;
                        goToStep(STEP_REVIEW_PLAN);
                    } else {
                        String serverError = this.goalDraft.errorMessage;
                        rebuildStep(STEP_DEFINE_GOAL);
                        if (serverError != null) {
                            this.goalDraft.errorMessage = serverError;
                            defineNextButton.active = false;
                        }
                    }
                });
        this.addRenderableWidget(defineNextButton);

        revalidateDefineGoal();
    }

    /**
     * Applies a stack dragged onto {@link EmiTargetPickerScreen} as the goal
     * target, the same as
     * typing an ID by hand. Public so that screen (and, transitively, {@code
     * com.mervyn.miforeman.compat.emi}) can call back into this one without it
     * needing to import
     * anything from {@code dev.emi} itself.
     */
    public void applyDroppedTarget(TargetType type, String idStr) {
        this.goalDraft.targetType = type;
        this.goalDraft.targetIdStr = idStr;
        rebuildStep(STEP_DEFINE_GOAL);
    }

    /**
     * Client-side revalidation of the Define Goal form, run on every field/toggle
     * change.
     */
    private void revalidateDefineGoal() {
        Optional<String> error = GoalFormValidation.validateGoalInputs(
                this.goalDraft.goalName, this.goalDraft.targetIdStr, this.goalDraft.targetType,
                this.goalDraft.rate, this.goalDraft.threshold);
        this.goalDraft.errorMessage = error.orElse(null);
        if (defineNextButton != null) {
            defineNextButton.active = error.isEmpty();
        }
    }

    private void goToStep(int step) {
        this.currentStep = step;
        rebuildStep(step);
    }

    private void buildStepReviewPlan() {
        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;
        int contentW = guiWidth() - (PADDING + ClipboardChrome.MAIN_BORDER) * 2 - 4;

        // Top-down layout, derived from actual row heights rather than hardcoded
        // offsets that can
        // drift out of sync (title/button-row and button-row/canvas overlaps both
        // traced back to
        // exactly that.)
        int titleRowHeight = 16; // matches the "+16" gap already used after the title in renderStepMonitor
        int topButtonRowHeight = 12;
        int rowGap = 4;

        int topButtonRowY = contentY + titleRowHeight;
        int canvasY = topButtonRowY + topButtonRowHeight + rowGap;
        int btnY = top + guiHeight() - PADDING - ClipboardChrome.MAIN_BORDER - 22;
        int contentH = btnY - rowGap - canvasY;

        if (this.goalDraft.currentPlan != null && this.goalDraft.currentPlan.graph() != null) {
            int detailWidth = detailCardCollapsed ? 0 : (int) (contentW * 0.42);
            int canvasWidth = contentW - (detailCardCollapsed ? 0 : detailWidth + 6);

            graphCanvas = new GraphCanvas(contentX, canvasY, canvasWidth, contentH,
                    this.goalDraft.currentPlan.graph(), this.goalDraft.graphLayout, cameraX, cameraY, cameraZoom,
                    selectedNodeId,
                    nodeId -> {
                        selectedNodeId = nodeId;
                        if (detailCard != null) {
                            detailCard.setNode(this.goalDraft.currentPlan.graph().node(nodeId));
                        }
                    },
                    layout -> {
                        this.goalDraft.graphLayout = layout;
                        if (undoLayoutButton != null)
                            undoLayoutButton.active = graphCanvas.canUndo();
                        if (redoLayoutButton != null)
                            redoLayoutButton.active = graphCanvas.canRedo();
                    },
                    (panX, panY, zoomLevel) -> {
                        this.cameraX = panX;
                        this.cameraY = panY;
                        this.cameraZoom = zoomLevel;
                    },
                    graphViewMode,
                    graphDragEnabled);
            this.addRenderableWidget(graphCanvas);

            if (!detailCardCollapsed) {
                RecipeGraphNode selectedNode = selectedNodeId != null
                        ? this.goalDraft.currentPlan.graph().node(selectedNodeId)
                        : null;
                detailCard = new DetailCard(contentX + canvasWidth + 6, canvasY, detailWidth, contentH, selectedNode,
                        this.goalDraft.currentPlan, this.goalDraft.perHour, showMachineNumbers, (resId, choiceRecipeId) -> {
                            this.goalDraft.recipeSelections.put(resId, choiceRecipeId);
                            // A MACHINE node's own id IS its recipe id, so cycling the recipe of the
                            // currently-selected machine node makes that id vanish from the rebuilt graph.
                            // Look the node up live by selectedNodeId (not a captured local --
                            // GraphCanvas's
                            // onSelect only calls detailCard.setNode(), it doesn't rebuild this step, so a
                            // closed-over node reference here can be stale) and follow the selection onto
                            // the newly-chosen recipe's node instead of losing it.
                            RecipeGraphNode cyclingNode = selectedNodeId != null && this.goalDraft.currentPlan != null
                                    && this.goalDraft.currentPlan.graph() != null
                                            ? this.goalDraft.currentPlan.graph().node(selectedNodeId)
                                            : null;
                            boolean cyclingSelectedMachine = cyclingNode != null
                                    && cyclingNode.getType() == NodeType.MACHINE;
                            computePlan();
                            if (this.goalDraft.currentPlan != null && selectedNodeId != null
                                    && this.goalDraft.currentPlan.graph().node(selectedNodeId) == null) {
                                selectedNodeId = cyclingSelectedMachine
                                        && this.goalDraft.currentPlan.graph().node(choiceRecipeId) != null
                                                ? choiceRecipeId
                                                : null;
                            }
                            rebuildStep(STEP_REVIEW_PLAN);
                        }, () -> {
                            this.showMachineNumbers = !this.showMachineNumbers;
                            rebuildStep(STEP_REVIEW_PLAN);
                        }, detailScrollOffset, v -> this.detailScrollOffset = v);
                this.addRenderableWidget(detailCard);
            } else {
                detailCard = null;
            }

            toggleDetailButton = new ClipboardButton(contentX + contentW - 90, topButtonRowY, 90, 12,
                    Component.literal(detailCardCollapsed ? "Show Details" : "Hide Details"),
                    b -> {
                        detailCardCollapsed = !detailCardCollapsed;
                        rebuildStep(STEP_REVIEW_PLAN);
                    });
            this.addRenderableWidget(toggleDetailButton);

            undoLayoutButton = new ClipboardButton(contentX, topButtonRowY, 40, 12,
                    Component.literal("Undo"), b -> graphCanvas.undo());
            undoLayoutButton.active = graphCanvas.canUndo();
            this.addRenderableWidget(undoLayoutButton);

            redoLayoutButton = new ClipboardButton(contentX + 44, topButtonRowY, 40, 12,
                    Component.literal("Redo"), b -> graphCanvas.redo());
            redoLayoutButton.active = graphCanvas.canRedo();
            this.addRenderableWidget(redoLayoutButton);

            resetLayoutButton = new ClipboardButton(contentX + 88, topButtonRowY, 40, 12,
                    Component.literal("Reset"), b -> graphCanvas.resetLayout());
            this.addRenderableWidget(resetLayoutButton);

            String viewLabel = switch (graphViewMode) {
                case ALL -> "View: All";
                case ITEMS_ONLY -> "View: Items";
                case MACHINES_ONLY -> "View: Machines";
            };
            toggleMachineViewButton = new ClipboardButton(contentX + 132, topButtonRowY, 86, 12,
                    Component.literal(viewLabel),
                    b -> {
                        graphViewMode = graphViewMode.next();
                        // The canvas can no longer highlight a now-hidden node -- clear
                        // the selection so DetailCard doesn't keep showing stale details for it.
                        if (selectedNodeId != null && this.goalDraft.currentPlan != null
                                && this.goalDraft.currentPlan.graph() != null) {
                            RecipeGraphNode selected = this.goalDraft.currentPlan.graph().node(selectedNodeId);
                            if (selected != null) {
                                if (graphViewMode == com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.ITEMS_ONLY
                                        && selected.getType() == NodeType.MACHINE) {
                                    selectedNodeId = null;
                                } else if (graphViewMode == com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.MACHINES_ONLY
                                        && selected.getType() != NodeType.MACHINE) {
                                    selectedNodeId = null;
                                }
                            }
                        }
                        rebuildStep(STEP_REVIEW_PLAN);
                    });
            this.addRenderableWidget(toggleMachineViewButton);

            toggleDragModeButton = new ClipboardButton(contentX + 222, topButtonRowY, 86, 12,
                    Component.literal(graphDragEnabled ? "Edit Mode" : "View Mode"),
                    b -> {
                        graphDragEnabled = !graphDragEnabled;
                        rebuildStep(STEP_REVIEW_PLAN);
                    });
            this.addRenderableWidget(toggleDragModeButton);
        }

        backButton = new ClipboardButton(contentX, btnY, 80, 16,
                Component.literal("<- Back"), b -> goToStep(STEP_DEFINE_GOAL));
        this.addRenderableWidget(backButton);

        nextButton = new ClipboardButton(contentX + contentW - 110, btnY, 110, 16,
                Component.literal("Save & Monitor"), b -> {
                    save();
                    goToStep(STEP_MONITOR);
                });
        this.addRenderableWidget(nextButton);
    }

    private void buildStepMonitor() {
        int left = (this.width - guiWidth()) / 2;
        int top = (this.height - guiHeight()) / 2;

        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;
        int contentW = guiWidth() - (PADDING + ClipboardChrome.MAIN_BORDER) * 2 - 4;

        Button scanButton = new ClipboardButton(contentX, contentY + 28, 90, 14,
                Component.literal("Scan Nearby"), b -> triggerScan());
        this.addRenderableWidget(scanButton);

        Button highlightsButton = new ClipboardButton(contentX + 94, contentY + 28, 90, 14,
                Component.literal(monitoringState.showInWorldHighlights ? "Highlights: On" : "Highlights: Off"),
                b -> {
                    monitoringState.showInWorldHighlights = !monitoringState.showInWorldHighlights;
                    WorldHighlightRenderer.setEnabled(monitoringState.showInWorldHighlights);
                    rebuildStep(STEP_MONITOR);
                });
        this.addRenderableWidget(highlightsButton);

        List<ReviewListPanel.ReviewRow> rows = monitoringState.buildReviewRows();
        monitoringState.updateWorldHighlightPositions(rows);

        int halfW = (contentW - 6) / 2;
        Button reviewButton = new ClipboardButton(contentX, contentY + 46, halfW, 16,
                Component.literal("Review Machines (" + rows.size() + ")"),
                b -> Minecraft.getInstance().setScreen(new ReviewMachinesScreen(
                        monitoringState, this::onMonitoringStateChanged, this)));
        this.addRenderableWidget(reviewButton);

        Button monitoringButton = new ClipboardButton(contentX + halfW + 6, contentY + 46, halfW, 16,
                Component.literal("View Monitoring (" + monitoringState.liveData.size() + ")"),
                b -> Minecraft.getInstance().setScreen(new MonitoringScreen(
                        monitoringState, goalDraft.perHour, this)));
        this.addRenderableWidget(monitoringButton);

        int btnY = top + guiHeight() - PADDING - ClipboardChrome.MAIN_BORDER - 22;
        backButton = new ClipboardButton(contentX, btnY, 80, 16,
                Component.literal("<- Back"), b -> goToStep(STEP_REVIEW_PLAN));
        this.addRenderableWidget(backButton);

        nextButton = new ClipboardButton(contentX + contentW - 80, btnY, 80, 16,
                Component.literal("Done"), b -> this.onClose());
        this.addRenderableWidget(nextButton);

        new RequestMonitoringUpdatePayload().sendToServer();
    }

    /**
     * Wired into {@link MonitoringState}'s apply* mutators as the {@code onChange}
     * callback --
     * matches what each of those methods did inline before the split.
     */
    private void onMonitoringStateChanged() {
        syncGoal();
        rebuildStep(STEP_MONITOR);
    }

    private void triggerScan() {
        if (this.goalDraft.currentPlan == null)
            return;
        ProductionGoal goal = buildCurrentGoal();
        new ScanRequestPayload(goal).sendToServer();
    }

    public void updateScanResults(List<ScanResultPayload.Candidate> candidates) {
        monitoringState.setScanResults(candidates);
        if (currentStep == STEP_MONITOR)
            rebuildStep(STEP_MONITOR);
    }

    private ProductionGoal buildCurrentGoal() {
        return goalDraft.buildGoal(monitoringState.linkedMachines, monitoringState.machineLinkHistory,
                monitoringState.rejectedMachines);
    }

    private void syncGoal() {
        if (!goalDraft.isReadyToSave())
            return;
        lastSyncedGoal = buildCurrentGoal();
        new GoalUpdatePayload(lastSyncedGoal).sendToServer();
    }

    private void computePlan() {
        goalDraft.computePlan(this.minecraft.level, monitoringState.linkedMachines);
    }

    private void drawStepIndicator(GuiGraphics guiGraphics, int left, int top) {
        int cx = left + guiWidth() / 2;
        int dotY = top + STEP_INDICATOR_Y + 4;
        int startX = cx - STEP_DOT_GAP;

        for (int i = 0; i < 3; i++) {
            int dx = startX + i * STEP_DOT_GAP;
            int dotColour = (i == currentStep) ? COLOUR_TITLE : COLOUR_MUTED;
            guiGraphics.fill(dx - STEP_DOT_RADIUS, dotY - STEP_DOT_RADIUS,
                    dx + STEP_DOT_RADIUS, dotY + STEP_DOT_RADIUS, dotColour);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (currentStep == STEP_MONITOR && monitoringState.tickAndShouldPoll()) {
            new RequestMonitoringUpdatePayload().sendToServer();
        }
    }

    public void updateLiveMonitoring(List<LiveMonitoringPayload.MachineStatusData> data) {
        monitoringState.setLiveData(data);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately no dimming/vignette here. renderTransparentBackground draws a
        // full-window
        // dark gradient (see vanilla Screen.renderTransparentBackground) -- the same
        // darkening
        // technique the pause/options menus use -- which read as "everything looks like
        // the esc
        // menu" rather than a lightweight tool held up over the still-visible game
        // world.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int guiWidth = guiWidth();
        int guiHeight = guiHeight();
        int left = (this.width - guiWidth) / 2;
        int top = (this.height - guiHeight) / 2;

        // --- Draw parchment background + clip accent ---
        ClipboardChrome.drawBackground(guiGraphics, left, top, guiWidth, guiHeight);

        // --- Draw step indicator ---
        if (!isMinimized) {
            drawStepIndicator(guiGraphics, left, top);
        }

        // --- Draw step-specific content ---
        if (isMinimized) {
            renderViewMode(guiGraphics, left, top);
        } else {
            switch (currentStep) {
                case STEP_DEFINE_GOAL -> renderStepDefineGoal(guiGraphics, left, top);
                case STEP_REVIEW_PLAN -> renderStepReviewPlan(guiGraphics, left, top);
                case STEP_MONITOR -> renderStepMonitor(guiGraphics, left, top);
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderViewMode(GuiGraphics guiGraphics, int left, int top) {
        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;

        guiGraphics.drawString(this.font, Component.literal("Production Goal Summary"), contentX, contentY,
                COLOUR_TITLE);

        int currentY = contentY + 20;

        guiGraphics.drawString(this.font, Component.literal("Goal Name:"), contentX, currentY, COLOUR_LABEL, false);
        guiGraphics.drawString(this.font, Component.literal(this.goalDraft.goalName), contentX + 80, currentY,
                COLOUR_TEXT, false);
        currentY += 15;

        guiGraphics.drawString(this.font, Component.literal("Target ID:"), contentX, currentY, COLOUR_LABEL, false);
        guiGraphics.drawString(this.font, Component.literal(this.goalDraft.targetIdStr), contentX + 80, currentY,
                COLOUR_TEXT, false);
        currentY += 15;

        guiGraphics.drawString(this.font, Component.literal("Target Type:"), contentX, currentY, COLOUR_LABEL, false);
        guiGraphics.drawString(this.font, Component.literal(this.goalDraft.targetType.name()), contentX + 80, currentY,
                COLOUR_TEXT, false);
        currentY += 15;

        String rateStr = String.format("%.2f units/%s", this.goalDraft.rate, this.goalDraft.perHour ? "hour" : "min");
        guiGraphics.drawString(this.font, Component.literal("Desired Rate:"), contentX, currentY, COLOUR_LABEL, false);
        guiGraphics.drawString(this.font, Component.literal(rateStr), contentX + 80, currentY, COLOUR_TEXT, false);
        currentY += 15;

        String thresholdStr = String.format("%d%%", (int) (this.goalDraft.threshold * 100));
        guiGraphics.drawString(this.font, Component.literal("Threshold:"), contentX, currentY, COLOUR_LABEL, false);
        guiGraphics.drawString(this.font, Component.literal(thresholdStr), contentX + 80, currentY, COLOUR_TEXT, false);
        currentY += 20;

        String statusStr = "Status: Planning Complete";
        int statusColour = COLOUR_GREEN;
        if (currentStep == STEP_MONITOR) {
            statusStr = "Status: Live Monitoring Active (" + this.monitoringState.liveData.size() + " nodes)";
            statusColour = COLOUR_CYAN;
        } else if (currentStep == STEP_DEFINE_GOAL) {
            statusStr = "Status: Goal Definition Draft";
            statusColour = COLOUR_AMBER;
        }
        guiGraphics.drawString(this.font, Component.literal(statusStr), contentX, currentY, statusColour, false);
    }

    private void renderStepDefineGoal(GuiGraphics guiGraphics, int left, int top) {
        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;

        guiGraphics.drawString(this.font, Component.literal("Define Goal"), contentX, contentY, COLOUR_TITLE);

        int y = contentY + 16;
        guiGraphics.drawString(this.font, Component.literal("Goal Name"), contentX, y, COLOUR_LABEL, false);
        y += 10 + FIELD_HEIGHT + 8;
        guiGraphics.drawString(this.font, Component.literal("Target Type & ID"), contentX, y, COLOUR_LABEL, false);
        y += 10 + FIELD_HEIGHT + 8;
        guiGraphics.drawString(this.font,
                Component.literal(this.goalDraft.perHour ? "Rate (units/hour)" : "Rate (units/min)"), contentX, y,
                COLOUR_LABEL, false);
        y += 10 + FIELD_HEIGHT + 8;
        guiGraphics.drawString(this.font, Component.literal("Threshold (%)"), contentX, y, COLOUR_LABEL, false);
        y += 10 + FIELD_HEIGHT + 6;

        if (this.goalDraft.errorMessage != null) {
            guiGraphics.drawString(this.font, Component.literal(this.goalDraft.errorMessage), contentX, y, COLOUR_ERROR,
                    false);
        }
    }

    private void renderStepReviewPlan(GuiGraphics guiGraphics, int left, int top) {
        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;
        guiGraphics.drawString(this.font, Component.literal("Factory Plan"), contentX, contentY, COLOUR_TITLE);
    }

    private void renderStepMonitor(GuiGraphics guiGraphics, int left, int top) {
        int contentX = left + PADDING + ClipboardChrome.MAIN_BORDER + 2;
        int contentY = top + PADDING + ClipboardChrome.MAIN_BORDER + 18;

        guiGraphics.drawString(this.font, Component.literal("Factory Monitoring"), contentX, contentY, COLOUR_TITLE);

        int currentY = contentY + 16;
        if (this.monitoringState.liveData.isEmpty()) {
            guiGraphics.drawString(this.font, Component.literal(" - None linked yet"), contentX + 4, currentY,
                    COLOUR_MUTED, false);
            return;
        }

        int red = 0, orange = 0, yellow = 0, green = 0, other = 0;
        for (var machine : this.monitoringState.liveData) {
            switch (machine.status()) {
                case "RED" -> red++;
                case "ORANGE" -> orange++;
                case "YELLOW" -> yellow++;
                case "GREEN" -> green++;
                default -> other++;
            }
        }

        // Counts lead with RED/ORANGE (what actually needs attention), not machine
        // order.
        String prefix = this.monitoringState.liveData.size() + " machines: ";
        guiGraphics.drawString(this.font, Component.literal(prefix), contentX + 4, currentY, COLOUR_TEXT, false);
        int segX = contentX + 4 + this.font.width(prefix);
        segX = drawStatusCount(guiGraphics, segX, currentY, red, "RED", COLOUR_ERROR);
        segX = drawStatusCount(guiGraphics, segX, currentY, orange, "ORANGE", 0xFFE67700);
        segX = drawStatusCount(guiGraphics, segX, currentY, yellow, "YELLOW", COLOUR_AMBER);
        segX = drawStatusCount(guiGraphics, segX, currentY, green, "GREEN", COLOUR_GREEN);
        drawStatusCount(guiGraphics, segX, currentY, other, "OTHER", COLOUR_MUTED);
    }

    private int drawStatusCount(GuiGraphics guiGraphics, int x, int y, int count, String label, int colour) {
        if (count == 0)
            return x;
        String segment = count + " " + label + "  ";
        guiGraphics.drawString(this.font, Component.literal(segment), x, y, colour, false);
        return x + this.font.width(segment);
    }

    private void save() {
        if (!goalDraft.isReadyToSave())
            return;
        lastSyncedGoal = buildCurrentGoal();
        new GoalUpdatePayload(lastSyncedGoal).sendToServer();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (currentStep == STEP_REVIEW_PLAN && graphCanvas != null) {
            // Ctrl+F / Cmd+F toggles search
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F && hasControlDown()) {
                graphCanvas.toggleSearch();
                return true;
            }
            if (graphCanvas.isSearchFocused()) {
                if (graphCanvas.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            if (graphCanvas.isSearchVisible() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                graphCanvas.toggleSearch();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (currentStep == STEP_REVIEW_PLAN && graphCanvas != null && graphCanvas.isSearchFocused()) {
            if (graphCanvas.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
