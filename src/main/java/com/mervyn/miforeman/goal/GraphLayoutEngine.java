package com.mervyn.miforeman.goal;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Layered graph layout for GraphCanvas, adapted from gtnh-factory-flow's {@code board-arrange.ts}.
 *
 * Settle passes use weighted isotonic regression (pool-adjacent-violators) to position nodes
 * near target connection points. Barycenter sorting and adjacent-swap passes reduce edge crossings.
 *
 * Locked {@link NodeGroup}s move as single blocks sized to their member bounds.
 * When {@code rearrangeInsideGroups} is enabled, each group's interior runs a scoped
 * layout pass that anchors boundary connections to external neighbor positions.
 *
 * Columns map directly to {@link RecipeGraphNode#getDepth()}. Cycle edges are ignored
 * during ranking (see {@link RecipeGraph#cyclicResourceIds()}).
 */
public final class GraphLayoutEngine {
    private GraphLayoutEngine() {}

    private static final int ROW_GAP = 8;
    private static final int SETTLE_SWEEPS = 8;
    private static final int REORDER_PASSES = 3;
    private static final int TRANSPOSE_PASSES = 3;

    /** A node's origin box: top-left plus size, in absolute canvas coordinates. */
    private record Box(int minX, int minY, int width, int height) {}

    /** One arrangeable unit in a layered pass: either an ungrouped {@link RecipeGraphNode}
     *  (an "atom") or a whole locked {@link NodeGroup} treated as one meta-card. */
    private static final class LayoutNode {
        final String sortKey;
        final int width;
        final int height;
        int column;
        double x;
        double y;
        long stableIndex;

        LayoutNode(String sortKey, int width, int height, int column) {
            this.sortKey = sortKey;
            this.width = width;
            this.height = height;
            this.column = column;
        }

        /** Stand-in for an external neighbor. Read by {@code PartnerEntry.other}, not placed in columns. */
        static LayoutNode phantom(double y) {
            LayoutNode p = new LayoutNode("", 0, 0, 0);
            p.y = y;
            return p;
        }
    }

    private record LayoutEdge(LayoutNode from, LayoutNode to, double fromAnchor, double toAnchor, double weight) {}

    private record PartnerEntry(LayoutNode other, double ownAnchor, double theirAnchor, double weight) {}

    /**
     * Computes positions for nodes in {@code graph}, keeping locked groups together.
     *
     * @param currentPositions Current canvas position for each node.
     * @param rearrangeInsideGroups When true, reorders nodes inside each group. When false,
     *                              preserves member offsets and moves each group as a block.
     * @return Map of node IDs to new positions.
     */
    public static Map<ResourceLocation, NodePosition> arrange(
            RecipeGraph graph,
            List<NodeGroup> groups,
            Map<ResourceLocation, NodePosition> currentPositions,
            boolean rearrangeInsideGroups,
            int nodeWidth, int nodeHeight, int columnGap
    ) {
        Map<ResourceLocation, NodePosition> result = new HashMap<>();
        if (graph == null || graph.nodes().isEmpty()) {
            return result;
        }

        Map<ResourceLocation, NodeGroup> groupOfMember = new HashMap<>();
        for (NodeGroup group : groups) {
            for (ResourceLocation member : group.memberIds()) {
                groupOfMember.put(member, group);
            }
        }

        // Group bounds and local Y offsets from current positions.
        Map<UUID, Box> box0 = new HashMap<>();
        Map<ResourceLocation, Double> localY0 = new HashMap<>();
        for (NodeGroup group : groups) {
            Box box = boundingBox(group.memberIds(), currentPositions, nodeWidth, nodeHeight);
            box0.put(group.id(), box);
            for (ResourceLocation member : group.memberIds()) {
                NodePosition pos = currentPositions.get(member);
                if (pos != null) {
                    localY0.put(member, (double) (pos.y() - box.minY()));
                }
            }
        }

        Map<UUID, Box> box1;
        Map<ResourceLocation, Double> localX1 = new HashMap<>();
        Map<ResourceLocation, Double> localY1;
        if (rearrangeInsideGroups && !groups.isEmpty()) {
            // Provisional outer pass lets interior layouts anchor to external neighbor positions.
            OuterResult provisional = runOuterPass(graph, groups, groupOfMember, box0, localY0, nodeWidth, nodeHeight, columnGap);

            box1 = new HashMap<>();
            localY1 = new HashMap<>();
            for (NodeGroup group : groups) {
                InteriorResult interior = arrangeGroupInterior(graph, group, groupOfMember, provisional, box0, localY0, nodeWidth, nodeHeight, columnGap);
                box1.put(group.id(), interior.box());
                localX1.putAll(interior.localX());
                localY1.putAll(interior.localY());
            }
        } else {
            box1 = box0;
            localY1 = localY0;
        }

        OuterResult finalOuter = runOuterPass(graph, groups, groupOfMember, box1, localY1, nodeWidth, nodeHeight, columnGap);

        result.putAll(finalOuter.atomPositions());
        for (NodeGroup group : groups) {
            NodePosition origin = finalOuter.metaOrigin().get(group.id());
            if (origin == null) continue;
            for (ResourceLocation member : group.memberIds()) {
                if (graph.node(member) == null) continue;
                Double y = localY1.get(member);
                Double x = rearrangeInsideGroups ? localX1.get(member) : null;
                NodePosition currentPos = currentPositions.get(member);
                int localX = (x != null)
                        ? (int) Math.round(x)
                        : (currentPos != null ? currentPos.x() - box1.get(group.id()).minX() : 0);
                if (y == null) continue;
                result.put(member, new NodePosition(origin.x() + localX, origin.y() + (int) Math.round(y)));
            }
        }
        return result;
    }

    private static Box boundingBox(java.util.Collection<ResourceLocation> members, Map<ResourceLocation, NodePosition> positions,
                                    int nodeWidth, int nodeHeight) {
        Bounds b = groupBounds(members, positions, nodeWidth, nodeHeight);
        return b == null ? new Box(0, 0, nodeWidth, nodeHeight) : new Box(b.minX(), b.minY(), b.width(), b.height());
    }

    /** Bounding box in absolute canvas coordinates. */
    public record Bounds(int minX, int minY, int maxX, int maxY) {
        public int width() { return maxX - minX; }
        public int height() { return maxY - minY; }
    }

    public static Bounds groupBounds(java.util.Collection<ResourceLocation> members, Map<ResourceLocation, NodePosition> positions,
                                      int nodeWidth, int nodeHeight) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (ResourceLocation member : members) {
            NodePosition pos = positions.get(member);
            if (pos == null) continue;
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            maxX = Math.max(maxX, pos.x() + nodeWidth);
            maxY = Math.max(maxY, pos.y() + nodeHeight);
        }
        return minX > maxX ? null : new Bounds(minX, minY, maxX, maxY);
    }

    private record OuterResult(Map<ResourceLocation, NodePosition> atomPositions, Map<UUID, NodePosition> metaOrigin) {}

    /** Lays out ungrouped nodes and group bounding boxes in a single layered pass. */
    private static OuterResult runOuterPass(
            RecipeGraph graph, List<NodeGroup> groups, Map<ResourceLocation, NodeGroup> groupOfMember,
            Map<UUID, Box> groupBox, Map<ResourceLocation, Double> memberLocalY,
            int nodeWidth, int nodeHeight, int columnGap
    ) {
        Map<ResourceLocation, LayoutNode> atomNodes = new HashMap<>();
        Map<UUID, LayoutNode> metaNodes = new HashMap<>();
        List<LayoutNode> allNodes = new ArrayList<>();

        for (RecipeGraphNode node : graph.nodes().values()) {
            if (groupOfMember.containsKey(node.getId())) continue;
            LayoutNode ln = new LayoutNode(node.getId().toString(), nodeWidth, nodeHeight, node.getDepth());
            atomNodes.put(node.getId(), ln);
            allNodes.add(ln);
        }
        for (NodeGroup group : groups) {
            Box box = groupBox.getOrDefault(group.id(), new Box(0, 0, nodeWidth, nodeHeight));
            int column = Integer.MAX_VALUE;
            for (ResourceLocation member : group.memberIds()) {
                RecipeGraphNode node = graph.node(member);
                if (node != null) column = Math.min(column, node.getDepth());
            }
            if (column == Integer.MAX_VALUE) column = 0;
            LayoutNode ln = new LayoutNode(group.id().toString(), box.width(), box.height(), column);
            metaNodes.put(group.id(), ln);
            allNodes.add(ln);
        }

        List<LayoutEdge> edges = new ArrayList<>();
        for (GraphEdge edge : graph.edges()) {
            LayoutNode from = resolveOwner(edge.from(), atomNodes, metaNodes, groupOfMember);
            LayoutNode to = resolveOwner(edge.to(), atomNodes, metaNodes, groupOfMember);
            if (from == null || to == null || from == to) continue;
            double fromAnchor = anchorFor(edge.from(), from, atomNodes, memberLocalY, nodeHeight);
            double toAnchor = anchorFor(edge.to(), to, atomNodes, memberLocalY, nodeHeight);
            edges.add(new LayoutEdge(from, to, fromAnchor, toAnchor, Math.max(edge.rate(), 0.01)));
        }

        assignPositions(allNodes, edges, columnGap);

        Map<ResourceLocation, NodePosition> atomPositions = new HashMap<>();
        for (var entry : atomNodes.entrySet()) {
            LayoutNode ln = entry.getValue();
            atomPositions.put(entry.getKey(), new NodePosition((int) Math.round(ln.x), (int) Math.round(ln.y)));
        }
        Map<UUID, NodePosition> metaOrigin = new HashMap<>();
        for (var entry : metaNodes.entrySet()) {
            LayoutNode ln = entry.getValue();
            metaOrigin.put(entry.getKey(), new NodePosition((int) Math.round(ln.x), (int) Math.round(ln.y)));
        }
        return new OuterResult(atomPositions, metaOrigin);
    }

    private static LayoutNode resolveOwner(ResourceLocation id, Map<ResourceLocation, LayoutNode> atomNodes,
                                            Map<UUID, LayoutNode> metaNodes, Map<ResourceLocation, NodeGroup> groupOfMember) {
        NodeGroup group = groupOfMember.get(id);
        if (group != null) return metaNodes.get(group.id());
        return atomNodes.get(id);
    }

    private static double anchorFor(ResourceLocation id, LayoutNode owner, Map<ResourceLocation, LayoutNode> atomNodes,
                                     Map<ResourceLocation, Double> memberLocalY, int nodeHeight) {
        if (atomNodes.get(id) == owner) {
            return nodeHeight / 2.0;
        }
        Double localY = memberLocalY.get(id);
        return (localY != null ? localY : 0.0) + nodeHeight / 2.0;
    }

    private record InteriorResult(Box box, Map<ResourceLocation, Double> localX, Map<ResourceLocation, Double> localY) {}

    /** Lays out nodes inside a group, pulling boundary connections toward external neighbor Y coordinates. */
    private static InteriorResult arrangeGroupInterior(
            RecipeGraph graph, NodeGroup group, Map<ResourceLocation, NodeGroup> groupOfMember,
            OuterResult provisionalOuter, Map<UUID, Box> box0, Map<ResourceLocation, Double> localY0,
            int nodeWidth, int nodeHeight, int columnGap
    ) {
        Map<ResourceLocation, LayoutNode> memberNodes = new HashMap<>();
        List<LayoutNode> allNodes = new ArrayList<>();
        int minDepth = Integer.MAX_VALUE;
        for (ResourceLocation member : group.memberIds()) {
            RecipeGraphNode node = graph.node(member);
            if (node == null) continue;
            minDepth = Math.min(minDepth, node.getDepth());
        }
        if (minDepth == Integer.MAX_VALUE) minDepth = 0;
        for (ResourceLocation member : group.memberIds()) {
            RecipeGraphNode node = graph.node(member);
            if (node == null) continue;
            LayoutNode ln = new LayoutNode(member.toString(), nodeWidth, nodeHeight, node.getDepth() - minDepth);
            memberNodes.put(member, ln);
            allNodes.add(ln);
        }

        NodePosition groupOrigin = provisionalOuter.metaOrigin().get(group.id());
        double originY = groupOrigin != null ? groupOrigin.y() : 0.0;

        List<LayoutEdge> edges = new ArrayList<>();
        Map<LayoutNode, List<PartnerEntry>> phantomPartners = new HashMap<>();
        for (GraphEdge edge : graph.edges()) {
            boolean fromIn = memberNodes.containsKey(edge.from());
            boolean toIn = memberNodes.containsKey(edge.to());
            if (fromIn && toIn) {
                edges.add(new LayoutEdge(memberNodes.get(edge.from()), memberNodes.get(edge.to()),
                        nodeHeight / 2.0, nodeHeight / 2.0, Math.max(edge.rate(), 0.01)));
            } else if (fromIn) {
                double targetY = externalY(edge.to(), groupOfMember, provisionalOuter, box0, localY0, nodeHeight) - originY;
                addPhantom(phantomPartners, memberNodes.get(edge.from()), nodeHeight / 2.0, targetY, edge.rate());
            } else if (toIn) {
                double targetY = externalY(edge.from(), groupOfMember, provisionalOuter, box0, localY0, nodeHeight) - originY;
                addPhantom(phantomPartners, memberNodes.get(edge.to()), nodeHeight / 2.0, targetY, edge.rate());
            }
        }

        assignPositions(allNodes, edges, columnGap, phantomPartners);

        int minX = 0, minY = 0;
        boolean any = false;
        for (LayoutNode ln : allNodes) {
            if (!any) { minX = (int) Math.round(ln.x); minY = (int) Math.round(ln.y); any = true; }
            minX = Math.min(minX, (int) Math.round(ln.x));
            minY = Math.min(minY, (int) Math.round(ln.y));
        }
        int width = 0, height = 0;
        Map<ResourceLocation, Double> localX = new HashMap<>();
        Map<ResourceLocation, Double> localY = new HashMap<>();
        for (var entry : memberNodes.entrySet()) {
            LayoutNode ln = entry.getValue();
            double relX = ln.x - minX;
            double relY = ln.y - minY;
            localX.put(entry.getKey(), relX);
            localY.put(entry.getKey(), relY);
            width = Math.max(width, (int) Math.round(relX) + ln.width);
            height = Math.max(height, (int) Math.round(relY) + ln.height);
        }
        return new InteriorResult(new Box(0, 0, width, height), localX, localY);
    }

    private static double externalY(ResourceLocation externalId, Map<ResourceLocation, NodeGroup> groupOfMember,
                                     OuterResult provisionalOuter, Map<UUID, Box> box0, Map<ResourceLocation, Double> localY0,
                                     int nodeHeight) {
        NodeGroup owningGroup = groupOfMember.get(externalId);
        if (owningGroup == null) {
            NodePosition pos = provisionalOuter.atomPositions().get(externalId);
            return pos != null ? pos.y() + nodeHeight / 2.0 : 0.0;
        }
        NodePosition origin = provisionalOuter.metaOrigin().get(owningGroup.id());
        Double local = localY0.get(externalId);
        if (origin == null || local == null) return 0.0;
        return origin.y() + local + nodeHeight / 2.0;
    }

    private static void addPhantom(Map<LayoutNode, List<PartnerEntry>> phantomPartners, LayoutNode member,
                                    double ownAnchor, double targetY, double weight) {
        phantomPartners.computeIfAbsent(member, k -> new ArrayList<>())
                .add(new PartnerEntry(LayoutNode.phantom(targetY), ownAnchor, 0.0, Math.max(weight, 0.01) * 3));
    }

    private static void assignPositions(List<LayoutNode> nodes, List<LayoutEdge> edges, int columnGap) {
        assignPositions(nodes, edges, columnGap, Map.of());
    }

    private static void assignPositions(List<LayoutNode> nodes, List<LayoutEdge> edges, int columnGap,
                                         Map<LayoutNode, List<PartnerEntry>> extraPartners) {
        if (nodes.isEmpty()) return;

        List<LayoutNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingInt((LayoutNode n) -> n.column).thenComparing(n -> n.sortKey));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).stableIndex = i;

        Map<Integer, List<LayoutNode>> byColumn = new TreeMap<>();
        for (LayoutNode n : sorted) byColumn.computeIfAbsent(n.column, k -> new ArrayList<>()).add(n);
        List<List<LayoutNode>> columns = new ArrayList<>(byColumn.values());
        for (List<LayoutNode> column : columns) column.sort(Comparator.comparingLong(n -> n.stableIndex));

        Map<LayoutNode, List<PartnerEntry>> partners = buildPartners(edges, extraPartners);

        placeRows(columns, partners);
        for (int pass = 0; pass < REORDER_PASSES; pass++) {
            polishColumnOrder(columns, partners);
            placeRows(columns, partners);
        }
        for (int pass = 0; pass < TRANSPOSE_PASSES; pass++) {
            if (!transposeToUncross(columns, edges)) break;
            placeRows(columns, partners);
        }

        double minY = Double.POSITIVE_INFINITY;
        for (LayoutNode n : nodes) minY = Math.min(minY, n.y);
        if (Double.isFinite(minY)) {
            for (LayoutNode n : nodes) n.y -= minY;
        }

        Map<Integer, Integer> columnWidth = new TreeMap<>();
        for (List<LayoutNode> column : columns) {
            if (column.isEmpty()) continue;
            int w = 0;
            for (LayoutNode n : column) w = Math.max(w, n.width);
            columnWidth.put(column.get(0).column, w);
        }
        List<Integer> orderedColumns = new ArrayList<>(columnWidth.keySet());
        Collections.sort(orderedColumns);
        Map<Integer, Double> columnX = new HashMap<>();
        double x = 0;
        for (int col : orderedColumns) {
            columnX.put(col, x);
            x += columnWidth.get(col) + columnGap;
        }
        for (LayoutNode n : nodes) {
            n.x = columnX.getOrDefault(n.column, 0.0);
        }
    }

    private static Map<LayoutNode, List<PartnerEntry>> buildPartners(List<LayoutEdge> edges, Map<LayoutNode, List<PartnerEntry>> extra) {
        Map<LayoutNode, List<PartnerEntry>> partners = new HashMap<>();
        for (LayoutEdge e : edges) {
            partners.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(new PartnerEntry(e.to(), e.fromAnchor(), e.toAnchor(), e.weight()));
            partners.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(new PartnerEntry(e.from(), e.toAnchor(), e.fromAnchor(), e.weight()));
        }
        for (var entry : extra.entrySet()) {
            partners.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
        }
        return partners;
    }

    private static double[] wishFor(LayoutNode node, List<PartnerEntry> list) {
        if (list == null || list.isEmpty()) {
            return new double[]{node.y, 0.1};
        }
        double sum = 0, total = 0;
        for (PartnerEntry p : list) {
            sum += (p.other().y + p.theirAnchor() - p.ownAnchor()) * p.weight();
            total += p.weight();
        }
        return new double[]{sum / total, total};
    }

    private static void placeRows(List<List<LayoutNode>> columns, Map<LayoutNode, List<PartnerEntry>> partners) {
        for (List<LayoutNode> column : columns) {
            double y = 0;
            for (int i = 0; i < column.size(); i++) {
                if (i > 0) y += ROW_GAP;
                LayoutNode node = column.get(i);
                node.y = y;
                y += node.height;
            }
        }
        for (int sweep = 0; sweep < SETTLE_SWEEPS; sweep++) {
            boolean downward = sweep % 2 == 0;
            for (int i = 0; i < columns.size(); i++) {
                settleColumn(columns.get(downward ? i : columns.size() - 1 - i), partners);
            }
        }
    }

    private static void settleColumn(List<LayoutNode> column, Map<LayoutNode, List<PartnerEntry>> partners) {
        int n = column.size();
        if (n == 0) return;
        double[] wish = new double[n];
        double[] weight = new double[n];
        double[] spacing = new double[n];
        for (int i = 0; i < n; i++) {
            LayoutNode node = column.get(i);
            double[] w = wishFor(node, partners.get(node));
            wish[i] = w[0];
            weight[i] = w[1];
            spacing[i] = i == 0 ? 0 : column.get(i - 1).height + ROW_GAP;
        }
        double[] ys = settleLine(wish, weight, spacing);
        for (int i = 0; i < n; i++) column.get(i).y = ys[i];
    }

    /** Weighted isotonic regression with minimum spacing (pool-adjacent-violators). */
    private static double[] settleLine(double[] wish, double[] weight, double[] spacing) {
        int n = wish.length;
        double[] starts = new double[n];
        double acc = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) acc += spacing[i];
            starts[i] = acc;
        }
        List<double[]> pools = new ArrayList<>(); // {mean, weight, count}
        for (int i = 0; i < n; i++) {
            double mean = wish[i] - starts[i];
            double w = weight[i];
            double count = 1;
            while (!pools.isEmpty() && pools.get(pools.size() - 1)[0] >= mean) {
                double[] prev = pools.remove(pools.size() - 1);
                mean = (prev[0] * prev[1] + mean * w) / (prev[1] + w);
                w += prev[1];
                count += prev[2];
            }
            pools.add(new double[]{mean, w, count});
        }
        double[] ys = new double[n];
        int index = 0;
        for (double[] pool : pools) {
            int count = (int) pool[2];
            for (int i = 0; i < count; i++) {
                ys[index] = pool[0] + starts[index];
                index++;
            }
        }
        return ys;
    }

    /** Sorts column members by average connected neighbor Y position to reduce edge crossings. */
    private static void polishColumnOrder(List<List<LayoutNode>> columns, Map<LayoutNode, List<PartnerEntry>> partners) {
        for (List<LayoutNode> column : columns) {
            if (column.size() < 2) continue;
            List<LayoutNode> sorted = new ArrayList<>(column);
            sorted.sort(Comparator.comparingDouble((LayoutNode n) -> wishFor(n, partners.get(n))[0])
                    .thenComparingLong(n -> n.stableIndex));
            column.clear();
            column.addAll(sorted);
        }
    }

    /** Swaps adjacent nodes in each column when swapping reduces edge crossings. */
    private static boolean transposeToUncross(List<List<LayoutNode>> columns, List<LayoutEdge> edges) {
        Map<LayoutNode, List<LayoutEdge>> byNode = new HashMap<>();
        for (LayoutEdge e : edges) {
            byNode.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            byNode.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        }
        boolean flippedAny = false;
        for (List<LayoutNode> column : columns) {
            for (int i = 0; i + 1 < column.size(); i++) {
                LayoutNode upper = column.get(i);
                LayoutNode lower = column.get(i + 1);
                List<LayoutEdge> involved = new ArrayList<>();
                for (LayoutEdge e : byNode.getOrDefault(upper, List.of())) if (!involved.contains(e)) involved.add(e);
                for (LayoutEdge e : byNode.getOrDefault(lower, List.of())) if (!involved.contains(e)) involved.add(e);
                if (involved.isEmpty()) continue;

                double upperCentre = upper.y + upper.height / 2.0;
                double lowerCentre = lower.y + lower.height / 2.0;
                double upperY = upper.y, lowerY = lower.y;
                double swappedUpperY = lowerCentre - upper.height / 2.0;
                double swappedLowerY = upperCentre - lower.height / 2.0;

                int stillCount = countCrossings(involved, edges);
                upper.y = swappedUpperY;
                lower.y = swappedLowerY;
                int swappedCount = countCrossings(involved, edges);
                if (swappedCount < stillCount) {
                    column.set(i, lower);
                    column.set(i + 1, upper);
                    flippedAny = true;
                } else {
                    upper.y = upperY;
                    lower.y = lowerY;
                }
            }
        }
        return flippedAny;
    }

    private static final double X_SPAN = 100000;

    private static int countCrossings(List<LayoutEdge> involved, List<LayoutEdge> all) {
        int total = 0;
        for (int a = 0; a < involved.size(); a++) {
            for (LayoutEdge other : all) {
                int b = involved.indexOf(other);
                if (b >= 0 && b <= a) continue;
                if (crossed(involved.get(a), other)) total++;
            }
        }
        return total;
    }

    private static boolean crossed(LayoutEdge first, LayoutEdge second) {
        if (first.from() == second.from() || first.from() == second.to()
                || first.to() == second.from() || first.to() == second.to()) {
            return false;
        }
        double[] a1 = pointOf(first, true);
        double[] a2 = pointOf(first, false);
        double[] b1 = pointOf(second, true);
        double[] b2 = pointOf(second, false);
        double d1 = turn(b1, b2, a1);
        double d2 = turn(b1, b2, a2);
        double d3 = turn(a1, a2, b1);
        double d4 = turn(a1, a2, b2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double[] pointOf(LayoutEdge edge, boolean fromEnd) {
        LayoutNode node = fromEnd ? edge.from() : edge.to();
        double anchor = fromEnd ? edge.fromAnchor() : edge.toAnchor();
        double x = node.column * X_SPAN + (fromEnd ? X_SPAN - 100 : 100);
        return new double[]{x, node.y + anchor};
    }

    private static double turn(double[] p, double[] q, double[] r) {
        return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
    }
}
