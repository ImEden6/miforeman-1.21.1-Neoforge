Type: task
Status: resolved

## Question

Decide the default chunk-radius for the auto-detect scan, and whether it's:

- fixed and only tunable via `Config.java` (server-op-style config, matches how other mod defaults are handled per `AGENTS.md`), or
- exposed as a control in the review panel itself (per-scan, player-facing), or
- both (a `Config.java` default with a GUI override).

This was flagged as an open step in `HANDOFF.md` ("Radius config — expose default radius in Config.java") and never resolved.

## Answer

**Both**, as anticipated but not previously committed to: a `Config.java` default, overridable per-scan in the GUI.

Added `Config.AUTOLINK_SCAN_RADIUS_CHUNKS` — a COMMON `ModConfigSpec.IntValue`, default **4 chunks**, clamped to **1-16**. Reasoning for the default: 4 chunks (64 blocks) centered on the player comfortably covers a single factory build without scanning so wide it risks picking up unrelated machines from other builds or incurring a heavy per-scan cost — matches the "bounded, fast" quality `HANDOFF.md` already called for in the chunk-radius-scoping decision. The 1-16 range guards against a config value that's either useless (0) or scans an unreasonably large area.

The per-scan GUI override (a numeric input in the review panel next to the scan button, defaulting to the config value but adjustable for that scan only, not persisted back to config) is **not implemented here** — no review-list widget exists yet to host it. This ticket only establishes the config default; the override control is for the future review-list build to wire up.

Verified: `./gradlew compileJava` succeeds.

Files changed: `Config.java`.
