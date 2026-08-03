Type: task
Status: resolved

Blocked by: 05

## Question

Once `STEP_DEFINE_GOAL` is rebuilt and verified on BlockUI (ticket 05), write a short verdict: effort spent (roughly how much code/time relative to the original vanilla implementation), what BlockUI bought visually/behaviorally (9-slice handling, tooltips, anything else that came free), what it cost (new dependency, `openAsLayer`/hand-off plumbing, anything that was awkward or fought the framework), and a recommendation on whether `STEP_MONITOR` (deferred — see map's Not yet specified) should get the same treatment.

## Answer

**Effort spent**: 4 decision tickets (architecture, pane construction, dependency, hand-off contract) resolved cheaply up front since the real BlockUI source was cloned locally rather than researched from memory. Implementation itself (`DefineGoalWindow`, `GoalFormResult`, `GoalFormValidation`, deletion of the old vanilla step) was roughly comparable in code volume to what it replaced. The real cost was **three rounds of real-client-only bugs**, none catchable from `compileJava`/`runGameTestServer`:
1. `NoClassDefFoundError` — plain `implementation` doesn't bundle into the shipped jar; needed `jarJar(implementation(...))`.
2. Window rendered oversized on a background that looked wrong and floated as a separate dialog — `BOWindow`'s default `WindowRenderType.OVERSIZED_VANILLA` doesn't match vanilla GUI scale, and the window wasn't sized to `ClipboardScreen`'s actual panel.
3. One wasted iteration misdiagnosing an unrelated pre-existing bug (`GraphCanvas`/`DetailCard`'s own nested panel border, on the *Review Plan* page) as being about this window, because the report came without the actual screenshot.

**What BlockUI bought**: `TextFieldVanilla`/`ButtonImage`/`Text` gave roughly the same capability as vanilla `EditBox`/`Button` at roughly the same code volume — no clear ergonomic win for a form this simple. `openAsLayer()` (keep the underlying screen alive, pop back to it) is genuinely nice, but it's arguably orthogonal to BlockUI itself — vanilla's own `pushGuiLayer`/`popGuiLayer` would give the same "modal step over a live screen" pattern with a plain `Screen` subclass, no new dependency required. BlockUI's other selling points (XML layouts, tooltip builder, `ScrollingList`/`CheckBox`/etc.) were never exercised by a form this simple, so they're unproven for this codebase, not disproven.

**What it cost**: a new ~468KB jarJar-embedded dependency, with a real (if currently inapplicable) warning about conflicts if another installed mod also embeds BlockUI from elsewhere. More importantly: two of the three real bugs were BlockUI-specific footguns (its dependency-bundling model, its window-scaling model) rather than general Minecraft-modding gotchas — meaning this integration risk is inherent to *choosing BlockUI specifically*, not a one-time cost that's now paid off for future steps.

**Recommendation: do not migrate `STEP_MONITOR` to BlockUI.** This trial's payoff was "roughly the same as vanilla, plus new integration risk that had to be discovered live three separate times." `STEP_MONITOR` is meaningfully harder than Define Goal was — `ReviewListPanel`'s link/unlink/reject/unreject list interactions, scrolling, and the in-world highlight toggle are real complexity this trial never tested BlockUI against. If the underlying goal is still "make the UI feel more native," the cheaper path is the one already proven in this codebase without any new dependency: the shared `NineSliceTexture` + generated-texture approach already used for `ClipboardScreen`/`GraphCanvas`/`DetailCard`.

This is a recommendation, not a unilateral decision — `STEP_MONITOR`'s fate is still the user's call.
