package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.client.DisplayFormat;
import com.mervyn.miforeman.goal.RecipeGraphNode;
import com.mervyn.miforeman.goal.GraphEdge;
import com.mervyn.miforeman.goal.NodeType;
import com.mervyn.miforeman.goal.ProductionGoal.FactoryPlan;
import com.mervyn.miforeman.goal.ProductionGoal.MachineRequirement;
import com.mervyn.miforeman.goal.ProductionGoal.MaterialFlow;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

public class DetailCard extends AbstractWidget {
    private static final int COLOUR_BORDER = 0xFF6B5030;
    private static final int COLOUR_TITLE = 0xFF4A2E0A;
    private static final int COLOUR_LABEL = 0xFF6B5030;
    private static final int COLOUR_TEXT = 0xFF3A2A18;
    private static final int COLOUR_MUTED = 0xFF8A7A68;
    private static final int COLOUR_GREEN = 0xFF2E7D32;
    private static final int COLOUR_CYAN = 0xFF006080;
    private static final int COLOUR_AMBER = 0xFF9A6C00;
    private static final int COLOUR_CYCLE_BOX = 0xFFD8C3A5;
    private static final int COLOUR_CYCLE_HOVER = 0xFFEAE7D9;

    private @Nullable RecipeGraphNode node;
    private final FactoryPlan plan;
    private final boolean perHour;
    private final boolean showNumbers;
    private final BiConsumer<ResourceLocation, ResourceLocation> onAmbiguity;
    private final Runnable onToggleNumbers;
    private final IntConsumer onScrollChange;
    private int scrollOffset;
    private int totalContentHeight = 0;

    // Boundaries of the inline cycle recipe box (for mouse click detection)
    private int cycleBoxX = 0;
    private int cycleBoxY = 0;
    private int cycleBoxW = 0;
    private int cycleBoxH = 0;
    private boolean isCycleBoxHovered = false;

    // Boundaries of the inline numbers toggle box
    private int numToggleBoxX = 0;
    private int numToggleBoxY = 0;
    private int numToggleBoxW = 0;
    private int numToggleBoxH = 0;
    private boolean isNumToggleHovered = false;

    public DetailCard(int x, int y, int width, int height, @Nullable RecipeGraphNode node,
                      FactoryPlan plan, boolean perHour, boolean showNumbers,
                      BiConsumer<ResourceLocation, ResourceLocation> onAmbiguity,
                      Runnable onToggleNumbers,
                      int initialScrollOffset, IntConsumer onScrollChange) {
        super(x, y, width, height, Component.literal("Detail Card"));
        this.node = node;
        this.plan = plan;
        this.perHour = perHour;
        this.showNumbers = showNumbers;
        this.onAmbiguity = onAmbiguity;
        this.onToggleNumbers = onToggleNumbers;
        this.scrollOffset = initialScrollOffset;
        this.onScrollChange = onScrollChange;
    }

