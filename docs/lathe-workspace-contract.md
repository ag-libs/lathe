# Lathe — Workspace Contract

Reference for the files a build front-end writes under `<root>/.lathe/`.
Everything downstream of the build (the server, the MCP server, `lathe-test-runner`, the editor clients) reads these files and never the build itself,
so a new front-end only has to produce them.
Design context: [Build-Tool-Agnostic Core](done/lathe-build-agnostic-core.md).

Front-ends today:

- **Maven** — `lathe-maven-extension`, `lathe-compiler` (the `hint=lathe` compiler shim), `lathe-maven-plugin` (`lathe:init`, `lathe:sync`), and `lathe-install` (server install, shared).
- **OpenJDK** — `lathe-openjdk-maven-plugin` (`sync`), which reads the `make` build of a JDK checkout.

Schema records live in `lathe-core` under `io.github.aglibs.lathe.core.schema`; file names are constants in `LatheLayout`.

## Workspace and modules

A directory is a Lathe workspace when it contains a `.lathe/` directory.
The LSP server takes the root from the editor's workspace folder and treats it as configured when `.lathe/` exists;
the clients find the root as the nearest ancestor holding `.lathe/`, and attach when the launcher for the host OS exists: `.lathe/lathe-launcher.sh` on POSIX, `.lathe\lathe-launcher.cmd` on Windows.
They start it with the workspace root as working directory.
On Windows they start it through `%ComSpec% /d /s /c ""<launcher>""` with the arguments passed verbatim: Node and similar runtimes won't start a `.cmd` without a shell, `/d` skips the user's AutoRun (whose output would corrupt the protocol on stdout), and `/s` with the doubled quotes keeps a path with spaces intact.

A **module** is any directory under `.lathe/` that holds an `lsp-params-<tree>.json`.
The server discovers modules by walking `.lathe/`; there is no module list.
`<moduleRel>` is the module's path relative to the workspace root, and the empty string for the root module, whose files sit directly in `.lathe/`.
It need not mirror a real directory: OpenJDK uses flat names (`java.base`, a build-tool batch name).

Every relative path saved in `.lathe/` is `/`-separated on every OS: `<moduleRel>`, compile-stamp keys, `workspace.json` `buildFiles` and `outputs` paths, resource roots, and the run-config `module`.
So a `.lathe/` written on Windows reads the same elsewhere, and a committed `lathe-run.json` works across OSes.
Readers resolve them with `Path.resolve`, which accepts `/` on Windows; absolute paths stay native.

A module has one or two **source trees**, named `classes` (main) and `test-classes` (test).
The names are Lathe's, not Maven's: every front-end uses them, whatever its build calls the outputs.
The front-end assigns the tree by scope (main or test), never from the build's directory names.
Run gating and main-class scope recognize only these two names, and the generated-sources mirror name is derived from them.

**A full sync prunes what the build no longer has.**
Since the server discovers modules by walking `.lathe/`, a capture the build stopped producing would otherwise stay a module, compiled with stale settings.
After a full sync, `.lathe/` holds only the source trees of the build's current modules (`CapturePruner` in `lathe-install`):

- a tree the module no longer has loses its `lsp-params-<tree>.json`, its stamps, its class mirror and its generated-sources mirror;
- a module the build no longer has loses all its trees and its `main-launch.json` and `test-launch.json`.

It deletes files, never a module directory, because modules nest (`.lathe/a/` holds `.lathe/a/b/`).
A scoped sync (`-pl`, a forced one included) never prunes: it sees part of the build.
On Maven a tree is current when its compile source roots hold Java, or its output directory exists from an earlier build: the sync runs on the root project before its modules build, so a source root a plugin adds in this build is not listed yet.

## Files

| Path under `.lathe/` | Format | Need |
|---|---|---|
| `<moduleRel>/lsp-params-<tree>.json` | `ModuleConfigData` | required |
| `lathe-launcher.sh`, `lathe-mcp-launcher.sh` (`.cmd` on Windows) | scripts (executable on POSIX) | required |
| `lathe-server.args`, `lathe-mcp.args` | java @argfiles the launchers read | required |
| `lathe.lock`, `<moduleRel>/module.lock` | empty file, mtime | required while writing |
| `workspace.json` | `WorkspaceManifestData` | recommended |
| `<moduleRel>/lsp-stamps-<tree>.json` | `CompiledStampsData` | recommended |
| `<moduleRel>/classes/`, `<moduleRel>/test-classes/` | bytecode mirror | recommended, unless `externalOutput` |
| `lathe-sync.sh` (`.cmd` on Windows) | script | recommended |
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
Classpath entries that point at a sibling module's build output are rewritten by the server to that module's mirror,
looked up in `workspace.json`'s `outputs`; any other path is used as written.

