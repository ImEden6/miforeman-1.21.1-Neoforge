package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.goal.NodePosition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Camera panning, zoom, and node dragging state for {@link GraphCanvas}.
 */
public class GraphCamera {
    private double panX, panY;
    private float zoom;

    private @Nullable ResourceLocation draggingNodeId;
    private @Nullable NodePosition dragNodeOriginalPos;
    private @Nullable NodePosition liveDragPos;
    private double dragAccumPixels;

    public record DragEnd(@Nullable ResourceLocation committedNodeId, @Nullable NodePosition committedFrom,
                    @Nullable NodePosition committedTo, @Nullable ResourceLocation clickedNodeId) {
        public static final DragEnd NONE = new DragEnd(null, null, null, null);
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

    double toCanvasX(int originX, double screenMouseX) {
        return (screenMouseX - (originX + panX)) / zoom;
    }

    double toCanvasY(int originY, double screenMouseY) {
        return (screenMouseY - (originY + panY)) / zoom;
    }

    void beginDrag(@Nullable ResourceLocation hitNodeId, @Nullable NodePosition hitNodePos) {
        dragAccumPixels = 0;
        draggingNodeId = hitNodeId;
        dragNodeOriginalPos = hitNodePos;
        liveDragPos = hitNodePos;
    }

    /** Returns true if this drag operation panned the camera instead of moving a node. */
    boolean onDrag(boolean dragEnabled, double dragX, double dragY) {
        dragAccumPixels += Math.abs(dragX) + Math.abs(dragY);
        if (dragEnabled && draggingNodeId != null && liveDragPos != null) {
            liveDragPos = new NodePosition(
                    liveDragPos.x() + (int) Math.round(dragX / zoom),
                    liveDragPos.y() + (int) Math.round(dragY / zoom));
            return false;
        }
        // View mode (dragEnabled == false), or the drag started off any node: always pan, even
        // if it started on a node -- that's the whole point, dragging can never nudge a node out
        // of place while dragEnabled is false.
        panX += dragX;
        panY += dragY;
        return true;
    }

    private static final double CLICK_DRAG_THRESHOLD = 4.0;

    DragEnd onRelease(boolean dragEnabled) {
        if (draggingNodeId == null) {
            return DragEnd.NONE;
        }
        DragEnd result;
        if (dragEnabled && dragAccumPixels > CLICK_DRAG_THRESHOLD && liveDragPos != null && dragNodeOriginalPos != null) {
            result = new DragEnd(draggingNodeId, dragNodeOriginalPos, liveDragPos, null);
        } else if (dragAccumPixels <= CLICK_DRAG_THRESHOLD) {
            result = new DragEnd(null, null, null, draggingNodeId);
        } else {
            // View mode, dragged past the threshold starting on a node -- already panned live in
            // onDrag(), nothing to commit.
            result = DragEnd.NONE;
        }
        liveDragPos = null;
        dragNodeOriginalPos = null;
        draggingNodeId = null;
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
