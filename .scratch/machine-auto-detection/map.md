# Map: Machine Auto-Detection

Label: wayfinder:map

## Destination

A scan-and-review flow where nearby machines are detected (reusing the existing **Recipe Match** strategy from `HANDOFF.md`: chunk-radius scan, match each machine's active recipe against the plan's recipe index), presented as a **unified toggle list** of currently-linked and newly-detected candidate machines that the player can individually add/remove from the goal's linked-machines set — plus **in-world highlight visualization** (color-coded outline per machine, linked vs candidate) controlled by an explicit toggle in the wizard.

## Notes

- Domain: Minecraft NeoForge 1.21.1 mod (`com.mervyn.miforeman`). See `AGENTS.md`, and `HANDOFF.md` for the Recipe Match design already converged on in a prior session.
- **Matching strategy stays as-is**: chunk-radius scan → `MachineBlockEntity` → `CrafterComponent.activeRecipe` (via reflection, already flagged fragile across MI versions in `AGENTS.md`) → check against recipe index built from `plan.intermediateFlows()` + target. Not re-litigated by this map.
- **Trigger**: a button in Step 2 (Monitor) of the wizard opens the review flow. Not a chat command for the primary path (a `/miforeman goal autolink` command may still exist later as a shortcut — fog, not decided).
- **Review list**: one unified list of rows — both already-linked machines and newly-detected-but-unlinked candidates. Toggling a row on links it, off unlinks it. This is the single "add and remove machines easily" surface.
- **In-world highlight**: client-side render of a color-coded outline/highlight on each listed machine's block position (linked vs candidate distinguished by color). Visibility is controlled by an **explicit toggle button in the wizard** — not implicitly tied to the panel just being open.
- **Undo/redo**: resolved by ticket 01 (see Decisions so far) — `MachineLinkHistory.undoStack`/`redoStack`, capped at **20 actions**, part of `ProductionGoal.machineLinkHistory`, synced via the existing whole-goal `GoalUpdatePayload`. Deliberately independent from [Graph Canvas UI](../graph-canvas-ui/map.md)'s `GraphLayoutState` (bespoke codecs, no shared generic history type), though the pattern mirrors it by convention.
- **Shared constraint**: depends on the wizard becoming genuinely full-screen — tracked as ticket 03 in [Graph Canvas UI](../graph-canvas-ui/map.md), not duplicated here.

## Decisions so far

- [Review list undo/redo persistence schema](issues/01-review-list-undo-persistence-schema.md) — added `MachineLinkAction`/`MachineLinkHistory` records (bespoke, not shared with Graph Canvas UI's `GraphLayoutState`); lives as `machineLinkHistory` on `ProductionGoal`, synced via the existing whole-goal `GoalUpdatePayload`. Undo/redo restores link state unconditionally, regardless of current scan range or recipe match — mirrors how `linkedMachines` already works elsewhere. `ClipboardScreen` wired to load/save it; no review-list widget exists yet to populate it. All 5 game tests pass.
- [Rescan rejected-candidate behavior](issues/02-rescan-rejected-candidate-behavior.md) — rejection sticks across future scans; rejecting a candidate requires a confirmation (unlinking an already-linked machine doesn't, since it's already undoable); un-rejecting happens via a "show rejected" filter in the review panel; manual right-click linking is unaffected by and can override rejection status. Surfaced a new requirement (persisted rejected-set) — see [Rejected candidates persistence](issues/04-rejected-candidates-persistence.md).
- [Scan radius default and config](issues/03-scan-radius-default-and-config.md) — added `Config.AUTOLINK_SCAN_RADIUS_CHUNKS` (default 4 chunks, range 1-16); a per-scan GUI override is planned but not implemented (no review-list widget exists yet).
- [Rejected candidates persistence](issues/04-rejected-candidates-persistence.md) — added `rejectedMachines` (`List<BlockPos>`, set-like convention matching `linkedMachines`) on `ProductionGoal`, with `withRejectedMachine`/`withoutRejectedMachine` mutators. Un-rejecting removes outright, independent of re-linking; no automatic pruning, same as `linkedMachines`. All 5 game tests pass.

## Not yet specified

- Exact visual design of the in-world highlight (specific colors, outline vs glow vs particle, render performance with hundreds of machines in range), and how the review list's confirmation prompt (ticket 02) is presented visually.
- Whether a `/miforeman goal autolink` command variant gets built alongside the button.
- The review-list widget itself (scan trigger, unified toggle rows, "show rejected" filter, per-scan radius override, in-world highlight rendering) — all four tickets' groundwork is done, but building the actual widget is implementation work for once this map's frontier is clear, not itself a ticket.

## Out of scope

_(none yet)_
