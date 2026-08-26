MI Foreman
==========

MI Foreman is an in-game planning and monitoring tool for [Modern Industrialization](https://www.curseforge.com/minecraft/mc-mods/modern-industrialization) on NeoForge 1.21.1. Define a production goal, get a machine list and node graph for it, then link real machines in your world so the mod can tell you when they're underperforming.

Features
--------
- **Production goals.** Set a target item or fluid and a desired rate. The goal lives on a Foreman's Clipboard item, which shows the goal in its tooltip and opens a full planning UI on use.
- **Recipe planning.** The clipboard walks MI's recipe graph to work out the machines, quantities, and raw inputs needed. Where more than one recipe can produce an intermediate, you pick which one and the plan recalculates around it.
- **EMI drag-drop targeting.** If EMI is installed, you can drag an item or fluid straight from EMI's sidebar onto the clipboard to set it as your goal target, instead of typing a resource ID.
- **Node graph editor.** The plan renders as a graph of machine and resource nodes with the flows between them. Nodes can be grouped or split, rates switch between per-minute and per-hour, and layout edits (node positions, grouping) persist on the clipboard.
- **Machine linking.** Right-click a placed MI machine with the clipboard to link or unlink it from the goal. Links are tracked server-side, so they work the same in multiplayer, and synced back to every client holding that clipboard.
- **Auto-detect scan.** Instead of clicking every machine by hand, scan a chunk radius around the player for machines whose active recipe matches the plan, then review and accept or reject candidates from a dedicated list screen.
- **In-world highlighting.** Linked, candidate, and selected machines get an on-screen block highlight, with configurable colors (RGB, HSV, or HSL sliders, or a raw hex code).
- **Live monitoring.** Linked machines report input/output rates averaged over a rolling window (default one hour, configurable). A monitoring screen lists every linked machine with a status of green, yellow, orange, or red based on how far its actual rate has fallen from the plan's expected rate, so a struggling machine stands out without you needing to watch it directly.
- **Commands.** `/miforeman goal create|print|plan|select` and `/miforeman recipes print` cover the same goal and planning operations from the console, for scripting or debugging without opening a GUI.

Dependencies
------------
- NeoForge (see [gradle.properties](gradle.properties) for the pinned version)
- Minecraft 1.21.1
- [Modern Industrialization](https://www.curseforge.com/minecraft/mc-mods/modern-industrialization), required
- [EMI](https://www.curseforge.com/minecraft/mc-mods/emi), optional, enables drag-drop goal targeting

Configuration
-------------
Server-side config options (see [Config.java](src/main/java/com/mervyn/miforeman/Config.java)):
- `autolinkScanRadiusChunks`: default radius for the auto-detect scan, overridable per-scan in the review panel.
- `monitoringWindowTicks`: length of the rolling rate-history window per linked machine.
- `trackerPruneIntervalTicks`: how often stale machine trackers get cleaned up.
- `defaultEfficiencyThreshold`: ratio of actual to expected rate below which a machine is flagged as underperforming.
- `includeProxiedRecipeTypes`: also index recipe types that build their recipe list at runtime instead of through the vanilla recipe manager. Off by default because it changes which recipes plans consider even without addons installed, since some of MI's own recipe types work this way.

Development
-----------
This project is built from the NeoForge MDK template. Open it in IntelliJ IDEA or Eclipse. If you run into missing libraries or dependency issues, run:

```bash
./gradlew --refresh-dependencies
```

`./gradlew clean` resets the build without affecting your code.

Mapping names
--------------
By default, the project uses the official Mojang mapping names for methods and fields in the Minecraft codebase. These names are covered by a specific license. See the mapping file itself or the reference copy at https://github.com/NeoForged/NeoForm/blob/main/Mojang.md.

Additional resources
---------------------
- NeoForge documentation: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
