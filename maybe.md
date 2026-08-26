# Maybe: nice-to-haves

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
- **Minimap or zoom-to-fit / center-on-selected** — no way to orient
  yourself or reset after getting lost in a large graph.
- **Collision feedback for manual drags** — dropping a dragged node on top of
  another currently gives no feedback; a "snap away" or warning outline on
  overlap would help.

## Monitoring & correctness
 
- [x] **Consolidate `computePlan()` into `planFromGraph()`** — Derived `computePlan()` directly
  from `computeRecipeGraph()`'s resolved nodes (`planFromGraph()`), eliminating `SubPlan`,
  `MachineStats`, `getSubPlan`, and `mergeScaled` into a unified single source of truth.

## Feature ideas

- **Alerts** — toast/chat notification when a machine sits RED > N seconds, or actual rate
  falls below threshold x expected. Currently you have to watch the monitor screen.
- **Bottleneck view** — rank machines by utilization (actual/expected) and feed it into
  `GraphCanvas` node/edge coloring, keying fill color to state (ambiguous, rate
  satisfied/deficit, raw vs intermediate vs target). The data already exists in
  `MachineTracker.energyEvents`.
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
- **Show a recipe's full input/output list in DetailCard** — a MACHINE node's undemanded
  byproducts (or any input/output the chosen recipe has that the graph doesn't otherwise track)
  are invisible right now; the graph only shows edges that were actually built for demanded
  resources. Surfacing the raw recipe (`RecipeGraphNode.getRecipe()` already carries the
  `MachineRecipe`) would at least let players see what else a machine produces, even if the tool
  doesn't route/plan around it.

## Code health
 
- [x] **`RecipeGraphTraverser.java` complexity & unification** — Resolved duplication and complexity
  by consolidating `computePlan()` onto `computeRecipeGraph()` via `planFromGraph()`, fixing cycle
  traversal memoization with white/gray/black DFS (`collectDag`), and removing the duplicate `getSubPlan`
  traversal pipeline. Validated across all 18 GameTests.