    public void setNode(@Nullable RecipeGraphNode node) {
        this.node = node;
        this.scrollOffset = 0;
        this.onScrollChange.accept(0);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // No border/fill of its own -- ClipboardScreen's own main panel is already the
        // background here (drawn before widgets, in Screen.render()); a second bordered panel
        // read as a nested "clipboard within the clipboard" rather than one continuous surface.
        // Just a thin divider on the shared edge with GraphCanvas, hinting at the boundary
        // between the two content areas without drawing a second frame.
        guiGraphics.fill(getX(), getY(), getX() + 1, getY() + getHeight(), COLOUR_BORDER);

        int maxScroll = Math.max(0, totalContentHeight - getHeight() + 8);
        // totalContentHeight is only known after a render pass has measured it (set at the end of
        // this method) -- on a freshly-constructed instance it's still 0, so skip the clamp on that
        // first frame or it would immediately zero out a restored initialScrollOffset before the
        // real content height is ever known.
        if (totalContentHeight > 0) {
            scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        }

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        int currentY = getY() + 4 - scrollOffset;
        Minecraft fontSource = Minecraft.getInstance();

        isCycleBoxHovered = false;
        cycleBoxW = 0;
        cycleBoxH = 0;
        isNumToggleHovered = false;
        numToggleBoxW = 0;
        numToggleBoxH = 0;

        if (node == null) {
            // Summary Mode
            guiGraphics.drawString(fontSource.font, "Factory Summary", getX() + 6, currentY, COLOUR_TITLE, false);
            currentY += 15;

            // Goal Output
            guiGraphics.drawString(fontSource.font, "Target Goal:", getX() + 6, currentY, COLOUR_LABEL, false);
            currentY += 10;
            String targetText = String.format(" - %.2f/%s %s",
                    plan.graph() != null ? plan.graph().targetRate() * (perHour ? 60.0 : 1.0) : 1.0,
                    perHour ? "h" : "m",
                    plan.graph() != null ? DisplayFormat.formatId(plan.graph().target()) : "Goal");
            guiGraphics.drawString(fontSource.font, targetText, getX() + 10, currentY, COLOUR_TEXT, false);
            currentY += 15;

            // Total Power Demand
            long totalPower = plan.totalPowerDemandEu();
            if (totalPower > 0) {
                String powerLine = String.format("Total Power: %d EU/t", totalPower);
                guiGraphics.drawString(fontSource.font, powerLine, getX() + 6, currentY, COLOUR_AMBER, false);
                currentY += 14;
            }

            // Machines Needed Header + Toggle Pill
            String header = "Machines Needed:";
            guiGraphics.drawString(fontSource.font, header, getX() + 6, currentY + 1, COLOUR_GREEN, false);

            String toggleText = showNumbers ? "# On" : "# Off";
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
                guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOUR_MUTED, false);
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
            currentY += 5;

            // Raw Inputs Needed
            guiGraphics.drawString(fontSource.font, "Raw Inputs:", getX() + 6, currentY, COLOUR_CYAN, false);
            currentY += 10;
            List<MaterialFlow> rawInputs = plan.rawInputs();
            if (rawInputs.isEmpty()) {
                guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOUR_MUTED, false);
                currentY += 10;
            } else {
                for (MaterialFlow flow : rawInputs) {
                    double rateVal = flow.rate() * (perHour ? 60.0 : 1.0);
                    String flowLine = String.format(" - %.1f/%s %s", rateVal, perHour ? "h" : "m", DisplayFormat.formatId(flow.resourceId()));
                    guiGraphics.drawString(fontSource.font, flowLine, getX() + 10, currentY, COLOUR_TEXT, false);
                    currentY += 10;
                }
            }
        } else {
            // Node Details Mode
            String title = DisplayFormat.formatId(node.getId());
            if (node.getType() == NodeType.MACHINE) {
                title = "Recipe: " + title;
            }
            guiGraphics.drawString(fontSource.font, title, getX() + 6, currentY, COLOUR_TITLE, false);
            currentY += 15;

            if (node.getType() == NodeType.MACHINE) {
                // Machine Node Details
                guiGraphics.drawString(fontSource.font, "Machine Count:", getX() + 6, currentY, COLOUR_LABEL, false);
                String countLine = String.format(" %.2f x %s", node.getMachineCount(), DisplayFormat.formatId(node.getMachineType()));
                guiGraphics.drawString(fontSource.font, countLine, getX() + 10, currentY + 10, COLOUR_TEXT, false);
                currentY += 24;

                // Energy / Duration / Power demand
                MachineRecipe recipe = node.getRecipe();
                long baseEu = node.getBaseEuPerTick() > 0 ? node.getBaseEuPerTick() : (recipe != null ? recipe.eu : 0);
                long totalEu = node.getTotalEuPerTick() > 0 ? node.getTotalEuPerTick() : (long) Math.ceil(node.getMachineCount() * baseEu);
                if (recipe != null || totalEu > 0) {
                    guiGraphics.drawString(fontSource.font, "Power & Duration:", getX() + 6, currentY, COLOUR_LABEL, false);
                    String infoLine = recipe != null
                            ? String.format(" Duration: %.1fs | %d EU/t (%d total)", recipe.duration / 20.0, baseEu, totalEu)
                            : String.format(" Power: %d EU/t (%d total)", baseEu, totalEu);
                    guiGraphics.drawString(fontSource.font, infoLine, getX() + 10, currentY + 10, COLOUR_MUTED, false);
                    currentY += 24;
                }

                // Process conditions (voltage tier, open water, nearby entity, etc. -- e.g. from
                // MI-Tweaks) gate whether this recipe can actually run beyond its item/fluid/EU
                // requirements. Reuse each condition's own appendDescription() instead of trying to
                // describe arbitrary third-party condition types ourselves.
                if (recipe != null && !recipe.conditions.isEmpty()) {
                    guiGraphics.drawString(fontSource.font, "Requirements:", getX() + 6, currentY, COLOUR_AMBER, false);
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

                // Inputs
                guiGraphics.drawString(fontSource.font, "Recipe Inputs:", getX() + 6, currentY, COLOUR_CYAN, false);
                currentY += 10;
                if (node.getInputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getInputs()) {
                        double rateVal = edge.rate() * (perHour ? 60.0 : 1.0);
                        String inputLine = String.format(" - %.1f/%s %s", rateVal, perHour ? "h" : "m", DisplayFormat.formatId(edge.from()));
                        guiGraphics.drawString(fontSource.font, inputLine, getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }
                currentY += 5;

                // Outputs
                guiGraphics.drawString(fontSource.font, "Recipe Outputs:", getX() + 6, currentY, COLOUR_GREEN, false);
                currentY += 10;
                if (node.getOutputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getOutputs()) {
                        double rateVal = edge.rate() * (perHour ? 60.0 : 1.0);
                        String outputLine = String.format(" - %.1f/%s %s", rateVal, perHour ? "h" : "m", DisplayFormat.formatId(edge.to()));
                        guiGraphics.drawString(fontSource.font, outputLine, getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }

            } else {
                // Resource Node Details
                guiGraphics.drawString(fontSource.font, "Required Flow Rate:", getX() + 6, currentY, COLOUR_LABEL, false);
                double rateVal = node.getRequiredRate() * (perHour ? 60.0 : 1.0);
                String rateText = String.format(" %.2f units / %s", rateVal, perHour ? "hour" : "min");
                guiGraphics.drawString(fontSource.font, rateText, getX() + 10, currentY + 10, COLOUR_TEXT, false);
                currentY += 24;

                // Produced by machine recipe
                guiGraphics.drawString(fontSource.font, "Produced By:", getX() + 6, currentY, COLOUR_GREEN, false);
                currentY += 10;
                if (node.getInputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - Raw Material Input", getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getInputs()) {
                        guiGraphics.drawString(fontSource.font, " - " + DisplayFormat.formatId(edge.from()), getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }
                currentY += 5;

                // Consumed by recipe
                guiGraphics.drawString(fontSource.font, "Consumed By:", getX() + 6, currentY, COLOUR_CYAN, false);
                currentY += 10;
                if (node.getOutputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - Final Target Product", getX() + 10, currentY, COLOUR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getOutputs()) {
                        guiGraphics.drawString(fontSource.font, " - " + DisplayFormat.formatId(edge.to()), getX() + 10, currentY, COLOUR_TEXT, false);
                        currentY += 10;
                    }
                }
            }

            // Recipe Ambiguity Cycle Button (Rendered for any node having ambiguity options)
            if (!node.getAmbiguityOptions().isEmpty()) {
                currentY += 8;
                guiGraphics.drawString(fontSource.font, "Alternative Recipes:", getX() + 6, currentY, COLOUR_AMBER, false);
                currentY += 12;

                cycleBoxX = getX() + 10;
                cycleBoxY = currentY;
                cycleBoxW = getWidth() - 20;
                cycleBoxH = 14;

                isCycleBoxHovered = mouseX >= cycleBoxX && mouseX < cycleBoxX + cycleBoxW &&
                                    mouseY >= cycleBoxY && mouseY < cycleBoxY + cycleBoxH;

                int boxColour = isCycleBoxHovered ? COLOUR_CYCLE_HOVER : COLOUR_CYCLE_BOX;
                guiGraphics.fill(cycleBoxX, cycleBoxY, cycleBoxX + cycleBoxW, cycleBoxY + cycleBoxH, boxColour);
                guiGraphics.renderOutline(cycleBoxX, cycleBoxY, cycleBoxW, cycleBoxH, COLOUR_BORDER);

                String cycleText = "Cycle Recipe (" + (node.getAmbiguityOptions().indexOf(node.getSelectedAmbiguity()) + 1)
                        + "/" + node.getAmbiguityOptions().size() + ")";
                int textWidth = fontSource.font.width(cycleText);
                int textX = cycleBoxX + (cycleBoxW - textWidth) / 2;
                guiGraphics.drawString(fontSource.font, cycleText, textX, cycleBoxY + 3, COLOUR_TEXT, false);
                currentY += 18;
            }
        }

        totalContentHeight = currentY + scrollOffset - getY();
        guiGraphics.disableScissor();

        // Draw scrollbar if content overflows
        if (maxScroll > 0) {
            int scrollbarWidth = 4;
            int scrollbarHeight = (int) (((double) getHeight() / totalContentHeight) * getHeight());
            scrollbarHeight = Math.max(10, scrollbarHeight);
            int scrollbarX = getX() + getWidth() - scrollbarWidth - 2;
            int scrollbarY = getY() + 2 + (int) (((double) scrollOffset / maxScroll) * (getHeight() - scrollbarHeight - 4));

            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, COLOUR_BORDER);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        if (button == 0 && node == null && isNumToggleHovered && onToggleNumbers != null) {
            onToggleNumbers.run();
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }

        if (node != null && !node.getAmbiguityOptions().isEmpty()) {
            if (mouseX >= cycleBoxX && mouseX < cycleBoxX + cycleBoxW &&
                mouseY >= cycleBoxY && mouseY < cycleBoxY + cycleBoxH) {
                
                List<ResourceLocation> options = node.getAmbiguityOptions();
                int idx = options.indexOf(node.getSelectedAmbiguity());
                int nextIdx = (idx + 1) % options.size();
                ResourceLocation nextSel = options.get(nextIdx);

                // A MACHINE node's ambiguityOptions/selectedAmbiguity describe whichever resourceId
                // first finalized it (see RecipeGraphTraverser.finalizeResourceNode), not necessarily
                // its first output edge -- for a multi-output recipe those two "firsts" can differ,
                // so the click must target ambiguityOwnerId, not outputs.get(0).
                ResourceLocation resourceId = node.getType() == NodeType.MACHINE
                        ? node.getAmbiguityOwnerId()
                        : node.getId();

                if (resourceId != null) {
                    onAmbiguity.accept(resourceId, nextSel);
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
