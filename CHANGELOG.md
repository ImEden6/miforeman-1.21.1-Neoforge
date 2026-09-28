# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Pick the auto-detect scan radius for a single scan with the -/+ buttons beside Scan Nearby,
  instead of always using the configured default. Server admins can cap it with the new
  `autolinkScanMaxRadiusChunks` option.
- Machines a scan newly finds give off a brief particle burst, so they are easy to spot.

### Changed
- Recipe graph wires now route around machine and resource cards instead of running
  straight through them, and wires sharing a corridor fan out side by side rather than
  overlapping into one line. A wire with nowhere sensible to go keeps its old straight
  connector.
- Graph wires have a light outline, so where two cross the upper one visibly breaks the
  lower instead of the two merging.
- Machine nodes on the graph show how many of their linked machines are running (e.g. 3/4).

### Fixed
- A graph node with several machines on its recipe showed whichever machine happened to be
  linked last, so a starved machine could hide behind a working one. It now shows the worst.

## [1.2.0] - 2026-09-08

### Added
- An Auto-Arrange button that tidies up the recipe graph layout for you.
- Select multiple nodes (ctrl/shift-click, or drag a selection box) and lock them together as
  a group, so Auto-Arrange treats the group as one block and leaves your hand-arranged cluster
  alone instead of scattering it.
- A toggle to let Auto-Arrange also tidy up the inside of a locked group, instead of always
  leaving it untouched.
- Buttons to pull a single node back out of a group, or dissolve a group entirely.
- Auto-Arrange moving dozens of nodes at once still undoes in a single step.

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
