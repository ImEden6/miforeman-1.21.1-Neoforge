# Map: Graph Canvas UI

Label: wayfinder:map

## Destination

Ship the Phase 2 `GraphCanvas` described in `plan.md`, wired into `ClipboardScreen`, replacing `TreePanel` in Step 1 of the wizard. An interactive node graph over the existing `RecipeGraph` data model: nodes draggable, canvas pannable/zoomable, clicking a node still opens `DetailCard` exactly as today.

## Notes

- Domain: Minecraft NeoForge 1.21.1 mod (`com.mervyn.miforeman`), client-side GUI (`AbstractWidget`/`GuiGraphics`). See `AGENTS.md` for architecture, `plan.md` for the original Phase 2 sketch.
- **Layout**: split panel retained — `GraphCanvas` takes the left column (`TreePanel`'s old spot), `DetailCard` stays in the right column. `DetailCard` is **collapsible/toggleable**; when closed, `GraphCanvas` reflows to fill the freed width.
- **Initial layout algorithm**: automatic topological layout on first render, left-to-right/top-to-bottom by `RecipeGraphNode.depth`. Manual drag overrides position for that node afterward.
- **Position persistence**: resolved by ticket 02 as `GraphLayoutState` (see Decisions so far) — a `graphLayout` field on `ProductionGoal`, synced via the existing whole-goal `GoalUpdatePayload`. Pruned to the current graph's node-id set every time `computeRecipeGraph()` runs — never accumulates stale entries.
- **Controls**: scroll wheel over canvas = zoom (centered on cursor); left-click-drag on a node = move it; left-click-drag on empty canvas = pan; click (no drag) on a node = select it, shows `DetailCard`.
- **Undo/redo**: resolved by ticket 02 — `GraphLayoutState.undoStack`/`redoStack`, capped at **20 actions** (oldest dropped past the cap), part of the same `graphLayout` field/sync path as positions.
- **Shared constraint**: the wizard (`ClipboardScreen`) needs to become genuinely full-screen — current layout is too cramped. Tracked as ticket 03 in this map; [Machine Auto-Detection](../machine-auto-detection/map.md) depends on the same fix.
- Edge visual styling (rate-based thickness, utilization color, `OptimizationOverlay`/bottleneck hints from plan.md's Phase 2 sketch) is explicitly **not** part of this destination — see Not yet specified.
- Consult `RecipeGraphTraverser.java` / `RecipeGraph.java` / `RecipeGraphNode.java` for the existing data model before designing anything that touches it.

## Decisions so far

- [Canvas zoom/pan rendering feasibility](issues/01-canvas-zoom-pan-rendering-feasibility.md) — no blockers; build on `GuiGraphics`'s existing `PoseStack` scale/translate + `enableScissor` (already used by `TreePanel`/`DetailCard`), the only net-new work is screen↔canvas coordinate conversion for hit-testing (no existing pattern to reuse). Full findings on branch `research/graph-canvas-zoom-pan`.
- [Full-screen wizard](issues/03-full-screen-wizard.md) — root cause was hardcoded `GUI_WIDTH`/`GUI_HEIGHT` constants; replaced with `guiWidth()`/`guiHeight()` computed from window size minus a 20px margin, floored at the original 380×280. `ClipboardScreen.java` now genuinely fills the window. Compiles clean; not yet visually verified in a running client.
- [Position and undo persistence schema](issues/02-position-and-undo-persistence-schema.md) — added `NodePosition`, `NodeMoveAction`, `GraphLayoutState` records; `GraphLayoutState` (positions + capped 20-action undo/redo stacks) lives as a new `graphLayout` field on `ProductionGoal`, synced via the existing whole-goal `GoalUpdatePayload` (no new network path). Pruned to the current graph's node set on every `computeRecipeGraph()` call — vanished-node entries are dropped outright, not kept as orphans. `ClipboardScreen` wired to load/prune/save it; no `GraphCanvas` widget exists yet to populate it. All 5 game tests pass, including the CODEC round-trip test.

## Not yet specified

- Edge rendering visual polish: rate-based thickness, color-coded utilization, bottleneck/optimization overlay hints. Revisit once the plain interactive graph ships.
- The `GraphCanvas` widget itself (rendering, drag handling, layout algorithm implementation, zoom/pan input) — the schema and rendering-feasibility groundwork are now done (tickets 01, 02), but building the actual widget is implementation work for once this map's frontier is clear, not itself a ticket.

## Out of scope

_(none yet)_
