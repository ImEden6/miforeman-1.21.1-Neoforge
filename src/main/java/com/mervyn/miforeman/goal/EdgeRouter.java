package com.mervyn.miforeman.goal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

/**
 * Grid A* wire router for graph edges, adapted from gtnh-factory-flow.
 *
 * <p>Inflated card boxes are hard blocks, so a wire never clips a card. Reusing a cell another
 * wire occupies costs a little more than open space, so parallel wires bundle. Ports sit inside
 * their card's inflated box, so the search runs between apron cells outside it and the ports are
 * joined on as straight stubs.
 *
 * <p>Routing is deterministic. It is far dearer than drawing, so callers must cache results and
 * re-route only when positions or visibility change, never per frame.
 */
public final class EdgeRouter {
    private EdgeRouter() {}

    public static final double COST_EMPTY = 1.0;
    /** Above COST_EMPTY so wires prefer clear space, below a detour so they bundle. */
    public static final double COST_SHARED = 1.3;
    public static final double COST_TURN = 2.0;
    /** Enforced as a hard block, not a cost penalty. */
    public static final int CLEARANCE_CELLS = 1;
    public static final int MAX_EXPANSIONS = 4000;
    /**
     * Search budget for one whole route call, in cell expansions. Running out drops the whole
     * graph to elbows.
     *
     * <p>At about 2,500 expansions per millisecond this is just under half a second, paid once per
     * layout change. quantum_upgrade, about the largest MI graph, takes 402ms. Counting
     * expansions keeps the result deterministic on a busy machine.
     */
    public static final int MAX_TOTAL_EXPANSIONS = 1_500_000;
    /** How far past the box spanned by a wire's ports the search may go, in cells. Lets a hopeless wire fail fast. */
    private static final int SEARCH_WINDOW_CELLS = 48;
    private static final int MAX_APRON_STEPS = 6;
    private static final int MARGIN_CELLS = 8;

    /** Exclusive maxima. */
    public record Obstacle(int minX, int minY, int maxX, int maxY) {}

    public record Request(int fromX, int fromY, int toX, int toY) {}

    public record Point(int x, int y) {}

    /** {@code fallback} marks a wire the search could not route, drawn as an elbow. */
    public record Route(List<Point> points, boolean fallback) {}

    /** One routing pass, kept for reuse. {@code unpacked} holds each wire before lane packing. */
    public record Routing(List<Request> requests, List<Obstacle> obstacles, List<List<Point>> unpacked,
                          List<Boolean> fallbacks, List<Route> routes, int expansions, boolean exhausted) {}

    /**
     * Routes every request in order, sharing one occupancy map. Returns one route per request.
     *
     * @param gridSize cell size in pixels. Gaps between stacked cards are only 8 to 14px, so a
     *                 coarse grid seals them.
     * @param laneGap  spacing between wires sharing a line. Zero disables packing.
     */
    public static List<Route> route(List<Request> requests, List<Obstacle> obstacles, int gridSize, int laneGap) {
        return route(requests, obstacles, gridSize, laneGap, MAX_TOTAL_EXPANSIONS);
    }

    public static List<Route> route(List<Request> requests, List<Obstacle> obstacles, int gridSize, int laneGap,
            int maxTotalExpansions) {
        return route(requests, obstacles, gridSize, laneGap, maxTotalExpansions, null).routes();
    }

    /** Reuses every wire from {@code previous} whose request is unchanged and not covered by a newly placed card. */
    public static Routing route(List<Request> requests, List<Obstacle> obstacles, int gridSize, int laneGap,
            @Nullable Routing previous) {
        return route(requests, obstacles, gridSize, laneGap, MAX_TOTAL_EXPANSIONS, previous);
    }

