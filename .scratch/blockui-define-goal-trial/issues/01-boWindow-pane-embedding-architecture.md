Type: research
Status: resolved

## Question

Can a BlockUI `Pane`/`View` tree be rendered from inside an existing vanilla `Screen` (i.e. embedded as one region of `ClipboardScreen`'s own `render()`), or does every Pane require full ownership of the screen via a `BOWindow`+`BOScreen`? This determines whether "migrate just the Define Goal step" is a widget-level change or a screen-level one.

## Answer

Resolved directly from the real source (cloned to `references/BlockUI`, branch `version/main`, commit `78dae57` — stripped of `.git`/gradle build files, `src/` + license/docs only).

**Every Pane needs a `BOWindow`.** `Pane.java` carries a `protected BOWindow window` field (line 46), set via `setWindow()`, and multiple mechanisms reach through it — scissoring mutates `window.getScreen().width`/`height` directly (`Pane.java:721-724`), tooltips build against `window` (`Pane.java:546-552`), hover panes get `putInside(window)` (`Pane.java:884`). There's no path to render a bare `Pane`/`View` without a `BOWindow` behind it.

`BOWindow extends View` (`BOWindow.java:22`) and **owns its own `BOScreen extends Screen`**, constructed eagerly in every `BOWindow` constructor (`BOWindow.java:82`: `screen = new BOScreen(this);`). So a `BOWindow` is not a widget you drop into your own `Screen`'s `render()` — it comes with a full vanilla `Screen` attached.

**But there are two ways to surface that `BOScreen`, and the second one fits this trial well:**
- `BOWindow.open()` (`BOWindow.java:158-161`) — `mc.submit(() -> mc.setScreen(screen))`. Fully replaces the current screen; the previous screen (`ClipboardScreen`) would be gone unless you reconstruct it.
- `BOWindow.openAsLayer()` (`BOWindow.java:166-169`) — `mc.submit(() -> mc.pushGuiLayer(screen))`. Vanilla's GUI-layer stack: pushes the BlockUI screen **on top of** the still-alive `ClipboardScreen` underneath. Popping the layer returns to that same `ClipboardScreen` instance, untouched — no manual serialize/reconstruct needed.

**Recommendation for this trial**: use `openAsLayer()`. Entering `STEP_DEFINE_GOAL` pushes a `BOWindow` (Java-built, see ticket 02) as a layer; `ClipboardScreen` itself stays alive underneath the whole time. "Next"/"Cancel" on the BlockUI layer needs to pop the layer and hand its field values back to the underlying `ClipboardScreen` instance — the hand-off contract for that is ticket 03 (blocked on nothing now, but design detail deferred there rather than decided here).
