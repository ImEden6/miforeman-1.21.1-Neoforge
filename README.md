MI Foreman
==========

MI Foreman plans and monitors factories for [Modern Industrialization](https://www.curseforge.com/minecraft/mc-mods/modern-industrialization) on NeoForge 1.21.1. Set a production goal, get the machine list and a node graph for it, then link the real machines in your world and see which ones fall behind.

Features
--------
- **Production goals.** Pick a target item or fluid and a rate. The goal lives on a Foreman's Clipboard: hover it for a summary, use it to open the planner.
- **Recipe planning.** The clipboard works out the machines, counts and raw inputs from MI's recipes. Where several recipes make the same thing, you choose one and the plan updates.
- **EMI drag and drop.** With EMI installed, drag an item or fluid from EMI's sidebar onto the clipboard to make it the target.
- **Exact plans.** Recycling loops and byproducts are solved as a linear program. Byproducts get used before anything is imported, a recipe that makes two things the plan needs shares its machines, and a loop with no way in gets a recipe that feeds it, or its item is imported.
- **Capacity readout.** The plan summary shows the most your linked machines can make and which machines limit it, marked on the graph. It assumes base speed, loaded machines and enough raw input.
- **Node graph.** Machines and resources are drawn as cards with the flows between them. Rates show per minute or per hour, and node positions and groups are saved on the clipboard.
- **Auto-Arrange and groups.** One button lays out the whole graph. Select some nodes (ctrl or shift click, or drag a box) and group them, and Auto-Arrange moves the group as a block unless you let it rearrange inside groups too. Nodes can leave a group, groups can be dissolved, and a big rearrange undoes in one step.
- **Machine linking.** Right-click a placed MI machine with the clipboard to link or unlink it. Links are kept on the server, so multiplayer works the same, and every client holding that clipboard sees them.
- **Auto-detect scan.** Scan a chunk radius around you for machines running recipes from the plan, then accept or reject them from a list. You can change the radius for one scan, and new finds give off a short particle burst.
- **In-world highlighting.** Linked, candidate and selected machines are outlined. All the colours are configurable, with RGB, HSV or HSL sliders or a hex code.
- **Placing idle machines.** A linked machine that has never run can be put on its graph node by hand from the list in the graph's corner. Its first real craft replaces the placement.
- **Live monitoring.** Linked machines report input and output rates over a rolling window (an hour by default). The monitoring screen lists them as green, yellow, orange or red by how far they fall short of the plan.
- **Commands, development builds only.** `/miforeman goal create|print|plan|select` and `/miforeman recipes print` do the same goal and planning work from the console. They aren't registered in a normal install.

Dependencies
------------
- NeoForge (version pinned in [gradle.properties](gradle.properties))
- Minecraft 1.21.1
- [Modern Industrialization](https://www.curseforge.com/minecraft/mc-mods/modern-industrialization), required
- [EMI](https://www.curseforge.com/minecraft/mc-mods/emi), optional, for drag and drop targeting

Configuration
-------------
Server options, defined in [Config.java](src/main/java/com/mervyn/miforeman/Config.java):
- `autolinkScanRadiusChunks`: default scan radius in chunks, centred on the player.
- `autolinkScanMaxRadiusChunks`: the largest radius a player can pick for one scan. Lower it on busy servers.
- `monitoringWindowTicks`: how long each linked machine's rate history is.
- `trackerPruneIntervalTicks`: how often trackers for unlinked machines are cleaned up.
- `defaultEfficiencyThreshold`: the share of the expected rate below which a machine counts as underperforming.
- `includeProxiedRecipeTypes`: also read recipe types that build their recipes at runtime. Off by default, because some of MI's own types work this way and turning it on changes which recipes plans use.

Development
-----------
This project started from the NeoForge MDK template. Open it in IntelliJ IDEA or Eclipse. For missing libraries or dependency problems, run:

```bash
./gradlew --refresh-dependencies
```

`./gradlew clean` resets the build and leaves your code alone.

Mapping names
--------------
The project uses the official Mojang mapping names for Minecraft's methods and fields. They're under their own license, see the mapping file or the copy at https://github.com/NeoForged/NeoForm/blob/main/Mojang.md.

Additional resources
---------------------
- NeoForge documentation: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