    public static Routing route(List<Request> requests, List<Obstacle> obstacles, int gridSize, int laneGap,
            int maxTotalExpansions, @Nullable Routing previous) {
        if (gridSize <= 0)
            throw new IllegalArgumentException("gridSize must be positive, got " + gridSize);
        if (requests.isEmpty())
            return new Routing(List.of(), List.copyOf(obstacles), List.of(), List.of(), List.of(), 0, false);

        int n = requests.size();
        int clearance = gridSize * CLEARANCE_CELLS;
        Grid grid = Grid.covering(requests, obstacles, gridSize);
        Map<Integer, Integer> usage = new HashMap<>();
        Search search = new Search(grid, maxTotalExpansions);
        List<List<Point>> polylines = new ArrayList<>(Collections.nCopies(n, null));
        List<Boolean> fallbacks = new ArrayList<>(Collections.nCopies(n, false));

        // An exhausted pass is all elbows. Reusing it would pin the graph to elbows for good.
        if (previous != null && !previous.exhausted()) {
            Set<Obstacle> before = new HashSet<>(previous.obstacles());
            Set<Obstacle> now = new HashSet<>(obstacles);
            List<Obstacle> added = obstacles.stream().filter(o -> !before.contains(o)).toList();
            List<Obstacle> changed = new ArrayList<>(added);
            previous.obstacles().stream().filter(o -> !now.contains(o)).forEach(changed::add);
            // Match by endpoints, since a plan change adds, drops and reorders wires.
            Map<Request, ArrayDeque<Integer>> previousAt = new HashMap<>();
            for (int j = 0; j < previous.requests().size(); j++)
                previousAt.computeIfAbsent(previous.requests().get(j), k -> new ArrayDeque<>()).add(j);
            for (int i = 0; i < n; i++) {
                ArrayDeque<Integer> matches = previousAt.get(requests.get(i));
                Integer j = matches == null ? null : matches.poll();
                if (j == null)
                    continue;
                List<Point> old = previous.unpacked().get(j);
                if (previous.fallbacks().get(j)) {
                    // Retry only when a nearby card moved, which may have opened a path.
                    if (!overlapsAny(old, changed, clearance)) {
                        polylines.set(i, new ArrayList<>(old));
                        fallbacks.set(i, true);
                    }
                } else if (allClear(old, added, clearance)) {
                    polylines.set(i, new ArrayList<>(old));
                    markUsage(old, grid, usage);
                }
            }
        }

        for (int i = 0; i < n; i++) {
            if (polylines.get(i) != null)
                continue;
            List<Point> routed = routeOne(requests.get(i), grid, usage, search);
            fallbacks.set(i, routed == null);
            polylines.set(i, routed != null ? routed : elbowPoints(requests.get(i)));
        }

        // Running out mid-pass would leave only the first wires routed, which looks like a bug.
        // Drop the whole graph to elbows.
        if (search.exhausted()) {
            List<List<Point>> elbows = new ArrayList<>(n);
            List<Route> allElbows = new ArrayList<>(n);
            for (Request request : requests) {
                elbows.add(List.copyOf(elbowPoints(request)));
                allElbows.add(elbow(request));
            }
            return new Routing(List.copyOf(requests), List.copyOf(obstacles), elbows,
                    List.copyOf(Collections.nCopies(n, true)), allElbows, search.totalExpansions, true);
        }

        List<List<Point>> unpacked = new ArrayList<>(n);
        List<List<Point>> packed = new ArrayList<>(n);
        for (List<Point> polyline : polylines) {
            unpacked.add(List.copyOf(polyline));
            packed.add(new ArrayList<>(polyline));
        }
        if (laneGap > 0)
            packIntoLanes(packed, fallbacks, laneGap, obstacles, clearance);

        List<Route> routes = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            routes.add(new Route(List.copyOf(packed.get(i)), fallbacks.get(i)));
        return new Routing(List.copyOf(requests), List.copyOf(obstacles), unpacked, List.copyOf(fallbacks), routes,
                search.totalExpansions, false);
    }

