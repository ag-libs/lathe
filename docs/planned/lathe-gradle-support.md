# Lathe — Gradle Support

## Status

**Proposed.** Lathe today derives its whole model from a **Maven** build (the
[Maven extension](../done/lathe-maven-extension.md) injects the compiler shim, `init`/`sync` goals, and
the test-capture dependency; [Run, Test, and Debug](../done/lathe-run-test-debug.md) captures and
replays from `.lathe/`). This document designs an equivalent **Gradle** front-end that produces the
identical `.lathe/` contract, so every downstream component — the language server, `lathe-test-runner`,
the MCP server, and all editor clients — works unchanged.

No Gradle code exists yet. This is a design, sliced for incremental delivery.

**Prerequisites:**

1. [Build-Tool-Agnostic Core](../done/lathe-build-agnostic-core.md) — **done (2026-10-11).**
   Downstream code no longer assumes Maven's `target/` layout or Maven-named manifest fields.
2. [Multi-OS](lathe-multi-os.md), all of it — decided 2026-10-20: Multi-OS closes before Gradle starts.
   Its sync script model (D3) is build-neutral and already golden-tested with a Gradle-shaped model, so Gradle fills the model in rather than writing script logic.
3. The **JDK 17 floor** ([Wider Maven and JDK Support](../done/lathe-wider-maven-support.md), slice 0, D7) — every module targets Java 17.
   Before slice 1, so the plugin is written against Java 17 from the start.
