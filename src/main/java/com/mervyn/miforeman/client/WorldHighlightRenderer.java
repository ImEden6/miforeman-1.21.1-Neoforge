package com.mervyn.miforeman.client;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.client.gui.ColourPalette;
import com.mervyn.miforeman.client.gui.ColourPalette.ColourKey;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side in-world highlight overlay for linked and candidate machines.
 * Renders depth-tested and see-through highlights for active machine positions.
 */
@EventBusSubscriber(modid = MIForeman.MODID, value = Dist.CLIENT)
public class WorldHighlightRenderer {
    private static final float OUTLINE_ALPHA = 1.0f;
    private static final float FILL_ALPHA = 0.6f;

    private static boolean enabled = false;
    private static List<GlobalPos> linkedPositions = List.of();
    private static List<GlobalPos> candidatePositions = List.of();
    /** The machine selected from the Monitoring screen, rendered in a distinct highlight color. */
    private static @Nullable GlobalPos selectedPosition = null;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setPositions(List<GlobalPos> linked, List<GlobalPos> candidates) {
        linkedPositions = linked;
        candidatePositions = candidates;
        clearSelectionIfUntracked();
    }

    /** Adds or removes a position from the linked set. */
    public static void setLinked(GlobalPos pos, boolean linked) {
        List<GlobalPos> updated = new ArrayList<>(linkedPositions);
        if (linked) {
            if (!updated.contains(pos)) updated.add(pos);
        } else {
            updated.remove(pos);
        }
        linkedPositions = updated;
        clearSelectionIfUntracked();
    }

    /** Clears selection if the position is no longer linked or a candidate. */
    private static void clearSelectionIfUntracked() {
        if (selectedPosition != null && !linkedPositions.contains(selectedPosition) && !candidatePositions.contains(selectedPosition)) {
            selectedPosition = null;
        }
    }

    public static void setSelected(@Nullable GlobalPos pos) {
        selectedPosition = pos;
    }

    public static @Nullable GlobalPos getSelected() {
        return selectedPosition;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) return;
        var currentDim = clientLevel.dimension();

        // "Locate" is independent of the general Highlights on/off toggle -- a located machine
        // stays visible even with highlights off, and the linked/candidate sets stay hidden while
        // off even if something happens to be located.
        boolean showGeneral = enabled && (!linkedPositions.isEmpty() || !candidatePositions.isEmpty());
        boolean showSelected = selectedPosition != null && selectedPosition.dimension().equals(currentDim);
        if (!showGeneral && !showSelected) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        float fillAlpha = FILL_ALPHA;

        // Read once per frame rather than per box. These are config-backed (ColourPalette), not
        // compile-time constants, but still cheap enough not to bother caching across frames.
        float[] linkedRgb = ColourPalette.getRgbFloats(ColourKey.LINKED);
        float[] candidateRgb = ColourPalette.getRgbFloats(ColourKey.CANDIDATE);
        float[] selectedRgb = ColourPalette.getRgbFloats(ColourKey.SELECTED);

        // Fill first (soft glow base layer), then outline on top (crisp edges) -- drawing every
        // fill box across both lists before any outline keeps the wireframes from getting buried
        // under a later box's glow. The selected position is skipped in its normal colour and
        // drawn separately afterward -- stacking two translucent fills on the same block would
        // blend into a muddy colour instead of the selected box cleanly overriding its normal one.
        VertexConsumer fillInside = bufferSource.getBuffer(MIForemanRenderTypes.FILL_INSIDE_BLOCKS);
        VertexConsumer fillOutside = bufferSource.getBuffer(MIForemanRenderTypes.FILL_OUTSIDE_BLOCKS);
        if (showGeneral) {
            for (GlobalPos pos : linkedPositions) {
                if (!pos.dimension().equals(currentDim) || pos.equals(selectedPosition)) continue;
                drawFilledBoxBoth(poseStack, fillInside, fillOutside, pos.pos(), camPos, linkedRgb[0], linkedRgb[1], linkedRgb[2], fillAlpha);
            }
            for (GlobalPos pos : candidatePositions) {
                if (!pos.dimension().equals(currentDim) || pos.equals(selectedPosition)) continue;
                drawFilledBoxBoth(poseStack, fillInside, fillOutside, pos.pos(), camPos, candidateRgb[0], candidateRgb[1], candidateRgb[2], fillAlpha);
            }
        }
        if (showSelected) {
            drawFilledBoxBoth(poseStack, fillInside, fillOutside, selectedPosition.pos(), camPos, selectedRgb[0], selectedRgb[1], selectedRgb[2], fillAlpha);
        }
        bufferSource.endBatch(MIForemanRenderTypes.FILL_INSIDE_BLOCKS);
        bufferSource.endBatch(MIForemanRenderTypes.FILL_OUTSIDE_BLOCKS);