    /** Includes port stubs, since a card dropped beside a port can cover a stub. */
    private static boolean allClear(List<Point> points, List<Obstacle> obstacles, int clearance) {
        if (obstacles.isEmpty())
            return true;
        for (int i = 0; i < points.size() - 1; i++)
            if (!segmentClear(points.get(i), points.get(i + 1), obstacles, clearance))
                return false;
        return true;
    }

    private static boolean overlapsAny(List<Point> points, List<Obstacle> obstacles, int clearance) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Point p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return !segmentClear(new Point(minX, minY), new Point(maxX, maxY), obstacles, clearance);
    }

    /** Cell indices aren't kept between passes because the grid's origin can move. */
    private static void markUsage(List<Point> points, Grid grid, Map<Integer, Integer> usage) {
        for (int i = 0; i < points.size() - 1; i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            int length = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.y() - a.y()));
            int steps = Math.max(1, length / grid.gridSize);
            for (int step = 0; step <= steps; step++) {
                int x = a.x() + (b.x() - a.x()) * step / steps;
                int y = a.y() + (b.y() - a.y()) * step / steps;
                int cell = grid.cellAt(grid.col(x), grid.row(y));
                if (cell >= 0)
                    usage.merge(cell, 1, Integer::sum);
            }
        }
    }

    public static List<Route> route(List<Request> requests, List<Obstacle> obstacles, int gridSize) {
        return route(requests, obstacles, gridSize, gridSize);
    }

    public record Hop(int segment, int from, int to) {}

    private record Seg(int owner, int index, boolean vertical, int fixed, int min, int max) {}

    private static final int HOP_PORT_MARGIN = 12;

    /**
     * Where each wire bumps over the wires beneath it. Only the later wire bumps, so each crossing
     * gets one bump. Port stubs and crossings near a segment end never bump, and close crossings
     * merge.
     */
    public static List<List<Hop>> hops(List<List<Point>> polylinesInDrawOrder, int cornerMargin, int halfWidth) {
        TreeMap<Integer, List<Seg>> verticals = new TreeMap<>();
        TreeMap<Integer, List<Seg>> horizontals = new TreeMap<>();
        List<List<Seg>> byOwner = new ArrayList<>(polylinesInDrawOrder.size());
        for (int owner = 0; owner < polylinesInDrawOrder.size(); owner++) {
            List<Point> points = polylinesInDrawOrder.get(owner);
            List<Seg> own = new ArrayList<>();
            int last = points.size() - 2;
            for (int i = 0; i <= last; i++) {
                Point a = points.get(i);
                Point b = points.get(i + 1);
                boolean vertical = a.x() == b.x() && a.y() != b.y();
                if (!vertical && !(a.y() == b.y() && a.x() != b.x()))
                    continue;
                int from = vertical ? a.y() : a.x();
                int to = vertical ? b.y() : b.x();
                // The stub merges into the first and last runs, so trim only the stretch by the port.
                if (i == 0)
                    from += Integer.signum(to - from) * HOP_PORT_MARGIN;
                if (i == last)
                    to -= Integer.signum(to - from) * HOP_PORT_MARGIN;
                int min = Math.min(from, to), max = Math.max(from, to);
                if ((to - from) * ((vertical ? b.y() - a.y() : b.x() - a.x())) <= 0)
                    continue;
                Seg seg = new Seg(owner, i, vertical, vertical ? a.x() : a.y(), min, max);
                (vertical ? verticals : horizontals).computeIfAbsent(seg.fixed(), k -> new ArrayList<>()).add(seg);
                own.add(seg);
            }
            byOwner.add(own);
        }

        List<List<Hop>> result = new ArrayList<>(polylinesInDrawOrder.size());
        for (int owner = 0; owner < polylinesInDrawOrder.size(); owner++) {
            List<Hop> hops = new ArrayList<>();
            for (Seg seg : byOwner.get(owner)) {
                if (seg.max() - seg.min() <= 2 * cornerMargin)
                    continue;
                TreeMap<Integer, List<Seg>> across = seg.vertical() ? horizontals : verticals;
                List<Integer> crossings = new ArrayList<>();
                for (List<Seg> candidates : across.subMap(seg.min() + cornerMargin, false,
                        seg.max() - cornerMargin, false).values()) {
                    for (Seg other : candidates) {
                        if (other.owner() < owner && other.min() + cornerMargin < seg.fixed()
                                && seg.fixed() < other.max() - cornerMargin)
                            crossings.add(other.fixed());
                    }
                }
                Collections.sort(crossings);
                int spanFrom = 0, spanTo = 0;
                boolean open = false;
                for (int at : crossings) {
                    if (open && at - halfWidth <= spanTo) {
                        spanTo = at + halfWidth;
                        continue;
                    }
                    if (open)
                        hops.add(new Hop(seg.index(), spanFrom, spanTo));
                    spanFrom = at - halfWidth;
                    spanTo = at + halfWidth;
                    open = true;
                }
                if (open)
                    hops.add(new Hop(seg.index(), spanFrom, spanTo));
            }
            result.add(hops);
        }
        return result;
    }

    public static Route elbow(Request request) {
        return new Route(elbowPoints(request), true);
    }

    private static List<Point> elbowPoints(Request request) {
        int midX = (request.fromX() + request.toX()) / 2;
        return new ArrayList<>(List.of(
                new Point(request.fromX(), request.fromY()),
                new Point(midX, request.fromY()),
                new Point(midX, request.toY()),
                new Point(request.toX(), request.toY())));
    }


    /** Returns null if the wire is boxed in or too expensive. */
    private static List<Point> routeOne(Request request, Grid grid, Map<Integer, Integer> usage, Search search) {
        if (search.exhausted())
            return null;
        // Ports sit inside their card's inflated box, so search apron to apron.
        // Most edges run right to left, since layout puts the target at x=0, so "outward"
        // depends on the wire's own direction.
        int outward = request.toX() >= request.fromX() ? 1 : -1;
        int startCell = grid.apron(request.fromX(), request.fromY(), outward);
        int goalCell = grid.apron(request.toX(), request.toY(), -outward);
        if (startCell < 0 || goalCell < 0)
            return null;

        List<Integer> cells = search.solve(startCell, goalCell, usage);
        if (cells == null)
            return null;

        for (int cell : cells)
            usage.merge(cell, 1, Integer::sum);

        List<Point> points = new ArrayList<>(cells.size() + 2);
        points.add(new Point(request.fromX(), request.fromY()));
        for (int i = 0; i < cells.size(); i++) {
            Point centre = grid.centreOf(cells.get(i));
            // Each apron sits in its port's cell row, so pinning it to the port's y keeps the
            // stub straight with no one-pixel jog.
            if (i == 0)
                centre = new Point(centre.x(), request.fromY());
            else if (i == cells.size() - 1)
                centre = new Point(centre.x(), request.toY());
            points.add(centre);
        }
        points.add(new Point(request.toX(), request.toY()));
        return orthogonalise(points);
    }

    /**
     * A* over (cell, incoming direction) states, reused across every wire in one pass.
     *
     * <p>Tables are primitive arrays reset by bumping a visit stamp. Rebuilding hash maps per wire
     * was most of the cost on real graphs. Ties break on cost then state key, so results are
     * deterministic.
     */
    private static final class Search {
        private static final int DIRECTIONS = 5;

        private final Grid grid;
        private final double[] bestG;
        private final int[] cameFrom;
        private final int[] stamp;
        private final PriorityQueue<Node> frontier = new PriorityQueue<>(
                Comparator.comparingDouble((Node node) -> node.f)
                        .thenComparingDouble(node -> node.g)
                        .thenComparingInt(node -> node.state));
        private int currentStamp;
        int totalExpansions;
        private final int maxTotalExpansions;

        /** Lets remaining wires skip straight to an elbow once the budget is spent. */
        boolean exhausted() {
            return totalExpansions > maxTotalExpansions;
        }

        Search(Grid grid, int maxTotalExpansions) {
            this.grid = grid;
            this.maxTotalExpansions = maxTotalExpansions;
            int states = grid.cols * grid.rows * DIRECTIONS;
            this.bestG = new double[states];
            this.cameFrom = new int[states];
            this.stamp = new int[states];
        }

        List<Integer> solve(int startCell, int goalCell, Map<Integer, Integer> usage) {
            if (startCell == goalCell)
                return List.of(startCell);

            currentStamp++;
            frontier.clear();

            int startCol = startCell % grid.cols, startRow = startCell / grid.cols;
            int goalCol = goalCell % grid.cols, goalRow = goalCell / grid.cols;
            int minCol = Math.min(startCol, goalCol) - SEARCH_WINDOW_CELLS;
            int maxCol = Math.max(startCol, goalCol) + SEARCH_WINDOW_CELLS;
            int minRow = Math.min(startRow, goalRow) - SEARCH_WINDOW_CELLS;
            int maxRow = Math.max(startRow, goalRow) + SEARCH_WINDOW_CELLS;

            int startState = stateOf(startCell, NO_DIRECTION);
            visit(startState, 0.0, -1);
            frontier.add(new Node(startState, 0.0, grid.heuristic(startCell, goalCell)));

            int expansions = 0;
            while (!frontier.isEmpty()) {
                if (++expansions > MAX_EXPANSIONS || ++totalExpansions > maxTotalExpansions)
                    return null;
                Node current = frontier.poll();
                if (stamp[current.state] != currentStamp || current.g > bestG[current.state])
                    continue;
                int cell = current.state / DIRECTIONS;
                int dir = current.state % DIRECTIONS;
                if (cell == goalCell)
                    return reconstruct(current.state);

                for (int nextDir = 0; nextDir < 4; nextDir++) {
                    int next = grid.step(cell, nextDir);
                    if (next < 0 || grid.blocked(next))
                        continue;
                    int nextCol = next % grid.cols, nextRow = next / grid.cols;
                    if (nextCol < minCol || nextCol > maxCol || nextRow < minRow || nextRow > maxRow)
                        continue;
                    double step = (usage.getOrDefault(next, 0) > 0 ? COST_SHARED : COST_EMPTY)
                            + (dir != NO_DIRECTION && nextDir != dir ? COST_TURN : 0.0);
                    double g = current.g + step;
                    int nextState = stateOf(next, nextDir);
                    if (stamp[nextState] == currentStamp && bestG[nextState] <= g)
                        continue;
                    visit(nextState, g, current.state);
                    frontier.add(new Node(nextState, g, g + grid.heuristic(next, goalCell)));
                }
            }
            return null;
        }

        private void visit(int state, double g, int from) {
            stamp[state] = currentStamp;
            bestG[state] = g;
            cameFrom[state] = from;
        }

        private List<Integer> reconstruct(int endState) {
            List<Integer> cells = new ArrayList<>();
            int state = endState;
            while (state >= 0) {
                cells.add(state / DIRECTIONS);
                state = cameFrom[state];
            }
            Collections.reverse(cells);
            return cells;
        }

        private static int stateOf(int cell, int dir) {
            return cell * DIRECTIONS + dir;
        }
    }

    private static final class Node {
        final int state;
        final double g;
        final double f;

        Node(int state, double g, double f) {
            this.state = state;
            this.g = g;
            this.f = f;
        }
    }

    /** "No incoming direction yet", so the first step is never a turn. */
    private static final int NO_DIRECTION = 4;


    private static List<Point> orthogonalise(List<Point> raw) {
        List<Point> squared = new ArrayList<>(raw.size() * 2);
        squared.add(raw.get(0));
        for (int i = 1; i < raw.size(); i++) {
            Point previous = squared.get(squared.size() - 1);
            Point point = raw.get(i);
            if (previous.equals(point))
                continue;
            if (previous.x() != point.x() && previous.y() != point.y())
                squared.add(new Point(point.x(), previous.y()));
            squared.add(point);
        }

        List<Point> simplified = new ArrayList<>(squared.size());
        for (Point point : squared) {
            int size = simplified.size();
            if (size > 0 && simplified.get(size - 1).equals(point))
                continue;
            if (size >= 2) {
                Point a = simplified.get(size - 2);
                Point b = simplified.get(size - 1);
                boolean collinear = (a.x() == b.x() && b.x() == point.x())
                        || (a.y() == b.y() && b.y() == point.y());
                if (collinear) {
                    simplified.set(size - 1, point);
                    continue;
                }
            }
            simplified.add(point);
        }
        return simplified;
    }


    /**
     * Fans out wires sharing a line. Port stubs stay pinned, and a shifted segment carries its
     * corner points so the polyline stays connected.
     *
     * <p>A shift is kept only if the moved segments stay clear of every obstacle. Packing runs
     * after the search, so without this check it slides wires through cards.
     */
    private static void packIntoLanes(List<List<Point>> polylines, List<Boolean> fallbacks, int laneGap,
            List<Obstacle> obstacles, int clearance) {
        Map<String, List<int[]>> lanes = new HashMap<>();
        for (int r = 0; r < polylines.size(); r++) {
            if (fallbacks.get(r))
                continue;
            List<Point> points = polylines.get(r);
            // The first and last segments are the port stubs, which stay on the card's port.
            for (int i = 1; i < points.size() - 2; i++) {
                Point a = points.get(i);
                Point b = points.get(i + 1);
                String lane = a.x() == b.x() ? "V:" + a.x() : (a.y() == b.y() ? "H:" + a.y() : null);
                if (lane == null)
                    continue;
                lanes.computeIfAbsent(lane, key -> new ArrayList<>()).add(new int[] { r, i });
            }
        }

        List<Map.Entry<String, List<int[]>>> ordered = new ArrayList<>(lanes.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, List<int[]>> entry : ordered) {
            List<int[]> members = entry.getValue();
            if (members.size() < 2)
                continue;
            members.sort(Comparator.<int[]>comparingInt(member -> member[0]).thenComparingInt(member -> member[1]));
            boolean vertical = entry.getKey().startsWith("V:");
            int centre = (members.size() - 1) / 2;
            for (int i = 0; i < members.size(); i++) {
                int offset = (i - centre) * laneGap;
                if (offset == 0)
                    continue;
                int[] member = members.get(i);
                List<Point> points = polylines.get(member[0]);
                List<Point> candidate = new ArrayList<>(points);
                shiftSegment(candidate, member[1], vertical, offset);
                if (segmentsClear(candidate, member[1], obstacles, clearance)) {
                    points.clear();
                    points.addAll(candidate);
                }
            }
        }
    }

    private static void shiftSegment(List<Point> points, int index, boolean vertical, int offset) {
        for (int i = index; i <= index + 1; i++) {
            Point point = points.get(i);
            points.set(i, vertical
                    ? new Point(point.x() + offset, point.y())
                    : new Point(point.x(), point.y() + offset));
        }
    }

    private static boolean segmentsClear(List<Point> points, int index, List<Obstacle> obstacles, int clearance) {
        int first = Math.max(0, index - 1);
        int last = Math.min(points.size() - 2, index + 1);
        for (int i = first; i <= last; i++)
            if (!segmentClear(points.get(i), points.get(i + 1), obstacles, clearance))
                return false;
        return true;
    }

    private static boolean segmentClear(Point a, Point b, List<Obstacle> obstacles, int clearance) {
        int minX = Math.min(a.x(), b.x());
        int maxX = Math.max(a.x(), b.x());
        int minY = Math.min(a.y(), b.y());
        int maxY = Math.max(a.y(), b.y());
        for (Obstacle obstacle : obstacles) {
            if (maxX > obstacle.minX() - clearance && minX < obstacle.maxX() + clearance
                    && maxY > obstacle.minY() - clearance && minY < obstacle.maxY() + clearance)
                return false;
        }
        return true;
    }


    private static final class Grid {
        final int originX;
        final int originY;
        final int cols;
        final int rows;
        final int gridSize;
        final boolean[] blocked;

        Grid(int originX, int originY, int cols, int rows, int gridSize) {
            this.originX = originX;
            this.originY = originY;
            this.cols = cols;
            this.rows = rows;
            this.gridSize = gridSize;
            this.blocked = new boolean[cols * rows];
        }

        static Grid covering(List<Request> requests, List<Obstacle> obstacles, int gridSize) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (Request request : requests) {
                minX = Math.min(minX, Math.min(request.fromX(), request.toX()));
                maxX = Math.max(maxX, Math.max(request.fromX(), request.toX()));
                minY = Math.min(minY, Math.min(request.fromY(), request.toY()));
                maxY = Math.max(maxY, Math.max(request.fromY(), request.toY()));
            }
            for (Obstacle obstacle : obstacles) {
                minX = Math.min(minX, obstacle.minX());
                maxX = Math.max(maxX, obstacle.maxX());
                minY = Math.min(minY, obstacle.minY());
                maxY = Math.max(maxY, obstacle.maxY());
            }

            int margin = gridSize * MARGIN_CELLS;
            int originX = minX - margin;
            int originY = minY - margin;
            int cols = Math.max(1, ((maxX + margin) - originX) / gridSize + 1);
            int rows = Math.max(1, ((maxY + margin) - originY) / gridSize + 1);

            Grid grid = new Grid(originX, originY, cols, rows, gridSize);
            for (Obstacle obstacle : obstacles)
                grid.block(obstacle);
            return grid;
        }

        void block(Obstacle obstacle) {
            int cx0 = col(obstacle.minX()) - CLEARANCE_CELLS;
            int cx1 = col(obstacle.maxX()) + CLEARANCE_CELLS;
            int cy0 = row(obstacle.minY()) - CLEARANCE_CELLS;
            int cy1 = row(obstacle.maxY()) + CLEARANCE_CELLS;
            for (int cy = Math.max(0, cy0); cy <= Math.min(rows - 1, cy1); cy++)
                for (int cx = Math.max(0, cx0); cx <= Math.min(cols - 1, cx1); cx++)
                    blocked[cy * cols + cx] = true;
        }

        int col(int x) {
            return Math.floorDiv(x - originX, gridSize);
        }

        int row(int y) {
            return Math.floorDiv(y - originY, gridSize);
        }

        int cellAt(int cx, int cy) {
            if (cx < 0 || cy < 0 || cx >= cols || cy >= rows)
                return -1;
            return cy * cols + cx;
        }

        boolean blocked(int cell) {
            return blocked[cell];
        }

        Point centreOf(int cell) {
            int cx = cell % cols;
            int cy = cell / cols;
            return new Point(originX + cx * gridSize + gridSize / 2, originY + cy * gridSize + gridSize / 2);
        }

        /** The first free cell stepping outward from a port, or -1 if the port is walled in. */
        int apron(int x, int y, int dx) {
            int cx = col(x);
            int cy = row(y);
            for (int step = 0; step <= MAX_APRON_STEPS; step++) {
                int cell = cellAt(cx + dx * step, cy);
                if (cell >= 0 && !blocked[cell])
                    return cell;
            }
            return -1;
        }

        /** Directions: 0=+x, 1=-x, 2=+y, 3=-y. */
        int step(int cell, int dir) {
            int cx = cell % cols;
            int cy = cell / cols;
            return switch (dir) {
                case 0 -> cellAt(cx + 1, cy);
                case 1 -> cellAt(cx - 1, cy);
                case 2 -> cellAt(cx, cy + 1);
                default -> cellAt(cx, cy - 1);
            };
        }

        /** Manhattan distance times the cheapest step, so it never overestimates. */
        double heuristic(int from, int to) {
            int dx = Math.abs((from % cols) - (to % cols));
            int dy = Math.abs((from / cols) - (to / cols));
            return (dx + dy) * COST_EMPTY;
        }
    }
}
