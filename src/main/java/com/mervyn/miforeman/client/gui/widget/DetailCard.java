package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.client.gui.ColourPalette;
import com.mervyn.miforeman.client.gui.ColourPalette.ColourKey;
import com.mervyn.miforeman.client.gui.GuiIcon;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import com.mervyn.miforeman.goal.GraphEdge;
import com.mervyn.miforeman.goal.MachineStatus;
import com.mervyn.miforeman.goal.NodeType;
import com.mervyn.miforeman.goal.RecipeGraph;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.MachineRequirement;
import com.mervyn.miforeman.goal.ProductionGoal.TargetType;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import aztech.modern_industrialization.client.util.RenderHelper;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import aztech.modern_industrialization.thirdparty.fabrictransfer.api.fluid.FluidVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

public class DetailCard extends AbstractWidget {
    private static final int COLOUR_BORDER = 0xFF6B5030;
    private static final int COLOUR_TITLE = 0xFF4A2E0A;
    private static final int COLOUR_LABEL = 0xFF6B5030;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_GREEN = MachineStatus.GREEN.colour();
    private static final int COLOUR_CYAN = 0xFF006080;
    private static final int COLOUR_AMBER = MachineStatus.YELLOW.colour();
    private static final int COLOUR_CYCLE_BOX = ColourKey.LOCATE_BUTTON.defaultArgb;
    private static final int COLOUR_CYCLE_HOVER = 0xFFEAE7D9;

    public record Callbacks(
            BiConsumer<ResourceLocation, ResourceLocation> onAmbiguity,
            Runnable onToggleNumbers,
            @Nullable Consumer<ResourceLocation> onToggleVisibility,
            @Nullable Runnable onUnhideAll,
            @Nullable Predicate<ResourceLocation> isHiddenPredicate,
            @Nullable Consumer<ResourceLocation> onExpandMaterial,
            @Nullable Predicate<ResourceLocation> hasManualSelection
    ) {
        public Callbacks(BiConsumer<ResourceLocation, ResourceLocation> onAmbiguity, Runnable onToggleNumbers) {
            this(onAmbiguity, onToggleNumbers, null, null, null, null, null);
        }
    }

    public record DisplayOptions(boolean perHour, boolean showNumbers, boolean expanded) {
    }

    private static final int ICON_SIZE = 16;
    private static final int RESOURCE_ROW_HEIGHT = 18;

    private @Nullable RecipeGraphNode node;
    private final FactoryPlan plan;
    private final List<ResourceRow> summaryInputRows;
    private final List<ResourceRow> summaryOutputRows;
    private final boolean perHour;
    private final boolean showNumbers;
    private final boolean expanded;
    private final Callbacks callbacks;
    private final IntConsumer onScrollChange;
    private int scrollOffset;
    private int totalContentHeight = 0;

    /** Drawn at the end of renderWidget, after disableScissor, so the scroll area never clips it. */
    private @Nullable Component pendingTooltip;
    private int pendingTooltipX;
    private int pendingTooltipY;

    private int cycleBoxX = 0;
    private int cycleBoxY = 0;
    private int cycleBoxW = 0;
    private int cycleBoxH = 0;
    private boolean isCycleBoxHovered = false;
    private int autoBoxX = 0;
    private int autoBoxY = 0;
    private int autoBoxW = 0;
    private int autoBoxH = 0;
    private boolean isAutoBoxHovered = false;

    private int numToggleBoxX = 0;
    private int numToggleBoxY = 0;
    private int numToggleBoxW = 0;
    private int numToggleBoxH = 0;
    private boolean isNumToggleHovered = false;

    private int nodeVisBoxX = 0;
    private int nodeVisBoxY = 0;
    private int nodeVisBoxW = 0;
    private int nodeVisBoxH = 0;
    private boolean isNodeVisHovered = false;

    private int expandBoxX = 0;
    private int expandBoxY = 0;
    private int expandBoxW = 0;
    private int expandBoxH = 0;
    private boolean isExpandBoxHovered = false;

