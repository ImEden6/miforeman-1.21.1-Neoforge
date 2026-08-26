# CONTRIBUTING.md — MI Foreman

## Quickstart

```powershell
./gradlew runClient                  # Minecraft client with mod
./gradlew runServer                  # Dedicated server (nogui)
./gradlew runGameTestServer          # All game tests, then exit
./gradlew build -x test             # Mod JAR at build/libs/miforeman-1.0.0.jar
./gradlew --refresh-dependencies     # Force refresh all Gradle deps
```

## Architecture

**MI Foreman** (`miforeman`) is a NeoForge 1.21.1 mod. Player holds a **Foreman's Clipboard** item, sets a production goal (item/fluid + desired rate), and the mod traverses MI's recipe graph to compute a factory plan. Linked machines are monitored in real time.

### Package map (`src/main/java/com/mervyn/miforeman/`)

| Entrypoint / Subsystem | File | What it does |
|---|---|---|
| Mod main | `MIForeman.java` | `@Mod("miforeman")` — registers items, components, commands, packets, game tests |
| Client mod main | `MIForemanClient.java` | Client event bus registrations (world highlights, key handlers, client setup) |
| Config (Common) | `Config.java` | `ModConfigSpec` (COMMON) — common/server configuration |
| Config (Client) | `client/ClientConfig.java` | Client settings — highlight colors, rendering preferences |
| Items | `registry/ModItems.java` | `DeferredRegister.Items` — one item: `foreman_clipboard` (stacksTo(1)) |
| Components | `registry/ModComponents.java` | `DeferredRegister.DataComponents` — `production_goal` component |
| Clipboard item | `item/ForemanClipboardItem.java` | Right-click machine → link/unlink; right-click air → open GUI (client) |
| Client bridge | `client/ClientAccess.java` | Static bridge to open screens, receive monitoring data, and handle client sync |
| In-world highlights | `client/WorldHighlightRenderer.java`, `MIForemanRenderTypes.java` | Renders translucent bounding boxes & status highlights for linked/scanned machines |
| GUI screens | `client/gui/ClipboardScreen.java` | Main clipboard screen hosting interactive DAG canvas, detail card, and workflow steps |
| | `client/gui/ReviewMachinesScreen.java` | Step 2 review screen for linked and candidate machines |
| | `client/gui/MonitoringScreen.java` | Step 3 live monitoring dashboard screen |
| | `client/gui/ColourPickerScreen.java` | In-game RGBA/HSB color picker screen for highlights and UI elements |
| | `client/gui/blockui/DefineGoalWindow.java` | Step 0 modal popup to configure target item/fluid, rate, and units |
| GUI widgets | `client/gui/widget/GraphCanvas.java`, `GraphCamera.java` | Interactive pan/zoom DAG canvas rendering recipe nodes and bezier edges |
| | `client/gui/widget/DetailCard.java` | Contextual inspector card for selected node, material flows, and ambiguity cycling |
| | `client/gui/widget/MonitoringListPanel.java`, `ReviewListPanel.java` | Scrollable panels for machine lists, status badges, and batch actions |
| Goal model | `goal/ProductionGoal.java` | Immutable record with `CODEC`/`STREAM_CODEC` — target, rate, plan, layout, history, uiState |
| Recipe traversal | `goal/RecipeGraphTraverser.java` | Recursive BFS through MI recipe graph; cycle handling, ambiguity resolution, DAG construction |
| Graph model | `goal/RecipeGraph.java`, `RecipeGraphNode.java`, `NodeType.java`, `GraphEdge.java` | DAG data model for factory planning & node canvas |
| Crafter adapter | `goal/UnifiedCrafter.java` | Unified crafter abstraction supporting base MI `CrafterComponent` and duck-typed modular multiblock crafters |
| Machine scanner | `goal/MachineScanner.java` | Server-side sphere/area scanning for unlinked MI & custom multiblock machines matching active graph |
| State tracking & sync | `goal/MachineLinkHistory.java`, `ClipboardUiState.java`, `GraphLayoutState.java`, `ClipboardCloseSync.java` | Persistence & undo/redo tracking for machine links, canvas positions, and UI state |
| Server monitoring | `goal/ServerMonitoringManager.java` | Server-side tracker & `@SubscribeEvent` tick handler — tracks `UnifiedCrafter` energy, status (GREEN/YELLOW/ORANGE/RED) |
| Commands | `command/ForemanCommands.java` | `/miforeman goal create\|print\|plan\|select` and `/miforeman recipes print` |
| Packets | `network/*.java` | 6 network packets for client-server communication (see below) |
| Rate limiting | `network/PacketRateLimiter.java` | Server-side rate limiter guarding network payloads |
| Mixins | `mixin/CrafterComponentAccessor.java` | Accessor mixin for `CrafterComponent.activeRecipe` |
| Game tests | `test/ForemanGameTests.java` | 20 `@GameTest`s verifying core logic (see below) |

### Network packets (registered in `MIForeman.java:75-107`)

- `goal_update` (`GoalUpdatePayload`, playToServer) — save goal + recompute factory plan
- `request_monitoring_update` (`RequestMonitoringUpdatePayload`, playToServer) — request live monitoring data
- `live_monitoring` (`LiveMonitoringPayload`, playToClient) — send per-machine status & rates to client
- `scan_request` (`ScanRequestPayload`, playToServer) — request sphere scan for candidate machines
- `scan_result` (`ScanResultPayload`, playToClient) — send scanned candidate machine coordinates to client
- `machine_link_sync` (`MachineLinkSyncPayload`, playToClient) — delta-sync machine link states to client

