package com.mervyn.miforeman.client;

import com.mervyn.miforeman.MIForeman;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side in-world highlight overlay for linked/candidate machines during the auto-detect
 * review flow. Toggled explicitly by ClipboardScreen's "Highlights: On/Off" button -- not tied
 * to the review panel being open, so highlights persist even after the clipboard screen closes
 * until the player explicitly turns them off again.
 *
 * <p>Each box is drawn as a translucent fill plus a crisp wireframe outline, each drawn
 * twice -- once into a normal depth-tested {@link MIForemanRenderTypes} (full brightness, only
 * where actually unobstructed) and once into a depth-test-flipped variant (half color/alpha,
 * draws only the portion currently hidden behind blocks) -- the same dual-pass "ghost through
 * walls" technique Minecolonies uses for its own overlays (see
 * references/minecolonies-1.21.1/.../worldevent/RenderTypes.java), so a highlighted machine
 * behind a wall is still visible, just dimmer than one in plain sight.
 */
@EventBusSubscriber(modid = MIForeman.MODID, value = Dist.CLIENT)
public class WorldHighlightRenderer {
    private static final float LINKED_R = 0.25f, LINKED_G = 0.80f, LINKED_B = 0.32f;
    private static final float CANDIDATE_R = 0.95f, CANDIDATE_G = 0.65f, CANDIDATE_B = 0.05f;
    private static final float SELECTED_R = 0.20f, SELECTED_G = 0.90f, SELECTED_B = 0.95f;

    private static final float OUTLINE_ALPHA = 1.0f;
    private static final float FILL_ALPHA = 0.6f;

    private static boolean enabled = false;
    private static List<BlockPos> linkedPositions = List.of();
    private static List<BlockPos> candidatePositions = List.of();
    /** The one machine "located" from the Monitoring screen, if any -- independent of the
     *  linked/candidate sets, drawn in its own color instead of that position's normal one. */
    private static @Nullable BlockPos selectedPosition = null;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setPositions(List<BlockPos> linked, List<BlockPos> candidates) {
        linkedPositions = linked;
        candidatePositions = candidates;
        clearSelectionIfUntracked();
    }

    /** Adds or removes a single position from the linked set without touching candidates --
     *  used by the in-world shift-right-click link/unlink path, which has no scan-candidate data
     *  to also resend. */
    public static void setLinked(BlockPos pos, boolean linked) {
        List<BlockPos> updated = new ArrayList<>(linkedPositions);
        if (linked) {
            if (!updated.contains(pos)) updated.add(pos);
        } else {
            updated.remove(pos);
        }
        linkedPositions = updated;
        clearSelectionIfUntracked();
    }

    /** A located machine that's no longer linked or a candidate (unlinked, rejected, or scanned
     *  away) has nothing left to locate -- drop the selection instead of leaving its box stuck on
     *  screen forever. */
    private static void clearSelectionIfUntracked() {
        if (selectedPosition != null && !linkedPositions.contains(selectedPosition) && !candidatePositions.contains(selectedPosition)) {
            selectedPosition = null;
        }
    }

    public static void setSelected(@Nullable BlockPos pos) {
        selectedPosition = pos;
    }

