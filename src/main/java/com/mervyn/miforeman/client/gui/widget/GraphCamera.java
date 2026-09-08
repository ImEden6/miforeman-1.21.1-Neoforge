package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.goal.NodePosition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Camera panning, zoom, node dragging, and marquee-select state for {@link GraphCanvas}.
 */
public class GraphCamera {
    private double panX, panY;
    private float zoom;

    private @Nullable ResourceLocation draggingNodeId;
    private @Nullable NodePosition dragNodeOriginalPos;
    private @Nullable NodePosition liveDragPos;
    private double dragAccumPixels;
    private boolean modifierHeld;
    /** True from beginDrag through onRelease. Distinguishes clicking empty canvas from an inactive drag. */
    private boolean dragActive;

    /** True when dragging on empty canvas with Shift or Ctrl held to form a selection rectangle. */
    private boolean marqueeActive;
    private double marqueeStartX, marqueeStartY;
    private double marqueeCurrentX, marqueeCurrentY;

    public record MarqueeRect(double minX, double minY, double maxX, double maxY) {}

    public record DragEnd(@Nullable ResourceLocation committedNodeId, @Nullable NodePosition committedFrom,
                    @Nullable NodePosition committedTo, @Nullable ResourceLocation clickedNodeId,
                    boolean clickedEmptySpace, boolean modifierHeld, @Nullable MarqueeRect marqueeRect) {
        public static final DragEnd NONE = new DragEnd(null, null, null, null, false, false, null);
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

    /** The active marquee selection rectangle in canvas coordinates, or null if none. */
    @Nullable MarqueeRect liveMarqueeRect() {
        if (!marqueeActive) return null;
        return new MarqueeRect(
                Math.min(marqueeStartX, marqueeCurrentX), Math.min(marqueeStartY, marqueeCurrentY),
                Math.max(marqueeStartX, marqueeCurrentX), Math.max(marqueeStartY, marqueeCurrentY));
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

    /** Returns true if this drag operation panned the camera instead of moving a node or
     *  dragging out a marquee. */
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
        // In view mode or when dragging empty space without modifiers, pan the canvas.
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
            // Started on empty canvas space, not a node.
            result = dragAccumPixels <= CLICK_DRAG_THRESHOLD
                    ? new DragEnd(null, null, null, null, true, modifierHeld, null)
                    : DragEnd.NONE; // panned past threshold
        } else if (dragEnabled && dragAccumPixels > CLICK_DRAG_THRESHOLD && liveDragPos != null && dragNodeOriginalPos != null) {
            result = new DragEnd(draggingNodeId, dragNodeOriginalPos, liveDragPos, null, false, modifierHeld, null);
        } else if (dragAccumPixels <= CLICK_DRAG_THRESHOLD) {
            result = new DragEnd(null, null, null, draggingNodeId, false, modifierHeld, null);
        } else {
            // In view mode, dragging a node pans the canvas without moving the node.
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
