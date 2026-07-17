# AGENTS.md — MI Foreman

## Quickstart

```powershell
./gradlew runClient                  # Launch Minecraft client with mod
./gradlew runServer                  # Launch dedicated server (nogui)
./gradlew runGameTestServer          # Run all game tests, then exit
./gradlew runData                    # Run data generators (output → src/generated/resources/)
./gradlew build                      # Build mod JAR
./gradlew --refresh-dependencies     # Force refresh all Gradle dependencies
```

## Architecture

**MI Foreman** is a NeoForge 1.21.1 mod (`miforeman`) that plans and monitors factories for the Modern Industrialization (MI) mod. A player gets a **Foreman's Clipboard** item, sets a production goal (target item/fluid + desired rate), and the mod calculates a factory plan by traversing MI's recipe graph. It then monitors linked machines in real time.

### Package map (`src/main/java/com/mervyn/miforeman/`)

| Entrypoint | File | What it does |
|---|---|---|
| Mod main | `MIForeman.java` | `@Mod("miforeman")` — registers everything, config, commands, packets, game tests |
| Client | `MIForemanClient.java` | `@Mod(value="miforeman", dist=Dist.CLIENT)` — registers config screen |
| Config | `Config.java` | `ModConfigSpec` (COMMON side) — logDirtBlock, magicNumber, items list |
| Items | `registry/ModItems.java` | `DeferredRegister.Items` — only one item: `foreman_clipboard` (stacks to 1) |
| Components | `registry/ModComponents.java` | `DeferredRegister.DataComponents` — `production_goal` component holding a `ProductionGoal` |
| Clipboard item | `item/ForemanClipboardItem.java` | Right-click machine → link/unlink; right-click air → open GUI (client) |
| GUI screen | `client/gui/ClipboardScreen.java` | Full-screen editor: input fields, recipe ambiguity buttons, live plan summary, machine monitoring |
| Client access | `client/ClientAccess.java` | Thin static bridge to open screen / receive monitoring data from network |
| Goal model | `goal/ProductionGoal.java` | Record with CODEC/STREAM_CODEC — name, type, target, rate, selections, plan, per_hour, threshold, linked machines |
| Recipe traversal | `goal/RecipeGraphTraverser.java` | Recursive BFS through MI recipe graph; handles cycles, ambiguity resolution, material flow scaling |
| Server monitoring | `goal/ServerMonitoringManager.java` | Per-tick `@SubscribeEvent` on `ServerTickEvent.Post` — tracks CrafterComponent energy, computes actual rates, status (GREEN/YELLOW/ORANGE/RED) |
| Commands | `command/ForemanCommands.java` | `/miforeman goal create|print|plan|select` and `/miforeman recipes print` |
| Packets | `network/*.java` | 4 packets: `GoalUpdatePayload` (C→S save goal), `RequestMonitoringUpdatePayload` (C→S ask status), `LiveMonitoringPayload` (S→C machine data), `GoalUpdateHandler`/`MonitoringPacketHandlers` server logic |
| Game tests | `test/ForemanGameTests.java` | 4 `@GameTest`s: component serialization, MI recipe reading, quantum_upgrade plan, bottleneck detection |
| Mixin config | `miforeman.mixins.json` | Declared but **empty** — no mixins currently used |

### Network packet registration

Done in `MIForeman.java` via `RegisterPayloadHandlersEvent`. Three packet types:
- `goal_update` (playToServer) — save goal + recompute plan
- `request_monitoring_update` (playToServer) — request live data from linked machines
- `live_monitoring` (playToClient) — server response with per-machine actual rates

### Composite build dependency

MI is included as a **composite Gradle build**:

```
// settings.gradle
includeBuild('references/Modern-Industrialization-1.21.x')

// build.gradle
implementation 'aztech:Modern-Industrialization:0.0.0-local'
```

The MI source lives at `references/Modern-Industrialization-1.21.x/`. You can edit it there and changes are picked up without republishing. Version `0.0.0-local` is set when env var `MI_VERSION` is absent.

### Reflection usage

`ServerMonitoringManager.java` uses `java.lang.reflect.Field` to access `CrafterComponent.activeRecipe` (a private field):

```java
ACTIVE_RECIPE_FIELD = CrafterComponent.class.getDeclaredField("activeRecipe");
ACTIVE_RECIPE_FIELD.setAccessible(true);
```

This is fragile across MI versions. Guarded by try/catch with error log.

### Template expansion

`neoforge.mods.toml` lives in `src/main/templates/META-INF/`. Properties are expanded via Groovy `${property}` in `build.gradle:generateModMetadata`. The `neoForge.ideSyncTask` ensures templates re-expand on IDE sync.

### Datagen

