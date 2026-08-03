package com.mervyn.miforeman.client;

import com.mervyn.miforeman.MIForeman;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Client-side in-world highlight overlay for linked/candidate machines during the auto-detect
 * review flow. Toggled explicitly by ClipboardScreen's "Highlights: On/Off" button -- not tied
 * to the review panel being open, so highlights persist even after the clipboard screen closes
 * until the player explicitly turns them off again. No precedent for this in the codebase or
 * in Modern Industrialization; the RenderType.lines()/LevelRenderer.renderLineBox pattern here
 * was confirmed against the decompiled vanilla sources (LevelRenderer's own block-hit-outline
 * rendering uses the same idiom).
 */
@EventBusSubscriber(modid = MIForeman.MODID, value = Dist.CLIENT)
public class WorldHighlightRenderer {
    private static final float LINKED_R = 0.18f, LINKED_G = 0.49f, LINKED_B = 0.20f;
    private static final float CANDIDATE_R = 0.90f, CANDIDATE_G = 0.60f, CANDIDATE_B = 0.0f;

    private static boolean enabled = false;
    private static List<BlockPos> linkedPositions = List.of();
    private static List<BlockPos> candidatePositions = List.of();

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void setPositions(List<BlockPos> linked, List<BlockPos> candidates) {
        linkedPositions = linked;
        candidatePositions = candidates;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!enabled) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (linkedPositions.isEmpty() && candidatePositions.isEmpty()) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        for (BlockPos pos : linkedPositions) {
            drawBoxOutline(poseStack, consumer, pos, camPos, LINKED_R, LINKED_G, LINKED_B);
        }
        for (BlockPos pos : candidatePositions) {
            drawBoxOutline(poseStack, consumer, pos, camPos, CANDIDATE_R, CANDIDATE_G, CANDIDATE_B);
        }

        bufferSource.endBatch(RenderType.lines());
    }

    private static void drawBoxOutline(PoseStack poseStack, VertexConsumer consumer, BlockPos pos, Vec3 camPos, float r, float g, float b) {
        double minX = pos.getX() - camPos.x;
        double minY = pos.getY() - camPos.y;
        double minZ = pos.getZ() - camPos.z;
        LevelRenderer.renderLineBox(poseStack, consumer, minX, minY, minZ, minX + 1.0, minY + 1.0, minZ + 1.0, r, g, b, 1.0f);
    }

    private WorldHighlightRenderer() {
    }
}
