# Lathe — Build-Tool-Agnostic Core

## Status

**Proposed.** A prerequisite for [Gradle Support](lathe-gradle-support.md), done as its own step before any Gradle code.
It also benefits [OpenJDK Support](lathe-openjdk-support.md), which today works around the same gaps with `externalOutput`.

## Why

Lathe's design says `.lathe/` is the seam between the build and everything else:
the server, `lathe-test-runner`, the MCP server, and the editor clients read `.lathe/` and never the build.
Two shipped changes already moved the biggest pieces behind that seam:

- **Sync** runs `.lathe/lathe-sync.sh`, written by the build integration ([Sync Launcher](lathe-sync-launcher.md)).
  No client, server, or MCP code composes a Maven command any more.
- **Formatting** runs the project's pinned formatter in-process from `.lathe/style.json` ([Pinned Formatters](../done/lathe-pinned-formatters.md)).
  The server no longer delegates to `mvn spotless:apply`.

An audit on 2026-10-10 (while reviewing the Gradle design) found what is still Maven-shaped downstream.
One item is behavioral and would make a second front-end produce wrong results; the rest is naming, texts, and an undocumented contract.
Fixing them first, against the Maven front-end alone, keeps the Gradle work a pure new front-end and lets this step be verified by the existing Maven tests.

## Goal

After this step, nothing outside the Maven front-end (`lathe-compiler`, `lathe-maven-extension`, `lathe-maven-plugin`) knows Maven:
a new front-end only has to write the files the workspace contract describes.
Maven behavior is unchanged, and the existing unit and invoker tests pass without edits to their expectations.

## Non-goals

- No Gradle code, and no change to how the Maven front-end captures compiler options or test launches.
- No change to the `.lathe/` source-tree names `classes` and `test-classes`.
  They are Lathe's layout names (the mirror is Lathe's own directory), not Maven's, and every front-end uses them.
- Maven-only client features stay Maven-only: Neovim's `pom.xml` validation (`pom.lua`) is a per-file feature, not a build assumption.

## Changes

### 1. Output map — the one behavioral change

**Problem.**
The server and the launch planner must turn a sibling module's build output on a classpath into that module's `.lathe/` mirror.
Both routines guess the module from the shape of the path:

- `ReactorRewrite.toLathe` (`lathe-core`, used by `LaunchPlan`, `CompletenessGate`, `ResourceRootIndex`) rewrites only a path whose parent directory is literally `target`, ending in `classes`, `test-classes`, or a jar.
- `ModuleSourceConfig.remapPath` (`lathe-server`, feeding the compile classpath and `WorkspaceModuleGraph`) is unconditional: any path under the workspace root becomes `.lathe/<grandparent>/<leaf>`.

Gradle's sibling outputs are `sub/build/classes/java/main` and `sub/build/libs/sub.jar` (both observed in the Gradle spike).
`ReactorRewrite` leaves them unchanged, so replay runs against `build/`;
`remapPath` maps them to `.lathe/` paths that do not exist, so cross-module code intelligence breaks.
`remapPath` is also wrong for Maven today: a jar committed inside the repo (for example `libs/vendor.jar`) is remapped to `.lathe/classes`.

**Design.**
The front-end, which knows its outputs, records them; downstream looks them up instead of guessing.
`workspace.json` gains an `outputs` list, one entry per build output that can appear on another module's classpath:

```json
"outputs": [
  { "path": "app/target/classes",          "module": "app", "tree": "classes" },
  { "path": "app/target/test-classes",     "module": "app", "tree": "test-classes" },
  { "path": "app/target/app-1.0.jar",      "module": "app", "tree": "classes" },
  { "path": "app/target/app-1.0-tests.jar","module": "app", "tree": "test-classes" }
]
```

Paths are workspace-relative.
The Maven sync writes each module's `outputDirectory`, `testOutputDirectory`, and its packaged main and test-jar file names.
One `lathe-core` helper resolves a path through the map;
a path not in the map passes through unchanged, which fixes the in-repo jar case.
`ReactorRewrite` and `ModuleSourceConfig.remapPath` both delegate to it, so the two can no longer disagree.
OpenJDK keeps `externalOutput` (its outputs are read in place, not mirrored); it writes no `outputs` entries.

**Alternative considered:** derive the map from each module's `lsp-params` `outputDir`.
That covers directories but not packaged jars, which the build places by its own naming rules, so the front-end must state them anyway.

### 2. Build files instead of POM paths

`WorkspaceManifestData.pomPaths` becomes `buildFiles`, the `POM_CHANGED` reconcile outcome becomes `BUILD_FILES_CHANGED`, and the prompt says "Build files changed" instead of "Maven project changed".
The watcher already treats the list as opaque paths (mtime and size), so Gradle will list `settings.gradle(.kts)`, `build.gradle(.kts)`, and `gradle.properties` with no further change.
This is a schema change; no migration is needed.

### 3. User-facing texts

Two kinds of text, handled differently:

