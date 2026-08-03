Type: task
Status: resolved

## Question

[Rescan rejected-candidate behavior](02-rescan-rejected-candidate-behavior.md) decided that rejecting a detected candidate sticks across future scans. That requires tracking "rejected" status somewhere distinct from `linkedMachines` (which only tracks linked machines) and `MachineLinkHistory` (which is an undo/redo action log, not current state).

Design and implement the persisted storage for this: most likely a new `Set<BlockPos> rejectedMachines` (or similar) field on `ProductionGoal`, following the same conventions established in [Position and undo persistence schema](../../graph-canvas-ui/issues/02-position-and-undo-persistence-schema.md) and [Review list undo/redo persistence schema](01-review-list-undo-persistence-schema.md) — CODEC/STREAM_CODEC addition, synced via the existing whole-goal `GoalUpdatePayload`, no new network path.

Also decide: does un-rejecting via the "show rejected" filter remove the entry from this set outright, or just temporarily surface it (with removal only happening on actual re-link)? And does a rescan need to prune this set against anything (e.g. if a rejected machine is later destroyed/replaced), or does it only ever get removed by explicit player action (un-reject or manual right-click re-link)?

## Answer

Added `List<BlockPos> rejectedMachines` as a new field on `ProductionGoal` (12th component), following the exact convention `linkedMachines` already established: `List<BlockPos>` used as a de-facto set (no `Set` codec pattern introduced), `optionalFieldOf("rejected_machines", List.of())` in CODEC, plain list codec in STREAM_CODEC. Synced via the existing whole-goal `GoalUpdatePayload` — no new network path.

Added two mutator methods on `ProductionGoal`:
- `withRejectedMachine(BlockPos)` — adds to the set (no-op if already present).
- `withoutRejectedMachine(BlockPos)` — removes from the set (no-op if absent) — this is the "un-reject" action.

**Un-rejecting removes outright**, immediately, as its own standalone action — decoupled from re-linking. A player can un-reject a machine (making it a visible candidate again) without that action also linking it; linking is a separate step via the toggle, same as any other candidate.

**No automatic pruning.** The rejected set only changes via explicit player action (un-reject via the "show rejected" filter, or a manual right-click re-link implicitly clearing it — that clearing behavior is for the future review-list/link-handling code to implement). This mirrors `linkedMachines`, which also has no automatic pruning tied to rescans or replans — a destroyed/replaced machine's stale `BlockPos` simply won't match anything on the next scan, it doesn't need active cleanup.

No review-list widget exists yet to call these methods — this ticket only establishes the persisted schema and its mutation semantics.

**Verified**: `./gradlew compileJava` succeeds; `./gradlew runGameTestServer` — all 5 existing game tests pass.

Files changed: `goal/ProductionGoal.java`, `client/gui/ClipboardScreen.java`.
