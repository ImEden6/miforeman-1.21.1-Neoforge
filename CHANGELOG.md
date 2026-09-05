# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.1] - 2026-09-05

### Fixed
- The graph screen's summary panel no longer shows your target rate as
  missing, and its byproducts list is now correctly labelled instead of
  called "Outputs."
- Fixed the search bars on the Review Machines and Monitoring screens
  needlessly redoing work in the background when you weren't searching.
- Ctrl+F no longer gets swallowed silently when the graph is hidden behind
  an expanded detail panel.
- Fixed the recipe graph occasionally holding onto stale data after you
  left it for the monitoring screen.

## [1.1.0] - 2026-09-05

### Added
- Stalled machines now tell you why: out of material, stuck in a dead loop,
  or clogged with output, instead of just showing "broken."
- Search bars on the Review Machines and Monitoring screens, which can also
  find a machine by the end product it's contributing to.
- The recipe graph now colours machines by their live status and shows a
  disposal ratio, making bottlenecks easy to spot at a glance.
- Clicking empty space on the recipe graph deselects the current machine
  and takes you back to the summary view.
- Redesigned the graph summary panel with icons and a proper list of
  outputs.

### Changed
- Faster graph rendering for machine nodes, reducing lag on large factories.

### Fixed
- Machines with a real problem (dead loop or clog) sometimes weren't
  highlighted on the graph, showed no product label, or didn't show up in
  end-product search.
- Fixed a couple of edge cases where the wrong stall reason could be shown,
  or two factories sharing a machine could confuse each other's status.

## [1.0.1] - 2026-08-29

### Added
- Crafting recipe for the Foreman's Clipboard: 1 Book + 1 Modern Industrialization Analog Circuit (shapeless).

## [1.0.0] - Initial release