    public DetailCard(int x, int y, int width, int height, @Nullable RecipeGraphNode node,
                      FactoryPlan plan, DisplayOptions options, Callbacks callbacks,
                      int initialScrollOffset, IntConsumer onScrollChange) {
        super(x, y, width, height, Component.translatable("miforeman.widget.detail_card"));
        this.node = node;
        this.plan = plan;
        this.summaryInputRows = plan.rawInputs().stream()
                .map(flow -> new ResourceRow(flow.resourceId(), flow.rate())).toList();
        RecipeGraph summaryGraph = plan.graph();
        this.summaryOutputRows = summaryGraph == null ? List.of()
                : RecipeGraphTraverser.collectByproductRates(summaryGraph).entrySet().stream()
                        .map(e -> new ResourceRow(e.getKey(), e.getValue()))
                        .toList();
        this.perHour = options.perHour();
        this.showNumbers = options.showNumbers();
        this.expanded = options.expanded();
        this.callbacks = callbacks;
        this.scrollOffset = initialScrollOffset;
        this.onScrollChange = onScrollChange;
    }

    private @Nullable com.mervyn.miforeman.goal.CapacitySolver.Result capacity;

    public void setCapacity(@Nullable com.mervyn.miforeman.goal.CapacitySolver.Result capacity) {
        this.capacity = capacity;
    }

    public void setNode(@Nullable RecipeGraphNode node) {
        this.node = node;
        this.scrollOffset = 0;
        this.onScrollChange.accept(0);
    }

    private boolean hasMaterialCandidates() {
        return node != null
                && node.getType() != NodeType.MACHINE
                && node.getInputs().isEmpty()
                && callbacks.onExpandMaterial() != null
                && Minecraft.getInstance().level != null
                && !RecipeGraphTraverser.getCandidateRecipes(Minecraft.getInstance().level, node.getId()).isEmpty();
    }

    private record ResourceRow(ResourceLocation id, double rate) {}

    private int renderResourcePanel(GuiGraphics guiGraphics, String title, ColourKey colourKey,
                                     List<ResourceRow> rows, int x, int y, int width, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int headerHeight = 12;
        int rowsHeight = Math.max(1, rows.size()) * RESOURCE_ROW_HEIGHT;
        int panelHeight = headerHeight + rowsHeight + 4;

        guiGraphics.fill(x, y, x + width, y + panelHeight, ColourPalette.get(colourKey));
        guiGraphics.renderOutline(x, y, width, panelHeight, COLOUR_BORDER);
        guiGraphics.drawString(mc.font, title, x + 4, y + 3, COLOUR_TITLE, false);

        int rowY = y + headerHeight;
        if (rows.isEmpty()) {
            guiGraphics.drawString(mc.font, I18n.get("miforeman.detail.none"), x + 4, rowY + 4, COLOUR_MUTED, false);
        } else {
            for (ResourceRow row : rows) {
                renderResourceRow(guiGraphics, row.id(), row.rate(), x + 4, rowY, mouseX, mouseY);
                rowY += RESOURCE_ROW_HEIGHT;
            }
        }
        return panelHeight;
    }

    private void renderResourceRow(GuiGraphics guiGraphics, ResourceLocation resourceId, double rate,
                                    int x, int y, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        renderResourceIcon(guiGraphics, resourceId, x, y);

        String rateText = DisplayFormat.formatRate(rate, perHour);
        guiGraphics.drawString(mc.font, rateText, x + ICON_SIZE + 4, y + (ICON_SIZE - 9) / 2, COLOUR_TEXT, false);

        if (RenderHelper.isPointWithinRectangle(x, y, ICON_SIZE, ICON_SIZE, mouseX, mouseY)) {
            pendingTooltip = Component.literal(DisplayFormat.formatId(resourceId));
            pendingTooltipX = mouseX;
            pendingTooltipY = mouseY;
        }
    }

