Type: research
Status: resolved

## Question

Does BlockUI require the XML layout DSL (like MineColonies' `windowresearch.xml`), or can a window's Panes be built directly in Java — closer to this codebase's existing style (`ClipboardScreen.buildStepDefineGoal` constructs `EditBox`/`Button` instances directly in Java, no layout file)? Matters because the Define Goal form has dynamic behavior (live validation swapping `errorMessage`/`nextButton.active`, toggle-button label text) that needs code-driven updates regardless of how the static layout is declared.

## Answer

Confirmed from `references/BlockUI` source: XML is optional, not required.

- `BOWindow` has both an XML-loading constructor (`BOWindow(ResourceLocation)`, calls `Loader.createFromXMLFile`) **and** a plain constructor (`BOWindow()` / `BOWindow(int w, int h)`, `BOWindow.java:66-83`) that does nothing but set `width`/`height` and construct the owned `BOScreen` — no XML involved.
- `View.addChild(Pane child)` / `addChild(Pane child, int index)` (`View.java:294,305`) are public — children can be added programmatically to any `View` (including a `BOWindow`, which extends `View`), exactly like `ClipboardScreen.addRenderableWidget(...)` today.
- Field-level equivalents to the current vanilla widgets both exist as plain Java-constructible classes in `references/BlockUI/src/main/java/com/ldtteam/blockui/controls/`:
  - `TextField.java` — has `setHandler(InputHandler)` (`TextField.java:584`), the direct equivalent of `EditBox.setResponder(...)` for live validation on every keystroke.
  - `Button.java` + `ButtonHandler.java` — click-handler equivalent of `Button.builder(...).build()`, usable for Cancel/Next/the ITEM-FLUID toggle/the Per-Hour-Per-Min toggle.
  - `ToggleButton.java` / `CheckBox.java` also available if a toggle reads better as a dedicated toggle widget than a relabeled `Button`.

Conclusion: the Define Goal trial does **not** need to learn or write any XML — it can build its `BOWindow` and child Panes in Java, in basically the same shape `buildStepDefineGoal()` already has today, just swapping vanilla widget constructors for BlockUI ones and `setResponder`/`Button.builder` for `setHandler`/`ButtonHandler`. XML remains available later if a declarative layout ever seems worth it, but it's not a blocker for this trial.