- **Maven** writes it after every compile (`ParamsWriter`, from the compiler shim), including scoped `-pl` builds.
- **OpenJDK** writes the `classes` tree only, with `externalOutput=true`: `MODULE_SYSTEM` for JDK modules, `CLASSPATH` for build tools.
- The server never writes it.

### `lathe-launcher.sh`, `lathe-mcp-launcher.sh`, and their argfiles

Copies of the launcher scripts and their argfiles the front-end installs under `~/.cache/lathe/servers/<version>/` (`ServerInstaller`, `LauncherCopier`, shared by both front-ends).
A launcher names no jar: it starts Java with the argfile beside it (`lathe-server.args`, `lathe-mcp.args`), which holds the module path or classpath and ends with the entry point, so a copy runs the same as the original.
Argfiles are written in `native.encoding`, the charset the Java launcher reads them in.
They are plain files, not symlinks, so `.lathe/` works where links can't be created (Windows without admin rights).
Every sync rewrites them when their content changes.
The scripts pick the JVM as `$LATHE_JAVA_HOME`, else the content of `.lathe/java-home`, else `java` on `PATH`,
then add `@.lathe/jvm.args` when present and `$LATHE_JVM_OPTS`, before the argfile.
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
| `buildFiles` | workspace-relative build files the server watches for changes (`BuildFileData`: `path` and an optional content `fingerprint`, size plus CRC32C). One with a fingerprint counts as changed only while its content differs from the synced one; one without, whenever it is touched |
| `resourceRoots` | resource directories, their build output, and owning module (`ResourceRootData`) |
| `outputs` | every build output that can appear on another module's classpath, and the mirror it stands for (`OutputData`) |
| `buildTool` | absolute path of the tool that ran the last build (the one `lathe-sync.sh` runs); the formatter's `%MVN%` |

Each `outputs` entry is a workspace-relative `path` (an output directory or a packaged jar), the owning `module` (`""` for the root), and its `tree`.
The server and launch planner rewrite a listed path to `.lathe/<module>/<tree>`; an unlisted path, such as a jar committed in the repo, is used as written.
A front-end lists what its build actually produces, so custom output locations need nothing special;
a front-end whose outputs are read in place (`externalOutput`) lists none.

The server polls it every 2 seconds:
a content change reloads the workspace, and an mtime-only change re-scans the reactor mirror.
Without it the server still compiles and navigates the workspace's own code, but has no dependency or JDK sources, no type index for them, no build-file watching, and no test runs.

- **Maven** writes it from `lathe:sync` on a full reactor only (a `-pl` build skips it), touching the mtime when the content is unchanged; each POM in `buildFiles` carries the fingerprint of the content Maven read.
  Its `outputs` are each non-`pom` module's output directories plus `<finalName>.jar` and `<finalName>-tests.jar`;
  a custom test-jar classifier or another classified jar is not listed.
- **OpenJDK** writes it with no `jdk` and empty dependency lists; its `buildFiles` are each synced module's and build tool's `_the.<batch>_batch` marker, which `make` touches after compiling that batch, so they carry no fingerprint: the touch is the signal.

### `<moduleRel>/lsp-stamps-<tree>.json`

`CompiledStampsData`: `stamps`, source-root-relative path to the source's mtime when it was last compiled into the mirror, and `built`, the same path to the content fingerprint (size plus CRC32C) the build last compiled.
The server uses `stamps` to find sources edited since the last build, and `built` so that `verify_change` takes a change put back as no change.
Without it every source counts as stale, which self-heals but prompts a sync and recompiles on reconcile.

Both front-ends write it next to the params; the server updates `stamps` after save compiles and orphan cleanup, and leaves `built` as the build wrote it.
The Maven front-end records `built` on every compile; the OpenJDK one leaves it empty.

### `<moduleRel>/classes/`, `<moduleRel>/test-classes/`

An exact copy of the tree's build output, resources included.
It feeds the server's cross-module classpath, the reactor type index, and test and main launches.
The server also writes into it: save compiles, resource copies, and stale-class cleanup keep it current between builds.

