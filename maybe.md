# Maybe — UI/UX nice-to-haves

Not scheduled, just captured so they don't get lost. Mostly aimed at
`GraphCanvas` (recipe graph) and `DetailCard`.

## Visual polish

- **Node depth/border treatment** — a 2-3px darker bottom/right edge (or
  lighter top-left highlight) so nodes read as raised tiles instead of flat
  debug rectangles.
- **Status color-coding** — key node fill color to state (ambiguous, rate
  satisfied/deficit, raw vs intermediate vs target), the way MineColonies
  keys research-tile texture to `ResearchButtonState`. Probably the single
  highest-value visual change since the graph's whole point is surfacing
  production status at a glance.
- **Edge weight/direction styling** — thickness or color scaled to
  `requiredRate`/utilization ratio, turning the graph into an actual
  production-flow diagram instead of boxes with lines. (Already flagged as
  out-of-scope in a `GraphCanvas` comment.)
- **Gradient node fill** instead of flat `COLOR_NODE_FILL` — cheap via
  `guiGraphics.fillGradient`, reads less like a placeholder.

## Interaction / UX

- **Hover state** — no visual feedback right now for the node under the
  cursor outside of drag; add a brightened border/fill on hover.
- **Tooltips on hover** — surface full recipe details (inputs/outputs,
  machine assignment, ambiguity options) instead of cramming everything into
  truncated node text; would also let nodes shrink back down.
- **Edge routing that avoids node overlap** — straight elbow connectors will
  cross through unrelated nodes once the graph gets busy; even a basic
  per-edge-index midpoint offset would help.
- **Selection propagates to edges** — highlight incoming/outgoing edges of
  the selected node (dim the rest) so dependencies are traceable without
  hunting through crossing lines. Cheap: filter `graph.edges()` by
  `from()`/`to() == selectedNodeId`.
- **Minimap or zoom-to-fit / center-on-selected** — no way to orient
  yourself or reset after getting lost in a large graph.
- **Collision feedback for manual drags** — dropping a dragged node on top of
  another currently gives no feedback; a "snap away" or warning outline on
  overlap would help.

## Native-clipboard theming follow-ups

- `clipboard_button.png` exists (regenerated, matches the parchment/leather
  style) but nothing in the code actually blits it — buttons are still
  vanilla `Button.builder(...)`. Could wire a custom 3-slice button texture
  (normal/hover/disabled) if the vanilla button look ever feels out of place
  against the reskinned panels.
- Re-check aspect ratio (`MIN_GUI_WIDTH`/`MIN_GUI_HEIGHT` in
  `ClipboardScreen.java`) after living with the landscape layout for a bit —
  440x230 was a first guess, not a measured choice.

## Code health

- **`RecipeGraphTraverser.java` complexity** — per Omen (`omen tdg`), this is
  the lowest-graded file in the repo: TDG grade B-, cyclomatic complexity 77,
  6 levels of nesting, 24.6% internal duplication. Unlike the `ClipboardScreen`/
  `GraphCanvas` cohesion split or the `formatId`/list-scroll dedup (both
  mechanical extractions), this is a real algorithmic-complexity problem in
  the recipe-traversal logic itself — reducing it means restructuring how the
  graph traversal branches, not just moving code around. Needs its own
  focused pass with test coverage before touching it, not an opportunistic
  cleanup.
