# CLAUDE.md

Agent-facing notes for this repo. See [CONTRIBUTING.md](CONTRIBUTING.md) for architecture, package
map, build/run commands, and the general conventions list: don't duplicate that here.

## MI internals gotchas (learned the hard way)

- **A drained `ConfigurableItemStack`/`ConfigurableFluidStack` forgets its configured resource
  type.** `AbstractConfigurableStack.setAmount(long)` resets `key` back to `getBlankVariant()`
  the instant `amount` hits 0 (confirmed by decompiling the class in the MI jar - no source is
  vendored). So a genuinely-starved input slot's `getVariant()` reports blank, not "IRON_INGOT at
  0": there is no live resource-type information left to inspect once material actually runs
  out.
- **`CrafterComponent.getRecipes(level, recipeType, itemInputs)` only returns candidates for
  slots that currently hold a nonzero amount**, as a direct consequence of the above. A machine
  with a fully-empty (or newly-placed, never-configured) input inventory gets an *empty*
  candidates list, not a list of "would-match if I had material" recipes. Don't use this method
  to answer "what recipe/resource is this starved machine waiting on": it structurally can't
  tell you once the machine is actually starved. Use tracked history instead (see
  `MachineTracker.lastKnownRecipeId`, added for exactly this).
- To inspect MI (or any curse.maven) dependency internals without vendored source: extract the
  class from the jar under `~/.gradle/caches/modules-2/files-2.1/curse.maven/...` and run
  `javap` (`-c` for bytecode) on it. No sources jar is published for this dependency.

## Test suite reality check

- `./gradlew runGameTestServer` has **no `--tests` filter** (NeoForge's task, not vanilla Gradle
  `test`): it always runs the full `ForemanGameTests` suite. Takes ~15-60s depending on
  configuration cache state.
- **Before attributing a gametest failure to your own change, run the suite on unmodified `main`
  first** to rule out a pre-existing failure - see the two found and fixed 2026-09-04 below for
  what that can look like (both silently passed CI/local runs for who knows how long because the
  assertions were unconditionally wrong, not because the code they tested was ever broken).
  - `testMachineStatusDynamicTransitions` was comparing a `MachineStatus` enum against a `String`
    via `"RED".equals(tracker.status)`: `String.equals` requires the other object to *be* a
    `String`, so this is always `false` no matter the real status. Three call sites (RED/GREEN/
    ORANGE checks). Fixed to `tracker.status != MachineStatus.RED` etc.
  - `testGraphCacheHitAndInvalidation` asserted `first == second` (reference equality) to prove a
    cache hit, but `computeRecipeGraph` always returns `cached.copy()`/`result.copy()`, never the
    stored instance, specifically so callers can't mutate a shared cache entry through a node's
    public setters (see its doc comment at
    [RecipeGraphTraverser.java:288-290](src/main/java/com/mervyn/miforeman/goal/RecipeGraphTraverser.java:288)). Two calls are *never* reference-equal by
    design, cache hit or not, so the test always failed regardless of whether caching actually
    worked. `RecipeGraphNode` also has no value-based `equals`/`hashCode` (identity is the right
    default for production code, given those public setters), so `.equals()` wasn't a drop-in fix
    either. Rewrote the test around a `sameGraphContent` structural-comparison helper (target,
    targetRate, node id set, edge (from,to) pairs, cyclicResourceIds) instead of identity: this
    tests result correctness/determinism across cache operations, which is what's actually
    observable from outside `RecipeGraphTraverser` now; it can no longer prove "was literally
    served from the cache map" without an internal hit-counter hook.

## Recent feature: dead-loop vs clog-lock (2026-09-04)

Added `FailureReason` (`STARVED` / `DEAD_LOOP` / `CLOG_LOCK` / `NONE`) alongside `MachineStatus`
to distinguish *why* a machine isn't running, per the pattern captured in [maybe.md](maybe.md).
Key design point for future extensions in this area: `RecipeGraphTraverser.collectDag` now
records recycling-loop membership into `RecipeGraph.cyclicResourceIds()` instead of silently
discarding back-edges; `RecipeGraphTraverser.peekCyclicResourceIds(goal)` is a **cache-only**
accessor safe to call from the per-tick `onServerTick` loop without forcing a full graph
recompute/copy. `MonitoringPacketHandlers.handleRequest`'s YELLOW/reason branch was extracted into
`ServerMonitoringManager.classifyLiveStatus` purely so it's unit-testable without a real network
`IPayloadContext`, no behavior change.

- **Real MI item graphs are cyclic more often than intuition suggests.** Even
  `modern_industrialization:iron_plate`, about as shallow a target as exists, has a genuine
  2-cycle: `minecraft:iron_ingot` <-> `minecraft:iron_nugget` via MI's packer/unpacker recipes (9
  nuggets makes 1 ingot; 1 ingot unpacks to 9 nuggets, each direction is a candidate recipe for
  the other's resource). This is correct MI data, not a graph-traversal bug. Don't assume a small
  or "simple" real target is acyclic without checking - this is exactly the kind of thing worth a
  snapshot-style test assertion (see `testRecipeGraphCyclicResourceIdsCaptured`) rather than a
  blind "must be empty" expectation.

## Recent feature: search on Review Machines / Monitoring screens (2026-09-04)

`GraphSearchState` (graph-canvas-only, hardwired to `RecipeGraphNode`/`ResourceLocation`) was
generalized into `client/gui/widget/SearchState.java`: generic over any ID type, matching against
a caller-supplied `Map<ID, List<String>>` of searchable text instead of deriving it inline. All
three screens (`GraphCanvas`, `ReviewMachinesScreen`, `MonitoringScreen`) now share this one
matching/cycling implementation; `GraphSearchBar` (the floating icon/bar chrome) stayed
graph-canvas-only and unchanged except its field type, since a linear list doesn't need
next/prev-match cycling - narrowing the list to matches is enough, so the two list screens just use
a plain `EditBox` (existing `ClipboardScreen` pattern) instead.

- **`EditBox.setValue(...)` unconditionally fires whatever responder is attached** (confirmed by
  decompiling `EditBox.setValue`'s bytecode: it always calls `onValueChange` at the end, not just
  when the value actually changes). Restoring a search query's text on `rebuild()` via `setValue`
  will re-trigger the responder immediately, before the rest of `rebuild()` (e.g. the list panel)
  has been (re)constructed. Fix: call `setValue(restoredQuery)` **before** `setResponder(...)`,
  the field's own no-op default responder absorbs that first call harmlessly, and the real
  responder is only attached once it's safe for it to run. Two live examples of this ordering:
  `ReviewMachinesScreen.rebuild()` and `MonitoringScreen.rebuild()`.
- **"Search by end product" walks the graph, not just each row's own recipe.** A machine's own
  immediate craft output (e.g. "Iron Plate") isn't what a player searching for the factory's final
  target (e.g. "Quantum Upgrade") is thinking of. `RecipeGraphTraverser.collectUpstreamResourceIds`
  exploits the graph's node-id scheme (resource nodes keyed by resource id, MACHINE nodes keyed by
  *recipe id*, see `finalizeResourceNode`) to walk forward from a machine's recipe id through
  alternating resource-machine-resource edges up to the target, collecting every resource in
  between. `MonitoringState.endProductNames` wraps this for the two list screens, computing the
  goal's graph once in `fromGoal` rather than per-row per-poll.