## Development commands

### Run / test / build

```powershell
./gradlew runClient                           # Client
./gradlew runServer                           # Headless server
./gradlew runGameTestServer                   # All game tests, then exit
./gradlew build -x test                       # Build JAR
```

### Game tests (`ForemanGameTests.java`)

| Test | What it verifies |
|---|---|
| `testProductionGoalComponent` | Save/retrieve `ProductionGoal` via `DataComponents` |
| `testProductionGoalStreamCodecParity` | `CODEC` and `STREAM_CODEC` round-trip parity on all `ProductionGoal` fields |
| `testReadMIRecipes` | Fetches all MI `MachineRecipe` types from registry |
| `testGenerateRequirementsComplex` | Quantum_upgrade graph: ≥210 assemblers, exact raw input rates |
| `testIdentifyBottlenecks` | Bronze compressor status logic (empty input -> RED, blocked output -> ORANGE) |
| `testCycleRecipePlan` | Ambiguity cycling updates selections and recomputes graph correctly |
| `testBuildRecipeIndex` | `MachineScanner` index maps each machine node to active recipe ID |
| `testRecipeGraphEdgesAreDeduped` | Edge deduplication per (from, to) pair & node/edge snapshot count validation |
| `testAmbiguityOwnerIdIsConsistent` | Ambiguity owner resource IDs match actual output edges on nodes |
| `testCloseSyncGoalPreservesLastSynced` | `computeCloseSyncGoal` preserves linked machines while applying UI state deltas |
| `testDimensionKeyedTrackers` | `ServerMonitoringManager` tracker keys are dimension-safe (`GlobalPos`) and prune cleanly |
| `testPowerDemandMatchesRequirements` | Machine EU/t power demand matches between `computePlan` and `MachineStats` |
| `testFluidTargetGoalTraversal` | Fluid production target graph traversal and candidate recipe resolution |
| `testGraphCacheHitAndInvalidation` | Graph cache returns cached instance and properly invalidates on goal mutation |
| `testPacketRateLimiterThrottling` | Server-side rate limiter throttles excessive network payloads |
| `testAmbiguityWrapAroundCycling` | Full-cycle ambiguity rotation returns graph structurally identical to initial state |
| `testMachineStatusDynamicTransitions` | In-world machine crafting status lifecycle transitions over server ticks |
| `testProxiedRecipeTypesConfigDefaultsOffAndTogglesCleanly` | Config toggling for proxied recipe types defaults off and cleanly round-trips |
| `testUnifiedCrafterStandardParity` | Asserts standard MI machines are wrapped directly via `StandardCrafterAdapter` |
| `testUnifiedCrafterModularDuckTyping` | Asserts duck-typed modular multiblock crafter components resolve correctly via `ModularCrafterAdapter` |

Tests use `@PrefixGameTestTemplate(false)` + `template="empty"` — no structure files needed.

## Conventions & gotchas

- **`localRuntime` not `runtimeOnly`**: Use `localRuntime` for optional test deps. `runtimeOnly` publishes the dep; `localRuntime` keeps it local.
- **Mixin config**: `miforeman.mixins.json` at `com.mervyn.miforeman.mixin`. Currently one accessor mixin, `CrafterComponentAccessor` (read access to `CrafterComponent.activeRecipe`). Add class refs here when adding mixins.
- **Config locations**: Common config via `ModContainer.registerConfig(ModConfig.Type.COMMON, ...)` in `MIForeman.java`; Client config via `ModContainer.registerConfig(ModConfig.Type.CLIENT, ...)` in `MIForemanClient.java`.
- **CurseMaven dependency** for MI (`build.gradle:162`): `implementation "curse.maven:modern-industrialization-405388:8439736"`. Not declared in `neoforge.mods.toml`.
- **Template expansion**: `neoforge.mods.toml` lives in `src/main/templates/META-INF/`. Expanded via Groovy `${property}` in `generateModMetadata`. `neoForge.ideSyncTask` ensures re-expansion on IDE sync.
- **Dimension-safe monitoring**: Machine links in `ProductionGoal` (`linkedMachines` / `rejectedMachines`), machine link history, network sync payloads, and `ServerMonitoringManager.TRACKERS` use `GlobalPos` end-to-end. `ProductionGoal.CODEC` maintains backward compatibility by decoding legacy bare `BlockPos` saves into Overworld `GlobalPos`. Stale trackers are pruned every 200 ticks against currently-linked machines.
- **StreamCodec & Codec Parity**: Any field added to `ProductionGoal` or sub-records must be synchronized across both `CODEC` and `STREAM_CODEC` (enforced by `testProductionGoalStreamCodecParity`).
- **Parchment mappings**: `parchment_mappings_version=2024.11.17` for `parchment_minecraft_version=1.21.1`.
- **Gradle 9.2.1** with BIN distribution (not ALL).
- **ProductionGoal rate** stored as per-minute; perHour toggle divides/multiplies by 60 for display.
- **Monitoring** keeps rolling 1-hour (72000-tick) energy event window per machine. Checks both hands for clipboard. Request interval: every 20 ticks (1 second).
- **`run/`, `.vscode/`, `.idea/`, `.run/`** in `.gitignore`.
