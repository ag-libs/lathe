# Lathe — Workspace Contract

Reference for the files a build front-end writes under `<root>/.lathe/`.
Everything downstream of the build (the server, the MCP server, `lathe-test-runner`, the editor clients) reads these files and never the build itself,
so a new front-end only has to produce them.
Design context: [Build-Tool-Agnostic Core](planned/lathe-build-agnostic-core.md).

Front-ends today:

- **Maven** — `lathe-maven-extension`, `lathe-compiler` (the `hint=lathe` compiler shim), `lathe-maven-plugin` (`lathe:init`, `lathe:sync`), and `lathe-install` (server install, shared).
- **OpenJDK** — `lathe-openjdk-maven-plugin` (`sync`), which reads the `make` build of a JDK checkout.

Schema records live in `lathe-core` under `io.github.aglibs.lathe.core.schema`; file names are constants in `LatheLayout`.

## Workspace and modules

A directory is a Lathe workspace when it contains a `.lathe/` directory.
The LSP server takes the root from the editor's workspace folder and treats it as configured when `.lathe/` exists;
the clients find the root as the nearest ancestor holding `.lathe/`, and attach only when `.lathe/lathe-launcher.sh` is executable.

A **module** is any directory under `.lathe/` that holds an `lsp-params-<tree>.json`.
The server discovers modules by walking `.lathe/`; there is no module list.
`<moduleRel>` is the module's path relative to the workspace root, and the empty string for the root module, whose files sit directly in `.lathe/`.
It need not mirror a real directory: OpenJDK uses flat names (`java.base`, a build-tool batch name).

A module has one or two **source trees**, named `classes` (main) and `test-classes` (test).
The names are Lathe's, not Maven's: every front-end uses them, whatever its build calls the outputs.
Run gating and main-class scope recognize only these two names, and the generated-sources mirror name is derived from them.

## Files

| Path under `.lathe/` | Format | Need |
|---|---|---|
| `<moduleRel>/lsp-params-<tree>.json` | `ModuleConfigData` | required |
| `lathe-launcher.sh`, `lathe-mcp-launcher.sh` | symlinks | required |
| `lathe.lock`, `<moduleRel>/module.lock` | empty file, mtime | required while writing |
| `workspace.json` | `WorkspaceManifestData` | recommended |
| `<moduleRel>/lsp-stamps-<tree>.json` | `CompiledStampsData` | recommended |
| `<moduleRel>/classes/`, `<moduleRel>/test-classes/` | bytecode mirror | recommended, unless `externalOutput` |
| `lathe-sync.sh` | shell script | recommended |
| `java-home` | plain text | recommended |
| `<moduleRel>/generated-sources/`, `<moduleRel>/generated-test-sources/` | sources mirror | optional |
| `<moduleRel>/test-launch.json` | `TestLaunchData` | optional (test run/debug) |
| `<moduleRel>/main-launch.json` | `MainLaunchData` | optional (main run/debug) |
| `jvm.args` | java `@argfile` | optional |
| `style.json` | `WorkspaceStyleData` | optional |

**Required** means a Lathe feature set does not work at all without it;
**recommended** means the server works without it but degrades visibly;
**optional** enables a specific feature.

### `<moduleRel>/lsp-params-<tree>.json`

The compiler configuration of one source tree: `ModuleConfigData`.

| Field | Meaning |
|---|---|
| `sourceTree` | `classes` or `test-classes` |
| `outputDir` | the build's real output directory, absolute |
| `generatedSourcesDir` | the build's annotation-processor output, or null |
| `sourceRoots`, `classpath`, `modulepath`, `processorPath` | absolute paths |
| `release`, `encoding`, `parameters`, `enablePreview`, `proc`, `compilerArgs` | javac options as the build passes them |
| `analysisMode` | `CLASSPATH` or `MODULE_SYSTEM` |
| `externalOutput` | true when the server reads `outputDir` in place instead of a `.lathe/` mirror |

