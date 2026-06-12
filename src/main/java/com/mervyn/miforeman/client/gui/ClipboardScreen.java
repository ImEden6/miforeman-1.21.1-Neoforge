package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.MachineRequirement;
import com.mervyn.miforeman.goal.ProductionGoal.MaterialFlow;
import com.mervyn.miforeman.goal.ProductionGoal.Ambiguity;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.network.GoalUpdatePayload;
import com.mervyn.miforeman.network.RequestMonitoringUpdatePayload;
import com.mervyn.miforeman.network.LiveMonitoringPayload;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
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
    private static final ResourceLocation TEX_SIDE_PANEL = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_side_panel.png");
    private static final ResourceLocation TEX_NODE_CANVAS = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_node_canvas.png");
    private static final ResourceLocation TEX_CLIP = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "textures/gui/clipboard_clip.png");

    // --- 9-Slice Constants ---
    private static final int MAIN_TEX_SIZE = 64;
    private static final int MAIN_BORDER = 6;
    private static final int PANEL_TEX_SIZE = 48;
    private static final int PANEL_BORDER = 4;

    // --- Layout Constants ---
    private static final int GUI_WIDTH = 380;
    private static final int GUI_HEIGHT = 280;
    private static final int PADDING = 8;
    private static final int LEFT_PANEL_WIDTH = 172;
    private static final int DIVIDER_GAP = 6;

    // --- Text Colors ---
    private static final int COLOR_TITLE = 0xFF4A2E0A;
    private static final int COLOR_LABEL = 0xFF6B5030;
    private static final int COLOR_ERROR = 0xFFCC3333;
    private static final int COLOR_GREEN = 0xFF2E7D32;
    private static final int COLOR_CYAN = 0xFF006080;
    private static final int COLOR_AMBER = 0xFF9A6C00;
    private static final int COLOR_TEXT = 0xFF3A2A18;
    private static final int COLOR_MUTED = 0xFF8A7A68;

    private String goalName;
    private TargetType targetType;
    private String targetIdStr;
    private double rate;
    private final Map<ResourceLocation, ResourceLocation> recipeSelections = new HashMap<>();
    private boolean perHour;
    private double threshold;
    private final List<BlockPos> linkedMachines = new ArrayList<>();
    
    private FactoryPlan currentPlan;
    private String errorMessage;

    private EditBox nameEdit;
    private Button typeButton;
    private EditBox targetEdit;
    private EditBox rateEdit;
    private EditBox thresholdEdit;
    private Button unitButton;
    private Button saveButton;
    private Button cancelButton;

    private final List<Button> ambiguityButtons = new ArrayList<>();
    private final List<LiveMonitoringPayload.MachineStatusData> liveData = new ArrayList<>();
    private int tickCount = 0;

    public ClipboardScreen(ItemStack stack) {
        super(Component.literal("Clipboard Goal Editor"));
        
        ProductionGoal goal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (goal != null) {
            this.goalName = goal.name();
            this.targetType = goal.type();
            this.targetIdStr = goal.targetId().toString();
            this.rate = goal.rate();
            this.recipeSelections.putAll(goal.recipeSelections());
            this.perHour = goal.perHour();
            this.threshold = goal.threshold();
            this.linkedMachines.addAll(goal.linkedMachines());
        } else {
            this.goalName = "Quantum Production";
            this.targetType = TargetType.ITEM;
            this.targetIdStr = "modern_industrialization:quantum_upgrade";
            this.rate = 1.0;
            this.perHour = false;
            this.threshold = 0.8;
        }
    }

    @Override
    protected void init() {
        int left = (this.width - GUI_WIDTH) / 2;
        int top = (this.height - GUI_HEIGHT) / 2;

        // Left panel content area (inside the side panel border)
        int lpLeft = left + PADDING + PANEL_BORDER + 2;
        int lpTop = top + PADDING + PANEL_BORDER + 18; // below clip + title
        int lpWidth = LEFT_PANEL_WIDTH - PANEL_BORDER * 2 - 4;

        nameEdit = new EditBox(this.font, lpLeft, lpTop + 12, lpWidth, 14, Component.literal("Goal Name"));
        nameEdit.setValue(this.goalName);
        nameEdit.setResponder(val -> { this.goalName = val; recomputePlan(); });
        this.addRenderableWidget(nameEdit);

        typeButton = Button.builder(Component.literal(this.targetType.name()), b -> {
            this.targetType = this.targetType == TargetType.ITEM ? TargetType.FLUID : TargetType.ITEM;
            b.setMessage(Component.literal(this.targetType.name()));
            recomputePlan();
        }).bounds(lpLeft, lpTop + 42, 45, 14).build();
        this.addRenderableWidget(typeButton);

        targetEdit = new EditBox(this.font, lpLeft + 50, lpTop + 42, lpWidth - 50, 14, Component.literal("Target ID"));
        targetEdit.setValue(this.targetIdStr);
        targetEdit.setResponder(val -> { this.targetIdStr = val; recomputePlan(); });
        this.addRenderableWidget(targetEdit);

        rateEdit = new EditBox(this.font, lpLeft, lpTop + 72, lpWidth, 14, Component.literal("Rate"));
        rateEdit.setValue(String.valueOf(this.rate));
        rateEdit.setResponder(val -> {
            try {
                this.rate = Double.parseDouble(val);
                this.errorMessage = null;
            } catch (NumberFormatException e) {
                this.errorMessage = "Invalid rate";
            }
            recomputePlan();
        });
        this.addRenderableWidget(rateEdit);

        thresholdEdit = new EditBox(this.font, lpLeft, lpTop + 102, lpWidth - 75, 14, Component.literal("Threshold"));
        thresholdEdit.setValue(String.valueOf((int) (this.threshold * 100)));
        thresholdEdit.setResponder(val -> {
            try {
                double pct = Double.parseDouble(val);
                this.threshold = pct / 100.0;
                this.errorMessage = null;
            } catch (NumberFormatException e) {
                this.errorMessage = "Invalid threshold";
            }
            recomputePlan();
        });
        this.addRenderableWidget(thresholdEdit);

        unitButton = Button.builder(Component.literal(this.perHour ? "Per Hour" : "Per Min"), b -> {
            this.perHour = !this.perHour;
            b.setMessage(Component.literal(this.perHour ? "Per Hour" : "Per Min"));
            recomputePlan();
        }).bounds(lpLeft + lpWidth - 70, lpTop + 102, 70, 14).build();
        this.addRenderableWidget(unitButton);

        // Buttons at bottom of left panel
        int btnY = top + GUI_HEIGHT - PADDING - MAIN_BORDER - 44;
        saveButton = Button.builder(Component.literal("Save"), b -> save()).bounds(lpLeft, btnY, lpWidth, 18).build();
        this.addRenderableWidget(saveButton);

        cancelButton = Button.builder(Component.literal("Cancel"), b -> this.onClose()).bounds(lpLeft, btnY + 22, lpWidth, 18).build();
        this.addRenderableWidget(cancelButton);

        recomputePlan();
        new RequestMonitoringUpdatePayload().sendToServer();
    }

    private void recomputePlan() {
        this.errorMessage = null;
        ResourceLocation targetRes = null;
        try {
            targetRes = ResourceLocation.tryParse(this.targetIdStr);
            if (targetRes == null) {
                this.errorMessage = "Invalid Target ID format";
            }
        } catch (Exception e) {
            this.errorMessage = "Invalid Target ID format";
        }

        if (targetRes != null) {
            if (this.targetType == TargetType.ITEM) {
                if (!BuiltInRegistries.ITEM.containsKey(targetRes)) {
                    this.errorMessage = "Item not found in registry";
                }
            } else {
                if (!BuiltInRegistries.FLUID.containsKey(targetRes)) {
                    this.errorMessage = "Fluid not found in registry";
                }
            }
        }

        if (this.errorMessage == null && targetRes != null) {
            try {
                ProductionGoal tempGoal = new ProductionGoal(
                    this.goalName,
                    this.targetType,
                    targetRes,
                    this.rate,
                    this.recipeSelections,
                    Optional.empty(),
                    this.perHour,
                    this.threshold,
                    this.linkedMachines
                );
                this.currentPlan = RecipeGraphTraverser.computePlan(this.minecraft.level, tempGoal);
            } catch (Exception e) {
                this.errorMessage = "Error calculating plan";
                this.currentPlan = null;
            }
        } else {
            this.currentPlan = null;
        }

        updateAmbiguityButtons();
    }

    private void updateAmbiguityButtons() {
        for (Button btn : this.ambiguityButtons) {
            this.removeWidget(btn);
        }
        this.ambiguityButtons.clear();

        if (this.currentPlan == null) {
            return;
        }

        int left = (this.width - GUI_WIDTH) / 2;
        int top = (this.height - GUI_HEIGHT) / 2;
        int rpLeft = left + PADDING + LEFT_PANEL_WIDTH + DIVIDER_GAP + PANEL_BORDER + 2;
        int rpWidth = GUI_WIDTH - PADDING * 2 - LEFT_PANEL_WIDTH - DIVIDER_GAP - PANEL_BORDER * 2 - 4;
        int currentY = top + PADDING + PANEL_BORDER + 32;

        List<Ambiguity> ambiguities = this.currentPlan.ambiguities();
        for (int i = 0; i < ambiguities.size(); i++) {
            Ambiguity ambiguity = ambiguities.get(i);
            ResourceLocation resourceId = ambiguity.resourceId();
            List<ResourceLocation> recipes = ambiguity.recipeIds();
            if (recipes.isEmpty()) continue;

            ResourceLocation currentSel = this.recipeSelections.get(resourceId);
            if (currentSel == null || !recipes.contains(currentSel)) {
                currentSel = recipes.get(0);
                this.recipeSelections.put(resourceId, currentSel);
            }

            final ResourceLocation activeSel = currentSel;
            String buttonText = formatId(resourceId) + ": " + formatId(activeSel);
            
            Button btn = Button.builder(Component.literal(buttonText), b -> {
                int idx = recipes.indexOf(activeSel);
                int nextIdx = (idx + 1) % recipes.size();
                ResourceLocation nextSel = recipes.get(nextIdx);
                this.recipeSelections.put(resourceId, nextSel);
                recomputePlan();
            }).bounds(rpLeft, currentY, rpWidth, 14).build();

            this.addRenderableWidget(btn);
            this.ambiguityButtons.add(btn);
            currentY += 16;
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

        tickCount++;
        if (tickCount >= 20) {
            tickCount = 0;
            new RequestMonitoringUpdatePayload().sendToServer();
        }
    }

    public void updateLiveMonitoring(List<LiveMonitoringPayload.MachineStatusData> data) {
        this.liveData.clear();
        this.liveData.addAll(data);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - GUI_WIDTH) / 2;
        int top = (this.height - GUI_HEIGHT) / 2;

        // --- Draw main clipboard background (9-slice) ---
        blitNineSlice(guiGraphics, TEX_MAIN, left, top, GUI_WIDTH, GUI_HEIGHT, MAIN_BORDER, MAIN_TEX_SIZE);

        // --- Draw metallic clip accent at top center ---
        int clipX = left + (GUI_WIDTH - 32) / 2;
        int clipY = top - 6; // slightly above the main panel
        guiGraphics.blit(TEX_CLIP, clipX, clipY, 0, 0, 32, 16, 32, 16);

        // --- Draw left side panel (Goal Editor) ---
        int lpX = left + PADDING;
        int lpY = top + PADDING;
        int lpW = LEFT_PANEL_WIDTH;
        int lpH = GUI_HEIGHT - PADDING * 2;
        blitNineSlice(guiGraphics, TEX_SIDE_PANEL, lpX, lpY, lpW, lpH, PANEL_BORDER, PANEL_TEX_SIZE);

        // --- Draw right node canvas panel (Plan Summary / Future Node Graph) ---
        int rpX = left + PADDING + LEFT_PANEL_WIDTH + DIVIDER_GAP;
        int rpY = top + PADDING;
        int rpW = GUI_WIDTH - PADDING * 2 - LEFT_PANEL_WIDTH - DIVIDER_GAP;
        int rpH = GUI_HEIGHT - PADDING * 2;
        blitNineSlice(guiGraphics, TEX_NODE_CANVAS, rpX, rpY, rpW, rpH, PANEL_BORDER, PANEL_TEX_SIZE);

        // --- Draw text labels ---
        int lpContentX = lpX + PANEL_BORDER + 2;
        int lpContentY = lpY + PANEL_BORDER + 2;

        guiGraphics.drawString(this.font, Component.literal("Goal Editor"), lpContentX, lpContentY, COLOR_TITLE);

        guiGraphics.drawString(this.font, Component.literal("Goal Name"), lpContentX, lpContentY + 16, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal("Target Type & ID"), lpContentX, lpContentY + 46, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal(this.perHour ? "Rate (units/hour)" : "Rate (units/min)"), lpContentX, lpContentY + 76, COLOR_LABEL);
        guiGraphics.drawString(this.font, Component.literal("Threshold (%) & Unit"), lpContentX, lpContentY + 106, COLOR_LABEL);

        // --- Right panel content ---
        int rpContentX = rpX + PANEL_BORDER + 2;
        int rpContentY = rpY + PANEL_BORDER + 2;

        guiGraphics.drawString(this.font, Component.literal("Plan Summary"), rpContentX, rpContentY, COLOR_TITLE);

        if (this.errorMessage != null) {
            guiGraphics.drawString(this.font, Component.literal("Error:"), rpContentX, rpContentY + 16, COLOR_ERROR);
            guiGraphics.drawString(this.font, Component.literal(this.errorMessage), rpContentX, rpContentY + 28, COLOR_ERROR);
        } else if (this.currentPlan != null) {
            int currentY = rpContentY + 16;
            List<Ambiguity> ambiguities = this.currentPlan.ambiguities();
            if (!ambiguities.isEmpty()) {
                guiGraphics.drawString(this.font, Component.literal("Ambiguities (Select recipe):"), rpContentX, currentY, COLOR_AMBER);
                currentY += 12 + ambiguities.size() * 16 + 4;
            }
            
            guiGraphics.drawString(this.font, Component.literal("Machines Needed:"), rpContentX, currentY, COLOR_GREEN);
            currentY += 12;
            List<MachineRequirement> machines = this.currentPlan.machines();
            if (machines.isEmpty()) {
                guiGraphics.drawString(this.font, Component.literal(" - None"), rpContentX + 4, currentY, COLOR_TEXT);
                currentY += 10;
            } else {
                int displayed = Math.min(machines.size(), 3);
                for (int i = 0; i < displayed; i++) {
                    MachineRequirement req = machines.get(i);
                    guiGraphics.drawString(this.font, Component.literal(String.format(" - %.1f x %s", req.count(), formatId(req.machineId()))), rpContentX + 4, currentY, COLOR_TEXT);
                    currentY += 10;
                }
                if (machines.size() > 3) {
                    guiGraphics.drawString(this.font, Component.literal(" - ... and " + (machines.size() - 3) + " more"), rpContentX + 4, currentY, COLOR_MUTED);
                    currentY += 10;
                }
            }
            
            currentY += 4;
            guiGraphics.drawString(this.font, Component.literal("Raw Inputs Needed:"), rpContentX, currentY, COLOR_CYAN);
            currentY += 12;
            List<MaterialFlow> rawInputs = this.currentPlan.rawInputs();
            if (rawInputs.isEmpty()) {
                guiGraphics.drawString(this.font, Component.literal(" - None"), rpContentX + 4, currentY, COLOR_TEXT);
                currentY += 10;
            } else {
                int displayed = Math.min(rawInputs.size(), 3);
                for (int i = 0; i < displayed; i++) {
                    MaterialFlow flow = rawInputs.get(i);
                    double rateVal = flow.rate() * (this.perHour ? 60.0 : 1.0);
                    guiGraphics.drawString(this.font, Component.literal(String.format(" - %.2f/%s %s", rateVal, this.perHour ? "hr" : "min", formatId(flow.resourceId()))), rpContentX + 4, currentY, COLOR_TEXT);
                    currentY += 10;
                }
                if (rawInputs.size() > 3) {
                    guiGraphics.drawString(this.font, Component.literal(" - ... and " + (rawInputs.size() - 3) + " more"), rpContentX + 4, currentY, COLOR_MUTED);
                    currentY += 10;
                }
            }

            currentY += 4;
            guiGraphics.drawString(this.font, Component.literal("Linked Factory Nodes:"), rpContentX, currentY, COLOR_AMBER);
            currentY += 12;
            if (this.liveData.isEmpty()) {
                guiGraphics.drawString(this.font, Component.literal(" - None linked yet"), rpContentX + 4, currentY, COLOR_MUTED);
            } else {
                int displayed = Math.min(this.liveData.size(), 4);
                for (int i = 0; i < displayed; i++) {
                    var machine = this.liveData.get(i);
                    int color = COLOR_MUTED;
                    if ("GREEN".equals(machine.status())) color = COLOR_GREEN;
                    else if ("YELLOW".equals(machine.status())) color = COLOR_AMBER;
                    else if ("RED".equals(machine.status())) color = COLOR_ERROR;
                    else if ("ORANGE".equals(machine.status())) color = 0xFFE67700;

                    double rateVal = machine.actualRate() * (this.perHour ? 60.0 : 1.0);
                    String text = String.format(" - %s: %.2f/%s (%s)", formatId(machine.machineId()), rateVal, this.perHour ? "hr" : "min", machine.status());
                    guiGraphics.drawString(this.font, Component.literal(text), rpContentX + 4, currentY, color);
                    currentY += 10;
                }
                if (this.liveData.size() > 4) {
                    guiGraphics.drawString(this.font, Component.literal(" - ... and " + (this.liveData.size() - 4) + " more"), rpContentX + 4, currentY, COLOR_MUTED);
                }
            }
        } else {
            guiGraphics.drawString(this.font, Component.literal("No plan generated."), rpContentX, rpContentY + 16, COLOR_MUTED);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws a texture as a 9-slice panel, stretching the center while preserving corners and edges.
     * 
     * @param guiGraphics the graphics context
     * @param texture     the texture ResourceLocation
     * @param x           screen x position
     * @param y           screen y position
     * @param width       desired rendered width
     * @param height      desired rendered height
     * @param border      border size in pixels (corners are border x border)
     * @param texSize     the source texture size (assumes square texture)
     */
    private void blitNineSlice(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int width, int height, int border, int texSize) {
        int innerTexSize = texSize - border * 2;
        int innerWidth = width - border * 2;
        int innerHeight = height - border * 2;

        // Top-left corner
        guiGraphics.blit(texture, x, y, 0, 0, border, border, texSize, texSize);
        // Top-right corner
        guiGraphics.blit(texture, x + width - border, y, texSize - border, 0, border, border, texSize, texSize);
        // Bottom-left corner
        guiGraphics.blit(texture, x, y + height - border, 0, texSize - border, border, border, texSize, texSize);
        // Bottom-right corner
        guiGraphics.blit(texture, x + width - border, y + height - border, texSize - border, texSize - border, border, border, texSize, texSize);

        // Top edge (stretch horizontally)
        blitStretched(guiGraphics, texture, x + border, y, innerWidth, border, border, 0, innerTexSize, border, texSize);
        // Bottom edge (stretch horizontally)
        blitStretched(guiGraphics, texture, x + border, y + height - border, innerWidth, border, border, texSize - border, innerTexSize, border, texSize);
        // Left edge (stretch vertically)
        blitStretched(guiGraphics, texture, x, y + border, border, innerHeight, 0, border, border, innerTexSize, texSize);
        // Right edge (stretch vertically)
        blitStretched(guiGraphics, texture, x + width - border, y + border, border, innerHeight, texSize - border, border, border, innerTexSize, texSize);

        // Center (stretch both)
        blitStretched(guiGraphics, texture, x + border, y + border, innerWidth, innerHeight, border, border, innerTexSize, innerTexSize, texSize);
    }

    /**
     * Tiles a texture region to fill the target area rather than stretching,
     * which avoids distortion on pixel art textures.
     */
    private void blitStretched(GuiGraphics guiGraphics, ResourceLocation texture, int x, int y, int width, int height, int u, int v, int uWidth, int vHeight, int texSize) {
        // Tile the source region across the target area
        for (int ty = 0; ty < height; ty += vHeight) {
            int drawH = Math.min(vHeight, height - ty);
            for (int tx = 0; tx < width; tx += uWidth) {
                int drawW = Math.min(uWidth, width - tx);
                guiGraphics.blit(texture, x + tx, y + ty, u, v, drawW, drawH, texSize, texSize);
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
            this.linkedMachines
        );

        new GoalUpdatePayload(finalGoal).sendToServer();
        this.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
