# MI Foreman
**RUP Inception Phase Document**
Version 1.0 | NeoForge 1.21.1

---

## 1. Vision Statement

MI Foreman is an in-game planning and monitoring tool for Modern Industrialization on NeoForge 1.21.1. It allows players to define production goals, automatically calculate machine and resource requirements, design factory layouts in a visual node graph, and monitor live factory performance to identify bottlenecks — all without leaving the game.

---

## 2. Use Cases

### UC1: Define Production Goal

**Summary:** Player specifies a target item and desired output rate.
**Actor:** Player
**Precondition:** Modern Industrialization is installed and loaded.

The player opens the MI Foreman interface and specifies a target item and a desired output rate. The mod records this as a named production goal, which serves as the baseline for all subsequent planning and monitoring use cases.

---

### UC2: Generate Machine Requirements

**Summary:** Mod traverses MI's recipe graph and calculates machines, quantities, and input rates.
**Actor:** Player
**Precondition:** A production goal exists (UC1).

Given a production goal, the mod traverses MI's recipe graph and calculates the required machines, their quantities, and raw input rates. Where multiple valid production paths exist for an intermediate item or fluid, the mod presents the options to the player before proceeding. Once all ambiguities are resolved, the final requirements are passed to UC3 for node graph generation.

---

### UC3: Design Node Graph

**Summary:** Mod auto-generates a node graph from UC2 requirements; player can adjust.
**Actor:** Player
**Precondition:** Machine requirements have been generated (UC2).

The mod auto-generates a suggested node graph from the requirements produced in UC2. The player can then manually adjust the graph.

**Nodes**
- Represent either a single machine instance or a grouped set (e.g. 4x Electric Blast Furnace), togglable per node.
- Input/output rates displayed inline on each node, with the time unit switchable between per-minute and per-hour.

**Edges**
- Represent specific item or fluid flows between nodes.
- A single output edge can split to multiple downstream nodes.

**Validation**
- Non-blocking. The mod warns the player when the graph does not satisfy the production goal but does not prevent saving or exiting.

**Persistence**
- The graph is stored on a special in-game item tied to the production goal. The item can be held, shared between players, and re-opened at any time.

---

### UC4: Monitor Live Factory

**Summary:** Mod attaches to real in-world machines and reads live input/output rates.
**Actor:** Player
**Precondition:** An active production goal exists (UC1).

The player manually links in-world machines to a production goal by right-clicking them with the goal item. Linked machines are tracked server-side, making this multiplayer compatible.

The mod samples machine input/output rates and averages them over a player-configurable window of either per-minute or per-hour. Instantaneous readings are not exposed.

Live data is displayed as an overlay on the node graph from UC3, shown top-right of each node. A dedicated monitoring screen is also available, switchable from the node graph view, which shows both the graph and a detailed rate breakdown side by side.

---

### UC5: Identify Bottlenecks

**Summary:** Mod flags underperforming nodes based on live data from UC4.
**Actor:** System (passive)
**Precondition:** Live monitoring is active (UC4).

Bottlenecks are flagged passively — only visible when the node graph or monitoring screen is open. No alerts are sent when the screen is closed.

Nodes are color coded to indicate their current state:

- **Green** — performing within the configured threshold of the production goal.
- **Yellow** — underperforming, below the player-configured threshold.
- **Red (starving)** — insufficient input rate reaching the machine.
- **Orange (saturating)** — output is backed up; machine cannot push items or fluids forward.

Starving and saturating are visually distinct states. Color coding appears both on the node graph overlay and the dedicated monitoring screen from UC4. The underperformance threshold is player-configurable, defined as a percentage of the expected rate from UC1.

---

## 3. Key Risks

- **Technical:** Can MI's recipe and machine data be read from NeoForge's recipe registry at runtime? Requires a feasibility spike before elaboration.
- **Sustainability:** The mod is tightly coupled to MI's internal data structures. MI version updates may break compatibility.
- **Scope:** All three phases (planning, layout, monitoring) in one mod represents a large project. Phased delivery is recommended.

---

## 4. Next Steps

- Perform feasibility spike: attempt to read MI recipe and machine stats from NeoForge's recipe registry.
- Based on spike results, move to elaboration phase for UC1 and UC2.
- Evaluate phased delivery: UC1–UC3 as v1.0, UC4–UC5 as v2.0.
