# Contributing to MI Foreman

## Running it

```powershell
./gradlew runClient            # client with the mod loaded
./gradlew runServer            # dedicated server, runs in run-server/
./gradlew runGameTestServer    # every gametest, then exits
./gradlew build                # jar in build/libs/
```

The client and the dev server use separate folders (`run/` and `run-server/`), so both can run at once.

## How it fits together

The player carries a Foreman's Clipboard holding a production goal: a target item or fluid and a rate per minute. The mod works out a plan from MI's recipes, draws it as a graph, and tracks the machines the player links.

Code lives under `src/main/java/com/mervyn/miforeman/`.

**Planning (`goal/`)**
- `ProductionGoal` is the saved goal, with a `CODEC` for saves and a `STREAM_CODEC` for packets.
- `RecipeGraphTraverser` builds the recipe graph for a goal, caches it, and fixes loops the default recipes leave with no way in.
- `PlanSolver`, `PlanMatrix` and `lp/` (`Simplex`, `StagedLp`) solve the plan's rates as a staged linear program.
- `CapacitySolver` works out how much the linked machines can make and what limits them.
- `PlanWorker` runs plan calculations off the main thread and keeps only the newest result.

**Graph layout and wires (`goal/`)**
- `GraphLayoutEngine` arranges cards. `GraphLayoutState` holds positions, groups, hidden nodes and undo history.
- `EdgeRouter` routes wires around cards on a grid with A*. `PortLayout` spreads wires along a card's sides.

**Monitoring**
- `goal/ServerMonitoringManager` updates a tracker per linked machine every server tick.
- `goal/UnifiedCrafter` wraps MI's `CrafterComponent` and modular multiblock crafters behind one interface.
- `goal/MachineScanner` finds candidate machines for the auto-detect scan.
- `goal/MachineRecipeHistory` saves each machine's last recipe across restarts.

**Client (`client/`)**
- `gui/ClipboardScreen` is the clipboard UI: define the goal, review the plan, monitor machines. `GoalDraft` holds the form state.
- `gui/widget/GraphCanvas` draws the graph, with `GraphCamera` for pan and zoom and `DetailCard` for the selected card.
- `gui/ReviewMachinesScreen` and `gui/MonitoringScreen` are the machine lists.
- `gui/ColourPickerScreen` edits the colours in `ColourPalette`, stored in `ClientConfig`.
- `WorldHighlightRenderer` outlines linked and candidate machines in the world.

**Networking (`network/`)**, registered in `MIForeman`:

| Packet | Direction | Purpose |
|---|---|---|
| `goal_update` | to server | save a goal; the server calculates its plan |
| `request_monitoring_update` | to server | ask for live machine data |
| `live_monitoring` | to client | per-machine status and rates |
| `scan_request` | to server | scan around the player for machines |
| `scan_result` | to client | the machines a scan found |
| `machine_link_sync` | to client | a machine was linked or unlinked |

`PacketRateLimiter` throttles the packets that come from GUI clicks.

**Other**
- `command/ForemanCommands` adds `/miforeman` commands, in development builds only.
- `mixin/CrafterComponentAccessor` reads `CrafterComponent`'s active recipe.
- `test/ForemanGameTests` holds every gametest. They use the `empty` template, so no structure files are needed.

## Things to know

- Gametests load Extended Industrialization, Tesseract and MI Tweaks as well as MI, so recipe snapshots include EI's recipes. A failing snapshot after a dependency bump usually means the recipes changed, not the code.
- Any field added to `ProductionGoal` or its records needs both codecs updated. `testProductionGoalStreamCodecParity` catches a mismatch.
- Rates are stored per minute. The per-hour toggle only changes the display.
- Use `localRuntime` for dependencies only needed in dev runs and tests. `runtimeOnly` would publish them.
- `neoforge.mods.toml` is a template in `src/main/templates/META-INF/`, filled from `gradle.properties`. `neo_version_range` there is the NeoForge players need, kept separate from `neo_version`, which dev runs use.
- Releases publish to CurseForge and Modrinth when a `v*` tag is pushed. The tag must match `mod_version`, and `CHANGELOG.md` needs a section for that version.
