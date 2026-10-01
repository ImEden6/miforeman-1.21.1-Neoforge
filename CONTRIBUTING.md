# CONTRIBUTING.md — MI Foreman

## Quickstart

```powershell
./gradlew runClient                  # Minecraft client with mod
./gradlew runServer                  # Dedicated server (nogui)
./gradlew runGameTestServer          # All game tests, then exit
./gradlew build -x test             # Mod JAR at build/libs/miforeman-<mod_version>.jar
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
| GUI widgets | `client/gui/widget/GraphCanvas.java`, `GraphCamera.java` | Interactive pan/zoom DAG canvas rendering recipe nodes, transitive edge bridging, and right-click visibility toggle |
| | `client/gui/widget/GraphSearchBar.java`, `GraphSearchState.java` | Floating search overlay widget, query matching, match cycling, and camera centering |
| | `client/gui/widget/HiddenNodesDrawer.java` | Slide-out drawer widget on left side of canvas displaying and unhiding hidden nodes |
| | `client/gui/widget/DetailCard.java` | Contextual inspector card for selected node, material flows, and ambiguity cycling |
| | `client/gui/widget/MonitoringListPanel.java`, `ReviewListPanel.java` | Scrollable panels for machine lists, status badges, and batch actions |
| Goal model | `goal/ProductionGoal.java` | Immutable record with `CODEC`/`STREAM_CODEC` — target, rate, plan, layout, history, uiState |
| Recipe traversal | `goal/RecipeGraphTraverser.java` | Recursive BFS through MI recipe graph; cycle handling, ambiguity resolution, DAG construction |
| Graph model | `goal/RecipeGraph.java`, `RecipeGraphNode.java`, `NodeType.java`, `GraphEdge.java` | DAG data model for factory planning & node canvas |
| Edge routing | `goal/EdgeRouter.java` | Grid-based A* wire router for graph edges: routes around node boxes, bundles wires into shared lanes, falls back to the old elbow when a wire can't be solved or the pass runs out of budget |
| Crafter adapter | `goal/UnifiedCrafter.java` | Unified crafter abstraction supporting base MI `CrafterComponent` and duck-typed modular multiblock crafters |
| Machine scanner | `goal/MachineScanner.java` | Server-side sphere/area scanning for unlinked MI & custom multiblock machines matching active graph |
| State tracking & sync | `goal/MachineLinkHistory.java`, `ClipboardUiState.java`, `GraphLayoutState.java`, `ClipboardCloseSync.java` | Persistence & undo/redo tracking for machine links, canvas positions, hidden nodes, and UI state |
| Server monitoring | `goal/ServerMonitoringManager.java` | Server-side tracker & `@SubscribeEvent` tick handler — tracks `UnifiedCrafter` energy, status (GREEN/YELLOW/ORANGE/RED) |
| Commands | `command/ForemanCommands.java` | `/miforeman goal create\|print\|plan\|select` and `/miforeman recipes print`. Registration is skipped when `FMLEnvironment.production` is set, so these exist in dev only |
| Packets | `network/*.java` | 6 network packets for client-server communication (see below) |
| Rate limiting | `network/PacketRateLimiter.java` | Server-side rate limiter guarding network payloads |
| Mixins | `mixin/CrafterComponentAccessor.java` | Accessor mixin for `CrafterComponent.activeRecipe` |
| Game tests | `test/ForemanGameTests.java` | 56 `@GameTest`s verifying core logic (see below) |

### Network packets (registered in `MIForeman.java:93-118`)

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
| `testUnifiedCrafterDirectModularDuckTyping` | Asserts duck-typed modular multiblock crafters resolve when methods reside directly on component |
| `testGraphSearchMatchingLogic` | Search matching against formatted display names, namespace IDs, case-insensitivity, and empty queries |
| `testGraphSearchMatchCycling` | Match selection indexing, forward/backward navigation, and cyclic wrap-around boundaries |
| `testGraphCameraCenteringMath` | `GraphCamera.centerOn` coordinate calculations across zoom levels and canvas viewport dimensions |
| `testGraphLayoutStateHiddenNodes` | Hidden node set immutability, single/batch toggling, and unhide-all reset |
| `testMaterialCandidateRecipesAndExpansion` | Candidate recipe resolution for single items and plan generation |
| `testStyreneButadieneRubberGraphTraversal` | Multi-tier fluid synthesis DAG traversal, intermediate fluid node generation, and dynamic selection expansion |
| `testClipboardUiStateStreamCodecParity` | `ClipboardUiState.STREAM_CODEC` round-trips every field directly |
| `testGraphLayoutStateStreamCodecParity` | `GraphLayoutState.STREAM_CODEC` round-trips groups and batched history |
| `testLiveMonitoringPayloadStreamCodecParity` | `LiveMonitoringPayload.STREAM_CODEC` round-trips every field, including `reason` and `disposalRatio` |
| `testGraphLayoutStateHistoryMigration` | Pre-`HistoryEntry` saves decode: bare `NodeMoveAction` items become single-move batches |
| `testGraphLayoutStateRemoveFromGroup` | Removing a node keeps remaining members; group dissolves below two members |
| `testGraphLayoutEngineDeterministicAndRigidGroupTranslation` | `GraphLayoutEngine.arrange` is deterministic and preserves member offsets when moving locked groups |
| `testGraphLayoutEngineRearrangeInsideGroups` | `arrange(rearrangeInsideGroups=true)` columns members by depth without coordinate drift |
| `testRecipeGraphCyclicResourceIdsCaptured` | Recycling-loop membership on real recipe data; iron_plate's iron_ingot/nugget 2-cycle as a snapshot |
| `testPeekCyclicResourceIdsCacheOnly` | `peekCyclicResourceIds` never forces a compute, and matches the real graph once one exists |
| `testUnionCyclicResourceIdsAcrossGoals` | A machine shared by two goals is cyclic if either goal's graph says so, order-independently |
| `testRecipeResourceIdsCollectsInputsAndOutputs` | The shared recipe walk behind `recipeTouchesCycle`, cross-checked against real graph edges |
| `testCollectUpstreamResourceIds` | "Search by end product" collects resources between machine and target, excluding MACHINE ids |
| `testCollectByproductRates` | Byproduct rates are positive, never already-demanded resources, and non-empty for complex chains |
| `testClassifyLiveStatusDeadLoopReason` | `classifyLiveStatus` separates DEAD_LOOP from NONE and DISPOSAL_THROTTLED shortfalls |
| `testComputeDisposalRatioUsesRealCapacityNotAdjustedCapacity` | Disposal ratio uses real stack-size-clamped capacity, so a full non-stackable output flags correctly |
| `testRecordActiveRecipeNeverGoesStale` | `lastKnownRecipeId` always reflects the latest recipe a machine ran |
| `testResolveDisplayRecipeIdFallsBackToLastKnown` | Fallback chain lastRecipeId -> saturatedRecipeId -> lastKnownRecipeId, so a RED machine still reports a recipe |
| `testMachineRecipeHistoryRestoresPrunedTracker` | Saved recipe history survives save/load, restores a pruned or post-restart tracker, never overrides live history, and drops an entry the machine at that spot can no longer run |
| `testUpdateMachineRestoresAndForgetsRecipeHistory` | On a real placed compressor, the per-tick update restores a pruned tracker's saved recipe, rejects a saved recipe of another machine type, and forgets the entry once the machine is broken |
| `testScanPayloadsCarryRadius` | Scan request and result payloads round-trip their radius with no leftover bytes |
| `testScanStepperAndNewCandidates` | The radius stepper starts from the right base and stays in 1-16, a capped pick shows the capped value, and only machines no link or earlier scan knew about get pinged |
| `testMachinePlacementClassifyAndCompatibleNodes` | Linked machines sort into unplaced / placed by hand / off-plan / on graph (an idle machine with only old off-plan history counts as unplaced), and only nodes of the machine's own recipe type are offered for placement (none when the type is unknown) |
| `testRecipeTypeIdOfRealMachineMatchesGraphNodes` | The recipe type read off a real placed compressor matches the machine type on iron_plate's compressor nodes, which the placement filter depends on |
| `testResolveDisplayRecipeAssignmentPrecedence` | A never-run machine shows on its assigned node flagged as assigned; a live recipe or on-plan history always beats the placement, while off-plan history from another build doesn't |
| `testMachineAssignmentsPersistAndFollowLinks` | Assignments survive the NBT and stream codecs, old clipboards load with none, unlinking drops the machine's assignment, unplacing removes it |
| `testScanRadiusOverrideClampAndLinkValidation` | A per-scan radius pick resolves the default and is clamped to the admin cap; link validation accepts anything the widest allowed scan can find, never tighter than the default |
| `testRecipeLiveSummaryWorstStatusAndRunningCount` | Several machines on one recipe fold into the worst status plus a running/total count; machines with no recipe id are left out |
| `testSearchStateGenericOverArbitraryId` | `SearchState` works over a non-`ResourceLocation` ID type with a hand-built text map |
| `testDisplayFormatFormatRate` | `DisplayFormat.formatRate` output, extracted from `DetailCard` |
| `testEdgeRouterRoutesAroundBlockingCard` | A wire routes around a card sitting between its endpoints, where the baseline elbow cuts through |
| `testEdgeRouterKeepsClearanceUnderLanePacking` | No wire clips a card on a grid of cards with lane packing on (packing used to shift wires into cards) |
| `testEdgeRouterIsDeterministic` | Routing the same input twice gives identical wires, so redraws can't make them jitter |
| `testEdgeRouterProducesCleanOrthogonalPolylines` | Wires meet their ports exactly, every segment is axis-aligned, no zero-length segments |
| `testEdgeRouterFallsBackWhenBoxedIn` | An unroutable wire returns a flagged elbow rather than vanishing |
| `testEdgeRouterPacksSharedLanesApart` | Two wires contending for one corridor get separate lanes instead of one overlapping line |
| `testEdgeRouterRejectsNonPositiveGridSize` | A zero or negative grid size throws instead of failing somewhere inside the search |
| `testEdgeRouterOnRealArrangedGraph` | A real arranged analog_circuit graph routes most wires with no clipping |
| `testPortLayoutSpreadsSharedFaces` | Wires sharing a card face get separate ports ordered by their other end's height, machine ports stay clear of the cut corners, overflow shares slots in order, lone wires stay centred |
| `testEdgeRouterOnRealGraphWithSpreadPorts` | analog_circuit routed with spread ports: no clipping, most wires route, and only over-capacity faces share a start point |
| `testEdgeRouterIncrementalMatchesContract` | Re-routing with the previous pass keeps every unaffected wire identical, re-routes moved cards' wires at a fraction of a full pass's cost, refuses to reuse a wire whose stub or path a new card now covers, and retries a fallback once its blocker moves |
| `testEdgeRouterIncrementalDoesNotDrift` | Five chained incremental re-routes on a real graph keep every wire ending at its ports, clear of every card, with most still routed |
| `testEdgeRouterHopsOncePerCrossing` | Only the wire drawn later bumps at a crossing; no bumps near corners, on stubs or on parallel overlaps; crossings closer than one bump merge |
| `testEdgeRouterHopsStayInsideSegments` | On a real routed graph every bump sits on an interior segment and inside its ends (caught a crash on segments shorter than two margins) |
| `testEdgeRouterGivesUpWholeGraphWhenBudgetExhausted` | A pass that runs out of budget falls back wholesale, never partially |
| `testEdgeRouterHandlesLargestRealGraph` | quantum_upgrade (588 cards, 864 wires) still routes, with no wire clipping a card |

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
