# Maybe: nice-to-haves

Not scheduled, just captured so they don't get lost.

Ideas from scouting `references/gtnh-factory-flow-main` (MIT licensed — code patterns are fair game
to lift directly, not just draw inspiration from; only GTNH's own game data/textures are excluded
from that grant).

## Graph wires

- **An animated marker must follow the real path, not interpolated endpoints.** The reference's
  "marching dashes" (`edge-pulse.ts`) are `ctx.stroke(path2d, {dash, gap})` with an advancing
  `lineDashOffset`, where `path2d` is built from the *exact same path string* the SVG wire draws.
  It moved off SVG only because `stroke-dashoffset` is a paint property that invalidated the whole
  edge layer every frame (616 edges cost ~90ms/frame). `GraphCanvas` has no animated marker today;
  if one is ever added, drive it off the same `EdgeRouter.Route.points()` list (not a lerp between
  the two endpoints) so it stays glued to the path through corners for free.

## Data pipeline (if we ever export/share recipe datasets)

- **In-game off-screen render for icons, not a screenshot tool** — their
  icon exporter runs *inside* the actual Minecraft client via a companion
  mod, using a real LWJGL framebuffer and the game's own `RenderItem` call,
  batched through a queued headless GUI screen so many stacks render in one
  client run. Produces pixel-accurate item art without maintaining a
  separate renderer.
- **Shard + index instead of shipping one big JSON blob** — normalized
  recipe data gets gzip-sharded into small fixed-size chunks plus a
  lightweight search/lookup index shipped separately, so a client fetches
  only the shard it needs.
