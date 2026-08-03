package com.mervyn.miforeman.client.gui.widget;

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

public class DetailCard extends AbstractWidget {
    private static final int COLOR_BORDER = 0xFF6B5030;
    private static final int COLOR_TITLE = 0xFF4A2E0A;
    private static final int COLOR_LABEL = 0xFF6B5030;
    private static final int COLOR_TEXT = 0xFF3A2A18;
    private static final int COLOR_MUTED = 0xFF8A7A68;
    private static final int COLOR_GREEN = 0xFF2E7D32;
    private static final int COLOR_CYAN = 0xFF006080;
    private static final int COLOR_AMBER = 0xFF9A6C00;
    private static final int COLOR_CYCLE_BOX = 0xFFD8C3A5;
    private static final int COLOR_CYCLE_HOVER = 0xFFEAE7D9;

    private @Nullable RecipeGraphNode node;
    private final FactoryPlan plan;
    private final boolean perHour;
    private final BiConsumer<ResourceLocation, ResourceLocation> onAmbiguity;
    private int scrollOffset = 0;
    private int totalContentHeight = 0;

    // Boundaries of the inline cycle recipe box (for mouse click detection)
    private int cycleBoxX = 0;
    private int cycleBoxY = 0;
    private int cycleBoxW = 0;
    private int cycleBoxH = 0;
    private boolean isCycleBoxHovered = false;

    public DetailCard(int x, int y, int width, int height, @Nullable RecipeGraphNode node,
                      FactoryPlan plan, boolean perHour,
                      BiConsumer<ResourceLocation, ResourceLocation> onAmbiguity) {
        super(x, y, width, height, Component.literal("Detail Card"));
        this.node = node;
        this.plan = plan;
        this.perHour = perHour;
        this.onAmbiguity = onAmbiguity;
    }

