# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.2] - 2026-10-09

### Added
- Pick one colour for highlighted wires on the recipe graph under "Highlighted graph wires" in the
  Colours screen. By default they keep their own colour; Reset goes back to that.
- Recolour the glow around highlighted wires under "Highlighted wire glow" in the Colours screen,
  or turn it off by setting its alpha to 00.

### Changed
- Selecting a card or searching on the recipe graph no longer recolours wires gold and grey.
  Wires keep their own colour, so a stalled machine's red wire stays red; highlighted wires turn
  solid with a faint glow and the rest fade.
- Zoomed far out on the recipe graph, where card text is too small to read, labels are drawn as
  plain bars. A Quantum Upgrade plan fully zoomed out draws in about a fifth of the time, and
  about half the time at the zoom levels that still show text.
- Recipe graph cards are fully opaque, so a card dragged over another hides what's under it.
- When an addon like Extended Industrialization adds another recipe for something MI already
  makes, plans now default to MI's recipe. Cycle Recipe still offers the addon's. Addon recipes are
  still used where MI has no working recipe of its own, such as bronze from EI's alloy smelter.
- Requires NeoForge 21.1.238 or newer, the same as Modern Industrialization 2.5.10.

## [1.3.1] - 2026-10-07

### Changed
- The text you type into the graph's search bar no longer has a drop shadow.

### Fixed
- The recipe graph no longer slows the game to a crawl on big plans. A Quantum Upgrade plan
  went from about 6 FPS to over 90, and parts of the graph that are off screen are no longer
  drawn at all, so zooming in makes it faster still.

## [1.3.0] - 2026-10-03

### Added
- The plan summary shows how much your linked machines can actually make (at base speed, from
  loaded machines) and what limits it, and limiting machines get a ▲ on the graph.
- Pick the auto-detect scan radius for a single scan with the -/+ buttons beside Scan Nearby,
  instead of always using the configured default. Server admins can cap it with the new
  `autolinkScanMaxRadiusChunks` option.
- Machines a scan newly finds give off a brief particle burst, so they are easy to spot.
- The recipe graph shows how many linked machines it has no node for (never run yet, or
  running a recipe outside the plan), and the monitoring list marks machines still waiting
  for their first craft.
- Click that count to list those machines and place a never-run one on a graph node by hand.
  Only nodes the machine can actually run are offered, and when just one fits it's a single
  click. The machine's first real craft replaces the placement.

### Changed
- Recipe graph wires now route around machine and resource cards instead of running
  straight through them, and wires sharing a corridor fan out side by side rather than
  overlapping into one line. A wire with nowhere sensible to go keeps its old straight
  connector.
- Where two graph wires cross, the one on top jumps over the other with a small bump.
- Moving a card, undo and redo only re-route the wires that actually changed, removing the
  brief hitch on very large graphs when a drag ends.
- Wires sharing a side of a card now attach at separate points, ordered by where they're
  headed, so a machine feeding several others shows separate wires instead of one thick line.
- Graph wires have a light outline, so where two cross the upper one visibly breaks the
  lower instead of the two merging.
- Machine nodes on the graph show how many of their linked machines are running (e.g. 3/4).

### Fixed
- Plans now solve recycling loops and byproducts exactly. A byproduct one machine gives off is
  used by another branch that needs it before anything is imported, and two products from one
  recipe share its machines instead of each building their own.
- Plans no longer build loops that make something from nothing, like ingots packed from nuggets
  unpacked from ingots, or cables "made" by unpacking an energy hatch built from those cables
  (MI lists that recipe first). The plan switches the loop to a recipe that makes just that item
  from outside inputs, or imports the item when there isn't one. The item's card says which, and
  an Auto button next to Cycle Recipe drops your own pick so the plan can choose again. A loop you
  pick by hand is kept, with a warning. Existing goals switch over the next time their plan is
  recalculated; linked machines that ran the old loop recipes then show as off the graph. If
  cycling recipes earlier left a goal with a looping recipe picked, press Auto to let the plan fix
  it. `/miforeman goal plan` lists these picks as `auto: <recipe>`.
- Changing an existing goal's target could open its recipe graph off-screen, because the
  previous graph's camera and card positions were kept. A new target now opens centred.
- Plans asked for 60 times too many machines, and too much power and too many byproducts with
  them: the per-minute goal rate was being treated as per second. Iron plates at 60 a minute
  now need 5 compressors, not 300.
- A graph node with several machines on its recipe showed whichever machine happened to be
  linked last, so a starved machine could hide behind a working one. It now shows the worst.
- Idle machines no longer drop off the recipe graph after the clipboard is put away for a few
  seconds, or after a server restart. Each machine's last recipe is now saved with the world.

### Known limitations
- The capacity readout assumes machines run at base speed, so overclocked or upgraded machines
  can make more than it says. Machines in unloaded chunks count as zero.
- When a plan swaps out a looping recipe on its own, it only uses recipes that make just that
  one item. Routes through recipes with byproducts (centrifuges, electrolyzers) are left for you
  to pick by hand.
- Batch multiblocks from addons, like MI Tweaks' Bulk Compactor, are linked and tracked, but a
  scan only offers them when the recipe they're running is the one the plan uses. If it isn't,
  cycle that item's recipe to match.

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
