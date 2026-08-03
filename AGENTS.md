# AGENTS.md — MI Foreman

## Quickstart

```powershell
./gradlew runClient                  # Minecraft client with mod
./gradlew runServer                  # Dedicated server (nogui)
./gradlew runGameTestServer          # All game tests, then exit
./gradlew runData                    # Datagen → src/generated/resources/
./gradlew build                      # Mod JAR at build/libs/miforeman-1.0.0.jar
./gradlew --refresh-dependencies     # Force refresh all Gradle deps
```

## Architecture

**MI Foreman** (`miforeman`) is a NeoForge 1.21.1 mod. Player holds a **Foreman's Clipboard** item, sets a production goal (item/fluid + desired rate), and the mod traverses MI's recipe graph to compute a factory plan. Linked machines are monitored in real time.

### Package map (`src/main/java/com/mervyn/miforeman/`)

| Entrypoint | File | What it does |
|---|---|---|
| Mod main | `MIForeman.java` | `@Mod("miforeman")` — registers items, components, commands, packets, game tests |
| Config | `Config.java` | `ModConfigSpec` (COMMON) — still uses template values (logDirtBlock, magicNumber) |
| Items | `registry/ModItems.java` | `DeferredRegister.Items` — one item: `foreman_clipboard` (stacksTo(1)) |
| Components | `registry/ModComponents.java` | `DeferredRegister.DataComponents` — `production_goal` component |
| Clipboard item | `item/ForemanClipboardItem.java` | Right-click machine → link/unlink; right-click air → open GUI (client) |
| GUI screen | `client/gui/ClipboardScreen.java` | Full-screen 3-step wizard: review plan, inputs, monitoring |
| Widgets | `client/gui/widget/TreePanel.java`, `DetailCard.java` | Split-panel tree + detail card for Step 1 |
| Client access | `client/ClientAccess.java` | Static bridge to open screen / receive monitoring data |
| Goal model | `goal/ProductionGoal.java` | Record with CODEC/STREAM_CODEC — name, type, target, rate, selections, plan, per_hour, threshold, linked machines |
| Recipe traversal | `goal/RecipeGraphTraverser.java` | Recursive BFS through MI recipe graph; cycles, ambiguity resolution, material flow scaling |
| Graph model | `goal/RecipeGraph.java`, `RecipeGraphNode.java`, `NodeType.java`, `GraphEdge.java` | DAG data model for UI rendering and future interactive node graph |
| Server monitoring | `goal/ServerMonitoringManager.java` | Per-tick `@SubscribeEvent` — tracks CrafterComponent energy, status (GREEN/YELLOW/ORANGE/RED) |
| Commands | `command/ForemanCommands.java` | `/miforeman goal create\|print\|plan\|select` and `/miforeman recipes print` |
| Packets | `network/*.java` | 3 packets: `GoalUpdatePayload` (C→S), `RequestMonitoringUpdatePayload` (C→S), `LiveMonitoringPayload` (S→C) |
| Game tests | `test/ForemanGameTests.java` | 5 `@GameTest`s (see below) |

### Network packets (registered in `MIForeman.java:91-108`)

- `goal_update` (playToServer) — save goal + recompute plan
- `request_monitoring_update` (playToServer) — request live data
- `live_monitoring` (playToClient) — per-machine actual rates

## Development commands

### Run / test / datagen

```powershell
./gradlew runClient                           # Client
./gradlew runServer                           # Headless server
./gradlew runGameTestServer                   # All game tests, then exit
./gradlew runData                             # Datagen (output: src/generated/resources/)
./gradlew build                               # Build JAR
```

### Game tests (`ForemanGameTests.java`)

| Test | What it verifies |
|---|---|
| `testProductionGoalComponent` | Save/retrieve ProductionGoal via DataComponents |
| `testReadMIRecipes` | Fetches all MI MachineRecipe types from registry |
| `testGenerateRequirementsComplex` | Quantum_upgrade graph: ≥210 assemblers, exact raw rates |
| `testIdentifyBottlenecks` | Bronze_compressor RED/ORANGE status logic |
| `testCycleRecipePlan` | Ambiguity cycling recomputes graph correctly |

Tests use `@PrefixGameTestTemplate(false)` + `template="empty"` — no structure files needed.

## Conventions & gotchas

- **`localRuntime` not `runtimeOnly`**: Use `localRuntime` for optional test deps. `runtimeOnly` publishes the dep; `localRuntime` keeps it local.
- **Mixin config exists but empty**: `miforeman.mixins.json` at `com.mervyn.miforeman.mixin`. `"mixins": []`. Add class refs here when adding mixins.
- **Config is COMMON side**: `ModContainer.registerConfig(ModConfig.Type.COMMON, ...)`.
- **CurseMaven dependency** for MI (`build.gradle:162`): `implementation "curse.maven:modern-industrialization-405388:8439736"`. Not declared in `neoforge.mods.toml`.
- **Template expansion**: `neoforge.mods.toml` lives in `src/main/templates/META-INF/`. Expanded via Groovy `${property}` in `generateModMetadata`. `neoForge.ideSyncTask` ensures re-expansion on IDE sync.
- **Datagen output dir**: `src/generated/resources/` is declared as an input in `sourceSets.main.resources` but may not exist yet. Generate with `./gradlew runData`.
- **Reflection**: `ServerMonitoringManager.java:60-70` uses `java.lang.reflect.Field` to access `CrafterComponent.activeRecipe`. Fragile across MI versions.
- **Parchment mappings**: `parchment_mappings_version=2024.11.17` for `parchment_minecraft_version=1.21.1`.
- **Gradle 9.2.1** with BIN distribution (not ALL).
- **ProductionGoal rate** stored as per-minute; perHour toggle divides/multiplies by 60 for display.
- **Monitoring** keeps rolling 1-hour (72000-tick) energy event window per machine. Checks both hands for clipboard. Request interval: every 20 ticks (1 second).
- **`.bbmodel` files** excluded from JAR (`build.gradle:25`).
- **`run/`, `.vscode/`, `.idea/`, `.run/`** in `.gitignore`.
- **Generated `.cache/` excluded**: `**/src/generated/**/.cache/` in `.gitignore`.

## Reference docs

- `plan.md` — implementation plan for RecipeGraph data model + Hybrid Split Panel UI (partially done)
- `HANDOFF.md` — previous session notes
- `references/MI_Foreman_Inception.md` — original design document
- `IMPLEMENTATION-PLAN-UI-WIZARD.md` (if it exists at root)