    public void setNode(@Nullable RecipeGraphNode node) {
        this.node = node;
        this.scrollOffset = 0;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // No border/fill of its own -- ClipboardScreen's own main panel is already the
        // background here (drawn before widgets, in Screen.render()); a second bordered panel
        // read as a nested "clipboard within the clipboard" rather than one continuous surface.
        // Just a thin divider on the shared edge with GraphCanvas, hinting at the boundary
        // between the two content areas without drawing a second frame.
        guiGraphics.fill(getX(), getY(), getX() + 1, getY() + getHeight(), COLOR_BORDER);

        int maxScroll = Math.max(0, totalContentHeight - getHeight() + 8);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        guiGraphics.enableScissor(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1);

        int currentY = getY() + 4 - scrollOffset;
        Minecraft fontSource = Minecraft.getInstance();

        isCycleBoxHovered = false;

        if (node == null) {
            // Summary Mode
            guiGraphics.drawString(fontSource.font, "Factory Summary", getX() + 6, currentY, COLOR_TITLE, false);
            currentY += 15;

            // Goal Output
            guiGraphics.drawString(fontSource.font, "Target Goal:", getX() + 6, currentY, COLOR_LABEL, false);
            currentY += 10;
            String targetText = String.format(" - %.2f/%s %s",
                    plan.graph() != null ? plan.graph().targetRate() * (perHour ? 60.0 : 1.0) : 1.0,
                    perHour ? "h" : "m",
                    plan.graph() != null ? formatId(plan.graph().target()) : "Goal");
            guiGraphics.drawString(fontSource.font, targetText, getX() + 10, currentY, COLOR_TEXT, false);
            currentY += 15;

            // Machines Needed
            guiGraphics.drawString(fontSource.font, "Machines Needed:", getX() + 6, currentY, COLOR_GREEN, false);
            currentY += 10;
            List<MachineRequirement> machines = plan.machines();
            if (machines.isEmpty()) {
                guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOR_MUTED, false);
                currentY += 10;
            } else {
                for (MachineRequirement req : machines) {
                    String machLine = String.format(" - %.1f x %s", req.count(), formatId(req.machineId()));
                    guiGraphics.drawString(fontSource.font, machLine, getX() + 10, currentY, COLOR_TEXT, false);
                    currentY += 10;
                }
            }
            currentY += 5;

            // Raw Inputs Needed
            guiGraphics.drawString(fontSource.font, "Raw Inputs:", getX() + 6, currentY, COLOR_CYAN, false);
            currentY += 10;
            List<MaterialFlow> rawInputs = plan.rawInputs();
            if (rawInputs.isEmpty()) {
                guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOR_MUTED, false);
                currentY += 10;
            } else {
                for (MaterialFlow flow : rawInputs) {
                    double rateVal = flow.rate() * (perHour ? 60.0 : 1.0);
                    String flowLine = String.format(" - %.1f/%s %s", rateVal, perHour ? "h" : "m", formatId(flow.resourceId()));
                    guiGraphics.drawString(fontSource.font, flowLine, getX() + 10, currentY, COLOR_TEXT, false);
                    currentY += 10;
                }
            }
        } else {
            // Node Details Mode
            String title = formatId(node.getId());
            if (node.getType() == NodeType.MACHINE) {
                title = "Recipe: " + title;
            }
            guiGraphics.drawString(fontSource.font, title, getX() + 6, currentY, COLOR_TITLE, false);
            currentY += 15;

            if (node.getType() == NodeType.MACHINE) {
                // Machine Node Details
                guiGraphics.drawString(fontSource.font, "Machine Count:", getX() + 6, currentY, COLOR_LABEL, false);
                String countLine = String.format(" %.2f x %s", node.getMachineCount(), formatId(node.getMachineType()));
                guiGraphics.drawString(fontSource.font, countLine, getX() + 10, currentY + 10, COLOR_TEXT, false);
                currentY += 24;

                // Energy / Duration if recipe details exist
                MachineRecipe recipe = node.getRecipe();
                if (recipe != null) {
                    guiGraphics.drawString(fontSource.font, "Recipe Info:", getX() + 6, currentY, COLOR_LABEL, false);
                    String infoLine = String.format(" Duration: %.1fs | Eu: %d", recipe.duration / 20.0, recipe.eu);
                    guiGraphics.drawString(fontSource.font, infoLine, getX() + 10, currentY + 10, COLOR_MUTED, false);
                    currentY += 24;
                }

                // Inputs
                guiGraphics.drawString(fontSource.font, "Recipe Inputs:", getX() + 6, currentY, COLOR_CYAN, false);
                currentY += 10;
                if (node.getInputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getInputs()) {
                        double rateVal = edge.rate() * (perHour ? 60.0 : 1.0);
                        String inputLine = String.format(" - %.1f/%s %s", rateVal, perHour ? "h" : "m", formatId(edge.from()));
                        guiGraphics.drawString(fontSource.font, inputLine, getX() + 10, currentY, COLOR_TEXT, false);
                        currentY += 10;
                    }
                }
                currentY += 5;

                // Outputs
                guiGraphics.drawString(fontSource.font, "Recipe Outputs:", getX() + 6, currentY, COLOR_GREEN, false);
                currentY += 10;
                if (node.getOutputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - None", getX() + 10, currentY, COLOR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getOutputs()) {
                        double rateVal = edge.rate() * (perHour ? 60.0 : 1.0);
                        String outputLine = String.format(" - %.1f/%s %s", rateVal, perHour ? "h" : "m", formatId(edge.to()));
                        guiGraphics.drawString(fontSource.font, outputLine, getX() + 10, currentY, COLOR_TEXT, false);
                        currentY += 10;
                    }
                }

            } else {
                // Resource Node Details
                guiGraphics.drawString(fontSource.font, "Required Flow Rate:", getX() + 6, currentY, COLOR_LABEL, false);
                double rateVal = node.getRequiredRate() * (perHour ? 60.0 : 1.0);
                String rateText = String.format(" %.2f units / %s", rateVal, perHour ? "hour" : "min");
                guiGraphics.drawString(fontSource.font, rateText, getX() + 10, currentY + 10, COLOR_TEXT, false);
                currentY += 24;

                // Produced by machine recipe
                guiGraphics.drawString(fontSource.font, "Produced By:", getX() + 6, currentY, COLOR_GREEN, false);
                currentY += 10;
                if (node.getInputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - Raw Material Input", getX() + 10, currentY, COLOR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getInputs()) {
                        guiGraphics.drawString(fontSource.font, " - " + formatId(edge.from()), getX() + 10, currentY, COLOR_TEXT, false);
                        currentY += 10;
                    }
                }
                currentY += 5;

                // Consumed by recipe
                guiGraphics.drawString(fontSource.font, "Consumed By:", getX() + 6, currentY, COLOR_CYAN, false);
                currentY += 10;
                if (node.getOutputs().isEmpty()) {
                    guiGraphics.drawString(fontSource.font, " - Final Target Product", getX() + 10, currentY, COLOR_MUTED, false);
                    currentY += 10;
                } else {
                    for (GraphEdge edge : node.getOutputs()) {
                        guiGraphics.drawString(fontSource.font, " - " + formatId(edge.to()), getX() + 10, currentY, COLOR_TEXT, false);
                        currentY += 10;
                    }
                }
            }

            // Recipe Ambiguity Cycle Button (Rendered for any node having ambiguity options)
            if (!node.getAmbiguityOptions().isEmpty()) {
                currentY += 8;
                guiGraphics.drawString(fontSource.font, "Alternative Recipes:", getX() + 6, currentY, COLOR_AMBER, false);
                currentY += 12;

                cycleBoxX = getX() + 10;
                cycleBoxY = currentY;
                cycleBoxW = getWidth() - 20;
                cycleBoxH = 14;

                isCycleBoxHovered = mouseX >= cycleBoxX && mouseX < cycleBoxX + cycleBoxW &&
                                    mouseY >= cycleBoxY && mouseY < cycleBoxY + cycleBoxH;

                int boxColor = isCycleBoxHovered ? COLOR_CYCLE_HOVER : COLOR_CYCLE_BOX;
                guiGraphics.fill(cycleBoxX, cycleBoxY, cycleBoxX + cycleBoxW, cycleBoxY + cycleBoxH, boxColor);
                guiGraphics.renderOutline(cycleBoxX, cycleBoxY, cycleBoxW, cycleBoxH, COLOR_BORDER);

                String cycleText = "Cycle Recipe (" + (node.getAmbiguityOptions().indexOf(node.getSelectedAmbiguity()) + 1)
                        + "/" + node.getAmbiguityOptions().size() + ")";
                int textWidth = fontSource.font.width(cycleText);
                int textX = cycleBoxX + (cycleBoxW - textWidth) / 2;
                guiGraphics.drawString(fontSource.font, cycleText, textX, cycleBoxY + 3, COLOR_TEXT, false);
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

            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, COLOR_BORDER);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;
        if (mouseX < getX() || mouseX >= getX() + getWidth() || mouseY < getY() || mouseY >= getY() + getHeight()) {
            return false;
        }

        if (node != null && !node.getAmbiguityOptions().isEmpty()) {
            if (mouseX >= cycleBoxX && mouseX < cycleBoxX + cycleBoxW &&
                mouseY >= cycleBoxY && mouseY < cycleBoxY + cycleBoxH) {
                
                List<ResourceLocation> options = node.getAmbiguityOptions();
                int idx = options.indexOf(node.getSelectedAmbiguity());
                int nextIdx = (idx + 1) % options.size();
                ResourceLocation nextSel = options.get(nextIdx);

                ResourceLocation resourceId = node.getType() == NodeType.MACHINE
                        ? (!node.getOutputs().isEmpty() ? node.getOutputs().get(0).to() : null)
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
            return true;
        }
        return false;
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
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }
}
