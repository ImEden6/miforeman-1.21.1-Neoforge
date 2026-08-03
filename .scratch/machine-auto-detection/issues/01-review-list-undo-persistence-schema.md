Type: task
Status: resolved

## Question

Design the concrete schema for the review list's persisted, `ProductionGoal`-backed undo/redo history: a capped (20-action), serializable history of link/unlink actions (machine `BlockPos`, action type, and enough context to reverse it), added to `ProductionGoal`'s CODEC/STREAM_CODEC, synced client→server via `GoalUpdatePayload`.

Check [Graph Canvas UI](../../graph-canvas-ui/map.md) ticket 02 (position-and-undo-persistence-schema) first — it's designing a similar capped action-history structure for node moves. Decide whether these two histories should share a common generic shape (e.g. a reusable capped-history codec helper) or are simple enough to stay independent, one-off implementations.

Also decide: does undoing an "unlink" action re-add the machine to the review list as linked even if it's since moved out of scan range, or only if it's still detected?

## Answer

**Sharing decision**: kept independent from Graph Canvas UI's `GraphLayoutState` rather than a shared generic history type. This codebase's codecs are all bespoke per-record (`ProductionGoal`, `FactoryPlan`, etc. each hand-write their own `Codec`/`StreamCodec`) — a generic capped-history wrapper would still need its own codec plumbing per element type, so genericizing buys no real reuse for just two call sites. Mirrors the same cap/undo/redo *pattern* by convention, not by shared code.

Implemented as two new records in `goal/`:

- **`MachineLinkAction(BlockPos pos, boolean from, boolean to)`** — one undoable/redoable link/unlink toggle. Full CODEC/STREAM_CODEC.
- **`MachineLinkHistory(List<MachineLinkAction> undoStack, List<MachineLinkAction> redoStack)`** — capped at `MAX_HISTORY = 20`. Unlike `GraphLayoutState`, this does **not** duplicate current state: the single source of truth for which machines are linked stays `ProductionGoal.linkedMachines()`. This is purely an action log.
  - `withToggle(pos, from, to)` — pushes onto `undoStack` (capped, oldest dropped), clears `redoStack`.
  - `undo()` / `redo()` — return a nullable `UndoResult(MachineLinkHistory history, BlockPos pos, boolean linked)`; the caller applies `linked` to the actual `linkedMachines` list (add/remove `pos`). Null when there's nothing to undo/redo.

**Fog item resolved** (does undo respect current scan range): **no — unconditional.** Restoring a link (or unlink) via undo does not check whether the machine is currently in scan range or still recipe-matches. This mirrors how `linkedMachines` already behaves elsewhere in the codebase: it's a persistent set independent of live detection (`ServerMonitoringManager` monitors whatever's in the list regardless of proximity), so undo just restores that same persistent state — detection status was never a gate on it.

**Sync**: same as `GraphLayoutState` — full-object via the existing whole-goal `GoalUpdatePayload`, no new network path. `MachineLinkHistory` is a new `machineLinkHistory` field on `ProductionGoal` (`optionalFieldOf("machine_link_history", MachineLinkHistory.EMPTY)`).

**Wiring**: `ClipboardScreen` gained a `machineLinkHistory` field, loaded from `goal.machineLinkHistory()` in the constructor and threaded into `finalGoal` in `save()`. No auto-detect review list widget exists yet to actually call `withToggle`/`undo`/`redo` — those are for the future review-list ticket/build to wire up. This ticket only establishes and proves the persisted schema.

**Verified**: `./gradlew compileJava` succeeds; `./gradlew runGameTestServer` — all 5 existing game tests pass, confirming the schema addition doesn't break existing persistence.

Files added: `goal/MachineLinkAction.java`, `goal/MachineLinkHistory.java`. Files changed: `goal/ProductionGoal.java`, `client/gui/ClipboardScreen.java`.
