# Maybe — nice-to-haves

Not scheduled, just captured so they don't get lost. Started as UI/UX polish
for `GraphCanvas` (recipe graph) and `DetailCard`; now also collects
correctness fixes, feature ideas, and docs debt.

## Visual polish

- **Node depth/border treatment** — a 2-3px darker bottom/right edge (or
  lighter top-left highlight) so nodes read as raised tiles instead of flat
  debug rectangles.
- **Status color-coding** — key node fill color to state (ambiguous, rate
  satisfied/deficit, raw vs intermediate vs target), the way MineColonies
  keys research-tile texture to `ResearchButtonState`. Probably the single
  highest-value visual change since the graph's whole point is surfacing
  production status at a glance.
- **Edge weight/direction styling** — thickness or color scaled to
  `requiredRate`/utilization ratio, turning the graph into an actual
  production-flow diagram instead of boxes with lines. (Already flagged as
  out-of-scope in a `GraphCanvas` comment.)
- **Gradient node fill** instead of flat `COLOR_NODE_FILL` — cheap via
  `guiGraphics.fillGradient`, reads less like a placeholder.

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

- `clipboard_button.png` exists (regenerated, matches the parchment/leather
  style) but nothing in the code actually blits it — buttons are still
  vanilla `Button.builder(...)`. Could wire a custom 3-slice button texture
  (normal/hover/disabled) if the vanilla button look ever feels out of place
  against the reskinned panels.
- Re-check aspect ratio (`MIN_GUI_WIDTH`/`MIN_GUI_HEIGHT` in
  `ClipboardScreen.java`) after living with the landscape layout for a bit —
  440x230 was a first guess, not a measured choice.

## Monitoring & correctness

- **Persist or cache `FactoryPlan.graph`** — `FactoryPlan.CODEC`/`STREAM_CODEC` drop the graph
  (`ProductionGoal.java`, known-null after network crossing per the comment in
  `ScanPacketHandlers.java`), so every clipboard reopen recomputes the whole traversal
  (`ClipboardScreen.java` recompute-on-open). Either serialize the graph or cache it keyed by
  plan inputs so big-graph opens are instant.
- **Command path drops newer goal fields** — `/miforeman goal plan` and `/miforeman goal select`
  rebuild the goal via the 5-arg constructor (`ForemanCommands.java`), silently wiping
  `graphLayout`, `machineLinkHistory`, and `rejectedMachines`. Route them through the same
  `withX` mutators the GUI uses.
- **Tick-loop cost** — `ServerMonitoringManager.onServerTick` walks all levels x players x
  hands every tick even with zero clipboards in play; short-circuit when none exist.
  `energyEvents.removeIf` runs on every add (O(n)) — a deque head-trim would do.
- **STREAM_CODEC drift guard** — `ProductionGoal.STREAM_CODEC` hand-rolls what `CODEC`
  declares; a field added to one but not the other fails silently. A cheap parity game test
  (round-trip both codecs, assert equality) guards this without rewriting the codec.
- **Config cleanup** — template entries still live in `Config.java` (`LOG_DIRT_BLOCK`,
  `MAGIC_NUMBER`, `ITEM_STRINGS`). Remove them and move monitoring window / poll interval /
  default threshold next to `AUTOLINK_SCAN_RADIUS_CHUNKS`.
- **`getSubPlan()` vs `resolveStructure()` rate divergence** — `RecipeGraphTraverser.java` has
  two independent implementations of the same "resolve chosen recipe, propagate demand rate"
  algorithm: `getSubPlan()` (recursive per-unit `SubPlan`, scaled/summed via `mergeScaled()`,
  feeds `computePlan()`'s numeric `FactoryPlan`) and `resolveStructure()` + `propagateRates()`
  (two-phase structural DAG + Kahn's-algorithm rate propagation, feeds `computeRecipeGraph()`'s
  visual graph). Both use identical candidate indexing/selection logic, so they *should* agree,
  but attempting to consolidate them (deriving `computePlan()` from `computeRecipeGraph()`'s
  resolved nodes) made `testGenerateRequirementsComplex` in `ForemanGameTests.java` fail: the
  pinned `polyvinyl_chloride` raw-input rate (437500.0/s) came out as 28000.0/s from the
  graph-derived path — a ~15.6x divergence, localized (the assembler-count and uu_matter-rate
  assertions in the same test still passed). Ruled out: different chosen-recipe (selection logic
  and candidate lists are byte-identical, goal has no `recipeSelections` overrides, so both must
  pick the same default); PVC's own recipe ambiguity (it's a true raw leaf in both algorithms per
  the test's existing comment). Unverified leads: a real cycle somewhere in the ~110 ambiguous
  intermediates upstream of `quantum_upgrade` that the two cycle-guards handle differently; a
  `memo`/`structMemo` key collision since both are keyed by bare `ResourceLocation` with no
  `TargetType`, so an item/fluid id collision could cross-contaminate one algorithm but not the
  other. Consolidating the two (there's a natural `planFromGraph()` shape for it) would remove
  this whole class of future drift, but is blocked on root-causing this discrepancy first — don't
  re-attempt the consolidation without figuring out which number is actually correct.

## Feature ideas

- **Alerts** — toast/chat notification when a machine sits RED > N seconds, or actual rate
  falls below threshold x expected. Currently you have to watch the monitor screen.
- **Bottleneck view** — rank machines by utilization (actual/expected) and feed it into
  `GraphCanvas` node/edge coloring; combines with the status color-coding item above. The
  data already exists in `MachineTracker.energyEvents`.
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

## Documentation debt

- **README.md** is still MDK boilerplate — no actual description of what MI Foreman does.
- **AGENTS.md is stale** — references `TreePanel` (replaced by `GraphCanvas`), says "3
  packets" (now 6), and points at `plan.md`, which no longer exists.
