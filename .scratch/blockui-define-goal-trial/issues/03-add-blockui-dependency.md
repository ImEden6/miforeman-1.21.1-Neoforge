Type: task
Status: resolved

## Question

Add BlockUI as a compile/runtime dependency to `build.gradle` and confirm the project still resolves and compiles with it present — a prerequisite task, nothing to decide.

CurseForge project id for BlockUI: **522992** (verified directly from the project's CurseForge page). A current NeoForge 1.21.1 release build is `blockui-1.0.205-1.21.1.jar`, file id `6646615` (non-snapshot, uploaded June 2025) — pin to that file id or a newer release-labeled (not snapshot) file if one has since shipped.

Steps:
1. Add `implementation "curse.maven:blockui-522992:<fileId>"` to the `dependencies` block in `build.gradle`, alongside the existing `curse.maven:modern-industrialization-405388:8439736` line (`build.gradle:162`) — same `cursemaven` repo already configured at `build.gradle:67`.
2. Run `./gradlew compileJava` (or a full build) and confirm it resolves and compiles clean, same bar used elsewhere in this repo.
3. Note whether BlockUI pulls in any transitive dependencies that need their own repo/exclusion (Structurize does **not** appear to be one — see prior conversation finding that Structurize depends on BlockUI, not the reverse — but confirm nothing else surprising comes along).

## Answer

Checked CurseForge at resolution time and a newer non-snapshot release had shipped since the ticket was written (`blockui-1.0.209-1.21.1.jar`, file id `7541336`, Jan 2026) — used that instead of the `6646615`/1.0.205 build noted in the question.

Added `implementation "curse.maven:blockui-522992:7541336"` to `build.gradle`'s `dependencies` block, next to the existing `curse.maven:modern-industrialization-405388` line (same `cursemaven` repo, already configured).

Verified:
- `./gradlew dependencies --configuration runtimeClasspath` shows `curse.maven:blockui-522992:7541336` resolved with **no transitive dependencies of its own** — confirms the earlier finding that Structurize depends on BlockUI, not the reverse; nothing extra came along.
- `./gradlew clean compileJava` — `BUILD SUCCESSFUL`, compiled against the classpath including the new jar (cache-keyed on classpath contents, not a stale skip).

No blockers, no new repo needed, no exclusions needed.

**Correction (found via real-client crash, not caught by anything above):** plain `implementation` makes a dependency available to compile against and to this project's own dev-run tasks (`compileJava`, `runGameTestServer`), but does **not** bundle it into the shipped mod jar, and BlockUI isn't installed as a separate mod in real player instances the way Modern Industrialization is expected to be. First real-client test crashed with `NoClassDefFoundError: com/ldtteam/blockui/views/BOWindow` (`ClassNotFoundException` underneath) the moment `openDefineGoalWindow()` ran — see crash report referenced in this session. `./gradlew dependencies`/`compileJava`/`runGameTestServer` all use the dev-environment classpath, which includes resolved dependencies regardless of how they're bundled for shipping, so none of them could have caught this.

Fixed by switching to JarJar embedding, since BlockUI is a small library with zero transitive dependencies (confirmed above) and it's not reasonable to ask players to install a whole second mod for one wizard step, unlike MI which this mod is built around:

```gradle
jarJar(implementation("curse.maven:blockui-522992:7541336"))
```

(Bare `jarJar("...")` alone doesn't add it to the compile classpath — `jarJar` and `implementation` are separate concerns; wrapping `implementation(...)` inside `jarJar(...)` does both, per NeoForged's ModDevGradle docs.)

Verified by building the real jar and inspecting it directly, not just trusting the task succeeding:
- `./gradlew build` — `:jarJar` task succeeds (one informational warning about potential runtime conflicts if another installed mod also embeds BlockUI from a different source; not applicable to this instance's current mod list).
- `unzip -l build/libs/miforeman-1.0.0.jar` shows `META-INF/jarjar/blockui-522992-7541336.jar` (468KB) + `metadata.json` present, with a valid synthesized version range (`[7541336,)`) — no manual version-range block was needed despite CurseMaven's file-id-as-version scheme.
