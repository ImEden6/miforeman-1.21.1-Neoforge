package com.mervyn.miforeman.client.gui;

import com.mervyn.miforeman.MIForeman;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** White icons in one atlas, tinted per draw. Drawn through the buffer source rather than
 *  {@code blit}, which draws immediately and so would land under the cards batched by
 *  {@code drawManaged}. */
public enum GuiIcon {
    WARNING(0, 0, 9, 8),
    ARROW_UP(10, 0, 7, 4),
    ARROW_DOWN(18, 0, 7, 4),
    HOURGLASS(26, 0, 7, 9),
    CLOSE(34, 0, 7, 7),
    EYE(42, 0, 13, 7),
    SEARCH(56, 0, 8, 8);

    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(MIForeman.MODID,
            "textures/gui/icons.png");
    private static final int ATLAS_WIDTH = 64;
    private static final int ATLAS_HEIGHT = 16;

    private final int u, v, width, height;

    GuiIcon(int u, int v, int width, int height) {
        this.u = u;
        this.v = v;
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void draw(GuiGraphics guiGraphics, int x, int y, int colour) {
        drawBatched(guiGraphics, x, y, colour);
        guiGraphics.flush();
    }

    public void drawCentered(GuiGraphics guiGraphics, int x, int y, int w, int h, int colour) {
        draw(guiGraphics, x + (w - width) / 2, y + (h - height) / 2, colour);
    }

    /** For use inside {@code drawManaged}, where the batch is flushed later. */
    public void drawBatched(GuiGraphics guiGraphics, int x, int y, int colour) {
        Matrix4f pose = guiGraphics.pose().last().pose();
        VertexConsumer buffer = guiGraphics.bufferSource().getBuffer(RenderType.text(ATLAS));
        float u0 = (float) u / ATLAS_WIDTH, u1 = (float) (u + width) / ATLAS_WIDTH;
        float v0 = (float) v / ATLAS_HEIGHT, v1 = (float) (v + height) / ATLAS_HEIGHT;
        buffer.addVertex(pose, x, y, 0).setColor(colour).setUv(u0, v0).setLight(LightTexture.FULL_BRIGHT);
        buffer.addVertex(pose, x, y + height, 0).setColor(colour).setUv(u0, v1).setLight(LightTexture.FULL_BRIGHT);
        buffer.addVertex(pose, x + width, y + height, 0).setColor(colour).setUv(u1, v1).setLight(LightTexture.FULL_BRIGHT);
        buffer.addVertex(pose, x + width, y, 0).setColor(colour).setUv(u1, v0).setLight(LightTexture.FULL_BRIGHT);
    }
}
