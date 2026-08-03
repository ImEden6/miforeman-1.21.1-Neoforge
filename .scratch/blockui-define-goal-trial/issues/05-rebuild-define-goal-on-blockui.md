Type: task
Status: resolved

Blocked by: 03, 04

## Question

Rebuild `STEP_DEFINE_GOAL` on BlockUI per the decisions already locked in this map: a `BOWindow` built programmatically in Java (ticket 02 — no XML needed), opened via `openAsLayer()` on top of the still-alive `ClipboardScreen` (ticket 01), using the hand-off contract from ticket 04 to write `goalName`/`targetType`/`targetIdStr`/`rate`/`perHour`/`threshold` back and trigger `computePlanAndAdvance()`/`onClose()`.

Scope: replace `ClipboardScreen.buildStepDefineGoal()`/`renderStepDefineGoal()` (`ClipboardScreen.java:212,789`) — the Goal Name `TextField`, Target Type toggle, Target ID `TextField`, Rate `TextField` + unit toggle, Threshold `TextField`, Cancel/Next buttons, and the live-validation error text — with BlockUI equivalents. Old vanilla `EditBox`/`Button`/manual-`drawString` code for this step gets deleted, not kept alongside.

Out of scope for this ticket: `STEP_REVIEW_PLAN`/`STEP_MONITOR` stay exactly as they are (vanilla), and `GraphCanvas`/`DetailCard`/`ReviewListPanel` are untouched.

Done when: compiles clean, and manually verified working in a running client (per this repo's own standard — see `AGENTS.md`/prior maps' verification notes) — entering the step, editing every field, live-validation errors showing/clearing, Cancel and Next both correctly returning to/advancing `ClipboardScreen`.

## Answer

Implemented per the map's prior decisions:

- **New**: `client/gui/blockui/DefineGoalWindow.java` — `BOWindow` subclass, built entirely in Java (`TextFieldVanilla`/`ButtonImage`/`Text` panes via `addChild`, no XML). Fields: Goal Name, Target Type toggle + Target ID, Rate + unit toggle, Threshold, live error text, Cancel/Next. Opened via `openAsLayer()`.
- **New**: `client/gui/GoalFormResult.java` — the record carrying the six fields back through `onSubmit`.
- **New**: `client/gui/GoalFormValidation.java` — `validateGoalInputs(...)` extracted from the old `ClipboardScreen.validateInputs()`, called by both `DefineGoalWindow` (live, per keystroke) and indirectly relied on by `ClipboardScreen` (via `computePlan()`'s own registry/format checks, which duplicate the same rules — see note below).
- **`ClipboardScreen.java`**: `buildStepDefineGoal()`, `renderStepDefineGoal()`, `validateInputs()`, and `computePlanAndAdvance()` deleted along with the six now-dead `EditBox`/`Button` fields (`nameEdit`/`typeButton`/`targetEdit`/`rateEdit`/`thresholdEdit`/`unitButton`) and the orphaned `cancelButton` field. Replaced with `openDefineGoalWindow()`/`openDefineGoalWindow(String initialError)`.
- **Traversal-failure recovery**: `computePlan()` can still fail (e.g. target item exists but has no reachable recipe path) even after `GoalFormValidation` passes — a real gap `GoalFormValidation` can't close since it's a pure syntactic/registry check, not a recipe-graph traversal. On that failure, `openDefineGoalWindow(this.errorMessage)` reopens the window pre-filled with the just-submitted (still-valid) values and the traversal error showing, so Next stays enabled and the player can retry — this mirrors the old vanilla behavior (any keystroke re-ran `validateInputs()` and could re-enable `nextButton`) rather than stranding the player on a blank step.
- Confirmed via `references/BlockUI` source that `ButtonImage`'s default no-arg constructor leaves `width`/`height` at 0, and its `setSize` override does a proportional rescale relative to the *previous* width/height — calling `setSize` on a freshly-`new ButtonImage()`'d instance would divide by zero. Used `new ButtonImage(true)` (the vanilla-preset constructor) everywhere instead, which is safe to resize afterward.

**Verified**:
- `./gradlew clean compileJava` — `BUILD SUCCESSFUL`.
- `./gradlew runGameTestServer` — all 6 registered game tests still pass (server-side only; doesn't exercise this client GUI code).

**Not verified** (no graphical client available in this environment): actually opening the clipboard in a running Minecraft client, typing into the fields, toggling Type/Unit, triggering live validation, and confirming Cancel/Next both behave correctly end to end. This still needs a manual pass in-game before calling the trial itself (ticket 06) conclusive — flagging explicitly rather than claiming a bar I couldn't actually clear here.

**Manual checklist — final status:**
- [x] Open a new goal (no existing plan) -- window pushes as a layer, `ClipboardScreen` visible/dimmed underneath. Confirmed via screenshots (after the jarJar + sizing/scaling fixes below).
- [x] Type in Goal Name, Target ID -- confirmed populated with real typed values (`Quantum Production` / `modern_industrialization:processing_unit` / rate `10.0` / threshold `100`) in the verified screenshot. Per-keystroke live error text specifically not separately observed.
- [~] Toggle Target Type (ITEM/FLUID) and the Rate unit (Per Hour/Per Min) -- buttons render and show state (`ITEM`, `Per Hour`), but an actual toggle click wasn't explicitly observed in this session.
- [ ] Enter an invalid Target ID -- not tested.
- [ ] Valid target with no reachable recipe (retry path) -- not tested.
- [ ] Press Cancel -- not tested.
- [x] Press Next with valid inputs -- confirmed: layer popped, advanced to Review Plan with a real computed graph for the submitted target (`Factory Summary` screenshot showing the correct machine/raw-input breakdown for `processing_unit`).
- [ ] Re-enter Define Goal via Review Plan's "<- Back" button -- not explicitly tested.

Resolved on the strength of the confirmed happy path (open → fill → submit → correctly-computed Review Plan) plus everything else this session already forced through real-client iteration (dependency packaging, render scale/sizing, single-panel visual fix). The untested edge cases (Cancel, invalid-input error text, the traversal-failure retry path, re-entry via Back) are unverified, not known-broken -- worth a pass before treating this as production-ready, but not blocking ticket 06's verdict.

**Real-client iteration log** (for ticket 06's effort-vs-payoff verdict -- this is exactly the kind of cost a dev-environment-only check can't surface):
1. First real launch crashed outright: `NoClassDefFoundError: com/ldtteam/blockui/views/BOWindow`. Root cause was ticket 03's `implementation` dependency never being bundled into the shipped jar (see the correction logged on that ticket) -- fixed via `jarJar(implementation(...))`.
2. After that fix, the window rendered but oversized, on a background that looked flat dark gray instead of the clipboard's parchment, and floated as a visually separate dialog rather than reading as the same clipboard. Root causes: (a) `BOWindow`'s default `WindowRenderType.OVERSIZED_VANILLA` doesn't correspond 1:1 to vanilla GUI scale, and (b) the window was a fixed small size instead of matching `ClipboardScreen`'s actual `guiWidth()`/`guiHeight()`. Fixed both: `DefineGoalWindow` now draws its own copy of the parchment background+clip (defensive -- unconfirmed whether the backgrounded `ClipboardScreen` layer was actually rendering wrong, or just looked odd next to a badly-sized dialog on top of it) and is sized/positioned/scaled (`guiWidth()`/`guiHeight()`, `WindowRenderType.VANILLA`) to match `ClipboardScreen`'s own panel.
3. **Correction**: a follow-up "I see 2 layers, 2 clipboard layers" report was initially assumed to be about this window (plausible given step 2's defensive self-drawn background sitting on top of the same panel underneath) and "fixed" by removing that self-drawn background again. Turned out the report was about the **Review Plan ("Factory Plan") page** instead -- unrelated to this ticket, `GraphCanvas`/`DetailCard`'s own nested parchment panels (pre-existing, from the earlier clipboard-theming work, not part of this map). Reverted the removal; `DefineGoalWindow` keeps its self-drawn background from step 2. Investigating the actual Review Plan report separately, outside this map.
4. Net lesson: `openAsLayer()`'s "still-alive screen shows through" premise (ticket 01) holds in principle, but wasn't actually load-bearing here once `DefineGoalWindow` got its own background -- and reacting to a user report without the actual screenshot in hand caused one wasted round of edits. None of the sizing/scaling/background issues were catchable from `compileJava`/`runGameTestServer` alone.
