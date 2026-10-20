package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.goal.NodePosition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

public class GraphCamera {
    private double panX, panY;
    private float zoom;

    private @Nullable ResourceLocation draggingNodeId;
    private @Nullable NodePosition dragNodeOriginalPos;
    private @Nullable NodePosition liveDragPos;
    private double dragAccumPixels;
    private boolean modifierHeld;
    /** Set from beginDrag through onRelease. onRelease also runs when no drag began, and without
     *  this a click on the search bar or drawer would clear the selection. */
    private boolean dragActive;

    private boolean marqueeActive;
    private double marqueeStartX, marqueeStartY;
    private double marqueeCurrentX, marqueeCurrentY;

    public record MarqueeRect(double minX, double minY, double maxX, double maxY) {}

    public record DragEnd(@Nullable ResourceLocation committedNodeId, @Nullable NodePosition committedFrom,
                    @Nullable NodePosition committedTo, @Nullable ResourceLocation clickedNodeId,
                    boolean clickedEmptySpace, boolean modifierHeld, @Nullable MarqueeRect marqueeRect) {
        public static final DragEnd NONE = new DragEnd(null, null, null, null, false, false, null);
    }

    public record Viewport(double minX, double minY, double maxX, double maxY) {
        public boolean intersects(double x0, double y0, double x1, double y1) {
            return x0 < maxX && x1 > minX && y0 < maxY && y1 > minY;
        }
    }

    public GraphCamera(double panX, double panY, float zoom) {
        this.panX = panX;
        this.panY = panY;
        this.zoom = zoom;
    }

    public double panX() {
        return panX;
    }

    public double panY() {
        return panY;
    }

    public float zoom() {
        return zoom;
    }

    public @Nullable ResourceLocation draggingNodeId() {
        return draggingNodeId;
    }

    @Nullable NodePosition liveDragPos() {
        return liveDragPos;
    }

    /** Canvas coordinates, or null if none. */
    @Nullable MarqueeRect liveMarqueeRect() {
        if (!marqueeActive) return null;
        return new MarqueeRect(
                Math.min(marqueeStartX, marqueeCurrentX), Math.min(marqueeStartY, marqueeCurrentY),
                Math.max(marqueeStartX, marqueeCurrentX), Math.max(marqueeStartY, marqueeCurrentY));
    }

    public Viewport viewport(int width, int height, double margin) {
        return new Viewport(-panX / zoom - margin, -panY / zoom - margin,
                (width - panX) / zoom + margin, (height - panY) / zoom + margin);
    }

    double toCanvasX(int originX, double screenMouseX) {
        return (screenMouseX - (originX + panX)) / zoom;
    }

    double toCanvasY(int originY, double screenMouseY) {
        return (screenMouseY - (originY + panY)) / zoom;
    }

    void beginDrag(@Nullable ResourceLocation hitNodeId, @Nullable NodePosition hitNodePos,
                   boolean modifierHeld, double startCanvasX, double startCanvasY) {
        dragAccumPixels = 0;
        draggingNodeId = hitNodeId;
        dragNodeOriginalPos = hitNodePos;
        liveDragPos = hitNodePos;
        dragActive = true;
        this.modifierHeld = modifierHeld;
        marqueeActive = hitNodeId == null && modifierHeld;
        marqueeStartX = startCanvasX;
        marqueeStartY = startCanvasY;
        marqueeCurrentX = startCanvasX;
        marqueeCurrentY = startCanvasY;
    }

    boolean onDrag(boolean dragEnabled, double dragX, double dragY) {
        dragAccumPixels += Math.abs(dragX) + Math.abs(dragY);
        if (marqueeActive) {
            marqueeCurrentX += dragX / zoom;
            marqueeCurrentY += dragY / zoom;
            return false;
        }
        if (dragEnabled && draggingNodeId != null && liveDragPos != null) {
            liveDragPos = new NodePosition(
                    liveDragPos.x() + (int) Math.round(dragX / zoom),
                    liveDragPos.y() + (int) Math.round(dragY / zoom));
            return false;
        }
        panX += dragX;
        panY += dragY;
        return true;
    }

    private static final double CLICK_DRAG_THRESHOLD = 4.0;

    DragEnd onRelease(boolean dragEnabled) {
        if (!dragActive) {
            return DragEnd.NONE;
        }
        DragEnd result;
        if (marqueeActive) {
            result = dragAccumPixels > CLICK_DRAG_THRESHOLD
                    ? new DragEnd(null, null, null, null, false, modifierHeld, liveMarqueeRect())
                    : DragEnd.NONE;
        } else if (draggingNodeId == null) {
            result = dragAccumPixels <= CLICK_DRAG_THRESHOLD
                    ? new DragEnd(null, null, null, null, true, modifierHeld, null)
                    : DragEnd.NONE;
        } else if (dragEnabled && dragAccumPixels > CLICK_DRAG_THRESHOLD && liveDragPos != null && dragNodeOriginalPos != null) {
            result = new DragEnd(draggingNodeId, dragNodeOriginalPos, liveDragPos, null, false, modifierHeld, null);
        } else if (dragAccumPixels <= CLICK_DRAG_THRESHOLD) {
            result = new DragEnd(null, null, null, draggingNodeId, false, modifierHeld, null);
        } else {
            // In view mode, dragging a node pans instead of moving it.
            result = DragEnd.NONE;
        }
        liveDragPos = null;
        dragNodeOriginalPos = null;
        draggingNodeId = null;
        dragActive = false;
        marqueeActive = false;
        return result;
    }

    boolean zoomAt(int originX, int originY, double mouseX, double mouseY, double scrollY,
                   float minZoom, float maxZoom) {
        double canvasXBefore = toCanvasX(originX, mouseX);
        double canvasYBefore = toCanvasY(originY, mouseY);
        float newZoom = Mth.clamp((float) (zoom * Math.pow(1.1, scrollY)), minZoom, maxZoom);
        zoom = newZoom;
        panX = mouseX - originX - canvasXBefore * zoom;
        panY = mouseY - originY - canvasYBefore * zoom;
        return true;
    }

    public void centerOn(int canvasWidth, int canvasHeight, double nodeX, double nodeY, double nodeWidth, double nodeHeight) {
        double centerX = nodeX + nodeWidth / 2.0;
        double centerY = nodeY + nodeHeight / 2.0;
        this.panX = (canvasWidth / 2.0) - (centerX * zoom);
        this.panY = (canvasHeight / 2.0) - (centerY * zoom);
    }
}
