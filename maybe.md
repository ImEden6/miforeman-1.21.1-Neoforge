# Maybe: nice-to-haves

Not scheduled, just captured so they don't get lost.

Wiped and refilled 2026-09-04 after scouting `references/gtnh-factory-flow-main`
(MIT licensed — code patterns are fair game to lift directly, not just draw
inspiration from; only GTNH's own game data/textures are excluded from that
grant). Everything below is a pattern from that codebase that could apply to
`GraphCanvas`/`DetailCard`/`MachineTracker`/the recipe-graph solver.

## Edge routing (GraphCanvas)

- **Grid-based A\* wire router** — instead of straight elbow connectors,
  route every edge on a shared 20px grid with one-cell obstacle clearance.
  Cost function makes a shared "lane" cheaper than detouring around it but
  pricier than an empty line (`COST_SHARED=1.3` vs `COST_EMPTY=1`), so
  parallel wires travel together on long runs and split apart near
  destinations — no manual per-edge-index offset needed. A separate packing
  pass (`packIntoLane`) then slots multiple wires sharing one line side by
  side with a fixed gap. Clearance is enforced by inflating obstacle rects by
  one grid cell in the search graph (hard block, not a cost penalty); the
  final port-to-card hop is the one exception, carved out geometrically via
  an "apron point" sitting exactly on the legal boundary rather than by
  relaxing the margin.
- **Async solve with stale-while-revalidate rendering** — past a wire-count
  threshold, route solving moves to a Web Worker; the renderer keeps drawing
  the last installed routes until a newer sequence-numbered result lands, so
  dragging never freezes. A boxed-in wire that truly can't route still draws
  a fallback straight/L path rather than vanishing or blocking the rest of
  the solve.

## Solver / MachineTracker

- **Staged LP instead of iterative descent for "what's actually running"**
  — their throughput solver used to be pure iterative equilibrium descent;
  now the real numbers come from a direct linear program solved as a
  lexicographic stage chain (maximize total activity → fairness → recycle
  before importing → ship before banking → minimize total flow, each stage
  locked as a constraint before the next runs). The old iterative engine
  was kept, but demoted to pure diagnosis. If `MachineTracker`/the plan
  solver ever needs to reason about shared-supply fairness or byproduct
  routing precisely, this staged-LP structure is a cleaner model than
  hand-rolled iteration.
- **`cyclicResourceIds` out-param threading** — `RecipeGraphTraverser.propagateRates` accepts
  a `Set<ResourceLocation> cyclicResourceIds` purely to forward it unchanged into `collectDag`
  (the actual writer, at its back-edge branch), which already takes 7 parameters. A code review
  flagged this as a mild Collection/Primitive Obsession smell -- bundling the traversal's
  out-params (`structNodes`/`nodes`/`edges`/`cyclicResourceIds`, plus `collectDag`'s own
  `forwardEdges`/`inDegree`/`onStack`/`done`) into one small mutable accumulator object passed
  once would let a future "collect X while traversing" feature add an accumulator without
  touching every signature in the chain again. Deliberately not done as part of the disposal-
  ratio/bottleneck-view review-fix batch (2026-09-04) -- it touches a recursive DFS with
  dedicated tests (`testRecipeGraphCyclicResourceIdsCaptured`, `testGraphCacheHitAndInvalidation`)
  and no bug is attached to it, just a real-but-optional cleanup.

## Data pipeline (if we ever export/share recipe datasets)

- **In-game off-screen render for icons, not a screenshot tool** — their
  icon exporter runs *inside* the actual Minecraft client via a companion
  mod, using a real LWJGL framebuffer and the game's own `RenderItem` call,
  batched through a queued headless GUI screen so many stacks render in one
  client run. Produces pixel-accurate item art without maintaining a
  separate renderer.
- **Shard + index instead of shipping one big JSON blob** — normalized
  recipe data gets gzip-sharded into small fixed-size chunks plus a
  lightweight search/lookup index shipped separately, so a client fetches
  only the shard it needs.

## Feature ideas (carried over)

- **Scan upgrades** — per-scan radius override in the Review panel (planned in
  `.scratch/machine-auto-detection/map.md`, never built), optional gentle force-load of
  scanned chunks, particle ping on newly found candidates.

## Deferred from the 2026-09-05 three-skill code review

Findings from running `code-review`, `code-review-skill`, and `mattpocock-skills:code-review`
against everything since the 1.0.1 changelog. The correctness/efficiency findings were fixed
directly; these two are lower-value or need a design call, not a blind patch.

- **GraphCanvas live-status colouring is keyed by `lastKnownRecipeId`**, which can miss a
  just-re-linked machine's node until the physical machine actually runs its newly-assigned
  recipe. Arguably correct (no live data exists yet for the new assignment) rather than a bug --
  worth a real design decision about what to show for "no data under the current assignment" before
  touching it, not a patch guessed at blind.
- **`GraphCamera.dragActive`** is a boolean that duplicates state already implied by the
  begin/end-drag call sequence; could likely be inferred instead of tracked separately.
