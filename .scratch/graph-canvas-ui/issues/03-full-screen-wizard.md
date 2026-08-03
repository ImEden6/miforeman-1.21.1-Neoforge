Type: task
Status: resolved

## Question

`ClipboardScreen` is nominally a "full-screen 3-step wizard" (per `AGENTS.md`) but the user reports the actual usable area feels too small/cramped. Investigate the current sizing logic in `ClipboardScreen.java` (init/layout code, hardcoded widths/margins) and determine what's constraining it, then decide the fix so the wizard genuinely fills the game window.

This is a shared prerequisite: [Machine Auto-Detection](../machine-auto-detection/map.md)'s review panel and this map's `GraphCanvas` both need the reclaimed space. Resolve here first; the other map's Notes point back at this ticket rather than duplicating the investigation.

## Answer

Root cause: `GUI_WIDTH = 380` / `GUI_HEIGHT = 280` were hardcoded constants in `ClipboardScreen.java`, centering a small fixed-size panel regardless of window size — despite `AGENTS.md` describing it as a "full-screen 3-step wizard," it never actually was.

Fix applied: replaced the fixed constants with `guiWidth()`/`guiHeight()` methods that compute the panel size as `this.width`/`this.height` minus a 20px `SCREEN_MARGIN` on each side, floored at the original `MIN_GUI_WIDTH` (380) / `MIN_GUI_HEIGHT` (280) so very small windows still get a usable minimum. All ~20 call sites across `rebuildStep`, `buildStepDefineGoal`, `buildStepReviewPlan`, `buildStepMonitor`, `drawStepIndicator`, and `render` were updated to call these methods instead of the removed constants.

No other layout changes were needed: the 9-slice background renderer (`blitNineSlice`) already stretches to arbitrary dimensions, and all content positioning (`contentX`/`contentW`/`contentH`, `TreePanel`/`DetailCard` widths) was already computed as offsets/percentages of the panel size, so it now scales automatically with the window.

Verified: `./gradlew compileJava` succeeds. Not manually verified in a running client (no client launch was performed as part of this ticket) — visual confirmation of the resize behavior in-game is recommended before this is considered fully done.

Files changed: `src/main/java/com/mervyn/miforeman/client/gui/ClipboardScreen.java`.
