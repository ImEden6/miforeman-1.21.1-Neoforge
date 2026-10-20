# Maybe

Ideas that aren't scheduled. Most come from GTNH Factory Flow, a planner for GregTech: New
Horizons. Its code is MIT licensed and can be reused, but GTNH's game data and textures can't.

## Graph wires

- **An animated marker has to follow the routed path.** GTNH Factory Flow draws its marching
  dashes on a canvas with an advancing dash offset, using the same path as the wire itself. Doing
  it with SVG's `stroke-dashoffset` repainted every edge every frame, about 90 ms a frame with 616
  edges. `GraphCanvas` has no animated marker. If one is added, drive it from
  `EdgeRouter.Route.points()`, not a line between the two endpoints, and it stays on the wire
  through corners.

## Recipe data export

Only matters if recipe datasets are ever exported or shared.

- **Render icons inside the game.** GTNH Factory Flow exports item icons from the real client
  through a companion mod, rendering each stack with the game's own item renderer into a
  framebuffer, many per run. The art matches the game exactly and there's no second renderer to
  maintain.
- **Ship shards and an index, not one big JSON file.** Recipe data is split into small gzipped
  chunks with a separate search index, so a client downloads only the chunk it needs.