4. **A Maven-free `lathe-install`** (see [What is reused vs new](#what-is-reused-vs-new)) — this design's slice 0 (moved here from Multi-OS on 2026-10-20, since only Gradle needs it).

**Decided 2026-10-18** (details in the sections below):

- **Order:** all of Multi-OS, Windows included, before slice 0 (*revised 2026-10-20*; first decided as Multi-OS Phase A before slice 2).
- **Shared sync code lives in `lathe-install`, made free of Maven.** Maven-specific adapters stay in `lathe-maven-plugin`.
- **Formatter version is observed** from Spotless's own dependency resolution; a committed `lathe-style.json` always wins; options come from Spotless's defaults with a one-time notice. No "latest" fallback.
- **Lathe artifacts come from a repository the reactor fills**, never `~/.m2` (the trap the invoker suite hit as 7a).
- **JDK 17+:** the plugin targets Java 17, so the Gradle daemon may run on 17; toolchain test workers on 17 load `lathe-junit`.

**Readiness review 2026-10-23** — checked against the code before slice 0; decided:

- The sync script model renders a front-end's module options *before* its goals, which Gradle rejects for a task option; the model gains a flag to put them after (see [The sync command](#the-sync-command)).
- Slice 0 moves every build-neutral sync class, not only the five first listed (see [What is reused vs new](#what-is-reused-vs-new)).
- The compiler capture matches `LatheCompiler.syncOutput` in full: it keeps the last good capture when the compile fails, mirrors generated sources, and takes the module lock (see [Capture point A](#capture-point-a--compiler-arguments--annotation-processing)).
- A Lathe-enabled build never changes the cache keys of the build's own tasks, so a team's shared build cache keeps working (see [the cache-key rule](#design-principle--leave-the-builds-cache-keys-alone-decided-2026-10-23)); compiler capture is therefore a finalizer task, spiked on 8.14.6 and 9.8.1.
- `jvm.args`, the `lathe-junit` injection, the module directory name, `externalOutput`, Gradle 9's no-tests failure, build-logic `buildFiles`, the sync script's replay of init scripts and properties, and Isolated Projects each get a decision in their section.

**Spiked 2026-10-10** on Gradle 8.14.6 and 9.8.1 (JDK 21) with a throwaway multi-project build
(a `java-library` project, a plain `java` project, an app with an annotation processor, JUnit 5, Guava, and Spotless).
Results are folded into the sections below and summarized in [Spike results](#spike-results).
No showstopper was found; both Gradle versions behaved identically.

**Spiked again 2026-10-23, every assumption** — on Gradle 8.8 (the floor), 8.14.6, and 9.8.1, in throwaway builds:
the capture task and the cache-key rule, Gradle 9's no-tests failure, init-script registration, Isolated Projects,
Error Prone's JVM flags, the Maven-orchestrated build with TestKit, and JPMS.
No showstopper. Three findings changed the design: the module-path split follows Gradle's rule rather than
plexus-java's; Error Prone's flags are in `forkOptions.jvmArgumentProviders`; and the Isolated-Projects-safe
plugin has three coding constraints. All are in the sections below and the [Spike results](#spike-results).

**Multi-OS:** the [Multi-OS Design](lathe-multi-os.md) changes the launcher and sync files below.
They become generated host-dialect scripts (`.sh` / `.cmd`, no symlinks), and the sync script runs the tool that ran the build, written into it as a literal; the Gradle front-end renders it from the same template as Maven, with its own goals and module pattern ([Multi-OS](lathe-multi-os.md) D3).
It closes before this design starts, so `latheSync` writes portable files from the start.

## Goal

Give a developer on a **Gradle** Java project the same "the tool understands my build" experience Lathe
gives Maven users: javac-accurate diagnostics/completion/navigation for **main and test** sources, and
run/test/debug replayed from captured bytecode — all derived from the real Gradle build, with the same
setup shape (register once, build once, point the editor at the cache).

## Architectural constraint — `.lathe/` is the seam

Lathe's model is build-derived, and the build tool is isolated behind a file contract. The
server, runner, MCP server, and clients **only read `.lathe/`** (plus the machine-wide
`~/.cache/lathe/`); once the [build-agnostic step](../done/lathe-build-agnostic-core.md) lands, none of them
know Maven exists. The entire Maven integration exists to *produce* these files:

| File | Schema (`lathe-core`) | Produced by (Maven) |
|---|---|---|
| `.lathe/<module>/lsp-params-<tree>.json` | `ModuleConfigData` | compiler shim (`LatheCompiler`/`ParamsWriter`) |
| `.lathe/<module>/{classes,test-classes}` | (mirrored bytecode) | compiler shim |
| `.lathe/<module>/lsp-stamps-<tree>.json` | `CompiledStampsData` | compiler shim |
| `.lathe/<module>/test-launch.json` | `TestLaunchData` | `lathe-junit` listener (in the Surefire fork) |
| `.lathe/<module>/main-launch.json` | `MainLaunchData` | `sync` goal (build-side derivation) |
| `.lathe/workspace.json` | `WorkspaceManifestData` (incl. `outputs`, `buildFiles`) | `sync` goal (`WorkspaceManifestWriter`) |
| `.lathe/java-home`, `.lathe/jvm.args` | plain text | `sync` goal |
| `.lathe/style.json` | `WorkspaceStyle` | `sync` goal (`WorkspaceStyleWriter`, from Spotless config) |
| `.lathe/lathe-sync.{sh,cmd}` | sync script running the tool that ran the build ([Multi-OS](lathe-multi-os.md) D3) | `sync` goal |
| `.lathe/lathe-launcher.{sh,cmd}`, `lathe-mcp-launcher.{sh,cmd}`, and their `lathe-server.args`, `lathe-mcp.args` | generated launchers and the argfiles they read ([Multi-OS](lathe-multi-os.md)) | `sync` goal |
| `.lathe/<module>/{generated-sources,generated-test-sources}` | annotation-processor output mirror | compiler shim |
| `.lathe/lathe.lock` | build lock, heartbeated, stale after 2 minutes (`LatheLock`) | Maven extension (whole build) |
| `.lathe/<module>/module.lock` | held while a module's capture is written | compiler shim |

The authoritative list, with semantics and which files are required, is the [Workspace Contract](../lathe-workspace-contract.md); this table is a summary.
Gradle names its source trees `classes`/`test-classes` like Maven: they are Lathe's mirror names.

**Therefore Gradle support = a Gradle plugin that emits the same files.** Everything downstream is
reused. This is the design's central constraint: the Gradle side is a new front-end onto an existing
contract, mirroring the Maven modules one-for-one rather than a parallel universe.

## Design principle — observe, don't reconstruct

Lathe's fidelity comes from riding the **real** build: the Maven compiler shim reads the exact
`CompilerConfiguration` `javac` was invoked with, and the `lathe-junit` listener reads the exact JVM the
Surefire fork launched. It never rebuilds a model of its own. The Gradle design keeps this: **wherever
Gradle actually invokes a compiler or launches a JVM, capture what it did; reconstruct only where Gradle
exposes no hook.** This is what avoids "the editor disagrees with the build" surprises, and it drives
the two decisions below (in-fork test capture; the honest limit on compiler-arg capture).

## Design principle — leave the build's cache keys alone (decided 2026-10-23)

Many Gradle teams share a remote build cache that CI fills and developers read.
Lathe is off on CI (the `CI` gate), so anything Lathe changes about a build task on a developer's machine —
an added action, an extra classpath entry, a changed system property — gives that task a different cache key
from CI's, and every such task misses the shared cache and rebuilds locally.
On a large build that is a big, silent slowdown, and it would be blamed on Lathe.

**Rule: a Lathe-enabled build adds its own tasks, but never changes the inputs or actions of the build's
existing tasks during an ordinary build.**
Two consequences shape the capture points:

- Compiler capture is a separate Lathe task that finalizes `JavaCompile`, not an action added to it
  ([Capture point A](#capture-point-a--compiler-arguments--annotation-processing)).
  A finalizer is not an input of the task it finalizes: spiked, `compileJava` stays `FROM-CACHE` from an
  entry stored without Lathe.
- `lathe-junit` joins the test classpath only in capture-only mode, when the `test` task is uncached anyway
  ([Capture point B](#capture-point-b--test-launch-testlaunchdata-in-fork)).

The TestKit suite asserts the rule: a fixture built without Lathe fills a build cache, and the same build
with Lathe enabled must take every existing task `FROM-CACHE`.

## Scope

**In:**

- Compiler-argument + annotation-processing capture for **main and test** source sets
  (`ModuleConfigData`), plus the bytecode mirror and compile stamps.
- Test-launch capture (in-fork) and main-launch derivation.
- Dependency `-sources` resolution, JDK sources, and `workspace.json`.
- Registration (settings plugin **and** init script), disabled-by-default / `.lathe/` opt-in gating.
- Run / test / debug for non-modular **and** modular (JPMS) projects — modular test as the last slice.

**Out (this design):**

- Non-JVM Gradle projects; Kotlin/Groovy/Scala compilation (Java only, as today).
- Android Gradle Plugin (a separate model; explicit non-goal).
- Composite builds whose *included builds are Java projects the editor should cover* (`includeBuild("../lib")`):
  only the main build's projects get a `.lathe/` model. Included builds that only contribute build logic
  are watched as `buildFiles` (see [Dependency sources & `workspace.json`](#dependency-sources--workspacejson)).
- Any change to the server, runner, MCP server, clients, or the `.lathe/` schema — the ones that are
  needed land beforehand in the [build-agnostic step](../done/lathe-build-agnostic-core.md).

## What is reused vs new

**Reused unchanged** (after the build-agnostic step): `lathe-core` (schema, `LatheLayout`, `LatheFlags`,
`LatheWorkspace`, `LatheLock`, `LaunchPlan`, the output map, type-index), **`lathe-junit`** (the JUnit
Platform capture listener + `PostDiscoveryFilter`, with only a worker-jar filter — see Capture B), `lathe-test-runner`,
`lathe-server`, `lathe-mcp-server`, and all editor clients.
Module/classpath placement is **not** reused from plexus-java: Gradle places modules by its own, simpler rule, and
the front-end follows Gradle (see [JPMS](#jpms-java-modules)).

**Reused after it moves — a Maven-free `lathe-install` (audit 2026-10-18).**
The sync work the plugin needs is build-neutral in substance but lives in Maven-bound modules today:

| Code | Lives in | Tied to Maven by |
|---|---|---|
| JDK sources and their type-index shard | `lathe-maven-plugin` (`jdk/JdkSource`, `JdkSourceResolver`, `JdkSourceSync`; `typeindex/JdkTypeIndexSync`, `TypeIndexFiles`) | Maven's `Log` |
| Dependency `-sources` extraction and type-index shards | `lathe-maven-plugin` (`dependency/DependencySource`, `DependencySourceSync`; `typeindex/DependencyTypeIndexSync`) | Maven's `Log` and artifact types; *resolving* the jars stays per tool (`DependencySourceResolver` on Maven, an artifact view on Gradle) |
| `workspace.json`, the sync script, `main-launch.json` | `lathe-maven-plugin` (`WorkspaceManifestWriter`, `SyncScriptWriter`, `MainLaunchWriter`) | Maven's `Log`; `MainLaunchWriter` also reads `MavenProject` |
| Formatter jars and `style.json` | `lathe-maven-plugin` (`FormatterResolver`, `WorkspaceStyleWriter`) | Aether resolution, and the Spotless POM reading, which stays Maven-only |
| `jvm.args` | `lathe-maven-plugin` (`JvmArgsWriter`) | reads the compiler plugin's POM configuration and `.mvn/jvm.config`; the *writing* moves, the reading stays per tool |
| Server install, artifact resolution | `lathe-install` (`ServerInstaller`, `ArtifactResolver`, `LauncherCopier`) | a `maven-core` / `maven-plugin-api` dependency |
| `lsp-params-*.json`, the bytecode and generated-sources mirrors, compile stamps | `lathe-compiler` (`ParamsWriter`, `LatheCompiler.syncOutput`) | Plexus `CompilerConfiguration` |

`ReactorProjects`, `ModuleResourcesReader`, `SyncCoordinator`, and the Mojos read the Maven model and stay in `lathe-maven-plugin`; the Gradle plugin has its own counterparts.

They move into `lathe-install`, which then depends only on `lathe-core`:

- a small logging interface replaces Maven's `Log`;
- an artifact-resolver interface, with a Maven implementation (Aether, in `lathe-maven-plugin`) and a Gradle one (in the plugin) — two implementations, so the interface earns its place;
- `ParamsWriter` and the mirror/stamp writer (`syncOutput`, including its keep-the-last-good-capture rule on a failed compile) take plain values; `lathe-compiler` keeps a thin adapter from `CompilerConfiguration`;
- `MainLaunchWriter` takes the resolved classpath, module placement, and `javaHome` as values;
- the dependency and JDK source sync, the type-index shards, `WorkspaceStyleWriter`, and `JvmArgsWriter` take the resolved jars and flags as values.

This is this design's slice 0. Multi-OS already puts the sync script model and renderers into `lathe-install` without Maven imports, so slice 0 moves the rest.

**New:** a `lathe-gradle-plugin` module (a **settings plugin** + a **project plugin** + a `latheSync`
task), depending on `lathe-core` and resolving `lathe-test-runner`/`lathe-junit` at sync time — the
direct analog of `lathe-maven-extension` + `lathe-maven-plugin`.

## Component mapping: Maven → Gradle

| Maven | Role | Gradle equivalent |
|---|---|---|
| `LatheLifecycleParticipant.afterProjectsRead` + `LatheModelInjector` | wire every module before build | **Settings plugin** using `gradle.lifecycle.beforeProject { }` to apply the project plugin to every project (Gradle 8.8+, no fallback) |
| `lathe:init` (mkdir `.lathe/`) | create dir at reactor root | project-plugin apply / `latheInit` |
| `lathe:sync` + `SyncCoordinator` (aggregator, `process-test-classes`) | source jars, JDK sources, server install, `workspace.json`, `main-launch.json` | root task **`latheSync`** |
| `lathe-compiler` (Plexus `Compiler`, `compilerId=lathe`) + `ParamsWriter` | capture compiler args, mirror classes, write stamps | a **capture task** finalizing each `JavaCompile` (typed-API capture; skipped when the compile fails) |
| `lathe-junit` (`LauncherSessionListener`, in-fork) | capture test JVM launch | **reused** — same listener in Gradle's test fork (drops `gradle-worker.jar`) |
| Aether `resolveArtifact(":sources")` | resolve `-sources` jars | `ArtifactView` + `withVariantReselection()` with `DocsType.SOURCES` |
| plexus-java `LocationManager` | module/classpath split | Gradle's own rule — every entry that is a module goes on the module path ([JPMS](#jpms-java-modules)) |
| `workspace.json` `outputs` (module `outputDirectory` + packaged jars) | map sibling outputs to `.lathe/` mirrors | each `JavaCompile.destinationDirectory` + each `Jar.archiveFile` (main and test jars) |
| `WorkspaceStyleWriter` (Spotless POM config) | pinned formatter → `style.json` | see [Formatter](#formatter-stylejson) |
| `LatheFlags` / `isPomOptOut` | gating & precedence | same logic, reading Gradle properties + `.lathe/` |

## Capture point A — compiler arguments & annotation processing

Maven captures these by *replacing the compiler* (`compilerId=lathe`) so `ParamsWriter` sees the
resolved `CompilerConfiguration`. **Gradle has no equally faithful hook.** The truly-faithful analog —
forcing a forked `javac` via `forkOptions.executable` and wrapping it — is not viable: it disables task
caching, forces forking, and since Gradle 8.0 the executable must byte-match the toolchain `javac`, with
a live regression where it is ignored entirely (gradle#37179, gradle#23990). So compiler capture is the
one place we **read Gradle's computed values** off the typed `JavaCompile` API rather than intercepting
the process:

| `ModuleConfigData` | Gradle API on `JavaCompile` |
|---|---|
| `sourceRoots` | `getSource()` / the `SourceSet`'s `java.srcDirs` |
| `classpath` | `getClasspath()` (`@CompileClasspath`, resolved) |
| `processorPath` | `getEffectiveAnnotationProcessorPath()` (resolved; empty ⇒ `proc=none`) |
| `compilerArgs` (incl. `-A…`, `-Xlint`, and manual `--patch-module`/`--add-*`) | `getOptions().getAllCompilerArgs()` (folds in `compilerArgumentProviders`) |
| `generatedSourcesDir` | `getOptions().getGeneratedSourceOutputDirectory()` (since Gradle 6.4) |
| `outputDir` | `getDestinationDirectory()` |
| `modulepath` | Gradle's rule applied to `getClasspath()` when the source set has a `module-info.java`: every entry that is a module (descriptor or `Automatic-Module-Name`) |
| `release` / `encoding` / `parameters` / `enablePreview` | `getOptions().getRelease()` (fallback `getSourceCompatibility()`) / `.getEncoding()` / `.isParameters()` / `.getAllCompilerArgs()` |
| `.lathe/java-home` (the server JDK) | `getJavaCompiler().get().getMetadata().getInstallationPath()` — the toolchain JDK that compiled, highest across projects, not the daemon's JDK |

`getAllCompilerArgs()` is the *computed* arg list Gradle hands the compiler, so it is largely
observation, not reconstruction.
Recording the **compiling** JDK as `.lathe/java-home` matters on Gradle, where toolchains are the norm:
the server then runs on the JDK that compiled the code, so its API level matches the build even when the project sets
`sourceCompatibility` instead of `release` (the toolchains limitation noted in [Wider Maven and JDK Support](../done/lathe-wider-maven-support.md), open question 6). **The one genuine reconstruction is the module-path/classpath split**
(Gradle's `inferModulePath` decision, which it does not expose) — done with **Gradle's own rule, not plexus-java's**
(see [JPMS](#jpms-java-modules)): when the source set has a `module-info.java`, every entry that *is* a module — a
`module-info.class` (also under `META-INF/versions/`) or an `Automatic-Module-Name` — goes on the module path,
whether or not the module requires it, and every other entry on the classpath. After a successful compile, a
**capture task** mirrors `destinationDirectory` → `.lathe/<module>/<tree>` and writes stamps — the same
`syncOutput` as `LatheCompiler` (see below). Annotation processing is fully visible: processor **path**
(`getEffectiveAnnotationProcessorPath`), processor **options** (`getAllCompilerArgs`), and
**generated-sources output** (`getGeneratedSourceOutputDirectory`), for both main and test.

*Spiked:* a separate task read `classpath`, `annotationProcessorPath`, `allCompilerArgs`
(`[-Xlint:all, -Afoo=bar]`), `generatedSourceOutputDirectory`, `destinationDirectory`, and `release`
through `Provider`s, and the configuration cache stored and reused the entry, on both 8.14.6 and 9.8.1.

**Sibling outputs on the classpath come in two shapes** (spiked).
On the compile classpath a `java-library` sibling is its classes directory (`lib/build/classes/java/main`)
but a plain-`java` sibling is its jar (`plainlib/build/libs/plainlib.jar`);
on the test runtime classpath both are jars (`lib/build/libs/lib.jar`).
The plugin therefore registers every `destinationDirectory` **and** every `Jar.archiveFile` in the
`workspace.json` `outputs` map, so the server and `LaunchPlan` resolve both shapes to the mirror.
Without that map (today's `target/`-shape guessing) neither shape is remapped.

**A capture task per source set, finalizing the compile (decided and spiked 2026-10-23).**
Each project gets `latheCaptureMain` and `latheCaptureTest`, each `dependsOn` its `JavaCompile` and attached
with `compileJava.finalizedBy(...)`, so every build that compiles also captures, with no extra command:

- **Its outputs are the mirror and stamps** under `.lathe/<module>/`, and its inputs the compile's output and
  settings. Gradle's own up-to-date check therefore re-runs it whenever the mirror is missing — a fresh clone,
  Lathe enabled on an already-built project, `.lathe/` wiped — even when `compileJava` is `UP-TO-DATE`, and skips it when nothing changed.
  An action on `JavaCompile` would not run in those cases, because an up-to-date task runs no actions.
- **It is never cached** (`cacheIf { false }`), so the build cache never restores a mirror holding another machine's paths.
- **It leaves `compileJava`'s cache key alone** ([the cache-key rule](#design-principle--leave-the-builds-cache-keys-alone-decided-2026-10-23)).
- **A failed compile skips it**, because its dependency failed — also with `--continue` — so the last good capture stays.
  Mirroring a failed compile's output would empty the module's classes and turn every dependent file red (the Maven side's WS-11).
  Unlike Maven, `lsp-params` is not refreshed on a failed compile either; the next successful one refreshes it.

*Spiked 2026-10-23* on 8.8, 8.14.6, and 9.8.1, with the configuration cache stored and reused: the outcomes
above held on all three. One early run on 8.14.6 skipped the capture under a reused configuration cache; the
same scenario (mirror wiped, compile `UP-TO-DATE`, entry reused) ran the capture on 8.8 and 8.14.6 with fresh
daemons, so it was a daemon left running while the spike's build script was being edited.

**The capture task does what `LatheCompiler.syncOutput` does — all of it (decided 2026-10-23).**
It calls the same moved writer (slice 0), so the two tools cannot drift:
- It mirrors the annotation-processor output (`getGeneratedSourceOutputDirectory()`) to `generated-sources` / `generated-test-sources`.
- It holds `.lathe/<module>/module.lock` while it writes, so the server never reads a half-written mirror.
- `sourceRoots` excludes the annotation-processor output directory, as on Maven: the server reads generated sources from the mirror, never from the build directory.
  Directories other plugins *generate into and add to `srcDirs`* (OpenAPI, protobuf) are real source roots and stay.

**Module directory = project directory relative to the root (decided 2026-10-23).**
`.lathe/<module>/` is named after the project directory, as on Maven, not after the Gradle path (`:a:b`).
`lathe-junit` already derives the module from the test worker's working directory, which is the project directory, so capture and compile land in the same place even for a project whose `projectDir` is remapped in `settings.gradle`.

**Mirror, not `externalOutput` (decided 2026-10-23).**
The contract lets a front-end set `externalOutput` so the server reads `build/classes` in place.
Gradle keeps the mirror instead: `./gradlew clean`, a failed compile, and a build running mid-edit must not take the editor's classes away, which is the mirror's whole purpose on Maven too.

**`jvm.args` (decided 2026-10-23).**
On Maven, `jvm.args` carries the module-access flags that javac plugins such as Error Prone need, read from `-J` compiler args and `.mvn/jvm.config`.
On Gradle the plugin collects the `--add-exports` / `--add-opens` flags, the way `JvmArgsWriter` does, from three places:

- **`JavaCompile.options.forkOptions.jvmArgumentProviders`** — the main source. The `net.ltgt.errorprone` plugin
  puts its flags here, not in `jvmArgs`, and Gradle then compiles in a worker JVM started with them although
  `isFork` stays `false`; it adds them even when the daemon already has them.
- `forkOptions.jvmArgs` — flags a build sets by hand for a forked compile.
- the daemon's `org.gradle.jvmargs` — for a build that wires Error Prone by hand and compiles inside the daemon.

Error Prone is common on Gradle, so without this the server's in-process javac fails on those builds.

*Spiked 2026-10-23* on 9.8.1 with `net.ltgt.errorprone` 4.1.0 and Error Prone 2.36.0: a separate task read
the ten flags from the providers (they are empty at configuration time in `jvmArgs`); `-Xplugin:ErrorProne` and
its `-XD` options came through `allCompilerArgs`, and `error_prone_core` through the processor path.
A standalone javac given the captured compiler args and processor path failed with `IllegalAccessError` without
the flags, and with them as `-J` flags (as `jvm.args` reaches the server JVM) reported the same `[DeadException]`
error as the Gradle build.

## Capture point B — test launch (`TestLaunchData`), in-fork

**Chosen: reuse the `lathe-junit` in-fork listener** — the same mechanism as Maven. Gradle's test
worker is always a real forked JVM, and Gradle passes system properties (`test.systemProperty` → real
`-D`), module directives, and `jvmArgs` as **actual JVM arguments** to that worker, so
`RuntimeMXBean.getInputArguments()` is *literally what Gradle launched*
([Test DSL](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html)). This is the
"observe, don't reconstruct" path, and it captures `-D` properties **more** completely than the Maven
side (which misses Surefire's booter-file `<systemPropertyVariables>`).

*Spiked — simpler than first assumed.*
An earlier draft expected the worker to load the test classpath into an isolated classloader (gradle#3698),
needing classloader-URL recovery. On 8.14.6 and 9.8.1 that is not the case:
`java.class.path` is the full test classpath with only `~/.gradle/caches/<version>/workerMain/gradle-worker.jar`
prepended, and tests load through the JDK's own `AppClassLoader`.
So the one Gradle-specific fix is to **drop the worker jar** from the captured classpath — and from the module
path, where Gradle puts it for a modular test run (see [JPMS](#jpms-java-modules)) — plus filtering
Gradle-internal JVM args (`-Dorg.gradle.internal.worker.tmpdir=…`, and on a modular run
`-Djdk.module.main=gradle.worker`, which names Gradle's worker as the main module; Gradle also adds `-Xmx512m`,
`-ea`, and locale properties, which are real launch settings and stay).
The working directory is the project directory, which is what `LaunchCapture` derives the module from.
`LaunchCapture`'s existing arg parser (`--module-path`, `--patch-module`, `--add-*`) is otherwise reused
verbatim; it already reads the `--option=value` form Gradle uses for module flags.

Capture is driven by `latheSync -Plathe.capture.only=true` running the `test` task in **capture-only** mode (the reused
`CaptureOnlyPostDiscoveryFilter` skips executing tests; the listener still fires) — exactly Maven's
`-Dlathe.capture.only=true` fork.

**Switching to capture-only — a project property, not a task option (decided 2026-10-23).**
Every project's plugin reads `lathe.capture.only` through `providers.gradleProperty` while the build is configured (a configuration-cache input), and when it is `true` configures its own real `test` task:

- `lathe-junit` on its classpath (see below), and `systemProperty("lathe.capture.only", "true")`, which the worker's `CaptureOnlyPostDiscoveryFilter` reads;
- `outputs.upToDateWhen { false }` and `outputs.cacheIf { false }` (see the cache interaction below);
- on Gradle 9+, `failOnNoDiscoveredTests = false` (see below);
- a dependency from `latheSync` on the `test` task, so the one command runs them.

A task option (`latheSync --with-tests`, the first draft of this decision) cannot do this: Gradle applies it to `latheSync` alone, after every project's `test` task is configured, and under Isolated Projects the root may not configure other projects' tasks anyway.
The property keeps Maven's flag name (`LatheFlags.CAPTURE_ONLY`), passed with `-P` like `-Plathe.disabled`; it also stays clear of Gradle's own `Test --tests` filter option.

The real `test` task is reconfigured, not copied: a separate `Test` task built from `test`'s settings would reconstruct the launch, missing whatever a build or plugin adds in `doFirst` actions or argument providers it does not copy.

**Getting the listener into the fork — capture mode only (decided 2026-10-23).**
The project plugin adds `lathe-junit` to the `test` task's runtime classpath only when `lathe.capture.only` is set.
The Maven extension adds it to every build, so a plain `mvn test` refreshes the capture; on Gradle that would change
every `test` task's classpath, and so its cache key, on every Lathe-enabled machine
([the cache-key rule](#design-principle--leave-the-builds-cache-keys-alone-decided-2026-10-23)).
The cost: a plain `./gradlew test` does not refresh `test-launch.json`; a sync with `-Plathe.capture.only=true` does.
Test setup changes rarely, and the server already prompts for a sync when a build file changes.
Capture needs the JUnit Platform (`useJUnitPlatform()`, including JUnit 4 through the vintage engine); a `Test` task on `useJUnit()` or `useTestNG()` opens no launcher session and is not captured — the same stated limitation as Maven's pure-TestNG forks.

**Gradle 9 fails a run with no tests (spiked 2026-10-23).**
Gradle 9 fails a `Test` task whose test sources yield no tests ("the test task did not discover any tests to
execute"), and capture-only filters every test out — observed on 9.8.1.
Setting `failOnNoDiscoveredTests = false` in capture mode fixes it, and the filter still runs in the worker.
Gradle 8.x neither fails nor has the property, so the plugin sets it only when `GradleVersion.current()` is 9.0 or
newer. The plugin is compiled against a newer Gradle API than its 8.8 floor for calls like this one, each guarded
by a version check; the TestKit version matrix catches an unguarded one.

**Cache interaction — capture mode always runs and is never cached** (spiked).
The listener only fires when Gradle actually forks the worker; an `UP-TO-DATE` or `FROM-CACHE` `test`
task forks nothing. Observed on both Gradle versions with `--build-cache`:

- Passing `lathe.capture.only` as a `Test.systemProperty` makes it a task input, so a capture-only run
  never satisfies a later real `test` (the real run executed instead of reusing the capture result).
- But a **second** capture-only sync was served `FROM-CACHE` and forked nothing — no capture.

An earlier draft proposed declaring `test-launch.json` as the task's output. That is wrong: the build
cache would *restore* the file from the cache entry, possibly produced on another machine with its
absolute paths. Instead, in capture mode only, the plugin sets
`outputs.upToDateWhen { false }` and `outputs.cacheIf { false }`.
Spiked with build and configuration caches on: two consecutive capture-only runs both forked, and the
real `test` task kept its own cache entry (`FROM-CACHE` / `UP-TO-DATE` as usual).
The sync script ([Multi-OS](lathe-multi-os.md) D3) passes `--no-configuration-cache`, so formatter-version
observation runs (see [Formatter](#formatter-stylejson)).
It does not need `--no-build-cache` (the first draft passed it): a `FROM-CACHE` `compileJava` still runs its
capture task (spiked 2026-10-23), so a sync keeps the speed of the build cache.

*Fallback (no-fork mode):* build-side derivation from the `Test` task (`getClasspath()`,
`getAllJvmArgs()`, `getSystemProperties()`, `getEnvironment()`, `getWorkingDir()`, `getModularity()`),
for environments where forking the test JVM during sync is undesirable. Documented, not primary.

Replay is unchanged: `LatheTestRunner` replaces Gradle's test worker exactly as it replaces Surefire's
booter, and `LaunchPlan.forTest()` + `ReactorRewrite` are reused verbatim — so none of Gradle's worker
internals affect replay.

## Capture point C — run / main launch (`MainLaunchData`)

Main is not run during a build (Maven doesn't either), so this is **derived build-side**, porting
`MainLaunchWriter` almost verbatim:

- classpath from `sourceSets.main.runtimeClasspath`; reactor siblings appear there as their jars (spiked on the test runtime classpath),
  which `LaunchPlan` maps to the `.lathe/` mirror through the `workspace.json` `outputs` map;
- module vs classpath split by Gradle's rule (every entry that is a module, when the main source set has a
  `module-info.java`; see [JPMS](#jpms-java-modules)), so a run matches what Gradle's own `JavaExec` would do;
- `javaHome` from the resolved `JavaLauncher` (toolchain-aware);
- for an `application`/`JavaExec` task, enrich from `getMainClass()`, `getJvmArgs()`, `getAllJvmArgs()`.

## JPMS (Java modules)

Split the problem into four quadrants — they are not equally hard:

| | Compile (code intelligence) | Launch (run/replay) |
|---|---|---|
| **main** | Gradle's module-path rule applied to `getClasspath()` | the same rule → `mode=MODULE`, `-m mod/Main` |
| **test** | only non-trivial when whitebox patching is set up | the one hard quadrant — but see below |

**The module-path split follows Gradle's rule, not plexus-java's (spiked 2026-10-23).**
When a source set has a `module-info.java`, Gradle puts every entry that *is* a module — a `module-info.class`
or an `Automatic-Module-Name` — on the module path, required or not, and everything else on the classpath.
plexus-java (the Maven side's placement) keeps only the modules the module graph needs.
They disagree on a module the code does not require: in the spike, javac got `commons-lang3` (a module, not
required) and `junit` (an `Automatic-Module-Name` only, not required) on the module path, where plexus-java 1.6.0
put `commons-lang3` on the classpath; both put the required `slf4j-api` on the module path and the plain jars
(`jsr305`, `hamcrest-core`) on the classpath.
Using such a module fails to compile either way, but with different messages, so the editor would disagree with
the build; Gradle's rule is also the simpler of the two. The Gradle front-end therefore applies Gradle's rule.

Three test arrangements exist, observed in the test worker (spiked 2026-10-23, 8.8, 8.14.6, 9.8.1):

- **Plain tests beside a modular main** (no test `module-info`, no plugin) — Gradle runs them on the classpath,
  in the unnamed module, with no module flags ([java_testing](https://docs.gradle.org/current/userguide/java_testing.html)).
  Capture is the ordinary classpath case.
- **Blackbox** (a test `module-info.java`, built into Gradle) — the worker runs with
  `--module-path=…` and `--add-modules=ALL-MODULE-PATH`, the test in its own module.
- **Whitebox** (`--patch-module` merging tests into the main module, plus `--add-reads`/`--add-opens`/`--add-modules`)
  — only when someone wires it: by hand, or through a plugin such as
  [GradleX java-module-testing](https://github.com/gradlex-org/java-module-testing) or
  [gradle-modules-plugin](https://github.com/java9-modularity/gradle-modules-plugin).
  With GradleX the worker received every directive (`--add-modules`, `--add-reads`, `--add-opens`,
  `--patch-module=com.example.app=<test classes>`, `--module-path=…`) and the test ran inside the main module.

In the two modular arrangements `gradle-worker.jar` is on the **module path** and `-Djdk.module.main=gradle.worker`
is set; capture drops both (see [Capture point B](#capture-point-b--test-launch-testlaunchdata-in-fork)).
A listener jar on the test runtime classpath — the `lathe-junit` stand-in — loaded in every arrangement, from
the classpath (unnamed module).

**The in-fork test capture (Capture B) already solves most of this.** Because those directives are real
JVM args on the worker, `getInputArguments()` captures the exact `--patch-module`/`--add-*` Gradle (or
the plugin, or the manual config) actually used — verbatim, no reconstruction. This is the payoff of
choosing in-fork for fidelity: it collapses the JPMS-test-launch problem into the capture we already do.

Layered strategy, most-faithful first:

1. **Test launch directives and module path → in-fork `getInputArguments()`** (Capture B). Ground truth: Gradle
   passes the module path it inferred as a real `--module-path=…` argument, so for tests nothing is reconstructed.
2. **Module path at compile time → Gradle's rule** (above), the one place it is not on any argument list Lathe can read.
3. **add-opens backfill → `LaunchPlan.completeAddOpens()`** already derives `mod/pkg=ALL-UNNAMED` for
   every package in the patched module's test-classes, covering gaps.
4. **Compile-side test module args → `getAllCompilerArgs()`** (manual patching and plugins that use
   `compilerArgs`/`CommandLineArgumentProvider` land here).
5. **Plugin awareness** — detect `java-module-testing` / `moduleplugin`; possibly recommend GradleX
   `java-module-testing` so modular users produce a clean, standard wiring Lathe captures.

**GradleX `java-module-testing` (spiked 2026-10-23).** Its directives reach the `Test` task's public
`allJvmArgs` too, but the module path does not (Gradle computes it at launch), so in-fork capture is the only
complete source — which the design already uses.
GradleX 1.7 and 1.8 do not run on Gradle 9.8.1 at all (they fail on an internal Gradle class,
"Found interface org.gradle.internal.jvm.JavaModuleDetector, but class was expected"); they work on 8.8 and
8.14.6. That is GradleX's compatibility to fix, not Lathe's; Lathe's fixtures use it on Gradle 8.x until it does.

Test **resources** on modular runs go on the classpath, a Gradle quirk to mirror not fight (gradle#38988);
the GradleX run showed the test classes directory on the classpath as well as patched into the module.

## Dependency sources & `workspace.json`

`latheSync` replaces `SyncCoordinator`:

- **`-sources` jars:** `configuration.incoming.artifactView { withVariantReselection(); attributes {
  DocsType.SOURCES, Category.DOCUMENTATION, … } }` — the modern replacement for the now-legacy
  `ArtifactResolutionQuery`.
  *Spiked:* the artifact view resolved most sources jars but **silently skipped Guava 33.4.0**
  (no error even with `lenient(false)`): its Gradle module metadata declares no matching sources
  variant, although Central has `guava-33.4.0-jre-sources.jar`. The legacy query found it, on both
  8.14.6 and 9.8.1 (not deprecated in 9.8.1). So the fallback is **per component** — query the
  classifier for every component the view left out — not a whole-resolution fallback. The legacy
  query is not configuration-cache safe; `latheSync` either opts out
  (`notCompatibleWithConfigurationCache`) or resolves the missing ones through a detached
  configuration of `group:name:version:sources` dependencies.
- **JDK sources, dependency source extraction, type-index shards, server install:** the build-neutral code
  slice 0 moves into `lathe-install` (it lives in `lathe-maven-plugin` today).
- **`WorkspaceManifestData`:** `buildFiles` → `settings.gradle(.kts)`, every `build.gradle(.kts)`,
  `gradle.properties`, version catalogs, and the build logic: `buildSrc/` and every included build that
  contributes plugins (`pluginManagement { includeBuild(...) }`, the usual `build-logic` convention plugins),
  since that is where Gradle builds keep their compiler settings; `outputs` → see Capture A; `resourceRoots` → each source
  set's `resources.srcDirs` + output; `runnerClasspath` → `lathe-test-runner` + JUnit Platform jars
  resolved via a detached configuration, at the versions the project's own test runtime classpath resolves
  (`junit-platform-launcher` at its `junit-platform-commons` version, `junit-jupiter-engine` at its
  `junit-jupiter-api` version), as `ServerInstaller.resolveJUnitSupportJars` does on Maven — a launcher that
  does not match the project's engine breaks replay.
- **Build lock:** `latheSync` (and any build with Lathe enabled) holds and heartbeats `.lathe/lathe.lock`
  like the Maven extension, through a build service that releases it when the build finishes.
- **Per-project facts, merged at the root (decided 2026-10-23).** The root `latheSync` must not read other
  projects' tasks or configurations, or the plugin is no longer Isolated-Projects-safe.
  Each project's `latheFacts` task writes its own facts (outputs, resource roots, its resolved `-sources`
  jars) to a file in its `build/` directory and exposes it as an outgoing artifact; the root task consumes those
  through a dependency on every project and merges them into `workspace.json`.
  *Spiked 2026-10-23* with Isolated Projects on, on 8.8 and 9.8.1 (configuration cache stored and reused): the
  per-project facts tasks ran in parallel, the root merged them, and a project without Java contributed nothing
  and caused no error. Three constraints came out of it, binding on the plugin's code:
  - **Read the project list once the settings file has run** (`settingsEvaluated`), walking the descriptor tree.
    `plugins {}` applies the settings plugin before the file's `include(...)` lines, so at `apply` the list is empty
    (the spike's first run resolved no facts at all).
  - **The `beforeProject` action is a standalone `IsolatedAction` class holding only plain values** (the project
    paths). Gradle serializes it; an action that captures anything else — a Kotlin-script lambda capturing its
    script, a reference to `Settings` — fails with "Failed to isolate 'GradleLifecycle' action".
  - **No JDK immutable collections in that isolated state on 8.8:** a `List.copyOf` field could not be restored
    ("Could not load the value of field"), an `ArrayList` could; 9.8.1 handles both. This is a justified exception
    to the repository's immutable-collections rule, confined to the isolated action.

## Formatter (`style.json`)

The Maven sync reads the Spotless plugin's POM configuration to find the pinned formatter and its
version (or the step's public `defaultVersion()` when the POM leaves it out), resolves its jars, and writes `style.json`.
On Gradle the configuration is DSL calls whose arguments disappear into Spotless internals:
the step **name** is readable, the version and options are not (spiked on Spotless 7.0.2, 2026-10-10).

**Decided 2026-10-18:**

1. **A committed `lathe-style.json` always wins.** The server already merges it per section over the generated `style.json`.
2. **The version is observed, not read from the configuration.** Spotless resolves its formatter through an ordinary
   project configuration (`spotless<hash>`) when its task runs. The project plugin registers
   `configurations.configureEach { incoming.afterResolve { … } }` and records the resolved formatter module
   (`com.google.googlejavaformat:google-java-format:<version>`, and the palantir / Eclipse equivalents).
   It matches the formatter coordinates *and* the `spotless` configuration-name prefix,
   so a project that uses google-java-format as an ordinary library is not mistaken for its formatter.
   - Each project's `latheFacts` depends on that project's `spotless<Format>` task. When nothing changed it is `UP-TO-DATE` and cheap,
     and the resolution still happens, because Spotless resolves the formatter to compute the task's inputs.
   - The sync runs with `--no-configuration-cache`: a run that *reuses* the configuration cache skips configuration, so nothing resolves.
3. **Options** (AOSP style, `reflowLongStrings`, `reorderImports`, `formatJavadoc`) are not observable through public API.
   Sync uses Spotless's own defaults (as Maven does for unset options) and says once:
   *"formatter options come from Spotless defaults; declare them in `lathe-style.json` if your build sets them."*
   A wrong guess is loud (the first save re-indents, CI's `spotlessCheck` disagrees), not silent.

**Rejected:**

- *"Latest" formatter version* — google-java-format sometimes changes output between versions, so formatting on save could fail CI's `spotlessCheck`.
- *Reading Spotless internals by reflection* — breaks across Spotless releases.
- *Formatting through Spotless itself* (`spotlessApply` with the IDE hook) — a Gradle invocation per format, which [Pinned Formatters](../done/lathe-pinned-formatters.md) replaced.
- *Detecting options by formatting probe samples through the IDE hook at sync* — exact, but more moving parts than the problem warrants; it stays a possible upgrade if users with non-default options ask.

## JDK requirement

The plugin and everything it loads (`lathe-core`, `lathe-install`) target **Java 17**
([Wider Maven and JDK Support](../done/lathe-wider-maven-support.md) D7), so:

- the Gradle daemon may run on JDK 17 or newer — Gradle 9's own minimum, and Gradle 8.8+ runs on 17;
- test workers that toolchains run on JDK 17 load `lathe-junit` (also Java 17), so test capture works under toolchains;
- the language server runs on the compiling toolchain JDK recorded in `.lathe/java-home` (see Capture A).

Projects compiling for older targets through toolchains are fine, as long as their *test* JVM is 17+:
`lathe-junit` runs inside it.

## Delivery & gating (the extension analog)

Two registration styles, mirroring Maven's "build extension in the POM" vs "core extension in `.mvn/`":

- **Settings plugin** in `settings.gradle(.kts)` — committed / team (analog of the extension in the root
  POM): `plugins { id("io.github.ag-libs.lathe") version "<lathe-version>" }`. It reads the gating and, if
  enabled, wires `gradle.lifecycle.beforeProject { apply(LatheProjectPlugin) }` (no cross-project
  configuration; Isolated-Projects-safe on 8.8+, together with the per-project `workspace.json` facts in
  [Dependency sources & `workspace.json`](#dependency-sources--workspacejson)) and registers `latheSync` on the
  root and a `latheFacts` task on every project.
- **Init script** (`~/.gradle/init.d/lathe.init.gradle.kts` or `--init-script`) — personal, zero
  build-file edits, machine-wide (analog of `.mvn/extensions.xml`).
  It puts the plugin on its `initscript` classpath and applies the settings plugin in `settingsEvaluated`, only
  when `pluginManager.hasPlugin("io.github.ag-libs.lathe")` is false: a build that commits Lathe in
  `plugins {}` keeps its own pinned version, and the personal script steps aside.

*Spiked 2026-10-23* on 8.8 and 9.8.1 with a stand-in plugin from a local repository: the `init.d` script and
`--init-script` both applied the settings plugin, which applied the project plugin to every project, and the
configuration cache stored and reused the entry; with Lathe both committed in `plugins {}` and in `init.d`,
the committed one applied and the init script stepped aside; with the same script in `init.d` and on
`--init-script`, the plugin applied once.

**Gating precedence — identical to Maven** (`LatheFlags` + `isPomOptOut`), reading Gradle properties:

| Condition | Effect |
|---|---|
| `lathe.disabled=true` in `gradle.properties` | disabled for everyone building the repo |
| `-Plathe.disabled=true` | disabled regardless |
| `-Plathe.disabled=false` | enabled, overrides `CI` |
| `CI` env set | Lathe does not run |
| `.lathe/` exists | overrides the property opt-out (not the absolute kills) |

**Fail-fast requirements check — the counterpart of the Maven side's
[D3](../done/lathe-wider-maven-support.md#d3--fail-fast-clearly-on-an-unsupported-build-decided-2026-10-18) (decided 2026-10-18).**
The settings plugin checks before any project is configured, as `afterProjectsRead` does for Maven: it collects every unmet requirement and,
if there is one, stops the build with one message naming each problem, its fix, and how to build without Lathe. No configuration, no partial support.

| | Maven (shipped) | Gradle |
|---|---|---|
| Tool version | `maven.version`, compared with Maven's `ComparableVersion`; floor 3.6.3 | `GradleVersion.current()`, Gradle's own comparison; floor **8.8** |
| Compiler requirement | each module's `maven-compiler-plugin` 3.6.2+ | none: `JavaCompile` is built into Gradle |
| Way out named in the message | `-Dlathe.disabled=true` | `-Plathe.disabled=true` |

- **A daemon older than JDK 17 cannot load the plugin at all** (Lathe's classes are Java 17), so the check never runs there; Gradle 8.x would show a bare class-version error.
  Gradle 9 requires JDK 17 for its daemon anyway; for 8.x, a small entry class compiled for an older Java checks the running JDK first and fails with a clear message, then loads the rest.
- **The message is shared, the version reading is not.** When this plugin is built, the wording (the heading, one line per problem with its fix, the way out) moves from
  `lathe-maven-extension`'s `LatheRequirements` into `lathe-core`, so both tools say it the same way; each tool keeps its own version reading and comparison.
  Not before: today the Maven extension is its only user.

## End-user setup

1. **Register** — settings plugin (committed) or init script (personal).
2. **Git-ignore** — add `.lathe/` to `.gitignore`.
3. **Opt in** — `mkdir .lathe` (off by default via `lathe.disabled=true` in `gradle.properties`).

### The sync command

```bash
./gradlew latheSync -Plathe.capture.only=true   # first time, and when test setup changes
./gradlew latheSync                             # refresh
```

`latheSync` depends on `compileJava` + `compileTestJava` for every subproject, so one command captures
compiler options + annotation processing (main & test), mirrors bytecode, derives `main-launch.json`,
resolves `-sources`/JDK sources, writes `workspace.json`, and installs the server + client into
`~/.cache/lathe/`.
With `-Plathe.capture.only=true` it also runs each `test` task in capture-only mode and captures
`test-launch.json` (in-fork — **no tests execute**; see [Capture point B](#capture-point-b--test-launch-testlaunchdata-in-fork)).
The split mirrors Maven's `process-test-classes` vs capture-only `test` (*decided 2026-10-23*).

| Maven | Gradle |
|---|---|
| `mvn clean test -Dlathe.capture.only=true` | `./gradlew latheSync -Plathe.capture.only=true` |
| `mvn process-test-classes` (refresh) | `./gradlew latheSync` |
| auto-refresh on `mvn test`/`verify`/`install` | compiler capture refreshes on any build that compiles (`./gradlew build`/`test`/`assemble`); test capture only through a sync with `-Plathe.capture.only=true` (the cache-key rule) |

Force a clean recapture if the build cache masks staleness: `./gradlew clean latheSync`.

**The rendered `.lathe/lathe-sync.{sh,cmd}` (decided 2026-10-23).**
It fills in the [Multi-OS](lathe-multi-os.md) D3 model, with three Gradle-specific points:

- **Module options go after the task.** A task option must follow its task (`latheSync --module app`), and
  Gradle rejects one placed before it. The D3 model today splices `moduleArgs` *before* the goals —
  `SyncScriptTest`'s Gradle-shaped case expects `--module app --module core latheSync` — so the model gains a
  way to place them after the goals (a change to `SyncScript` and its test, made before slice 2).
  Its `testsGoals` for Gradle are `latheSync -Plathe.capture.only=true`, the counterpart of Maven's
  `test -Dlathe.capture.only=true`.
- **It replays how the build was launched.** A build registered through an init script that is not in
  `~/.gradle/init.d/` was started with `--init-script <file>`; the script passes the same flag, or the
  replayed build runs without Lathe. The same holds for the `-P` / `-D` properties the build needed (the
  Maven side's WS-12): the plugin records the start parameters' init scripts and project and system
  properties from `gradle.startParameter` and writes them into `fixedArgs`, leaving out Lathe's own
  (`lathe.capture.only`, `lathe.disabled`), which the script's modes set.
  *Spiked 2026-10-23:* `getInitScripts()` holds exactly the `--init-script` files (the ones to replay;
  `getAllInitScripts()` adds the `init.d` ones, which load anyway), and `getProjectProperties()` /
  `getSystemPropertiesArgs()` hold the `-P` / `-D` values.
- **It runs the Gradle that ran the build, on the JDK that ran it.** As Maven records `maven.home/bin/mvn` (the
  distribution the wrapper resolved, not the wrapper), the plugin records the distribution's own launcher,
  `gradle.gradleHomeDir/bin/gradle` (`gradle.bat` on Windows) — the same version, with no wrapper download step.
  The script's `JAVA_HOME` is the JDK the daemon runs on (the plugin's `java.home`), not the compiling toolchain
  recorded in `.lathe/java-home` for the server; the two differ whenever a project compiles through a toolchain.
- **It builds only what was asked for.** `--module app` builds `app` and, through task dependencies, what
  it depends on — not the projects that depend on it, the same limit as Maven's `-pl … -am` (MC-8).
  A caller that must verify dependents names them.

## Repository & build integration (polyglot)

The lathe repo is a Maven reactor; the Gradle plugin cannot be a normal Maven module, because authoring
a Gradle plugin needs `gradleApi()` and its E2E tests need `gradleTestKit()` — **both are
Gradle-distribution artifacts, not Maven Central jars.** So `lathe-gradle-plugin` is **built by Gradle**,
making the repo polyglot (one Gradle sub-build beside the Maven reactor). It is a **build submodule, not
a git submodule** — same repo, same version, one source tree.

**Dependency flow — a repository the reactor fills (decided 2026-10-18).**
The reactor installs Lathe's artifacts (`lathe-core`, `lathe-install`, `lathe-junit`, `lathe-test-runner`, and the server for the TestKit tests)
into a repository under `target/`, the way the invoker suite fills `lathe-maven-plugin/target/local-repo`.
The Gradle build reads them from there with `maven { url = uri(providers.gradleProperty("latheRepo").get()) }`, passed as `-PlatheRepo=…`,
and never from `~/.m2`:

- `~/.m2` can hold an older build of the same SNAPSHOT, so a build that skipped `install` would silently test stale jars — the invoker suite's 7a bug ([Test Framework](../done/lathe-test-framework.md) §7a, §8).
- With a reactor-filled repository, a plain `mvn verify` tests what it just built, and nothing is written to the developer's `~/.m2`.
- Third-party dependencies still come from Maven Central (and Gradle's own cache).

**Reactor ordering.** `lathe-gradle-plugin` (pom packaging) does not *compile* against `lathe-core`,
so Maven would not know to order it after core — a hazard under parallel `-T` builds.
Declaring `<dependency>` entries on the Lathe modules it consumes (unused for compilation) forces correct ordering,
and listing the module last keeps it so in parallel builds.

Gate the Gradle exec behind a profile / `-Dlathe.gradle.skip=true` so scoped or quick builds
(`mvn -pl lathe-core test`) and contributors who do not touch the Gradle plugin are not forced to run
Gradle or download a distribution.

**The version is passed in, not hardcoded:** the orchestrating `exec-maven-plugin` runs
`./gradlew build -Plathe.version=${project.version} -PlatheRepo=…`, and the Gradle build resolves
`io.github.ag-libs:lathe-core:${lathe.version}` from that repository.
At release, `versions:set` stamps the real version and the same flags flow to Gradle — lockstep with no drift.

**How it is wired — chosen: Maven-orchestrated.** A `pom`-packaged Maven module `lathe-gradle-plugin`,
listed last in the reactor, binds **`exec-maven-plugin`** to run `./gradlew build -Plathe.version=…`
(and the TestKit tests) in a later phase. This reuses the repo's existing pattern — CI already drives
`run-specs.sh` via `exec-maven-plugin` — so a single `mvn install` builds and tests everything, Gradle
included: one entry point, one CI stage. (Alternative, rejected for now: a standalone Gradle sub-build
with its own CI job — more idiomatic for Gradle, but two commands / two CI stages.)

**CI:** the outer build runs one Gradle version; the **Gradle version matrix** (8.8 floor / latest 8.x / latest 9.x)
lives *inside* the TestKit tests via `GradleRunner.withGradleVersion(...)`, not in the outer build.

*Spiked 2026-10-23* with a miniature reactor: a `core` jar module and a `pom`-packaged `gradle-plugin` module
listed last, depending on `core`, whose directory also holds the Gradle build.
`maven-invoker-plugin:install` (the invoker suite's mechanism) filled `target/lathe-repo` with the reactor-built
jar at `package`, and `exec-maven-plugin` ran `gradle build -PlatheRepo=… -Plathe.version=…` at
`integration-test`; the Gradle build took `core` only from that repository (`content { includeGroup(...) }`)
and put its own output under `target/gradle`, so `mvn clean` removes it.
`mvn clean verify` built the plugin and passed a TestKit test on 8.8 and 9.8.1 (configuration cache on) that ran
code from `core`; `-T 4` kept the order; `-Dlathe.gradle.skip=true` skipped both the repository fill and the
Gradle run; a failing TestKit test failed the Maven build; and after changing `core`, the test saw the change —
no stale jar — while `~/.m2` received nothing.
The spike ran an installed Gradle and `withGradleInstallation`; the repository will use the Gradle wrapper and
`withGradleVersion`, which download distributions, so CI caches `~/.gradle/wrapper/dists`.

**Release:** the plugin publishes to the **Gradle Plugin Portal** (`com.gradle.plugin-publish`),
separate from the Maven `central-publishing` flow, with the version pinned in lockstep to the Lathe
release. Wiring this into `release.yml` is a follow-up detail, not part of the core design.

## Testing (end-to-end)

**Harness: Gradle TestKit (`GradleRunner`)** — the official functional-test tool for Gradle plugins and
the direct analog of the Maven-invoker ITs in `lathe-maven-plugin/src/it/`. It runs a **real** Gradle
build against fixture projects, and `withGradleVersion(...)` makes it download/cache the distribution
itself (no system Gradle install; drives the version matrix). Because test capture is in-fork, these
tests exercise a real `test`/capture-only fork.

Two layers, mirroring the Maven side:

1. **Plugin functional tests (TestKit).** Real Gradle build on fixtures; assert the `.lathe/` outputs
   exist and are correct (`lsp-params-*.json`, `classes/`, `test-launch.json`, `main-launch.json`,
   `workspace.json`).
2. **Cross-tool contract tests — the real "no surprises" check.** Since `.lathe/` *is* the seam, take a
   fixture available in **both** Maven and Gradle form, produce `.lathe/` with each, and **diff them**
   (path-normalized); then boot the existing `lathe-server`/`lathe-test-runner` against the
   Gradle-produced `.lathe/` and assert identical diagnostics/replay to the Maven-produced one. This
   directly proves the Gradle front-end does not diverge.

**Fixture matrix:** single-project, multi-project, an annotation-processor project, a **modular main**,
and a **modular whitebox test** (with and without GradleX `java-module-testing`) — so the JPMS quadrants
and the fidelity claims are all under test.
The readiness-review decisions get their own cases: a compile that fails after a good capture (the mirror
survives), an Error Prone project (`jvm.args`), init-script registration replayed by `lathe-sync`, convention
plugins in `buildSrc/` and in an included `build-logic` build (`buildFiles`), a run with the configuration
cache and Isolated Projects enabled, the cache-key check (a build cache filled without Lathe serves every
existing task to the Lathe-enabled build), a `.lathe/` wiped under an up-to-date build (the capture refills it),
and a capture-only test run on Gradle 9.

## Slicing

0. **A Maven-free `lathe-install`** — the logging and artifact-resolver interfaces, and the build-neutral sync work moved in from `lathe-maven-plugin` and `lathe-compiler` (the full list is in [What is reused vs new](#what-is-reused-vs-new)).
   Pure moves behind the existing Maven tests: no behaviour change, the invoker suite stays green.
   Prerequisites, all done before it: the [Build-Tool-Agnostic Core](../done/lathe-build-agnostic-core.md),
   the JDK 17 floor ([Wider Maven and JDK Support](../done/lathe-wider-maven-support.md) slice 0), and all of [Multi-OS](lathe-multi-os.md).
1. **Compiler capture (main + test)** — settings/project plugin + gating + the fail-fast requirements check + the capture
   tasks finalizing `JavaCompile` → `ModuleConfigData`, bytecode and generated-sources mirrors, stamps, `jvm.args`.
   Its TestKit suite includes the cache-key check and the configuration cache stored and reused. Delivers full **code
   intelligence** for main and test.
2. **`workspace.json` + dependency/JDK sources + server install + formatter + the sync script** — `latheSync`; the sync script fills in Multi-OS D3's model (tool, goals, `-Plathe.capture.only=true` for `--tests`, a repeatable `--module` task option placed after the task, the replayed init scripts and properties); the per-project `latheFacts` merge. Completes the editor
   experience (go-to-definition into libraries/JDK, formatting with the project's formatter version).
3. **Run / main launch** — `MainLaunchData` derivation (incl. modular main).
4. **Test launch (in-fork)** — `lathe-junit` on the `test` classpath in capture mode + worker-jar filter + `failOnNoDiscoveredTests = false` on Gradle 9+; capture-only (always-run, never-cached) via `latheSync -Plathe.capture.only=true`, which each project's plugin reads to reconfigure its own `test` task.
   First confirms Gradle 9's no-tests failure and, if needed, sets `failOnNoDiscoveredTests = false` in capture mode.
   Delivers test run/debug/replay for non-modular **and** any modular test whose directives are real JVM
   args (the common case).
5. **Modular test edge cases** — plugin-awareness + `completeAddOpens` backfill + fallbacks for opaque
   arg injection.

## Alternatives considered

- **Build-side test derivation as primary** (read the `Test` task instead of in-fork). Rejected as the
  primary path under "observe, don't reconstruct" — it reconstructs the launch rather than reading it,
  and would miss/duplicate whatever the modular wiring actually does. Kept as a documented no-fork
  fallback.
- **`forkOptions.executable` javac wrapper** to intercept the real compiler (the true Maven-shim
  analog). Rejected: disables caching, forces forking, and is broken/ignored across Gradle 8/9
  (gradle#37179, gradle#23990). Compiler capture uses the typed API instead.
- **`ArtifactResolutionQuery`** for `-sources`. Legacy and slated for removal; fallback only.
- **Tooling API project model** (reconstruct a model out-of-process). Rejected: reconstructs rather than
  reads the real task graph — the drift Lathe avoids.
- **Standalone Gradle plugin repo / git submodule.** Rejected in favor of a build submodule in the same
  repo (one version, one source tree); avoids publishing `lathe-core` just to consume it.

## Open decisions

1. **Repo wiring** — *decided:* Maven-orchestrated `exec-maven-plugin` module (one `mvn` entry point),
   reading Lathe's artifacts from a reactor-filled repository (`-PlatheRepo=…`, never `~/.m2`) with
   `-Plathe.version=${project.version}`, filled by `maven-invoker-plugin:install`. Spiked 2026-10-23 (see
   [Repository & build integration](#repository--build-integration-polyglot)), so the standalone Gradle
   sub-build fallback is not expected to be needed.
2. **GradleX `java-module-testing` injection mechanism** — *answered 2026-10-23:* its directives are real JVM
   arguments of the test worker, so in-fork capture sees all of them, module path included; the task's public
   `allJvmArgs` has the directives but not the module path. GradleX itself does not yet run on Gradle 9.8.1.
   See [JPMS](#jpms-java-modules).
3. **Minimum supported Gradle version** — *decided:* **Gradle 8.8**, the first version where every capture
   point works through the standard plugin API with no fallback. The binding API is
   `gradle.lifecycle.beforeProject` (8.8), which applies the project plugin to every project without
   cross-project configuration (Isolated-Projects-safe); the other capture APIs (`JavaCompile`'s destination
   and generated-source directories, effective processor path, compiler args, toolchains) predate it.
   8.8 itself, 8.14.6, and 9.8.1 are verified by the spikes (8.8 on 2026-10-23: every capture point).
4. **Capture finalizer shape** — *decided and spiked 2026-10-23:* a capture task per source set that depends on
   its `JavaCompile` and finalizes it, with the mirror as its declared output and never cached. It runs when the
   compile is `UP-TO-DATE` but the mirror is missing, is skipped when the compile fails, and leaves the compile's
   cache key alone; an action on `JavaCompile` (the earlier leaning) does none of the first and breaks the last.
   See [Capture point A](#capture-point-a--compiler-arguments--annotation-processing). Test capture (B) is
   settled: always-run, never-cached in capture mode (not an output declaration).
5. **Plugin Portal release wiring** — how `com.gradle.plugin-publish` slots into the tag-driven release.
   Not blocking a slice, but the Portal must approve ownership of the `io.github.ag-libs` plugin ID namespace
   before the first publish, which takes days: register the account and request it early.
6. **Formatter discovery** — *decided:* committed `lathe-style.json` wins; otherwise the version is
   observed from Spotless's resolution and options come from Spotless defaults; see [Formatter](#formatter-stylejson).
7. **Shared sync code** — *decided:* in `lathe-install`, made Maven-free; see [What is reused vs new](#what-is-reused-vs-new).
8. **JDK** — *decided:* Java 17+; see [JDK requirement](#jdk-requirement).
9. **Readiness review** — *decided 2026-10-23:* a Lathe-enabled build leaves the build's own tasks' cache keys
   alone; the capture task keeps the last good capture on a failed
   compile and does all of `syncOutput`; `.lathe/<module>` is the project directory; the mirror stays (no
   `externalOutput`); `jvm.args` comes from the compile's fork-option argument providers (where Error Prone puts its flags), its `jvmArgs`, and `org.gradle.jvmargs`; `lathe-junit`
   joins the `test` classpath only in capture mode; `latheSync -Plathe.capture.only=true` captures tests (a project property, since a task option cannot
   reconfigure other projects' `test` tasks); the sync script places module
   options after the task and replays init scripts and properties; per-project `latheFacts` merged at the root
   keep Isolated Projects safe; build logic is in `buildFiles`; composite builds of Java projects are out.
   Each is detailed in its section.

## Spike results

Run 2026-10-10 on Gradle 8.14.6 and 9.8.1, JDK 21, in a throwaway multi-project build. Identical on both.

| Question | Result |
|---|---|
| Compiler args, AP path, generated dir, output dir, `release` from the typed API, config-cache safe? | Yes |
| Sibling outputs on the compile classpath | `java-library`: `build/classes/java/main`; plain `java`: `build/libs/<name>.jar` |
| Sibling outputs on the test runtime classpath | jars (`build/libs/<name>.jar`) for both |
| `java.class.path` inside the test worker | full test classpath, `gradle-worker.jar` prepended; JDK `AppClassLoader` |
| Test worker working directory / JVM args | project dir; real `-D`/`jvmArgs`, plus `-Dorg.gradle.internal.worker.tmpdir` |
| Capture-only vs real `test` up-to-date | separate (system property is an input) |
| Repeated capture-only under build cache | `FROM-CACHE`, no fork — fixed by `upToDateWhen { false }` + `cacheIf { false }` in capture mode |
| `-sources` via artifact view | most resolve; Guava silently skipped; legacy classifier query finds it |
| Spotless pinned formatter version | name readable, version not (no public API) |
| *2026-10-18:* formatter version via `configurations.configureEach { incoming.afterResolve }` | yes — explicit `1.22.0` and the default (`1.24.0` on Spotless 7.0.2), on 8.14.6 and 9.8.1, also when `spotlessJava` is `UP-TO-DATE` and when the configuration cache is stored; **not** when it is reused |
| *2026-10-18:* formatter options (e.g. `aosp()`) through public API | no — only inside an opaque internal task input |
| *2026-10-23:* capture-only `test` run (every test filtered out) on Gradle 9.8.1 | **fails** ("did not discover any tests"); passes with `failOnNoDiscoveredTests = false`, and the filter runs in the worker |
| *2026-10-23:* the same on Gradle 8.14.6 | passes; `failOnNoDiscoveredTests` does not exist |
| *2026-10-23:* capture task as a `compileJava` finalizer — the compile's cache key | unchanged: `compileJava` `FROM-CACHE` from an entry stored without Lathe |
| *2026-10-23:* compile `UP-TO-DATE`, `.lathe/` wiped | the capture task runs and refills the mirror |
| *2026-10-23:* nothing changed | the capture task is `UP-TO-DATE` |
| *2026-10-23:* compile fails (also with `--continue`) | the capture task is skipped; the last good mirror stays |
| *2026-10-23:* the above with the configuration cache stored and reused | same on both versions; an early 8.14.6 miss was a stale daemon (the same scenario captures with fresh daemons) |
| *2026-10-23:* JPMS, modular main — javac's real arguments vs the typed API vs plexus-java | the typed API reports every jar as classpath; javac got modules (required or not, incl. `Automatic-Module-Name` only) on `--module-path`, plain jars on `-classpath`; plexus-java 1.6.0 put a non-required module on the classpath — so Gradle's rule, not plexus-java's |
| *2026-10-23:* JPMS, tests — plain, blackbox (test `module-info`), GradleX whitebox, on 8.8 / 8.14.6 / 9.8.1 | plain: classpath, no module flags; blackbox and whitebox: every directive and the module path are real worker JVM args (`--opt=value` form), `gradle-worker.jar` on the module path, `-Djdk.module.main=gradle.worker`; a listener jar loads in all three; GradleX 1.7/1.8 fail on 9.8.1 |
| *2026-10-23:* Maven-orchestrated Gradle build — reactor-filled repository via `invoker:install`, `exec-maven-plugin` → `gradle build`, TestKit on 8.8 and 9.8.1 | `mvn clean verify` and `-T 4` pass; the skip flag skips the Gradle part; a TestKit failure fails Maven; the freshly built jar is used and `~/.m2` is untouched |
| *2026-10-23:* Error Prone (`net.ltgt.errorprone` 4.1.0) — where its JVM flags are, and whether they let a separate javac run it | in `forkOptions.jvmArgumentProviders`, not `jvmArgs`, also when `org.gradle.jvmargs` has them; readable from another task; a standalone javac fails without them and reports Error Prone's diagnostics with them |
| *2026-10-23:* Isolated Projects — per-project facts as outgoing artifacts, merged by the root, on 8.8 and 9.8.1 | works, configuration cache reused; the project list must be read in `settingsEvaluated`; the `beforeProject` action must be an `IsolatedAction` holding plain values; 8.8 cannot restore a `List.copyOf` field there (an `ArrayList` works) |
| *2026-10-23:* init-script registration (`init.d`, `--init-script`, and alongside a committed `plugins {}`) on 8.8 and 9.8.1 | applies the settings and project plugins, configuration cache reused; a committed registration wins; never applied twice; start parameters expose the init scripts, `-P` / `-D`, `gradleHomeDir`, and the daemon's `java.home` |
| *2026-10-23:* **the 8.8 floor** — `gradle.lifecycle.beforeProject`, the typed `JavaCompile` reads (classpath, processor path, `allCompilerArgs`, generated and destination dirs, `release`), the toolchain's install path, sibling shapes, `-sources` via the artifact view, formatter observation, the test worker (`gradle-worker.jar`, project-dir cwd, real `-D` args), capture-only tests, and every capture-task outcome above | all as on 8.14.6 and 9.8.1; the toolchain path is the compiling JDK (17), not the daemon's (21); the main runtime classpath has siblings as jars |

Not covered by the spikes: replaying a captured modular launch through `LatheTestRunner` (that is Lathe code,
exercised by slice 4 and 5's TestKit fixtures; the Maven side already replays modular whitebox launches), and
hand-written whitebox wiring (its directives are the build's own JVM arguments, which in-fork capture reads like
GradleX's).
(A toolchain other than the daemon JDK is covered by the 8.8 spike: JDK 17 toolchain, JDK 21 daemon.)

## Non-goals (this design)

- Android Gradle Plugin, and non-Java (Kotlin/Groovy/Scala) compilation.
- Any change to the server, runner, MCP server, clients, or the `.lathe/` schema — the whole point is
  that they are untouched; the changes they need land first in the
  [build-agnostic step](../done/lathe-build-agnostic-core.md).
