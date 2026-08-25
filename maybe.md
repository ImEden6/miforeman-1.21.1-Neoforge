# Maybe — nice-to-haves

Not scheduled, just captured so they don't get lost. Started as UI/UX polish
for `GraphCanvas` (recipe graph) and `DetailCard`; now also collects
correctness fixes, feature ideas, and docs debt.

## Interaction / UX

- **Hover state** — no visual feedback right now for the node under the
  cursor outside of drag; add a brightened border/fill on hover.
- **Tooltips on hover** — surface full recipe details (inputs/outputs,
  machine assignment, ambiguity options) instead of cramming everything into
  truncated node text; would also let nodes shrink back down.
- **Edge routing that avoids node overlap** — straight elbow connectors will
  cross through unrelated nodes once the graph gets busy; even a basic
  per-edge-index midpoint offset would help.
- **Selection propagates to edges** — highlight incoming/outgoing edges of
  the selected node (dim the rest) so dependencies are traceable without
  hunting through crossing lines. Cheap: filter `graph.edges()` by
  `from()`/`to() == selectedNodeId`.
- **Minimap or zoom-to-fit / center-on-selected** — no way to orient
  yourself or reset after getting lost in a large graph.
- **Collision feedback for manual drags** — dropping a dragged node on top of
  another currently gives no feedback; a "snap away" or warning outline on
  overlap would help.

## Native-clipboard theming follow-ups

- Re-check aspect ratio (`MIN_GUI_WIDTH`/`MIN_GUI_HEIGHT` in
  `ClipboardScreen.java`) after living with the landscape layout for a bit —
  440x230 was a first guess, not a measured choice.

## Monitoring & correctness

- **Consolidate `computePlan()` into `planFromGraph()`** — Now that the traversal discrepancy
  has been resolved (Kahn's algorithm cycle deadlocks fixed via reachable acyclic `collectDag` and
  PVC rate reconciled at 28000.0/s), derive `computePlan()` directly from `computeRecipeGraph()`'s
  resolved nodes to unify the pipeline into a single source of truth.

## Tests

- **Power demand calculation & math validation** — verify `FactoryPlan.totalPowerDemandEu()`
  strictly matches `sum(req.totalEuPerTick())` across all machine requirements in the plan,
  and validate that machine nodes with EU/t consumption scale with `ceil(count) * baseEuPerTick`.
- **Fluid target goal traversal** — test a `ProductionGoal` with `TargetType.FLUID` (e.g.
  `synthetic_oil`, `cryofluid`, `sulfuric_acid`) to verify both `computePlan` and
  `computeRecipeGraph` produce valid non-empty DAGs, correct machine requirements, and non-zero inputs.
- **Graph cache hit & invalidation semantics** — verify `GRAPH_CACHE` in `RecipeGraphTraverser`
  returns the exact same cached reference on identical goal queries, re-evaluates fresh instances
  after `clearGraphCache()`, and isolates entries across different `recipeSelections`.
- **Packet rate limiter throttling & multi-player isolation** — unit test `PacketRateLimiter.tryAcquire`
  to ensure rapid consecutive calls within `minIntervalTicks` are rejected, allowed after the interval,
  and track limits independently per player UUID without cross-contamination.
- **Multi-recipe wrap-around cycling** — verify ambiguity cycling on resources with 3+ alternative
  recipes (e.g. petrochemical distillation pathways) wraps around cleanly through all options
  without leaving orphaned nodes or stale edges.
- **Machine status dynamic transition tests** — test in-world machine state transitions across
  ticks from empty inputs (RED) to active crafting (GREEN) and blocked output saturation (ORANGE).

## Feature ideas

- **Alerts** — toast/chat notification when a machine sits RED > N seconds, or actual rate
  falls below threshold x expected. Currently you have to watch the monitor screen.
- **Bottleneck view** — rank machines by utilization (actual/expected) and feed it into
  `GraphCanvas` node/edge coloring, keying fill color to state (ambiguous, rate
  satisfied/deficit, raw vs intermediate vs target). The data already exists in
  `MachineTracker.energyEvents`.
- **Colour picker / theme customization (partially done)** — `ColourPalette` +
  `ColourPickerScreen` (opened via the "Colours" button on `ClipboardScreen`) now cover the two
  colours that actually carry meaning today: the in-world highlight boxes
  (`WorldHighlightRenderer`'s linked/candidate/located colours) and the Monitor list's
  Locate/Located buttons. Every other `COLOUR_*` value (`DetailCard`, `ClipboardScreen`,
  `GraphCanvas`, `MonitoringScreen`, `ReviewMachinesScreen`, `DefineGoalWindow`, and the list-panel
  widgets) is still a hardcoded `private static final int` — extend `ColourPalette.ColourKey` for
  those once something above actually starts keying colour to meaning (status colour-coding,
  bottleneck view, ambiguity highlighting).
- **Ambiguity resolution in DetailCard** — pick among alternative recipes per node in the GUI
  instead of falling back to `/miforeman goal select`.
- **Link-coverage indicator** — the plan says you need 12 assemblers; nothing shows "8 of 12
  linked". Match linked machines to plan slots via their active recipe; show coverage bars in
  the Monitor step.
- **Raw-inputs shopping list** — copyable text/JSON export of raw materials + rates for use
  outside the game.
- **Trend sparklines** — hours-scale rate/energy history charts in the Monitor step;
  server-side persistence across restarts is the optional stretch goal.
- **EMI/JEI integration** — click a node ingredient to inspect its recipes; set the goal
  target by dragging an item out of EMI.
- **Scan upgrades** — per-scan radius override in the Review panel (planned in
  `.scratch/machine-auto-detection/map.md`, never built), optional gentle force-load of
  scanned chunks, particle ping on newly found candidates.
- **Cross-dimension goals** — `linkedMachines` carries no dimension info; supporting real
  multi-dimension factories means GlobalPos end-to-end (codec, link sync, server-side
  validation, scanner, GUI lists). Pending market research on whether players actually build
  these; the planned GlobalPos tracker keys keep the monitoring side compatible either way.
- **Show a recipe's full input/output list in DetailCard** — a MACHINE node's undemanded
  byproducts (or any input/output the chosen recipe has that the graph doesn't otherwise track)
  are invisible right now; the graph only shows edges that were actually built for demanded
  resources. Surfacing the raw recipe (`RecipeGraphNode.getRecipe()` already carries the
  `MachineRecipe`) would at least let players see what else a machine produces, even if the tool
  doesn't route/plan around it.

## Code health

- **`RecipeGraphTraverser.java` complexity** — per Omen (`omen tdg`), this is
  the lowest-graded file in the repo: TDG grade B-, cyclomatic complexity 77,
  6 levels of nesting, 24.6% internal duplication. Unlike the `ClipboardScreen`/
  `GraphCanvas` cohesion split or the `formatId`/list-scroll dedup (both
  mechanical extractions), this is a real algorithmic-complexity problem in
  the recipe-traversal logic itself — reducing it means restructuring how the
  graph traversal branches, not just moving code around. Needs its own
  focused pass with test coverage before touching it, not an opportunistic
  cleanup.
