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

## Layout (GraphCanvas auto-arrange, if we ever build one)

- **Locked-region-as-meta-card** — the single most reusable idea found this
  session. A manually-arranged sub-group (their "board") is never
  re-laid-out by an auto-arrange pass; it's treated as one fixed-size card
  in the outer layout, and each wire crossing its boundary reports the
  *actual current position* of the member it touches as that meta-card's
  port height — so wires between groups still land straight even though the
  group's interior was never touched. Generalizes past factory-planners to
  any "don't destroy what the user manually organized" nested-graph editor.
- **Layered layout via exact isotonic regression, not force-directed** —
  Sugiyama-style column layering (topological/longest-path), then each
  node's position within a column is solved by weighted isotonic regression
  (PAVA) toward a barycenter "wish" position from its neighbors — an exact
  1-D constrained least-squares placement, not an iterative physics
  relaxation. Deterministic, no settling time, no jitter.
- **Phantom nodes for boundary-aware sub-layout** — when laying out one
  group's interior, add one placeholder node per external neighbor the
  group has wires to, weighted heavier than normal wires, so members with
  outside connections get pulled toward the edge those connections actually
  leave through. Discard the phantoms after solving and re-anchor the real
  result.

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
(Disposal ratio and the machine-status bottleneck view for `GraphCanvas` -- built
2026-09-04: see `ServerMonitoringManager.computeDisposalRatio`,
`FailureReason.DISPOSAL_THROTTLED`, and `GraphCanvas.updateLiveStatus`. The
resource-node rate-satisfied/deficit coloring this bullet also described was
deliberately left out -- it needs joining `RecipeGraphNode.getRequiredRate()`
against summed live rates across all producing machines, materially more
plumbing than the direct per-machine status coloring that shipped.)

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
- **Avoid hardcoded texts** - the code is full of hardcoded texts, which should be replaced with
  translatable texts.
- **Avoid hardcoded colors** - the code is full of hardcoded colors, which should be replaced with
  `ColourPalette` colors.
- **Avoid hardcoded constants** - the code is full of hardcoded constants, which should be replaced
  with `Constants`.
(Basically just use lang files.)
