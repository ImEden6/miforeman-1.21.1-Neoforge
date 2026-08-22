package com.mervyn.miforeman.client.gui.widget;

import com.mervyn.miforeman.goal.NodePosition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Pan/zoom/drag interaction state and math for {@link GraphCanvas} -- split out (see
 * .claude/plans/gleaming-mapping-waffle.md) since it shares almost no fields with the
 * layout/rendering half of what used to be one class. Framework-free on purpose: takes the
 * widget's screen origin as parameters rather than holding a widget reference.
 */
class GraphCamera {
    private double panX, panY;
    private float zoom;

    private @Nullable ResourceLocation draggingNodeId;
    private @Nullable NodePosition dragNodeOriginalPos;
    private @Nullable NodePosition liveDragPos;
    private double dragAccumPixels;

    record DragEnd(@Nullable ResourceLocation committedNodeId, @Nullable NodePosition committedFrom,
                    @Nullable NodePosition committedTo, @Nullable ResourceLocation clickedNodeId) {
        static final DragEnd NONE = new DragEnd(null, null, null, null);
    }

    GraphCamera(double panX, double panY, float zoom) {
        this.panX = panX;
        this.panY = panY;
        this.zoom = zoom;
    }

    double panX() {
        return panX;
    }

    double panY() {
        return panY;
    }

    float zoom() {
        return zoom;
    }

    @Nullable ResourceLocation draggingNodeId() {
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

    /** Returns true iff this drag panned the camera (so the caller should fire its
     *  camera-change callback) rather than moving a node in place. */
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
}
