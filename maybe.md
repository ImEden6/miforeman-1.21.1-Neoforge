# Maybe: nice-to-haves

Not scheduled, just captured so they don't get lost.

Wiped and refilled 2026-09-04 after scouting `references/gtnh-factory-flow-main`
(MIT licensed — code patterns are fair game to lift directly, not just draw
inspiration from; only GTNH's own game data/textures are excluded from that
grant). Everything below is a pattern from that codebase that could apply to
`GraphCanvas`/`DetailCard`/`MachineTracker`/the recipe-graph solver.

## Edge routing (GraphCanvas)

**The synchronous router is built** (2026-09-21) as `goal/EdgeRouter.java`, wired into
`GraphCanvas` and covered by nine gametests. Grid A* with hard one-cell obstacle
clearance, `COST_SHARED=1.3` vs `COST_EMPTY=1` lane sharing, a turn penalty, apron points
for the port hop, and an obstacle-aware packing pass are all in. Every real graph,
quantum_upgrade included, routes on the render thread.

- **The 20px grid in the original note is wrong for this canvas.** Columns are 140 apart
  and rows 40, so corridors are 44px wide and vertical gaps between stacked cards are
  14px (8px inside an auto-arranged group). At 20px with one-cell clearance every gap
  seals and every wire falls back. `EDGE_GRID_SIZE` is 6.
- **Lane packing must be obstacle-aware.** It runs after the search and knows nothing of
  what the search routed around, so an unchecked offset slides a wire off a legal path
  straight through a card: 320 clipped wires out of 440 on a synthetic grid before
  `segmentsClear` gated the shifts. With the gate, clipping is zero at every size tested.
- **Ports must be chosen from the wire's own geometry, not a flow assumption.** The graph
  lays out target-first at x=0 with inputs extending right, so most edges run
  *right-to-left*: 128 of analog_circuit's 154 edges do. Leaving every card by its right
  face and arriving at every left face therefore sent the majority of wires out the back
  of both cards and the long way around. The old elbows absorbed this as a harmless Z; A*
  paid for it in full. Fixing it took quantum_upgrade from 12.2s and 187 fallbacks to
  402ms and 157, and was worth more than every other optimisation combined.
- **Beware calibrating a budget against the worst case only.** `MAX_TOTAL_EXPANSIONS` was
  first set to 120k from quantum_upgrade measurements taken while the port bug was still
  present. That sat below the cost of even the smallest real graph, so every graph but a
  trivial one fell back wholesale and the router did nothing in game while all its tests
  passed. Worth remembering that a green suite said nothing about this: the tests asserted
  fallbacks were *allowed*, not that routing ever actually happened. The real-graph tests
  now assert a majority of wires genuinely route.
- **Sizing.** At ~2,500 expansions/ms: steel_plate (86 cards, 111 wires) 61ms,
  analog_circuit (120/154) 33ms, electronic_circuit (197/263) 62ms, digital_circuit
  (272/379) 100ms, advanced_motor (312/444) 182ms, quantum_upgrade (588/864) 402ms with
  711 of 864 wires routed. Paid once per layout change, never per frame.

- **Async solve with stale-while-revalidate rendering** — still open, but no longer
  urgent: it was only ever "load-bearing" while the port bug made routing 30x dearer than
  it needed to be. What it would buy now is removing the ~400ms hitch on the very largest
  graphs when a drag commits, which is the one place the cost is still felt. The
  synchronous version already approximates the pattern during a drag by keeping cached
  routes for every wire that didn't move and elbowing only the dragged node's own wires.
  Java has no Web Worker, so the open questions are what happens when layout changes
  mid-solve, whether a sequence number suffices or routes must be invalidated on mutation,
  and how a stale route referencing a since-removed node is kept from being drawn. A
  cheaper alternative worth weighing first: re-route only the wires whose endpoints moved.

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
