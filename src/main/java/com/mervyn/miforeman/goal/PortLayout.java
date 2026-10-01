package com.mervyn.miforeman.goal;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each wire attaches to its two cards. Wires sharing a card face get their own points,
 * spread down the face and ordered by where their other end sits, so a fan-out leaves as
 * separate wires instead of one thick line.
 */
public final class PortLayout {
    private PortLayout() {
    }

    public static final int MIN_SPACING = 4;
    private static final double PREFERRED_SPACING = 8.0;

    /** One wire between two cards, by their top-left corners. */
    public record Wire(ResourceLocation from, ResourceLocation to, int fromX, int fromY, boolean fromMachine,
                       int toX, int toY, boolean toMachine) {
        /** Matches GraphCanvas: a wire leaves each card by the face pointing at the other. */
        boolean leftward() {
            return toX < fromX;
        }
    }

    /** Port y offsets from each card's top edge. */
    public record Ports(int fromOffset, int toOffset) {}

    private record End(int wire, boolean isFrom, int otherY, int otherX) {}

    private record Face(ResourceLocation node, boolean right) {}

    /** {@code chamfer} is how far a machine card's corners are cut; its ports stay below and above it. */
    public static List<Ports> assign(List<Wire> wires, int nodeHeight, int chamfer) {
        Map<Face, List<End>> faces = new HashMap<>();
        Map<Face, Boolean> machineFace = new HashMap<>();
        for (int i = 0; i < wires.size(); i++) {
            Wire w = wires.get(i);
            boolean leftward = w.leftward();
            Face fromFace = new Face(w.from(), !leftward);
            Face toFace = new Face(w.to(), leftward);
            faces.computeIfAbsent(fromFace, f -> new ArrayList<>()).add(new End(i, true, w.toY(), w.toX()));
            faces.computeIfAbsent(toFace, f -> new ArrayList<>()).add(new End(i, false, w.fromY(), w.fromX()));
            machineFace.put(fromFace, w.fromMachine());
            machineFace.put(toFace, w.toMachine());
        }

        int[] fromOffsets = new int[wires.size()];
        int[] toOffsets = new int[wires.size()];
        for (Map.Entry<Face, List<End>> entry : faces.entrySet()) {
            List<End> ends = entry.getValue();
            ends.sort(Comparator.comparingInt(End::otherY).thenComparingInt(End::otherX)
                    .thenComparingInt(End::wire).thenComparing(End::isFrom));
            int[] offsets = spread(ends.size(), nodeHeight, machineFace.get(entry.getKey()) ? chamfer + 1 : 4);
            for (int k = 0; k < ends.size(); k++) {
                End end = ends.get(k);
                (end.isFrom() ? fromOffsets : toOffsets)[end.wire()] = offsets[k];
            }
        }

        List<Ports> result = new ArrayList<>(wires.size());
        for (int i = 0; i < wires.size(); i++) {
            result.add(new Ports(fromOffsets[i], toOffsets[i]));
        }
        return result;
    }

    /** Offsets for {@code n} ports in order, centred, inside {@code [margin, height - margin]}.
     *  Past the face's capacity, neighbouring wires share a slot so the order still holds. */
    static int[] spread(int n, int height, int margin) {
        int[] offsets = new int[n];
        int band = height - 2 * margin;
        int slots = Math.max(1, Math.min(n, band / MIN_SPACING + 1));
        double spacing = slots > 1 ? Math.min((double) band / (slots - 1), PREFERRED_SPACING) : 0;
        double centre = height / 2.0;
        for (int k = 0; k < n; k++) {
            int slot = (int) ((long) k * slots / n);
            offsets[k] = (int) Math.round(centre + (slot - (slots - 1) / 2.0) * spacing);
        }
        return offsets;
    }
}
