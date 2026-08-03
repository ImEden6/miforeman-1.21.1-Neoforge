Type: research
Status: resolved

## Question

What NeoForge/Minecraft `GuiGraphics`/`PoseStack` APIs are available for implementing a clipped, zoomable, pannable sub-viewport inside an `AbstractWidget`?

Specifically:
- Can `GuiGraphics` scale + translate a region (`pushPose`/`scale`/`translate` or equivalent) so node positions/edges can be drawn in "canvas space" and rendered at the current zoom/pan transform?
- Is there a scissor/clip mechanism (`enableScissor`/similar) to keep the canvas's rendering confined to its widget bounds, especially when `DetailCard` is open vs collapsed and the canvas width changes?
- How should mouse coordinates (click, drag, scroll) be converted between screen space and canvas space given the current zoom/pan, for hit-testing against node positions and dragging?
- Are there existing vanilla or NeoForge screens that already implement pan/zoom (e.g. map rendering, structure block bounds, any node-graph-like UI) worth referencing for a known-working pattern?

Findings should be captured on a `research/graph-canvas-zoom-pan` branch with a summary the answer can point to.

## Answer

Full findings are on branch `research/graph-canvas-zoom-pan` in `RESEARCH-graph-canvas-zoom-pan.md`, derived from the project's own decompiled Minecraft/NeoForge sources jar (`build/moddev/artifacts/neoforge-21.1.233-sources.jar`), not from memory.

1. **Scale/translate**: verified — `GuiGraphics.pose()` exposes the `PoseStack` directly; `pushPose/translate/scale/popPose` is a proven idiom, already used inside `GuiGraphics` itself and in MI's `NuclearReactorGuiClient`/`HudRenderer` (in `references/Modern-Industrialization-1.21.x`).
2. **Scissor/clip**: verified — `GuiGraphics.enableScissor`/`disableScissor` exist and nest safely via rectangle intersection. This repo's own `TreePanel.java`/`DetailCard.java` already use exactly this pattern, so handling `DetailCard`'s collapse/expand just means keeping the canvas widget's bounds current before scissoring.
3. **Coordinate conversion**: no vanilla auto-conversion — mouse events (`AbstractWidget.mouseClicked`/`onDrag`, `GuiEventListener.mouseScrolled`) arrive in screen space. `GraphCanvas` must invert its own pan/zoom transform for hit-testing and divide drag deltas by the current zoom factor.
4. **Reference implementations**: no vanilla screen does true 2D pan+zoom. Closest patterns: NeoForge's `ScrollPanel` (manual pan offset) and vanilla's `RecipeBookPage` (fixed grid, no zoom) — both checked and are partial fits at best; MapRenderer and the structure-block bounds editor were checked and ruled out. Recommendation: compose the proven idioms already present in this codebase (`TreePanel`/`DetailCard`'s scissor pattern) and in MI (`PoseStack` scale/translate) rather than looking for an existing full pan+zoom widget to copy.

Conclusion: no blockers. `GraphCanvas` can be built on `GuiGraphics`'s existing `PoseStack` transform + scissor support; the only net-new work is the screen↔canvas coordinate conversion for hit-testing, which has no existing pattern in this codebase to reuse and should be designed fresh.
