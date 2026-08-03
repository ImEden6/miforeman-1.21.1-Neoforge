Type: task
Status: resolved

## Question

Design the concrete schema for `GraphCanvas`'s two persisted, `ProductionGoal`-backed structures:

1. **`nodePositions`**: `Map<ResourceLocation, Vec2>` — CODEC/STREAM_CODEC shape, how it's pruned against the current `RecipeGraph` node set on every `computeRecipeGraph()` call, and how updates sync client→server via `GoalUpdatePayload` (full map each time, or delta?).
2. **Undo/redo action history**: a capped (20-action), serializable history of node-move actions (node id, from-position, to-position or similar) added to the same CODEC/STREAM_CODEC. Needs: exact action representation, eviction policy once the cap is hit, and how undo/redo mutates `nodePositions` and re-syncs.

Also resolve the fog item this surfaces: what happens when an undo would restore a position for a node that no longer exists in the current graph (e.g. after a replan pruned it)? Decide whether undo is a no-op for that entry, restores it as an orphaned position until the next prune, or something else.

Not blocked by ticket 01, but the rendering approach it finds may inform whether positions need any canvas-space vs world-space distinction — check its findings if already resolved.

## Answer

Implemented as three new records in `goal/`:

- **`NodePosition(int x, int y)`** — a node's position in GraphCanvas space (not screen space), stable across window resizes, pan, and zoom. Full CODEC/STREAM_CODEC.
- **`NodeMoveAction(ResourceLocation nodeId, NodePosition from, NodePosition to)`** — one undoable/redoable drag. Full CODEC/STREAM_CODEC.
- **`GraphLayoutState(Map<ResourceLocation, NodePosition> nodePositions, List<NodeMoveAction> undoStack, List<NodeMoveAction> redoStack)`** — the whole persisted unit, with:
  - `withMove(nodeId, from, to)` — updates the position, pushes onto `undoStack` (capped at `MAX_HISTORY = 20`, oldest dropped), clears `redoStack` (standard undo/redo semantics: a new action invalidates redo history).
  - `undo()` / `redo()` — pop from one stack, apply the reverse/forward position, push onto the other. No-ops when the relevant stack is empty.
  - `prunedTo(RecipeGraph graph)` — drops any position or undo/redo entry referencing a node id no longer in the graph's current node set.
  - Full CODEC/STREAM_CODEC (`node_positions` map field + `undo_stack`/`redo_stack` list fields).
  - `redoStack` needs no separate cap: entries only enter it by being popped off `undoStack` (already capped), so it can never exceed `MAX_HISTORY` either.

**Sync**: full-object, not delta. `GraphLayoutState` is a new field on `ProductionGoal` (`graphLayout`, `optionalFieldOf("graph_layout", GraphLayoutState.EMPTY)` in CODEC, always-present in STREAM_CODEC), so it rides the same whole-goal sync `GoalUpdatePayload` already uses for every other field — no new network path needed.

**Fog item resolved** (undo restoring a position for a vanished node): entries are **dropped outright at prune time**, not kept as resurrectable orphans. Once a node is gone, there's nothing left to "undo" a move onto, so keeping a dangling entry would just be dead weight the UI would need to special-case. `ClipboardScreen.computePlan()` now calls `this.graphLayout = this.graphLayout.prunedTo(graph)` immediately after every `computeRecipeGraph()` call, matching the Notes' stated pruning point.

**Wiring**: `ClipboardScreen` gained a `graphLayout` field, loaded from `goal.graphLayout()` in the constructor, pruned after every replan, and threaded into `finalGoal` in `save()`. No `GraphCanvas` widget exists yet to actually populate `withMove`/`undo`/`redo` — those calls are for the future canvas ticket to wire up. This ticket only establishes and proves the persisted schema.

**Verified**: `./gradlew compileJava` succeeds; `./gradlew runGameTestServer` — all 5 existing game tests pass, including `testProductionGoalComponent` (the CODEC round-trip test), confirming the schema change doesn't break existing persistence.

Files added: `goal/NodePosition.java`, `goal/NodeMoveAction.java`, `goal/GraphLayoutState.java`. Files changed: `goal/ProductionGoal.java`, `client/gui/ClipboardScreen.java`.
