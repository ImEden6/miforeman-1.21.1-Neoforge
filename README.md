MI Foreman
==========

MI Foreman is an in-game planning and monitoring tool for [Modern Industrialization](https://www.curseforge.com/minecraft/mc-mods/modern-industrialization) on NeoForge 1.21.1. It lets players define production goals, auto-calculate the machines and resources needed, lay out factory designs in a visual node graph, and monitor live factory performance to spot bottlenecks — all without leaving the game.

Features
--------
- **Production goals** — specify a target item and desired output rate as a baseline for planning.
- **Machine requirement calculation** — traverses MI's recipe graph to work out required machines, quantities, and input rates, prompting the player when multiple valid recipe paths exist.
- **Node graph editor** — auto-generates an editable graph of machines and item/fluid flows, persisted on an in-game item that can be shared between players.
- **Live monitoring** — link real in-world machines to a goal and track averaged input/output rates over a configurable window.
- **Bottleneck detection** — passively flags underperforming nodes while the graph or monitoring screen is open.

Dependencies
------------
- NeoForge (see [gradle.properties](gradle.properties) for the pinned version)
- Minecraft 1.21.1
- [Modern Industrialization](https://www.curseforge.com/minecraft/mc-mods/modern-industrialization) (required)
- [EMI](https://www.curseforge.com/minecraft/mc-mods/emi) (optional, for drag-drop recipe picking)

Development
-----------
This project is built from the NeoForge MDK template. Open it in IntelliJ IDEA or Eclipse, and if you run into missing libraries or dependency issues, run:

```bash
./gradlew --refresh-dependencies
```

`./gradlew clean` resets the build without affecting your code.

Mapping Names
--------------
By default, the project uses the official Mojang mapping names for methods and fields in the Minecraft codebase. These names are covered by a specific license — see the mapping file itself or the reference copy at https://github.com/NeoForged/NeoForm/blob/main/Mojang.md.

Additional Resources
---------------------
- NeoForge Documentation: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