- **After `.lathe/` exists**, a remediation names the sync script, which every front-end writes:
  "run `mvn test` to capture it" (`WorkspaceSession`) becomes `.lathe/lathe-sync.sh --tests <module>`;
  the MCP tool descriptions (`LatheMcpServer`, `LatheMcpTools`) speak of a multi-module Java build and the sync script, not a Maven reactor and `mvn test`;
  "run lathe:sync" (`LatheLanguageServer`) names the script.
- **Before `.lathe/` exists** there is no script, so the first-sync remediation has to name a build command.
  `LatheLayout.SETUP_REMEDIATION` and the Neovim "no workspace" and "launcher not found" messages (`lathe.lua`, `sync.lua`) point at the installation guide and list each supported front-end's first command, Maven's being the only one for now.
  The version-skew message ("bump the `lathe-maven-extension` version") names the Lathe build plugin generically, with Maven's coordinate as the example.

`LaunchCapture`'s `kind` value `surefire` becomes a neutral `junit-platform`; nothing reads it.

### 4. The workspace contract, written down

The files a front-end must produce are known only from the Maven code.
A reference document (`docs/lathe-workspace-contract.md`) lists every `.lathe/` file, its schema record, who writes it, and its semantics:

| File | Schema / format |
|---|---|
| `<module>/lsp-params-<tree>.json` | `ModuleConfigData` |
| `<module>/{classes,test-classes}/`, generated-sources mirrors | bytecode / sources |
| `<module>/lsp-stamps-<tree>.json` | `CompiledStampsData` |
| `<module>/test-launch.json` | `TestLaunchData` |
| `<module>/main-launch.json` | `MainLaunchData` |
| `workspace.json` | `WorkspaceManifestData` (with `outputs`, `buildFiles`) |
| `java-home`, `jvm.args` | plain text |
| `style.json` | `WorkspaceStyle` |
| `lathe-sync.sh` | sync script contract ([Sync Launcher](lathe-sync-launcher.md)) |
| `lathe-launcher.sh`, `lathe-mcp-launcher.sh` | launcher links into `~/.cache/lathe/servers/` |
| `lathe.lock` | build lock: held while a build writes `.lathe/`, heartbeated, considered stale after 2 minutes (`LatheLock`) |

User-owned files (`lathe-run.json`, `run.json`, `lathe-style.json`) are listed as files a front-end must never write.
The lock deserves the most care: the Maven extension heartbeats it for the whole reactor build, and the Neovim client pre-touches it when a sync starts;
a front-end that does not heartbeat it lets the server reconcile against half-written output.

### 5. Neovim test discovery

`neotest.lua` skips `target` but not `build`, and filters test files by Surefire's default include patterns.
The directory skip uses the `outputs` from `workspace.json` (their top-level build directories) instead of a fixed name.
The Surefire patterns stay as a pre-filter (Gradle projects follow the same `*Test` naming in practice); the comment states it as a convention, not a Maven rule.

### 6. Formatter command `%MODULE%`

The opt-in command-file formatter resolves `%MODULE%` as the nearest ancestor holding a `pom.xml`.
It uses the module registry instead (the module whose source roots contain the file).
`%MVN%` stays: it is a convenience token a user writes into a committed `lathe-style.json`, explicitly Maven.

### 7. Guarding the result

Maven-only constants in `LatheLayout` (`POM_XML`, `TARGET_DIR`, `MVN_DIR`, `MAVEN_OPTS_PROPERTY`, the compiler-plugin coordinates, `SYNC_PHASE`, `SYNC_COMMAND`) move into a nested `LatheLayout.Maven` class.
A test in each downstream module fails if its main sources reference `LatheLayout.Maven`, so a new Maven assumption is caught at review time rather than by the next front-end.

## Verification

- The Maven invoker tests (`multi-module`, `LspSmokeTest`, and the rest) pass unchanged; they are the proof that Maven behavior did not move.
- Output-map unit tests use Maven-shaped **and** Gradle-shaped paths (`build/classes/java/main`, `build/libs/app.jar`), proving neutrality without a Gradle build.
- A regression test for an in-repo jar (`libs/vendor.jar`) passing through unchanged.

## Slicing

1. **Contract document** — docs only; settles the file list the later slices reference.
2. **Output map** — `workspace.json` `outputs`, the `lathe-core` helper, `ReactorRewrite` and `remapPath` delegating, the Maven sync writing the entries.
3. **Build files and texts** — `buildFiles`, `BUILD_FILES_CHANGED`, server and MCP texts, the capture `kind`.
4. **Client** — Neovim messages and neotest directory skip.
5. **`%MODULE%` and the `LatheLayout.Maven` guard.**

## Open decisions

1. **Output map location** — `workspace.json` (recommended, one read, already loaded by every consumer) or a separate `.lathe/outputs.json`.
2. **First-sync wording** — list every front-end's first command in the remediation, or detect the build tool from the root (`pom.xml` vs `settings.gradle`) and name only that one.
   Listing keeps the server free of build detection; detection gives a shorter message.