- **Maven** replaces it after every compile; at the end of the build the extension adds the files a later step wrote to the output and the copy lacks (such as an annotation processor's `META-INF/services` entry written in `process-classes`), never a class.
- **OpenJDK** writes none and sets `externalOutput`, so the server reads the build's output in place.

### `lathe-sync.sh`

On Windows the scripts are `cmd` batch files (`lathe-sync.cmd`, `lathe-launcher.cmd`, `lathe-mcp-launcher.cmd`), rendered from the same templates; `.lathe/` holds only the host's.

A script (executable on POSIX) that re-runs this front-end's sync from the workspace root:

```
.lathe/lathe-sync.sh [--tests] [module ...]
```

- `--tests` also re-captures test launches without running tests.
- Modules scope the sync to those modules and their upstream.
- It runs the bare sync goal: properties and profiles given on the command line of the build that wrote it
  (`-D`, `-P`) are not replayed, so the script never depends on which build happened to run last.
  Flags every build of the project needs belong where the build tool reads them on its own: `.mvn/maven.config`
  or a profile activated by the JDK version (Maven), `gradle.properties` (Gradle); per user, `MAVEN_ARGS`
  (Maven 3.9+) or `~/.gradle/gradle.properties`.

The Neovim client runs it for the server's sync prompts and `:LatheSync`;
server and MCP remediation messages name it.
A front-end that cannot honor `--tests` or modules accepts and ignores them with a note on stderr.
Full specification: [Sync Launcher](planned/lathe-sync-launcher.md).

The script runs the tool that ran the last build, on that build's JDK, both written in as absolute paths; it chooses nothing at runtime, and stops with a hint to rebuild if the tool is gone.
Every front-end renders it from one model, `lathe-install`'s `SyncScript` ([Multi-OS](done/lathe-multi-os.md) D3).
Maven writes it from `lathe:sync` on a full reactor.

### `java-home`

The absolute JDK home the server runs on, normally the JDK the build compiles with, so the server's javac matches the build's.
Without it the launchers fall back to `java` on `PATH`.

- **Maven** writes `$JAVA_HOME`, else the Maven JVM's home, on a full reactor.
- **OpenJDK** writes the freshly built exploded JDK.

### `<moduleRel>/generated-sources/`, `<moduleRel>/generated-test-sources/`

A copy of the build's annotation-processor output for the tree (`generated-sources` for `classes`, `generated-test-sources` for `test-classes`).
Navigation and the module router use it; the server regenerates into it when processors run on save.
Maven copies it when the build produced one, and removes it when a compile produced none, so a removed processor's types leave the editor too.

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

## Versioning

One version covers all of `.lathe/`: `schemaVersion` in `workspace.json` (`LatheLayout.SCHEMA_VERSION`).
Any change to the format of any `.lathe/` file bumps it; the other files carry no version of their own.
A server that finds another version ignores `workspace.json` and warns to run `.lathe/lathe-sync.sh`, which rewrites it.
A bump that changes a per-module file must also make the sync rewrite that file for every module, including those whose compile is up to date.

The single check is enough because `.lathe/` is regenerable, never migrated:
the fix for any mismatch is a rebuild, so the version only has to detect it and say so.
The bump matters most for the quiet case: an old file that still parses, with a newly added field silently defaulted instead of failing.

The user cache below has its own version, `LatheLayout.CACHE_SCHEMA_VERSION`, carried by the type-index shards and dependency-source markers,
so a `.lathe/` format change does not force re-extracting and re-indexing every dependency.

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
| `servers/<version>/` | launcher scripts and their argfiles; `latest/` holds copies of the highest version's | front-end |
| `deps/<groupId>/<artifactId>/<version>/` | extracted dependency sources (no `META-INF/maven/`), with a `.lathe-source.json` marker | front-end |
| `jdks/<key>/` | extracted JDK `src.zip` | front-end |
| `type-index/deps/…`, `type-index/jdks/…` | type-index shards (`TypeIndexFile`) | front-end |
| `logs/` | MCP server logs | server |

The server reads `deps/`, `jdks/`, and `type-index/` only through paths listed in `workspace.json`.

## Front-end coverage

| File | Maven | OpenJDK |
|---|---|---|
| `lsp-params-<tree>.json` | yes | yes, `classes` only |
| launchers and argfiles | yes | yes |
| `lathe.lock`, `module.lock` | yes | `lathe.lock` only |
| `workspace.json` | yes, full reactor | yes, `buildFiles` = batch markers |
| `lsp-stamps-<tree>.json` | yes | yes |
| bytecode mirror | yes | no (`externalOutput`) |
| `lathe-sync.sh` | yes, full reactor | yes, re-runs its own sync goal |
| `java-home` | yes, full reactor | yes |
| generated-sources mirror | yes | no |
| `test-launch.json` | yes | no |
| `main-launch.json` | yes | no |
| `jvm.args` | yes, full reactor | no |
| `style.json` | yes, full reactor | no |

## Known issues

Found while writing this contract (2026-10-11); each is a gap in the current front-ends or server, not part of the contract.

- **The root module is spelled two ways.**
  The compiler, test capture, and server use `""`; `main-launch.json` and `resourceRoots` use `"."`.
- **The module lock has no heartbeat.**
  A compile plus mirror copy over 2 minutes looks stale to readers.
- **Several writes are not atomic.**
  Params, stamps, and the OpenJDK `workspace.json` are written in place, and the mirror is deleted before it is copied.
- **The server writes mirror directories for OpenJDK modules.**
  It creates `generated-sources` for every module and writes build-tool save output under `.lathe/<batch>/classes`, although OpenJDK writes no mirror.
