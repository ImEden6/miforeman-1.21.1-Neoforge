package com.mervyn.miforeman.goal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * Grid-based A* wire router for GraphCanvas edges, adapted from gtnh-factory-flow's
 * wire-routing pass.
 *
 * Edges were previously drawn as fixed horizontal-vertical-horizontal elbows, which knew
 * nothing about node boxes: a wire passing over a card drew straight through it, and two
 * edges with similar endpoints produced identical elbows that overlapped into one
 * indistinguishable line. This routes each wire around the cards instead.
 *
 * <p>The canvas is discretised into a {@code gridSize} grid. Obstacles (node boxes) are
 * inflated by {@link #CLEARANCE_CELLS} cell and hard-blocked in the search graph rather
 * than merely penalised, so a wire can never clip or hug a card. Reusing a cell an earlier
 * wire already occupies costs {@link #COST_SHARED} against {@link #COST_EMPTY} for open
 * space: dearer than a clear run, but cheaper than a long detour, so parallel wires bundle
 * along shared corridors and split near their endpoints without any per-edge offset
 * bookkeeping. {@link #COST_TURN} discourages staircasing.
 *
 * <p>An endpoint sits on the boundary of the very card the clearance rule forbids, so the
 * first and last hops are carved out geometrically: the search runs between "apron" cells
 * just outside each inflated box, and the true endpoints are appended as straight stubs.
 *
 * <p>Routing is deterministic: requests are routed in the order given, ties in the search
 * frontier break on a fixed cell ordering, and identical input always yields identical
 * output. A wire that is genuinely boxed in, or that exceeds {@link #MAX_EXPANSIONS},
 * falls back to the old elbow rather than vanishing or stalling the rest of the batch.
 *
 * <p>This class is pure geometry with no rendering or threading. Routing is far dearer
 * than the three fills it replaces, so callers must cache results and re-route only when
 * positions or visibility actually change, never per frame.
 */
public final class EdgeRouter {
    private EdgeRouter() {}

    /** Cost of entering a cell no other wire occupies. */
    public static final double COST_EMPTY = 1.0;
    /** Cost of entering a cell an earlier wire already uses. Above {@link #COST_EMPTY} so
     *  wires prefer clear space, below the cost of a detour so they bundle rather than
     *  wander when space is tight. */
    public static final double COST_SHARED = 1.3;
    /** Added when a step changes direction, to favour long straight runs over staircases. */
    public static final double COST_TURN = 2.0;
    /** Obstacle inflation, in cells. Enforced as a hard block, not a cost penalty. */
    public static final int CLEARANCE_CELLS = 1;
    /** Per-wire search ceiling, so one hopeless wire can't eat the whole pass. */
    public static final int MAX_EXPANSIONS = 4000;
    /**
     * Default ceiling on the search work one whole {@code route} call may do, in cell
     * expansions. Exhausting it drops the whole graph back to elbows.
     *
     * <p>Measured at roughly 2,500 expansions per millisecond, so this is a little under
     * half a second of worst-case work, paid once per layout change -- on open,
     * auto-arrange, undo/redo and drag commit -- and never per frame. Real graphs, arranged
     * by {@link GraphLayoutEngine} at canvas geometry, all fit inside it: steel_plate (86
     * cards, 111 wires) 61ms, analog_circuit (120/154) 33ms, electronic_circuit (197/263)
     * 62ms, digital_circuit (272/379) 100ms, advanced_motor (312/444) 182ms, and
     * quantum_upgrade (588 cards, 864 wires, about the largest MI can ask for) 402ms.
     *
     * <p>A budget in expansions rather than milliseconds keeps routing deterministic: the
     * same graph always yields the same wires, so nothing shifts between redraws on a
     * machine that happens to be busy.
     */
    public static final int MAX_TOTAL_EXPANSIONS = 1_500_000;
    /**
     * How far outside the box spanned by a wire's two ports the search may wander, in cells.
     *
     * <p>Nearly every edge joins adjacent columns, so a local window costs almost nothing in
     * route quality while letting a wire that cannot be routed exhaust its frontier and fall
     * back quickly instead of exploring the whole canvas first.
     */
    private static final int SEARCH_WINDOW_CELLS = 48;
    /** Cells searched outward from an endpoint when looking for a free apron. */
    private static final int MAX_APRON_STEPS = 6;
    /** Cells of slack added around the content bounds, so wires can leave the bounding box
     *  to get around cards sitting at the edge of the graph. */
    private static final int MARGIN_CELLS = 8;

    /** An axis-aligned box wires must route around, in canvas coordinates. Exclusive maxima. */
    public record Obstacle(int minX, int minY, int maxX, int maxY) {}

    /** One wire to route, from a source port to a target port, in canvas coordinates. */
    public record Request(int fromX, int fromY, int toX, int toY) {}

    /** A point on a routed polyline, in canvas coordinates. */
    public record Point(int x, int y) {}

    /** A routed wire as a polyline of at least two points. {@code fallback} marks a wire the
     *  search could not route, drawn as the old elbow so it still appears. */
    public record Route(List<Point> points, boolean fallback) {}

    /**
     * One routing pass, kept so the next pass can reuse wires whose endpoints didn't move.
     * {@code unpacked} is each wire before lane packing, since packing re-runs over every wire.
     */
    public record Routing(List<Request> requests, List<Obstacle> obstacles, List<List<Point>> unpacked,
                          List<Boolean> fallbacks, List<Route> routes, int expansions, boolean exhausted) {}

    /**
     * Routes every request around {@code obstacles}, in order, sharing one occupancy map so
     * later wires can bundle onto earlier ones. Returns one route per request, positionally.
     *
     * @param gridSize cell size in pixels; must be positive. Smaller grids find tighter
     *                 passages at a steep cost in search space. The canvas leaves only an
     *                 8-14px vertical gap between stacked cards, so a grid coarse enough to
     *                 seal those gaps pushes wires into the wider inter-column corridors,
     *                 which is usually what reads best anyway.
     * @param laneGap  perpendicular spacing applied to wires sharing a line, so a bundle
     *                 reads as several wires rather than one thick one. Zero disables packing.
     */
    public static List<Route> route(List<Request> requests, List<Obstacle> obstacles, int gridSize, int laneGap) {
        return route(requests, obstacles, gridSize, laneGap, MAX_TOTAL_EXPANSIONS);
    }

    /**
     * As {@link #route(List, List, int, int)}, with an explicit whole-pass budget in cell
     * expansions. Exists so the give-up behavior can be exercised directly with a small
     * budget, rather than by hunting for a graph large enough to trip the default.
     */
    public static List<Route> route(List<Request> requests, List<Obstacle> obstacles, int gridSize, int laneGap,
            int maxTotalExpansions) {
        return route(requests, obstacles, gridSize, laneGap, maxTotalExpansions, null).routes();
    }

    /**
     * As {@link #route(List, List, int, int)}, reusing every wire from {@code previous} whose
     * request is unchanged and that no newly placed card now sits on. Only the rest are searched,
     * so moving one card re-routes its own wires instead of the whole graph.
     */
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

        // An exhausted pass is all elbows; reusing it would pin the graph to elbows for good.
        if (previous != null && !previous.exhausted() && previous.requests().size() == n) {
            Set<Obstacle> before = new HashSet<>(previous.obstacles());
            Set<Obstacle> now = new HashSet<>(obstacles);
            List<Obstacle> added = obstacles.stream().filter(o -> !before.contains(o)).toList();
            List<Obstacle> changed = new ArrayList<>(added);
            previous.obstacles().stream().filter(o -> !now.contains(o)).forEach(changed::add);
            for (int i = 0; i < n; i++) {
                Request request = requests.get(i);
                if (!request.equals(previous.requests().get(i)))
                    continue;
                List<Point> old = previous.unpacked().get(i);
                if (previous.fallbacks().get(i)) {
                    // Retry only when a card near it moved, since that may have opened a path.
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

        // Running out of budget mid-pass would leave the wires that happened to come first
        // routed and the rest as elbows, which reads as a bug rather than a limit. Give up on
        // the whole graph instead, which is exactly the old behavior and looks deliberate.
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

    /** Every segment, port stubs included: a card dropped beside a port can cover a stub. */
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

    /** Counts a reused wire's cells as occupied, so new wires still bundle onto it. Cell
     *  indices aren't kept between passes because the grid's origin can move. */
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

    /** Convenience overload using the grid size as the lane gap. */
    public static List<Route> route(List<Request> requests, List<Obstacle> obstacles, int gridSize) {
        return route(requests, obstacles, gridSize, gridSize);
    }

    /** The old fixed horizontal-vertical-horizontal elbow, kept as the fallback path. */
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

    // ---- search ----------------------------------------------------------------

    /** Routes one wire, or returns null if it is boxed in or too expensive to solve. */
    private static List<Point> routeOne(Request request, Grid grid, Map<Integer, Integer> usage, Search search) {
        if (search.exhausted())
            return null;
        // The ports themselves sit inside the inflated box of the card they belong to, so
        // the search runs apron-to-apron and the ports are stitched on afterwards.
        //
        // Which way "outward" points depends on the wire, not on the graph: the recipe
        // graph is laid out target-first at x=0 with inputs extending right, so most edges
        // run right-to-left, and a fixed left-to-right assumption would push both aprons
        // away from each other and force every such wire the long way around its cards.
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
            // Both aprons were found by stepping horizontally from their port, so each sits
            // in the port's own cell row and the port's y is inside that free cell. Pinning
            // the apron to the port's y keeps it there while making the stub a straight
            // line, instead of leaving a one-pixel jog where stub meets route.
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
     * A* over (cell, incoming direction) states, reused across every wire in one routing
     * pass.
     *
     * <p>The per-state tables are primitive arrays allocated once and reset by bumping a
     * visit stamp rather than being cleared, because a routing pass runs one search per
     * wire and rebuilding hash maps for each was the bulk of the cost on a real graph.
     * Search is confined to a window around the wire's own ports; see
     * {@link EdgeRouter#SEARCH_WINDOW_CELLS}.
     *
     * <p>Deterministic: frontier ties break on accumulated cost then state key, so
     * equal-cost paths always resolve the same way.
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
        /** Search work spent across every wire in this pass; see {@link #MAX_TOTAL_EXPANSIONS}. */
        int totalExpansions;
        private final int maxTotalExpansions;

        /** Whether the pass has spent its whole budget, so remaining wires can skip straight
         *  to an elbow instead of each paying for a doomed search. */
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

        /** The path of cells from start to goal, or null if there isn't a usable one. */
        List<Integer> solve(int startCell, int goalCell, Map<Integer, Integer> usage) {
            if (startCell == goalCell)
                return List.of(startCell);

            currentStamp++;
            frontier.clear();

            // Confine the search to a window around the two ports, in grid cells.
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

    /** Stands in for "no incoming direction yet", so the very first step is never a turn. */
    private static final int NO_DIRECTION = 4;

    // ---- polyline cleanup ------------------------------------------------------

    /**
     * Turns the cell-centre chain into a clean orthogonal polyline: inserts a corner where
     * the path changes axis, then drops points that sit mid-run on a straight segment.
     */
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

    // ---- lane packing ----------------------------------------------------------

    /**
     * Fans out wires that ended up on the same line so a bundle reads as several wires
     * instead of one thick one. Only interior segments move: the stubs at either end stay
     * pinned to their ports, and shifting a segment carries both of its shared corner
     * points, so the polyline stays connected and orthogonal.
     *
     * <p>A shift is only kept if the moved segment and the two neighbours it drags with it
     * stay clear of every obstacle. Packing runs after the search, so it has no idea what
     * the search was routing around; without this check it happily slides a wire off a
     * legal path and straight through a card, which is exactly the clipping the router
     * exists to prevent. A wire whose offset would collide simply stays in the shared lane.
     */
    private static void packIntoLanes(List<List<Point>> polylines, List<Boolean> fallbacks, int laneGap,
            List<Obstacle> obstacles, int clearance) {
        Map<String, List<int[]>> lanes = new HashMap<>();
        for (int r = 0; r < polylines.size(); r++) {
            if (fallbacks.get(r))
                continue;
            List<Point> points = polylines.get(r);
            // Segment i runs points[i] -> points[i+1]. The first and last segments are the
            // port stubs, which must stay where the card's port actually is.
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

    /** Moves one segment sideways, carrying both of its shared corner points. */
    private static void shiftSegment(List<Point> points, int index, boolean vertical, int offset) {
        for (int i = index; i <= index + 1; i++) {
            Point point = points.get(i);
            points.set(i, vertical
                    ? new Point(point.x() + offset, point.y())
                    : new Point(point.x(), point.y() + offset));
        }
    }

    /**
     * Whether the shifted segment and the two neighbours that stretch to meet it all stay
     * clear of every obstacle. Only those three can have moved, so the rest of the
     * polyline is left alone.
     */
    private static boolean segmentsClear(List<Point> points, int index, List<Obstacle> obstacles, int clearance) {
        int first = Math.max(0, index - 1);
        int last = Math.min(points.size() - 2, index + 1);
        for (int i = first; i <= last; i++)
            if (!segmentClear(points.get(i), points.get(i + 1), obstacles, clearance))
                return false;
        return true;
    }

    /** Whether an axis-aligned segment misses every obstacle, keeping {@code clearance}. */
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

    // ---- grid ------------------------------------------------------------------

    /** The discretised canvas: a rectangular cell field with inflated obstacles baked in. */
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

        /** Blocks every cell the box touches, inflated by {@link #CLEARANCE_CELLS}. */
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

        /**
         * The first free cell stepping horizontally outward from a port. The port sits on
         * its card's boundary, inside the inflated box, so this walks out until it clears
         * the block — the geometric carve-out that lets clearance stay a hard rule
         * everywhere else. Returns -1 if the port is walled in.
         */
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

        /** The neighbour of {@code cell} in direction {@code dir} (0=+x, 1=-x, 2=+y, 3=-y). */
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

        /** Manhattan cell distance times the cheapest possible step, so it never overestimates. */
        double heuristic(int from, int to) {
            int dx = Math.abs((from % cols) - (to % cols));
            int dy = Math.abs((from / cols) - (to / cols));
            return (dx + dy) * COST_EMPTY;
        }
    }
}