Without it, files under the module are "not in a Lathe module" and get no compiler, and runs are blocked as not compiled.
Classpath entries that point at a sibling module's build output are rewritten by the server to that module's mirror;
see [Known issues](#known-issues) for how that rewrite is guessed today.

- **Maven** writes it after every compile (`ParamsWriter`, from the compiler shim), including scoped `-pl` builds.
- **OpenJDK** writes the `classes` tree only, with `externalOutput=true`: `MODULE_SYSTEM` for JDK modules, `CLASSPATH` for build tools.
- The server never writes it.

### `lathe-launcher.sh`, `lathe-mcp-launcher.sh`

Absolute symlinks to `~/.cache/lathe/servers/<version>/`, where the front-end installs the server and writes the real launcher scripts (`ServerInstaller`, `WorkspaceLauncherLinker`, shared by both front-ends).
The scripts pick the JVM as `$LATHE_JAVA_HOME`, else the content of `.lathe/java-home`, else `java` on `PATH`,
then add `@.lathe/jvm.args` when present.
`java-home` and `jvm.args` are resolved relative to the working directory, so clients spawn the launcher with the workspace root as cwd.

### `lathe.lock`, `module.lock`

An empty file; only its existence and mtime matter (`LatheLock`).
It is held while it exists and its mtime is under 2 minutes old; an older lock is stale and ignored.

- **Reactor lock** `.lathe/lathe.lock`: held for a whole build that writes `.lathe/`.
  The Maven extension takes it after the projects are read and heartbeats it every 30 seconds until the session ends;
  the Neovim client pre-touches it when it starts `lathe-sync.sh`, so the server sees the build before Maven starts.
  While it is held, the server suppresses reloads, sync prompts, and reconcile, so it never reads half-written output.
- **Module lock** `.lathe/<moduleRel>/module.lock`: held while one module's params, stamps, and mirror are rewritten (the compiler shim).
  Launch readers and the run gate wait for it.
  It has its own name because the root module's directory is `.lathe/` itself: under a shared name, releasing the root's module lock would release the reactor lock mid-build.

A front-end that writes `.lathe/` while an editor may be running holds the reactor lock for the whole write, and heartbeats it if the write can exceed 2 minutes.

### `workspace.json`

Workspace-wide data: `WorkspaceManifestData`.

| Field | Meaning |
|---|---|
| `schemaVersion` | `LatheLayout.SCHEMA_VERSION`; on mismatch the server ignores the whole file |
| `workspaceRoot` | absolute root |
| `serverVersion` | the front-end's Lathe version (informational) |
| `runnerClasspath` | `lathe-test-runner` plus JUnit Platform jars; empty blocks test runs |
| `jdk` | JDK sources and type-index shard (`JdkSourceData`), or null |
| `dependencySources` | per dependency jar: extracted sources dir, classpath, type-index shard (`DependencyData`) |
| `pomPaths` | workspace-relative build files the server watches for changes |
| `resourceRoots` | resource directories, their build output, and owning module (`ResourceRootData`) |

The server polls it every 2 seconds:
a content change reloads the workspace, and an mtime-only change re-scans the reactor mirror.
Without it the server still compiles and navigates the workspace's own code, but has no dependency or JDK sources, no type index for them, no build-file watching, and no test runs.

- **Maven** writes it from `lathe:sync` on a full reactor only (a `-pl` build skips it), touching the mtime when the content is unchanged.
- **OpenJDK** writes it with empty lists and no `jdk`.

### `<moduleRel>/lsp-stamps-<tree>.json`

`CompiledStampsData`: source-root-relative path to the source's mtime when it was last compiled into the mirror.
The server uses it to find sources edited since the last build.
Without it every source counts as stale, which self-heals but prompts a sync and recompiles on reconcile.

Both front-ends write it next to the params; the server updates it after save compiles and orphan cleanup.

### `<moduleRel>/classes/`, `<moduleRel>/test-classes/`

An exact copy of the tree's build output, resources included.
It feeds the server's cross-module classpath, the reactor type index, and test and main launches.
The server also writes into it: save compiles, resource copies, and stale-class cleanup keep it current between builds.

- **Maven** replaces it after every compile.
- **OpenJDK** writes none and sets `externalOutput`, so the server reads the build's output in place.

### `lathe-sync.sh`

An executable script that re-runs this front-end's sync from the workspace root:

```
.lathe/lathe-sync.sh [--tests] [module ...]
```

- `--tests` also re-captures test launches without running tests.
- Modules scope the sync to those modules and their dependents.

The Neovim client runs it for the server's sync prompts and `:LatheSync`;
server and MCP remediation messages name it.
A front-end that cannot honor `--tests` or modules accepts and ignores them with a note on stderr.
Full specification: [Sync Launcher](planned/lathe-sync-launcher.md).

Maven writes it from `lathe:sync` on a full reactor.

### `java-home`

The absolute JDK home the server runs on, normally the JDK the build compiles with, so the server's javac matches the build's.
Without it the launchers fall back to `java` on `PATH`.

- **Maven** writes `$JAVA_HOME`, else the Maven JVM's home, on a full reactor.
- **OpenJDK** writes the freshly built exploded JDK.

### `<moduleRel>/generated-sources/`, `<moduleRel>/generated-test-sources/`

A copy of the build's annotation-processor output for the tree (`generated-sources` for `classes`, `generated-test-sources` for `test-classes`).
Navigation and the module router use it; the server regenerates into it when processors run on save.
Maven copies it when the build produced one.

### `<moduleRel>/test-launch.json`

The JVM launch of the module's test fork, captured from inside it: `TestLaunchData` (mode, java home, module and class path, `--patch-module`, `--add-*` flags, JVM args, working directory).
Paths are the build's real ones; the server rewrites sibling outputs to the mirror at launch.
Without it the module's tests cannot be run or debugged from the editor.

Maven captures it with a JUnit Platform `LauncherSessionListener` (`lathe-junit`) that the extension adds to the test classpath;
any test run writes it, and `lathe-sync.sh --tests` writes it without running tests.

### `<moduleRel>/main-launch.json`

The JVM launch for running the module's `main` classes: `MainLaunchData`, the same shape as the test launch without `--patch-module`.
Maven derives it in `lathe:sync` from the module's runtime classpath and module placement, without forking.
Without it `main` classes cannot be run or debugged from the editor.

### `jvm.args`

One `--add-exports=` / `--add-opens=` token per line, passed to the server JVM as `@.lathe/jvm.args`.
It opens `jdk.compiler` internals that in-process javac plugins (for example Error Prone) and pinned formatters need.
Maven collects them from `maven-compiler-plugin` `-J` flags, `.mvn/jvm.config`, `MAVEN_OPTS`, and the formatter's grants, and deletes the file when the set is empty.

### `style.json`

The workspace formatter and indentation: `WorkspaceStyleData` (`FormatterSpec` with engine, version, options, resolved jars; `IndentSpec`).
The server reads it at startup; the user-owned `lathe-style.json` overrides it section by section.
Maven derives it from the root project's Spotless Java step.
Details: [Pinned Formatters](done/lathe-pinned-formatters.md).

## User-owned files

A front-end never writes these:

| File | Owner |
|---|---|
| `<root>/lathe-run.json` | committed, shared run configurations |
| `<root>/.lathe/run.json` | machine-local run configurations, saved by the server |
| `<root>/lathe-style.json` | committed formatter and indentation overrides |

## User cache

The front-end also fills parts of the user cache (`~/.cache/lathe`, or `-Dlathe.cache`), which `.lathe/` files point into:

| Path | Content | Written by |
|---|---|---|
| `servers/<version>/` | launcher scripts; `latest` links the highest version | front-end |
| `deps/<gav>/` | extracted dependency sources, with a `.lathe-source.json` marker | front-end |
| `jdks/<key>/` | extracted JDK `src.zip` | front-end |
| `type-index/deps/…`, `type-index/jdks/…` | type-index shards (`TypeIndexFile`) | front-end |
| `logs/` | MCP server logs | server |

The server reads `deps/`, `jdks/`, and `type-index/` only through paths listed in `workspace.json`.

## Front-end coverage

| File | Maven | OpenJDK |
|---|---|---|
| `lsp-params-<tree>.json` | yes | yes, `classes` only |
| launcher links | yes | yes |
| `lathe.lock`, `module.lock` | yes | no |
| `workspace.json` | yes, full reactor | yes, empty lists |
| `lsp-stamps-<tree>.json` | yes | yes |
| bytecode mirror | yes | no (`externalOutput`) |
| `lathe-sync.sh` | yes, full reactor | no |
| `java-home` | yes, full reactor | yes |
| generated-sources mirror | yes | no |
| `test-launch.json` | yes | no |
| `main-launch.json` | yes | no |
| `jvm.args` | yes, full reactor | no |
| `style.json` | yes, full reactor | no |

## Known issues

Found while writing this contract (2026-10-11); each is a gap in the current front-ends or server, not part of the contract.

- **Sibling-output rewrite is guessed from the path.**
  The server and launch planner turn a sibling module's output into its mirror by path shape (a `target` parent for launches; any workspace path for the compile classpath),
  so a jar committed in the repo is wrongly redirected and OpenJDK build-tool classpaths point at missing paths.
  The output map in the [design](planned/lathe-build-agnostic-core.md) replaces the guess.
- **The root module is spelled two ways.**
  The compiler, test capture, and server use `""`; `main-launch.json` and `resourceRoots` use `"."`.
- **The module lock has no heartbeat.**
  A compile plus mirror copy over 2 minutes looks stale to readers.
- **OpenJDK does not hold the reactor lock and writes no `lathe-sync.sh`.**
  The server can reconcile against half-written output while it syncs, and sync prompts point at a missing script.
- **Launch schema versions are written but not checked.**
  Only `workspace.json` is version-gated.
- **Several writes are not atomic.**
  Params, stamps, and the OpenJDK `workspace.json` are written in place, and the mirror is deleted before it is copied.
- **The server writes mirror directories for OpenJDK modules.**
  It creates `generated-sources` for every module and writes build-tool save output under `.lathe/<batch>/classes`, although OpenJDK writes no mirror.