    public static @Nullable BlockPos getSelected() {
        return selectedPosition;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        // "Locate" is independent of the general Highlights on/off toggle -- a located machine
        // stays visible even with highlights off, and the linked/candidate sets stay hidden while
        // off even if something happens to be located.
        boolean showGeneral = enabled && (!linkedPositions.isEmpty() || !candidatePositions.isEmpty());
        boolean showSelected = selectedPosition != null;
        if (!showGeneral && !showSelected) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        float fillAlpha = FILL_ALPHA;

        // Fill first (soft glow base layer), then outline on top (crisp edges) -- drawing every
        // fill box across both lists before any outline keeps the wireframes from getting buried
        // under a later box's glow. The selected position is skipped in its normal color and
        // drawn separately afterward -- stacking two translucent fills on the same block would
        // blend into a muddy color instead of the selected box cleanly overriding its normal one.
        VertexConsumer fillInside = bufferSource.getBuffer(MIForemanRenderTypes.FILL_INSIDE_BLOCKS);
        VertexConsumer fillOutside = bufferSource.getBuffer(MIForemanRenderTypes.FILL_OUTSIDE_BLOCKS);
        if (showGeneral) {
            for (BlockPos pos : linkedPositions) {
                if (pos.equals(selectedPosition)) continue;
                drawFilledBoxBoth(poseStack, fillInside, fillOutside, pos, camPos, LINKED_R, LINKED_G, LINKED_B, fillAlpha);
            }
            for (BlockPos pos : candidatePositions) {
                if (pos.equals(selectedPosition)) continue;
                drawFilledBoxBoth(poseStack, fillInside, fillOutside, pos, camPos, CANDIDATE_R, CANDIDATE_G, CANDIDATE_B, fillAlpha);
            }
        }
        if (showSelected) {
            drawFilledBoxBoth(poseStack, fillInside, fillOutside, selectedPosition, camPos, SELECTED_R, SELECTED_G, SELECTED_B, fillAlpha);
        }
        bufferSource.endBatch(MIForemanRenderTypes.FILL_INSIDE_BLOCKS);
        bufferSource.endBatch(MIForemanRenderTypes.FILL_OUTSIDE_BLOCKS);

        VertexConsumer lineInside = bufferSource.getBuffer(MIForemanRenderTypes.LINES_INSIDE_BLOCKS);
        VertexConsumer lineOutside = bufferSource.getBuffer(MIForemanRenderTypes.LINES_OUTSIDE_BLOCKS);
        if (showGeneral) {
            for (BlockPos pos : linkedPositions) {
                if (pos.equals(selectedPosition)) continue;
                drawBoxOutlineBoth(poseStack, lineInside, lineOutside, pos, camPos, LINKED_R, LINKED_G, LINKED_B);
            }
            for (BlockPos pos : candidatePositions) {
                if (pos.equals(selectedPosition)) continue;
                drawBoxOutlineBoth(poseStack, lineInside, lineOutside, pos, camPos, CANDIDATE_R, CANDIDATE_G, CANDIDATE_B);
            }
        }
        if (showSelected) {
            drawBoxOutlineBoth(poseStack, lineInside, lineOutside, selectedPosition, camPos, SELECTED_R, SELECTED_G, SELECTED_B);
        }
        bufferSource.endBatch(MIForemanRenderTypes.LINES_INSIDE_BLOCKS);
        bufferSource.endBatch(MIForemanRenderTypes.LINES_OUTSIDE_BLOCKS);
    }

    private static void drawBoxOutlineBoth(PoseStack poseStack, VertexConsumer inside, VertexConsumer outside,
                                            BlockPos pos, Vec3 camPos, float r, float g, float b) {
        double minX = pos.getX() - camPos.x;
        double minY = pos.getY() - camPos.y;
        double minZ = pos.getZ() - camPos.z;
        LevelRenderer.renderLineBox(poseStack, inside, minX, minY, minZ, minX + 1.0, minY + 1.0, minZ + 1.0,
                r / 2, g / 2, b / 2, OUTLINE_ALPHA / 2);
        LevelRenderer.renderLineBox(poseStack, outside, minX, minY, minZ, minX + 1.0, minY + 1.0, minZ + 1.0,
                r, g, b, OUTLINE_ALPHA);
    }

    private static void drawFilledBoxBoth(PoseStack poseStack, VertexConsumer inside, VertexConsumer outside,
                                           BlockPos pos, Vec3 camPos, float r, float g, float b, float alpha) {
        double minX = pos.getX() - camPos.x;
        double minY = pos.getY() - camPos.y;
        double minZ = pos.getZ() - camPos.z;
        drawFilledBox(poseStack, inside, minX, minY, minZ, minX + 1.0, minY + 1.0, minZ + 1.0,
                r / 2, g / 2, b / 2, alpha / 2);
        drawFilledBox(poseStack, outside, minX, minY, minZ, minX + 1.0, minY + 1.0, minZ + 1.0,
                r, g, b, alpha);
    }

    /** No vanilla helper builds filled-box geometry (unlike renderLineBox), so this emits the 6 faces directly. */
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