    private void renderResourceIcon(GuiGraphics guiGraphics, ResourceLocation resourceId, int x, int y) {
        if (RecipeGraphTraverser.getItemOrFluidType(resourceId) == TargetType.FLUID) {
            RenderHelper.drawFluidInGui(guiGraphics, FluidVariant.of(BuiltInRegistries.FLUID.get(resourceId)), x, y);
        } else {
            RenderHelper.renderAndDecorateItem(guiGraphics, Minecraft.getInstance().font,
                    new ItemStack(BuiltInRegistries.ITEM.get(resourceId)), x, y);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(getX(), getY(), getX() + 1, getY() + getHeight(), COLOUR_BORDER);

        int maxScroll = Math.max(0, totalContentHeight - getHeight() + 8);
        if (totalContentHeight > 0) {
            scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        }

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        int currentY = getY() + 4 - scrollOffset;
        Minecraft fontSource = Minecraft.getInstance();

        isCycleBoxHovered = false;
        cycleBoxW = 0;
        cycleBoxH = 0;
        isAutoBoxHovered = false;
        autoBoxW = 0;
        autoBoxH = 0;
        isNumToggleHovered = false;
        numToggleBoxW = 0;
        numToggleBoxH = 0;
        isNodeVisHovered = false;
        nodeVisBoxW = 0;
        nodeVisBoxH = 0;
        isExpandBoxHovered = false;
        expandBoxW = 0;
        expandBoxH = 0;
        pendingTooltip = null;

        if (node == null) {
            RecipeGraph graph = plan.graph();
            String title = graph != null ? DisplayFormat.formatId(graph.target()) : I18n.get("miforeman.detail.factory_summary");
            guiGraphics.drawString(fontSource.font, title, getX() + 6, currentY, COLOUR_TITLE, false);
            currentY += 12;

            long totalPower = plan.totalPowerDemandEu();
            String metaLine = totalPower > 0
                    ? I18n.get("miforeman.detail.machines_power", plan.machines().size(), totalPower)
                    : I18n.get("miforeman.detail.machines", plan.machines().size());
            guiGraphics.drawString(fontSource.font, metaLine, getX() + 6, currentY, COLOUR_MUTED, false);
            currentY += 15;

            if (graph != null) {
                String targetLine = I18n.get("miforeman.detail.target", DisplayFormat.formatRate(graph.targetRate(), perHour),
                        DisplayFormat.formatId(graph.target()));
                guiGraphics.drawString(fontSource.font, targetLine, getX() + 6, currentY, COLOUR_LABEL, false);
                currentY += 12;
                if (capacity != null) {
                    boolean enough = capacity.maxRate() >= graph.targetRate() * (1 - 1e-6);
                    guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.can_make", DisplayFormat.formatRate(capacity.maxRate(), perHour),
                            DisplayFormat.formatRate(graph.targetRate(), perHour)),
                            getX() + 6, currentY, enough ? COLOUR_GREEN : COLOUR_AMBER, false);
                    currentY += 10;
                    var limits = !capacity.blockers().isEmpty() ? capacity.blockers() : capacity.bottlenecks();
                    if (!enough && !limits.isEmpty()) {
                        List<String> names = limits.stream().limit(2)
                                .map(id -> DisplayFormat.formatId(ResourceLocation.parse(id))).toList();
                        String list = String.join(", ", names);
                        if (limits.size() > 2) list = I18n.get("miforeman.detail.and_more", list, limits.size() - 2);
                        String label = I18n.get(!capacity.blockers().isEmpty()
                                ? "miforeman.detail.no_machines" : "miforeman.detail.limited_by", list);
                        if (fontSource.font.width(label) > getWidth() - 16)
                            label = fontSource.font.plainSubstrByWidth(label, getWidth() - 24) + "..";
                        guiGraphics.drawString(fontSource.font, label, getX() + 10, currentY, COLOUR_MUTED, false);
                        currentY += 10;
                    }
                    guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.capacity_note"), getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 12;
                }
            }

            List<ResourceRow> inputRows = summaryInputRows;
            List<ResourceRow> outputRows = summaryOutputRows;

            if (expanded) {
                int gap = 6;
                int panelWidth = (getWidth() - 12 - gap) / 2;
                int panelStartY = currentY;
                int inputsHeight = renderResourcePanel(guiGraphics, I18n.get("miforeman.detail.inputs"), ColourKey.INPUT_PANEL,
                        inputRows, getX() + 6, panelStartY, panelWidth, mouseX, mouseY);
                int outputsHeight = renderResourcePanel(guiGraphics, I18n.get("miforeman.detail.byproducts"), ColourKey.OUTPUT_PANEL,
                        outputRows, getX() + 6 + panelWidth + gap, panelStartY, panelWidth, mouseX, mouseY);
                currentY = panelStartY + Math.max(inputsHeight, outputsHeight) + 8;
            } else {
                int panelWidth = getWidth() - 12;
                currentY += renderResourcePanel(guiGraphics, I18n.get("miforeman.detail.inputs"), ColourKey.INPUT_PANEL,
                        inputRows, getX() + 6, currentY, panelWidth, mouseX, mouseY) + 6;
                currentY += renderResourcePanel(guiGraphics, I18n.get("miforeman.detail.byproducts"), ColourKey.OUTPUT_PANEL,
                        outputRows, getX() + 6, currentY, panelWidth, mouseX, mouseY) + 8;
            }

            String header = I18n.get("miforeman.detail.machines_needed");
            guiGraphics.drawString(fontSource.font, header, getX() + 6, currentY + 1, COLOUR_GREEN, false);

            String toggleText = I18n.get(showNumbers ? "miforeman.detail.numbers_on" : "miforeman.detail.numbers_off");
            int toggleTextW = fontSource.font.width(toggleText);
            int headerWidth = fontSource.font.width(header);
            numToggleBoxX = getX() + 6 + headerWidth + 6;
            numToggleBoxY = currentY;
            numToggleBoxW = toggleTextW + 6;
            numToggleBoxH = 10;

            isNumToggleHovered = mouseX >= numToggleBoxX && mouseX < numToggleBoxX + numToggleBoxW &&
                                 mouseY >= numToggleBoxY && mouseY < numToggleBoxY + numToggleBoxH;

            int pillBg = isNumToggleHovered ? COLOUR_CYCLE_HOVER : COLOUR_CYCLE_BOX;
            guiGraphics.fill(numToggleBoxX, numToggleBoxY, numToggleBoxX + numToggleBoxW, numToggleBoxY + numToggleBoxH, pillBg);
            guiGraphics.renderOutline(numToggleBoxX, numToggleBoxY, numToggleBoxW, numToggleBoxH, COLOUR_BORDER);
            guiGraphics.drawString(fontSource.font, toggleText, numToggleBoxX + 3, numToggleBoxY + 1, COLOUR_TEXT, false);

            currentY += 13;
            List<MachineRequirement> machines = plan.machines();
            if (machines.isEmpty()) {
                guiGraphics.drawString(fontSource.font, " - " + I18n.get("miforeman.detail.none"), getX() + 10, currentY, COLOUR_MUTED, false);
                currentY += 10;
            } else {
                for (MachineRequirement req : machines) {
                    String machLine;
                    if (showNumbers) {
                        machLine = req.totalEuPerTick() > 0
                                ? String.format(" - %.1f x %s (%d EU/t)", req.count(), DisplayFormat.formatId(req.machineId()), req.totalEuPerTick())
                                : String.format(" - %.1f x %s", req.count(), DisplayFormat.formatId(req.machineId()));
                    } else {
                        machLine = String.format(" - %s", DisplayFormat.formatId(req.machineId()));
                    }
                    guiGraphics.drawString(fontSource.font, machLine, getX() + 10, currentY, COLOUR_TEXT, false);
                    currentY += 10;
                }
            }
        } else {
            String title = DisplayFormat.formatId(node.getId());
            if (node.getType() == NodeType.MACHINE) {
                title = I18n.get("miforeman.detail.recipe_title", title);
            }
            guiGraphics.drawString(fontSource.font, title, getX() + 6, currentY, COLOUR_TITLE, false);
            currentY += 13;

            boolean isHidden = callbacks.isHiddenPredicate() != null && callbacks.isHiddenPredicate().test(node.getId());
            String visText = I18n.get(isHidden ? "miforeman.detail.unhide" : "miforeman.detail.hide");
            int visTextW = fontSource.font.width(visText);
            nodeVisBoxX = getX() + 6;
            nodeVisBoxY = currentY;
            nodeVisBoxW = visTextW + 6;
            nodeVisBoxH = 10;
            isNodeVisHovered = mouseX >= nodeVisBoxX && mouseX < nodeVisBoxX + nodeVisBoxW &&
                               mouseY >= nodeVisBoxY && mouseY < nodeVisBoxY + nodeVisBoxH;
            int visBg = isNodeVisHovered ? COLOUR_CYCLE_HOVER : COLOUR_CYCLE_BOX;
            guiGraphics.fill(nodeVisBoxX, nodeVisBoxY, nodeVisBoxX + nodeVisBoxW, nodeVisBoxY + nodeVisBoxH, visBg);
            guiGraphics.renderOutline(nodeVisBoxX, nodeVisBoxY, nodeVisBoxW, nodeVisBoxH, COLOUR_BORDER);
            guiGraphics.drawString(fontSource.font, visText, nodeVisBoxX + 3, nodeVisBoxY + 1, isHidden ? COLOUR_MUTED : COLOUR_TEXT, false);
            currentY += 15;

            if (node.getType() == NodeType.MACHINE) {
                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.machine_count"), getX() + 6, currentY, COLOUR_LABEL, false);
                String countLine = String.format(" %.2f x %s", node.getMachineCount(), DisplayFormat.formatId(node.getMachineType()));
                guiGraphics.drawString(fontSource.font, countLine, getX() + 10, currentY + 10, COLOUR_TEXT, false);
                currentY += 24;

                MachineRecipe recipe = node.getRecipe();
                long baseEu = node.getBaseEuPerTick() > 0 ? node.getBaseEuPerTick() : (recipe != null ? recipe.eu : 0);
                long totalEu = node.getTotalEuPerTick() > 0 ? node.getTotalEuPerTick() : (long) Math.ceil(node.getMachineCount() * baseEu);
                if (recipe != null || totalEu > 0) {
                    guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.power_duration"), getX() + 6, currentY, COLOUR_LABEL, false);
                    String infoLine = recipe != null
                            ? " " + I18n.get("miforeman.detail.duration_power", String.format("%.1f", recipe.duration / 20.0), baseEu, totalEu)
                            : " " + I18n.get("miforeman.detail.power", baseEu, totalEu);
                    guiGraphics.drawString(fontSource.font, infoLine, getX() + 10, currentY + 10, COLOUR_MUTED, false);
                    currentY += 24;
                }

                if (recipe != null && !recipe.conditions.isEmpty()) {
                    guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.requirements"), getX() + 6, currentY, COLOUR_AMBER, false);
                    currentY += 10;
                    List<Component> conditionLines = new java.util.ArrayList<>();
                    for (var condition : recipe.conditions) {
                        condition.appendDescription(conditionLines);
                    }
                    for (Component line : conditionLines) {
                        guiGraphics.drawString(fontSource.font, " - " + line.getString(), getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                    currentY += 5;
                }

                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.recipe_inputs"), getX() + 6, currentY, COLOUR_CYAN, false);
                currentY += 10;
                if (node.getInputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - " + I18n.get("miforeman.detail.none"), getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getInputs()) {
                        String inputLine = " - " + DisplayFormat.formatRate(edge.rate(), perHour) + " " + DisplayFormat.formatId(edge.from());
                        guiGraphics.drawString(fontSource.font, inputLine, getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }
                currentY += 5;

                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.recipe_outputs"), getX() + 6, currentY, COLOUR_GREEN, false);
                currentY += 10;
                if (node.getOutputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - " + I18n.get("miforeman.detail.none"), getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getOutputs()) {
                        String outputLine = " - " + DisplayFormat.formatRate(edge.rate(), perHour) + " " + DisplayFormat.formatId(edge.to());
                        guiGraphics.drawString(fontSource.font, outputLine, getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }

            } else {
                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.required_rate"), getX() + 6, currentY, COLOUR_LABEL, false);
                double rateVal = node.getRequiredRate() * (perHour ? 60.0 : 1.0);
                String rateText = " " + I18n.get(perHour ? "miforeman.detail.units_per_hour" : "miforeman.detail.units_per_min",
                        String.format("%.2f", rateVal));
                guiGraphics.drawString(fontSource.font, rateText, getX() + 10, currentY + 10, COLOUR_TEXT, false);
                currentY += 24;

                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.produced_by"), getX() + 6, currentY, COLOUR_GREEN, false);
                currentY += 10;
                if (node.getInputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - " + I18n.get("miforeman.detail.raw_input"), getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                    if (hasMaterialCandidates()) {
                        String expandText = I18n.get("miforeman.detail.expand_material");
                        int expandTextW = fontSource.font.width(expandText);
                        expandBoxX = getX() + 10;
                        expandBoxY = currentY;
                        expandBoxW = expandTextW + 6;
                        expandBoxH = 10;
                        isExpandBoxHovered = GuiMath.contains(expandBoxX, expandBoxY, expandBoxW, expandBoxH, mouseX, mouseY);
                        int expandBg = isExpandBoxHovered ? COLOUR_CYCLE_HOVER : COLOUR_CYCLE_BOX;
                        guiGraphics.fill(expandBoxX, expandBoxY, expandBoxX + expandBoxW, expandBoxY + expandBoxH, expandBg);
                        guiGraphics.renderOutline(expandBoxX, expandBoxY, expandBoxW, expandBoxH, COLOUR_BORDER);
                        guiGraphics.drawString(fontSource.font, expandText, expandBoxX + 3, expandBoxY + 1, COLOUR_TEXT, false);
                        currentY += 12;
                    }
                } else {
                    for (GraphEdge edge : node.getInputs()) {
                        guiGraphics.drawString(fontSource.font, " - " + DisplayFormat.formatId(edge.from()), getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }
                currentY += 5;

                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.consumed_by"), getX() + 6, currentY, COLOUR_CYAN, false);
                currentY += 10;
                if (node.getOutputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - " + I18n.get("miforeman.detail.final_product"), getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getOutputs()) {
                        guiGraphics.drawString(fontSource.font, " - " + DisplayFormat.formatId(edge.to()), getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }
            }

            if (plan.graph() != null && plan.graph().unsourcedResourceIds().contains(node.getId())) {
                currentY += 8;
                GuiIcon.WARNING.draw(guiGraphics, getX() + 6, currentY - 1, COLOUR_AMBER);
                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.dead_loop"), getX() + 8 + GuiIcon.WARNING.width(), currentY, COLOUR_AMBER, false);
                currentY += 10;
                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.dead_loop_hint"), getX() + 10, currentY, COLOUR_MUTED, false);
                currentY += 10;
            }
            if (plan.graph() != null && plan.graph().autoImports().contains(node.getId())) {
                currentY += 8;
                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.imported_1"), getX() + 6, currentY, COLOUR_MUTED, false);
                currentY += 10;
                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.imported_2"), getX() + 10, currentY, COLOUR_MUTED, false);
                currentY += 10;
            }

            if (!node.getAmbiguityOptions().isEmpty()) {
                ResourceLocation owner = node.getType() == NodeType.MACHINE ? node.getAmbiguityOwnerId() : node.getId();
                boolean manual = owner != null && callbacks.hasManualSelection() != null
                        && callbacks.hasManualSelection().test(owner);
                currentY += 8;
                guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.alternatives"), getX() + 6, currentY, COLOUR_AMBER, false);
                currentY += 12;

                cycleBoxX = getX() + 10;
                cycleBoxY = currentY;
                cycleBoxW = getWidth() - 20 - (manual ? 34 : 0);
                cycleBoxH = 14;

                isCycleBoxHovered = mouseX >= cycleBoxX && mouseX < cycleBoxX + cycleBoxW &&
                                    mouseY >= cycleBoxY && mouseY < cycleBoxY + cycleBoxH;

                int boxColour = isCycleBoxHovered ? COLOUR_CYCLE_HOVER : COLOUR_CYCLE_BOX;
                guiGraphics.fill(cycleBoxX, cycleBoxY, cycleBoxX + cycleBoxW, cycleBoxY + cycleBoxH, boxColour);
                guiGraphics.renderOutline(cycleBoxX, cycleBoxY, cycleBoxW, cycleBoxH, COLOUR_BORDER);

                String cycleText = node.getSelectedAmbiguity() == null
                        ? I18n.get("miforeman.detail.pick_recipe", node.getAmbiguityOptions().size())
                        : I18n.get("miforeman.detail.cycle_recipe", node.getAmbiguityOptions().indexOf(node.getSelectedAmbiguity()) + 1,
                                node.getAmbiguityOptions().size());
                int textWidth = fontSource.font.width(cycleText);
                int textX = cycleBoxX + (cycleBoxW - textWidth) / 2;
                guiGraphics.drawString(fontSource.font, cycleText, textX, cycleBoxY + 3, COLOUR_TEXT, false);

                if (manual) {
                    autoBoxX = cycleBoxX + cycleBoxW + 4;
                    autoBoxY = cycleBoxY;
                    autoBoxW = 30;
                    autoBoxH = cycleBoxH;
                    isAutoBoxHovered = mouseX >= autoBoxX && mouseX < autoBoxX + autoBoxW
                            && mouseY >= autoBoxY && mouseY < autoBoxY + autoBoxH;
                    guiGraphics.fill(autoBoxX, autoBoxY, autoBoxX + autoBoxW, autoBoxY + autoBoxH,
                            isAutoBoxHovered ? COLOUR_CYCLE_HOVER : COLOUR_CYCLE_BOX);
                    guiGraphics.renderOutline(autoBoxX, autoBoxY, autoBoxW, autoBoxH, COLOUR_BORDER);
                    String autoText = I18n.get("miforeman.detail.auto");
                    guiGraphics.drawString(fontSource.font, autoText, autoBoxX + (autoBoxW - fontSource.font.width(autoText)) / 2,
                            autoBoxY + 3, COLOUR_TEXT, false);
                }
                currentY += 18;
                if (owner != null && plan.graph() != null && plan.graph().autoSelections().containsKey(owner)) {
                    guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.auto_picked_1"), getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                    guiGraphics.drawString(fontSource.font, I18n.get("miforeman.detail.auto_picked_2"), getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                }
            }
        }

        totalContentHeight = currentY + scrollOffset - getY();
        guiGraphics.disableScissor();

        if (maxScroll > 0) {
            int scrollbarWidth = 4;
            int scrollbarHeight = (int) (((double) getHeight() / totalContentHeight) * getHeight());
            scrollbarHeight = Math.max(10, scrollbarHeight);
            int scrollbarX = getX() + getWidth() - scrollbarWidth - 2;
            int scrollbarY = getY() + 2 + (int) (((double) scrollOffset / maxScroll) * (getHeight() - scrollbarHeight - 4));

            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, COLOUR_BORDER);
        }

        // After disableScissor, so a tooltip near the scroll edge isn't clipped.
        if (pendingTooltip != null) {
            guiGraphics.renderTooltip(fontSource.font, pendingTooltip, pendingTooltipX, pendingTooltipY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        if (button == 0 && node == null && callbacks.onToggleNumbers() != null &&
                mouseX >= numToggleBoxX && mouseX < numToggleBoxX + numToggleBoxW &&
                mouseY >= numToggleBoxY && mouseY < numToggleBoxY + numToggleBoxH) {
            callbacks.onToggleNumbers().run();
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }

        if (button == 0 && node != null && callbacks.onToggleVisibility() != null &&
                mouseX >= nodeVisBoxX && mouseX < nodeVisBoxX + nodeVisBoxW &&
                mouseY >= nodeVisBoxY && mouseY < nodeVisBoxY + nodeVisBoxH) {
            callbacks.onToggleVisibility().accept(node.getId());
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }

        if (button == 0 && node != null && callbacks.onExpandMaterial() != null &&
                GuiMath.contains(expandBoxX, expandBoxY, expandBoxW, expandBoxH, mouseX, mouseY)) {
            callbacks.onExpandMaterial().accept(node.getId());
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }

        if (button == 0 && node != null && autoBoxW > 0 && GuiMath.contains(autoBoxX, autoBoxY, autoBoxW, autoBoxH, mouseX, mouseY)) {
            ResourceLocation owner = node.getType() == NodeType.MACHINE ? node.getAmbiguityOwnerId() : node.getId();
            if (owner != null) {
                callbacks.onAmbiguity().accept(owner, null);
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
        }

        if (node != null && !node.getAmbiguityOptions().isEmpty()) {
            if (mouseX >= cycleBoxX && mouseX < cycleBoxX + cycleBoxW &&
                mouseY >= cycleBoxY && mouseY < cycleBoxY + cycleBoxH) {
                
                List<ResourceLocation> options = node.getAmbiguityOptions();
                int idx = options.indexOf(node.getSelectedAmbiguity());
                int nextIdx = (idx + 1) % options.size();
                ResourceLocation nextSel = options.get(nextIdx);

                ResourceLocation resourceId = node.getType() == NodeType.MACHINE
                        ? node.getAmbiguityOwnerId()
                        : node.getId();

                if (resourceId != null) {
                    callbacks.onAmbiguity().accept(resourceId, nextSel);
                    this.playDownSound(Minecraft.getInstance().getSoundManager());
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !active) return false;

        int maxScroll = Math.max(0, totalContentHeight - getHeight() + 8);
        if (maxScroll > 0) {
            scrollOffset = Mth.clamp(scrollOffset - (int) (scrollY * 12 * 2), 0, maxScroll);
            onScrollChange.accept(scrollOffset);
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
