# Map: BlockUI Define Goal Trial

Label: wayfinder:map

## Destination

`STEP_DEFINE_GOAL` (the Goal Name / Target Type & ID / Rate / Threshold form in `ClipboardScreen`) fully rebuilt on BlockUI — replacing the current vanilla `EditBox`/`Button`/manual-`drawString` code for that step — compiling and verified working in-game. This is an **execution-inclusive** map: tickets end in shipped code, not just a plan. Deliverable also includes a short written verdict (effort vs. payoff) that a future `STEP_MONITOR` BlockUI migration decision gets judged against.

## Notes

- Domain: Minecraft NeoForge 1.21.1 mod (`com.mervyn.miforeman`), client-side GUI. See `AGENTS.md` for architecture.
- **Authoritative BlockUI reference**: `references/BlockUI` — full source cloned from `github.com/ldtteam/BlockUI` branch `version/main` (commit `78dae57`), stripped of `.git` and all gradle build files (src + license/docs only). Use this, not memory, for API shape.
- **`references/minecolonies-1.21.1` is mislabeled** — its `gradle.properties` pins `exactMinecraftVersion=1.20.1`/`forgeVersion=47.1.3`. It's MineColonies for 1.20.1 Forge, not 1.21.1/NeoForge. Its `WindowResearchTree.java` is fine as a *usage-pattern* reference (how MineColonies composes BlockUI Panes) but not trustworthy for exact 1.21.1 API shape — check `references/BlockUI` directly for that.
- `STEP_MONITOR` migration is explicitly **deferred**, gated on this trial's ticket 06 verdict — see Not yet specified, not Out of scope.
- `GraphCanvas`/`DetailCard`/`ReviewListPanel` are untouched by this map.
- Prior related work: the 9-slice clipboard texture pipeline (`tools/gen_clipboard_textures.py`, `NineSliceTexture.java`) reskinned `ClipboardScreen`/`GraphCanvas`/`DetailCard` to share one parchment look. Whether the Define Goal BlockUI rebuild reuses those generated textures (via BlockUI's own `Image`/`ButtonImage` 9-patch handling) or something else is open — see Not yet specified.
- If in doubt on a ticket, use `/grilling`/`/domain-modeling`.
- While testing this map's ticket 05, also found and fixed a pre-existing, unrelated visual bug on the Review Plan page: `GraphCanvas`/`DetailCard` were each drawing their own bordered parchment panel on top of `ClipboardScreen`'s main panel, reading as two nested clipboards. Fixed inline (both widgets now draw no border of their own, just a thin 1px divider between them) — not tracked as a ticket here since it predates and is unrelated to the BlockUI work.
- **`compileJava`/`runGameTestServer` are not sufficient proof of "works"** — both run against the dev-environment classpath, which doesn't reflect how dependencies get bundled into the shipped jar. Ticket 03's `NoClassDefFoundError` crash on a real client (BlockUI wasn't in the shipped jar despite compiling and passing gametests fine) is why: verify packaging (`unzip -l` the built jar) for anything dependency-related, and treat "compiles clean" as necessary but not sufficient for this map's execution-inclusive bar.

## Decisions so far

- [BOWindow/Pane embedding architecture](issues/01-boWindow-pane-embedding-architecture.md) — every `Pane` requires a `BOWindow` (which owns its own `BOScreen`); can't embed bare Panes inside `ClipboardScreen`'s own `render()`. But `BOWindow.openAsLayer()` (`mc.pushGuiLayer`) pushes the BlockUI screen on top of the still-alive `ClipboardScreen` rather than replacing it (`BOWindow.open()` would) — that's the integration point this map uses.
- [Programmatic Pane construction](issues/02-programmatic-pane-construction.md) — BlockUI does **not** require XML; `BOWindow`/`View` can be built and populated in plain Java (`addChild`), same shape as today's hand-rolled widgets. `TextField.setHandler` ≈ `EditBox.setResponder`; `Button`/`ButtonHandler` ≈ `Button.builder(...)`.
- [Add BlockUI dependency](issues/03-add-blockui-dependency.md) — `jarJar(implementation("curse.maven:blockui-522992:7541336"))` (1.0.209-1.21.1 release). **Corrected mid-trial**: plain `implementation` compiled fine but crashed a real client (`NoClassDefFoundError`) since it doesn't bundle into the shipped jar and BlockUI isn't a mod players have installed separately, unlike MI. JarJar embeds it directly (zero transitive deps, so nothing else to drag in) — confirmed present in the built jar's `META-INF/jarjar/`.
- [State hand-off contract](issues/04-state-handoff-contract.md) — functional callbacks (`Consumer<GoalFormResult> onSubmit`, `Runnable onCancel`), matching `GraphCanvas`/`DetailCard`'s existing callback convention rather than a back-reference to `ClipboardScreen`. `validateInputs()`'s rules get extracted into a static/pure function callable from both the BlockUI layer and `ClipboardScreen`, so validation logic isn't duplicated.
- [Rebuild Define Goal on BlockUI](issues/05-rebuild-define-goal-on-blockui.md) — shipped (`DefineGoalWindow`/`GoalFormResult`/`GoalFormValidation`, old vanilla step code deleted). Happy path (open → fill → submit → correctly-computed Review Plan) confirmed on a real client after three rounds of real-client-only fixes: jarJar packaging (see ticket 03), render scale/sizing (`WindowRenderType.VANILLA` + matching `guiWidth()`/`guiHeight()`), and the window drawing its own parchment background rather than relying on the backgrounded `ClipboardScreen` layer to show through. Edge cases (Cancel, invalid-input errors, the no-recipe retry path, re-entry via Back) are unverified, not known-broken. Full iteration log on the ticket.
- [Write verdict](issues/06-write-verdict.md) — **recommend against** migrating `STEP_MONITOR` to BlockUI. Payoff for Define Goal was roughly equal to the vanilla code it replaced (no clear ergonomic win for a form this simple), while cost included three rounds of real-client-only bugs — two of them BlockUI-specific footguns (dependency bundling, window scaling), not one-time costs now paid off. If "feel more native" is still the goal, the cheaper already-proven path is the existing `NineSliceTexture` + generated-texture approach, no new dependency required. Recommendation, not a unilateral decision — `STEP_MONITOR`'s fate is still the user's call.

## Not yet specified

- `STEP_MONITOR` BlockUI migration — deferred, gated on ticket 06's verdict. Includes `ReviewListPanel`'s link/unlink/reject/unreject list interactions, which are real complexity `STEP_DEFINE_GOAL` doesn't have.

## Out of scope

_(none yet)_

## Out of scope

_(none yet)_