        VertexConsumer lineInside = bufferSource.getBuffer(MIForemanRenderTypes.LINES_INSIDE_BLOCKS);
        VertexConsumer lineOutside = bufferSource.getBuffer(MIForemanRenderTypes.LINES_OUTSIDE_BLOCKS);
        if (showGeneral) {
            for (GlobalPos pos : linkedPositions) {
                if (!pos.dimension().equals(currentDim) || pos.equals(selectedPosition)) continue;
                drawBoxOutlineBoth(poseStack, lineInside, lineOutside, pos.pos(), camPos, linkedRgb[0], linkedRgb[1], linkedRgb[2]);
            }
            for (GlobalPos pos : candidatePositions) {
                if (!pos.dimension().equals(currentDim) || pos.equals(selectedPosition)) continue;
                drawBoxOutlineBoth(poseStack, lineInside, lineOutside, pos.pos(), camPos, candidateRgb[0], candidateRgb[1], candidateRgb[2]);
            }
        }
        if (showSelected) {
            drawBoxOutlineBoth(poseStack, lineInside, lineOutside, selectedPosition.pos(), camPos, selectedRgb[0], selectedRgb[1], selectedRgb[2]);
        }
        bufferSource.endBatch(MIForemanRenderTypes.LINES_INSIDE_BLOCKS);
        bufferSource.endBatch(MIForemanRenderTypes.LINES_OUTSIDE_BLOCKS);
    }

    private static void drawBoxOutlineBoth(PoseStack poseStack, VertexConsumer inside, VertexConsumer outside,
                                            BlockPos pos, Vec3 camPos, float r, float g, float b) {
        double minX = pos.getX() - camPos.x - 0.001;
        double minY = pos.getY() - camPos.y - 0.001;
        double minZ = pos.getZ() - camPos.z - 0.001;
        double maxX = pos.getX() + 1.001 - camPos.x;
        double maxY = pos.getY() + 1.001 - camPos.y;
        double maxZ = pos.getZ() + 1.001 - camPos.z;
        LevelRenderer.renderLineBox(poseStack, inside, minX, minY, minZ, maxX, maxY, maxZ,
                r / 2, g / 2, b / 2, OUTLINE_ALPHA / 2);
        LevelRenderer.renderLineBox(poseStack, outside, minX, minY, minZ, maxX, maxY, maxZ,
                r, g, b, OUTLINE_ALPHA);
    }

    private static void drawFilledBoxBoth(PoseStack poseStack, VertexConsumer inside, VertexConsumer outside,
                                           BlockPos pos, Vec3 camPos, float r, float g, float b, float alpha) {
        double minX = pos.getX() - camPos.x - 0.001;
        double minY = pos.getY() - camPos.y - 0.001;
        double minZ = pos.getZ() - camPos.z - 0.001;
        double maxX = pos.getX() + 1.001 - camPos.x;
        double maxY = pos.getY() + 1.001 - camPos.y;
        double maxZ = pos.getZ() + 1.001 - camPos.z;
        drawFilledBox(poseStack, inside, minX, minY, minZ, maxX, maxY, maxZ,
                r / 2, g / 2, b / 2, alpha / 2);
        drawFilledBox(poseStack, outside, minX, minY, minZ, maxX, maxY, maxZ,
                r, g, b, alpha);
    }

    /** Emits 6 quad faces for a filled box. */
    private static void drawFilledBox(PoseStack poseStack, VertexConsumer buffer,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ,
                                       float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        float x0 = (float) minX, y0 = (float) minY, z0 = (float) minZ;
        float x1 = (float) maxX, y1 = (float) maxY, z1 = (float) maxZ;

        quad(buffer, pose, r, g, b, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // -X
        quad(buffer, pose, r, g, b, a, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1); // +X
        quad(buffer, pose, r, g, b, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // -Y
        quad(buffer, pose, r, g, b, a, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0); // +Y
        quad(buffer, pose, r, g, b, a, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0); // -Z
        quad(buffer, pose, r, g, b, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // +Z
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, float r, float g, float b, float a,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4) {
        buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        buffer.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }

    private WorldHighlightRenderer() {
    }
}
