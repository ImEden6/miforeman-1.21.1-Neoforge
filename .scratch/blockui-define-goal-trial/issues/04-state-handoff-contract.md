Type: grilling
Status: resolved

## Question

Ticket 01 settled that the Define Goal step becomes a `BOWindow` pushed via `openAsLayer()` on top of the still-alive `ClipboardScreen`. When the player presses **Next** or **Cancel** on that BlockUI layer, it needs to pop itself and hand its field values (`goalName`, `targetType`, `targetIdStr`, `rate`, `perHour`, `threshold`) back to the underlying `ClipboardScreen` instance, then trigger the existing `computePlanAndAdvance()` (Next) or `onClose()` (Cancel) — `ClipboardScreen.java:285,343`.

What's the hand-off contract? Candidates to weigh:
- **(a) Direct back-reference** — the `BOWindow` subclass takes a `ClipboardScreen` reference at construction and writes straight into its fields/calls its methods on Next/Cancel.
- **(b) Functional callback** — construction takes `Consumer`/`BiConsumer` callbacks (mirroring how `GraphCanvas`/`DetailCard` already take `onSelect`/`onLayoutChange`/`onAmbiguity` callbacks today) so the BlockUI window doesn't need to know about `ClipboardScreen` as a concrete type.
- **(c) Shared mutable state object** — pull `goalName`/`targetType`/`targetIdStr`/`rate`/`perHour`/`threshold` out into a small holder object both screens read/write, closer to how `ProductionGoal` already aggregates this same field set.

Also needs an answer for: does live validation (`validateInputs()`, `ClipboardScreen.java:292`) get duplicated into the BlockUI layer's own `TextField.setHandler` callbacks, or does the BlockUI layer forward every keystroke back to `ClipboardScreen.validateInputs()` so there's one copy of the validation logic?

## Answer

**Hand-off shape**: functional callbacks, consistent with `GraphCanvas`/`DetailCard`'s existing convention (`Consumer<ResourceLocation> onSelect`, `Consumer<GraphLayoutState> onLayoutChange`, `BiConsumer<...> onAmbiguity`, `CameraChangeListener`) rather than a direct back-reference to `ClipboardScreen` or a shared mutable holder object. The Define Goal `BOWindow` takes:
- `Consumer<GoalFormResult> onSubmit` — a new small record bundling `goalName`/`targetType`/`targetIdStr`/`rate`/`perHour`/`threshold`, fired once when Next is pressed and the layer pops (`ClipboardScreen` then does whatever `computePlanAndAdvance()` needs with those values).
- `Runnable onCancel` — fired when Cancel is pressed and the layer pops without submitting (`ClipboardScreen`'s existing `onClose()` path).

No reference back to `ClipboardScreen` as a concrete type; the BlockUI window stays as decoupled from it as `GraphCanvas`/`DetailCard` already are.

**Live validation**: extract `ClipboardScreen.validateInputs()`'s rules (`ClipboardScreen.java:292-341`) into a static/pure function — e.g. `validateGoalInputs(String name, String targetIdStr, TargetType type, double rate, double threshold, Level level) -> Optional<Component>` — callable independently by both the BlockUI layer's `TextField.setHandler` callbacks (against its own locally-held field values while the layer is open) and by `ClipboardScreen` itself. One copy of the validation rules, no back-reference required for this either. `validateInputs()` already reads only local field values today (not anything screen-instance-specific), so this is a straightforward extraction, not a redesign.
