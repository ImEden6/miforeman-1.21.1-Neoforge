# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-09-05

### Added
- Machine status now explains *why* a machine has stalled (starved input,
  dead-loop, or clog-lock) instead of just flagging it as broken.
- Search on the Review Machines and Monitoring screens, including matching
  against a machine's upstream end product, not just its own recipe.
- A disposal ratio and live-status colouring on the recipe graph, so
  bottleneck machines stand out visually.
- Clicking empty canvas space on the recipe graph now deselects the current
  node and returns to the summary panel.
- Redesigned graph screen summary panel with icons and a real outputs list.

### Changed
- Machine node rendering on the recipe graph now draws its gradient fill
  with fewer calls per frame, reducing render cost.

### Fixed
- RED (dead-loop/clog-lock) machines were never highlighted on the recipe
  graph's bottleneck view, missing product labels and end-product search
  matches — `displayRecipeId` wasn't being set for them.
- Stall-reason classification could pick an arbitrary underperformance
  reason instead of preferring a real dead-loop, and two goals sharing a
  machine could nondeterministically clobber each other's cyclic-resource
  tracking depending on tick order.

## [1.0.1] - 2026-08-29

### Added
- Crafting recipe for the Foreman's Clipboard: 1 Book + 1 Modern Industrialization Analog Circuit (shapeless).

## [1.0.0] - Initial release