`src/generated/resources/` is an input resource directory (declared in `build.gradle:20`). Currently **empty**. To generate: `./gradlew runData`. The datagen run config is already wired:
```
programArguments = ['--mod', 'miforeman', '--all', '--output', '.../src/generated/resources/', '--existing', '.../src/main/resources/']
```

## Development commands

### Run the game

```powershell
./gradlew runClient                           # Client (default, connect to local server)
./gradlew runServer                           # Headless server
```

### Game tests

```powershell
./gradlew runGameTestServer                   # All game tests
# Tests use @PrefixGameTestTemplate(false) + template="empty", so no structure file needed
```

Tests are in `test/ForemanGameTests.java`:
- `testProductionGoalComponent` — saves/retrieves a ProductionGoal via DataComponents
- `testReadMIRecipes` — fetches all MI MachineRecipe types/recipes from registry (fails if MI not loaded)
- `testGenerateRequirementsComplex` — traverses quantum_upgrade recipe graph, asserts ≥210 assemblers, exact raw input rates
- `testIdentifyBottlenecks` — places a bronze_compressor, tests RED/ORANGE status logic

### Data generation

```powershell
./gradlew runData
# Output goes to src/generated/resources/
# Existing resources are read from src/main/resources/
```

### Build

```powershell
./gradlew build
# JAR at build/libs/miforeman-1.0.0.jar
```

### Refresh dependencies

```powershell
./gradlew --refresh-dependencies
```

## Key conventions (repo-specific)

- **`localRuntime` not `runtimeOnly`**: Use `localRuntime` for optional test dependencies (declared in `build.gradle:151-153`). `runtimeOnly` would publish the dependency; `localRuntime` keeps it local.
- **Mixin config exists but no mixins**: The `miforeman.mixins.json` file declares the `com.mervyn.miforeman.mixin` package but the `"mixins": []` list is empty. If adding a mixin, add the class reference here.
- **No IntelliJ `.run/` in version control**: Listed in `.gitignore`.
- **No `.vscode/` in version control**: Also in `.gitignore`.
- **Generated `.cache/` excluded**: `**/src/generated/**/.cache/` in `.gitignore`.
- **BlockBench `.bbmodel` files excluded** from final JAR (filtered in `build.gradle:25`).
- **Template files use Groovy `${}`** syntax, **not** Jinja or Mustache.
- **Config is COMMON side**: Uses `ModConfigSpec` via `ModContainer.registerConfig(ModConfig.Type.COMMON, ...)`.
- **Parchment mappings**: `parchment_mappings_version=2024.11.17` for `parchment_minecraft_version=1.21.1`.
- **Gradle 9.2.1** with BIN distribution (not ALL — no Gradle source docs in IDE).

## Resource asset structure

```
src/main/resources/assets/miforeman/
├── lang/en_us.json                       # Translations for items + config
├── models/item/foreman_clipboard.json    # Item model
├── textures/
│   ├── gui/
│   │   ├── clipboard_main.png            # 9-slice main background (64x64, border 6)
│   │   ├── clipboard_side_panel.png      # 9-slice left panel (48x48, border 4)
│   │   ├── clipboard_node_canvas.png     # 9-slice right panel (48x48, border 4)
│   │   ├── clipboard_clip.png            # Top accent (32x16, non-sliced)
│   │   └── clipboard_button.png          # Button texture (unused in current code)
│   └── item/foreman_clipboard.png        # Item icon
```

GUI textures use 9-slice rendering (`blitNineSlice` in `ClipboardScreen.java:444-469`). The center tiles rather than stretches (`blitStretched`).

## Important constraints

- MI Foreman **requires** Modern Industrialization at runtime (dependency is implicit via composite build, not declared in `neoforge.mods.toml`).
- The `foreman_clipboard` item has `stacksTo(1)`.
- `ProductionGoal` rate is stored internally as **per minute**; the perHour toggle in the GUI divides/multiplies by 60 for display.
- The `ServerMonitoringManager` keeps a rolling 1-hour (72000-tick) energy event window per machine.
- Server-side monitoring in `MonitoringPacketHandlers` checks **both hands** for the clipboard item.
- Live monitoring request interval: every 20 ticks (1 second) from `ClipboardScreen.tick()`.

## In-flight / known state

- `HANDOFF.md` exists — records a previous session that cleaned up boilerplate, generated mod logos, and identified the need for custom GUI textures.
- Mod logos: `icon.png` at both project root and `src/main/resources/icon.png`.
- `logoFile="icon.png"` in `neoforge.mods.toml`.
- Clipboard GUI textures exist but `clipboard_button.png` is unused in current code.
- No data generators have been run yet (no existing content in `src/generated/resources/`).
- No `.github/workflows/` files present.
- Mod version is `1.0.0`.
