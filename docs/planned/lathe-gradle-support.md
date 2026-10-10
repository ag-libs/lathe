# Lathe — Gradle Support

## Status

**Proposed.** Lathe today derives its whole model from a **Maven** build (the
[Maven extension](../done/lathe-maven-extension.md) injects the compiler shim, `init`/`sync` goals, and
the test-capture dependency; [Run, Test, and Debug](../done/lathe-run-test-debug.md) captures and
replays from `.lathe/`). This document designs an equivalent **Gradle** front-end that produces the
identical `.lathe/` contract, so every downstream component — the language server, `lathe-test-runner`,
the MCP server, and all editor clients — works unchanged.

No Gradle code exists yet. This is a design, sliced for incremental delivery.

**Prerequisite:** [Build-Tool-Agnostic Core](lathe-build-agnostic-core.md), done first as its own step.
An audit on 2026-10-10 found that downstream code is not yet fully build-neutral:
sibling-output path mapping assumes Maven's `target/` layout, and several texts and manifest fields are Maven-named.
That step fixes them against Maven alone, so this design stays a pure new front-end.

**Spiked 2026-10-10** on Gradle 8.14.6 and 9.8.1 (JDK 21) with a throwaway multi-project build
(a `java-library` project, a plain `java` project, an app with an annotation processor, JUnit 5, Guava, and Spotless).
Results are folded into the sections below and summarized in [Spike results](#spike-results).
No showstopper was found; both Gradle versions behaved identically.

## Goal

Give a developer on a **Gradle** Java project the same "the tool understands my build" experience Lathe
gives Maven users: javac-accurate diagnostics/completion/navigation for **main and test** sources, and
run/test/debug replayed from captured bytecode — all derived from the real Gradle build, with the same
setup shape (register once, build once, point the editor at the cache).

## Architectural constraint — `.lathe/` is the seam

Lathe's model is build-derived, and the build tool is isolated behind a file contract. The
server, runner, MCP server, and clients **only read `.lathe/`** (plus the machine-wide
`~/.cache/lathe/`); once the [build-agnostic step](lathe-build-agnostic-core.md) lands, none of them
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
| `.lathe/lathe-sync.sh` | sync script ([Sync Launcher](lathe-sync-launcher.md)) | `sync` goal |
| `.lathe/lathe-launcher.sh`, `lathe-mcp-launcher.sh` | links into `~/.cache/lathe/servers/` | `sync` goal |
| `.lathe/lathe.lock` | build lock, heartbeated, stale after 2 minutes (`LatheLock`) | Maven extension (whole build) |

The full list, with semantics, is the workspace contract document the build-agnostic step writes.
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
- Any change to the server, runner, MCP server, clients, or the `.lathe/` schema — the ones that are
  needed land beforehand in the [build-agnostic step](lathe-build-agnostic-core.md).

## What is reused vs new

**Reused unchanged** (after the build-agnostic step): `lathe-core` (schema, `LatheLayout`, `LatheFlags`,
`LatheWorkspace`, `LatheLock`, `LaunchPlan`, the output map, type-index), **`lathe-junit`** (the JUnit
Platform capture listener + `PostDiscoveryFilter`, with only a worker-jar filter — see Capture B), `lathe-test-runner`,
`lathe-server`, `lathe-mcp-server`, all editor clients, and **plexus-java `LocationManager`** for
module/classpath placement.

**New:** a `lathe-gradle-plugin` module (a **settings plugin** + a **project plugin** + a `latheSync`
task), depending on `lathe-core` and resolving `lathe-test-runner`/`lathe-junit` at sync time — the
direct analog of `lathe-maven-extension` + `lathe-maven-plugin`.

## Component mapping: Maven → Gradle

| Maven | Role | Gradle equivalent |
|---|---|---|
| `LatheLifecycleParticipant.afterProjectsRead` + `LatheModelInjector` | wire every module before build | **Settings plugin** using `gradle.lifecycle.beforeProject { }` to apply the project plugin to every project (falls back to `gradle.beforeProject`/`allprojects` on < 8.8) |
| `lathe:init` (mkdir `.lathe/`) | create dir at reactor root | project-plugin apply / `latheInit` |
| `lathe:sync` + `SyncCoordinator` (aggregator, `process-test-classes`) | source jars, JDK sources, server install, `workspace.json`, `main-launch.json` | root task **`latheSync`** |
| `lathe-compiler` (Plexus `Compiler`, `compilerId=lathe`) + `ParamsWriter` | capture compiler args, mirror classes, write stamps | non-cacheable **capture finalizer** on each `JavaCompile` (typed-API capture) |
| `lathe-junit` (`LauncherSessionListener`, in-fork) | capture test JVM launch | **reused** — same listener in Gradle's test fork (drops `gradle-worker.jar`) |
| Aether `resolveArtifact(":sources")` | resolve `-sources` jars | `ArtifactView` + `withVariantReselection()` with `DocsType.SOURCES` |
| plexus-java `LocationManager` | module/classpath split | reused as-is |
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
| `modulepath` | plexus-java split of `getClasspath()` + `module-info.java` |
| `release` / `encoding` / `parameters` / `enablePreview` | `getOptions().getRelease()` (fallback `getSourceCompatibility()`) / `.getEncoding()` / `.isParameters()` / `.getAllCompilerArgs()` |

`getAllCompilerArgs()` is the *computed* arg list Gradle hands the compiler, so it is largely
observation, not reconstruction. **The one genuine reconstruction is the module-path/classpath split**
(Gradle's `inferModulePath` decision, which it does not expose) — done with plexus-java, the same
library and logic Maven uses, so the two agree by construction. After the compile, a **non-cacheable
finalizer** mirrors `destinationDirectory` → `.lathe/<module>/<tree>` and writes stamps — the same
`syncOutput` shape as `LatheCompiler`. Annotation processing is fully visible: processor **path**
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
So the one Gradle-specific fix is to **drop the worker jar** from the captured classpath, plus filtering
Gradle-internal JVM args (`-Dorg.gradle.internal.worker.tmpdir=…`; Gradle also adds `-Xmx512m`, `-ea`,
and locale properties, which are real launch settings and stay).
The working directory is the project directory, which is what `LaunchCapture` derives the module from.
`LaunchCapture`'s existing arg parser (`--module-path`, `--patch-module`, `--add-*`) is otherwise reused
verbatim.

Capture is driven by `latheSync` running the `test` task in **capture-only** mode (the reused
`CaptureOnlyPostDiscoveryFilter` skips executing tests; the listener still fires) — exactly Maven's
`-Dlathe.capture.only=true` fork.

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
`lathe-sync.sh` additionally passes `--no-build-cache` ([Sync Launcher](lathe-sync-launcher.md)) so a
cached `compileJava` does not skip compiler capture either.

*Fallback (no-fork mode):* build-side derivation from the `Test` task (`getClasspath()`,
`getAllJvmArgs()`, `getSystemProperties()`, `getEnvironment()`, `getWorkingDir()`, `getModularity()`),
for environments where forking the test JVM during sync is undesirable. Documented, not primary.

Replay is unchanged: `LatheTestRunner` replaces Gradle's test worker exactly as it replaces Surefire's
booter, and `LaunchPlan.forTest()` + `ReactorRewrite` are reused verbatim — so none of Gradle's worker
internals affect replay.

## Capture point C — run / main launch (`MainLaunchData`)

Main is not run during a build (Maven doesn't either), so this is **derived build-side**, porting
`MainLaunchWriter` almost verbatim:

- classpath from `sourceSets.main.runtimeClasspath` (reactor siblings resolve to their `classes` dir);
- module vs classpath split via plexus-java `LocationManager` on the resolved classpath +
  `module-info.java` (byte-identical to Maven);
- `javaHome` from the resolved `JavaLauncher` (toolchain-aware);
- for an `application`/`JavaExec` task, enrich from `getMainClass()`, `getJvmArgs()`, `getAllJvmArgs()`.

## JPMS (Java modules)

Split the problem into four quadrants — they are not equally hard:

| | Compile (code intelligence) | Launch (run/replay) |
|---|---|---|
| **main** | plexus-java split of `getClasspath()` + `module-info` | plexus-java split → `mode=MODULE`, `-m mod/Main` |
| **test** | only non-trivial when whitebox patching is set up | the one hard quadrant — but see below |

Three quadrants reuse the plexus-java placement Maven already relies on; **modular main is essentially
free.** The subtlety is the modular **test** quadrant, and the key fact is that **Gradle core does
almost nothing here**: a test source set without `module-info.java` is treated as a plain classpath
library and modules are ignored ([java_testing](https://docs.gradle.org/current/userguide/java_testing.html)).
Whitebox module testing (`--patch-module` merging tests into the main module, plus
`--add-reads`/`--add-opens`/`--add-modules`) happens only when someone wires it explicitly — manually,
or via the community plugins that exist precisely because Gradle lacks native support
([gradle-modules-plugin](https://github.com/java9-modularity/gradle-modules-plugin),
[GradleX java-module-testing](https://github.com/gradlex-org/java-module-testing)).

**The in-fork test capture (Capture B) already solves most of this.** Because those directives are real
JVM args on the worker, `getInputArguments()` captures the exact `--patch-module`/`--add-*` Gradle (or
the plugin, or the manual config) actually used — verbatim, no reconstruction. This is the payoff of
choosing in-fork for fidelity: it collapses the JPMS-test-launch problem into the capture we already do.

Layered strategy, most-faithful first:

1. **Test launch directives → in-fork `getInputArguments()`** (Capture B). Ground truth.
2. **Module path → plexus-java** where Gradle *inferred* it (the one thing not on any arg list).
3. **add-opens backfill → `LaunchPlan.completeAddOpens()`** already derives `mod/pkg=ALL-UNNAMED` for
   every package in the patched module's test-classes, covering gaps.
4. **Compile-side test module args → `getAllCompilerArgs()`** (manual patching and plugins that use
   `compilerArgs`/`CommandLineArgumentProvider` land here).
5. **Plugin awareness** — detect `java-module-testing` / `moduleplugin`; possibly recommend GradleX
   `java-module-testing` so modular users produce a clean, standard wiring Lathe captures.

Residue to verify: how GradleX `java-module-testing` injects args (public provider vs internal fork
wiring — only the latter would escape capture); test **resources** on modular runs go on the classpath,
a Gradle quirk to mirror not fight (gradle#38988).

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
- **JDK sources, server install, type index:** reused from `lathe-core`.
- **`WorkspaceManifestData`:** `buildFiles` → `settings.gradle(.kts)`, every `build.gradle(.kts)`,
  `gradle.properties`, and version catalogs; `outputs` → see Capture A; `resourceRoots` → each source
  set's `resources.srcDirs` + output; `runnerClasspath` → `lathe-test-runner` + JUnit Platform jars
  resolved via a detached configuration.
- **Build lock:** `latheSync` (and any build with Lathe enabled) holds and heartbeats `.lathe/lathe.lock`
  like the Maven extension, through a build service that releases it when the build finishes.

## Formatter (`style.json`)

The Maven sync reads the Spotless plugin's POM configuration to find the pinned formatter and its
version, resolves its jars, and writes `style.json`. *Spiked on Spotless 7.0.2:* the Gradle side exposes
the step **name** (`google-java-format` via `SpotlessTask.stepsInternalRoundtrip.steps`) but not the
version, which lives behind lazy lambdas in Spotless internals. There is no public API to read it.

Options, in order of preference:

1. **Committed `lathe-style.json`** names the formatter and version; the server already merges it per
   section over the generated `style.json`. Sync warns when Spotless is applied but no formatter is
   declared.
2. **Bundled formatter** when nothing is declared (today's fallback).
3. **Spotless internals** (force the step's state and read the version by reflection) — works, but
   breaks across Spotless releases; only if users ask for zero-config parity.

This is the one place Gradle does not reach Maven parity without user input.

## Delivery & gating (the extension analog)

Two registration styles, mirroring Maven's "build extension in the POM" vs "core extension in `.mvn/`":

- **Settings plugin** in `settings.gradle(.kts)` — committed / team (analog of the extension in the root
  POM): `plugins { id("io.github.ag-libs.lathe") version "0.1.12" }`. It reads the gating and, if
  enabled, wires `gradle.lifecycle.beforeProject { apply(LatheProjectPlugin) }` (no cross-project
  configuration; Isolated-Projects-safe on 8.8+) and registers `latheSync` on root.
- **Init script** (`~/.gradle/init.d/lathe.init.gradle.kts` or `--init-script`) — personal, zero
  build-file edits, machine-wide (analog of `.mvn/extensions.xml`).

**Gating precedence — identical to Maven** (`LatheFlags` + `isPomOptOut`), reading Gradle properties:

| Condition | Effect |
|---|---|
| `lathe.disabled=true` in `gradle.properties` | disabled for everyone building the repo |
| `-Plathe.disabled=true` | disabled regardless |
| `-Plathe.disabled=false` | enabled, overrides `CI` |
| `CI` env set | Lathe does not run |
| `.lathe/` exists | overrides the property opt-out (not the absolute kills) |

## End-user setup

1. **Register** — settings plugin (committed) or init script (personal).
2. **Git-ignore** — add `.lathe/` to `.gitignore`.
3. **Opt in** — `mkdir .lathe` (off by default via `lathe.disabled=true` in `gradle.properties`).

**The sync command:**

```bash
./gradlew latheSync
```

`latheSync` depends on `compileJava` + `compileTestJava` for every subproject and runs the `test` task
in capture-only mode, so one command: captures compiler options + annotation processing (main & test),
mirrors bytecode, captures `test-launch.json` (in-fork, capture-only — **no tests execute**), derives
`main-launch.json`, resolves `-sources`/JDK sources, writes `workspace.json`, and installs the server +
client into `~/.cache/lathe/`.

| Maven | Gradle |
|---|---|
| `mvn clean test -Dlathe.capture.only=true` | `./gradlew latheSync` |
| `mvn process-test-classes` (refresh) | `./gradlew latheSync` |
| auto-refresh on `mvn test`/`verify`/`install` | auto-refresh on `./gradlew build`/`test`/`assemble` |

Force a clean recapture if the build cache masks staleness: `./gradlew clean latheSync`.

## Repository & build integration (polyglot)

The lathe repo is a Maven reactor; the Gradle plugin cannot be a normal Maven module, because authoring
a Gradle plugin needs `gradleApi()` and its E2E tests need `gradleTestKit()` — **both are
Gradle-distribution artifacts, not Maven Central jars.** So `lathe-gradle-plugin` is **built by Gradle**,
making the repo polyglot (one Gradle sub-build beside the Maven reactor). It is a **build submodule, not
a git submodule** — same repo, same version, one source tree.

**Dependency flow:** `mvn install` publishes `lathe-core` + `lathe-test-runner` (+ `lathe-junit`) to
`~/.m2`; the Gradle sub-build consumes them via `mavenLocal()`.

**Reactor ordering & the `install` requirement.** In a reactor `mvn install`, each module runs its full
lifecycle *including `install`* before the next starts, so `lathe-core` is in `~/.m2` by the time the
plugin module's `exec-maven-plugin` runs Gradle — Gradle picks it up. Two conditions make this reliable:

- **The build must reach `install`.** `mvn package`/`verify` never write to `~/.m2`, so Gradle would not
  see a fresh `lathe-core`. The whole-repo build is therefore `mvn install` — the same constraint the
  Maven-invoker ITs already impose (see `ci.yml`), and what CI already runs.
- **Declare the ordering deps.** `lathe-gradle-plugin` (pom packaging) does not *compile* against
  `lathe-core`, so Maven would not know to order it after core — a hazard under parallel `-T` builds.
  Declaring `<dependency>` entries on `lathe-core`/`lathe-test-runner`/`lathe-junit` (unused for
  compilation) forces correct reactor ordering. Combined with listing the module last, ordering holds
  even in parallel builds.

Gate the Gradle exec behind a profile / `-Dlathe.gradle.skip=true` so scoped or quick builds
(`mvn -pl lathe-core test`) and contributors who do not touch the Gradle plugin are not forced to run
Gradle or download a distribution.

**SNAPSHOT resolution from `~/.m2`.** The Lathe artifacts are `0.1.0-SNAPSHOT`; `mvn install` writes them
under `~/.m2/.../0.1.0-SNAPSHOT/lathe-core-0.1.0-SNAPSHOT.jar` (locally-installed SNAPSHOTs keep the
literal `-SNAPSHOT` name, not a timestamp). Gradle resolves that when:

1. **`mavenLocal()` is declared and listed first** — it is not a default repo, and Gradle reads it
   directly (outside the normal download cache), so a fresh `mvn install` is seen on the next run;
   listing it first makes the local SNAPSHOT win over any remote of the same coordinate. A `-SNAPSHOT`
   version is a *changing module*; add `resolutionStrategy.cacheChangingModulesFor(0, "seconds")` to be
   safe against Gradle's changing-module cache.
2. **The version is passed in, not hardcoded** — the orchestrating `exec-maven-plugin` runs
   `./gradlew build -Plathe.version=${project.version}`, and the Gradle build resolves
   `io.github.ag-libs:lathe-core:${lathe.version}`. In dev that is `0.1.0-SNAPSHOT` (just installed into
   `~/.m2`); at release, `versions:set` stamps the real version, Maven installs it, and the same flag
   flows to Gradle — lockstep with no drift, and the SNAPSHOT/release cases are handled uniformly.

**How it is wired — chosen: Maven-orchestrated.** A `pom`-packaged Maven module `lathe-gradle-plugin`,
listed last in the reactor, binds **`exec-maven-plugin`** to run `./gradlew build -Plathe.version=…`
(and the TestKit tests) in a later phase. This reuses the repo's existing pattern — CI already drives
`run-specs.sh` via `exec-maven-plugin` — so a single `mvn install` builds and tests everything, Gradle
included: one entry point, one CI stage. (Alternative, rejected for now: a standalone Gradle sub-build
with its own CI job — more idiomatic for Gradle, but two commands / two CI stages.)

**CI:** the outer build runs one Gradle version; the **Gradle version matrix** (7.x floor / 8.8 / 9.x)
lives *inside* the TestKit tests via `GradleRunner.withGradleVersion(...)`, not in the outer build.

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

## Slicing

0. **[Build-Tool-Agnostic Core](lathe-build-agnostic-core.md)** — separate, Maven-only step, done first:
   `outputs` map, `buildFiles`, neutral texts, the written workspace contract.
1. **Compiler capture (main + test)** — settings/project plugin + gating + the non-cacheable capture
   finalizer on `JavaCompile` → `ModuleConfigData`, bytecode mirror, stamps. Delivers full **code
   intelligence** for main and test.
2. **`workspace.json` + dependency/JDK sources + server install** — `latheSync`. Completes the editor
   experience (go-to-definition into libraries/JDK).
3. **Run / main launch** — `MainLaunchData` derivation (incl. modular main).
4. **Test launch (in-fork)** — reuse `lathe-junit` + worker-jar filter; capture-only (always-run, never-cached) via `latheSync`.
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
   with `mavenLocal()`-first + `-Plathe.version=${project.version}` for SNAPSHOT/release resolution. A
   standalone Gradle sub-build remains the fallback if the exec orchestration proves awkward.
2. **GradleX `java-module-testing` injection mechanism** — whether its module args reach the public task
   API (build-side/in-fork capture suffices) or only internal fork wiring (needs the in-fork ground
   truth). Determines how self-sufficient modular-test capture is.
3. **Minimum supported Gradle version** — 8.14.6 and 9.8.1 are verified by the spike; the 7.x floor is
   unverified (toolchain and annotation-processor-path APIs; `getGeneratedSourceOutputDirectory` is
   6.4+). Settling on 8.x as the floor is the cheap option.
4. **Capture finalizer shape** — per-task finalizer vs one aggregating task; must stay non-cacheable and
   configuration-cache-safe either way. Reading the `JavaCompile` values through `Provider`s from a
   separate task is spiked and configuration-cache safe. Test capture (B) is settled: always-run,
   never-cached in capture mode (not an output declaration).
5. **Plugin Portal release wiring** — how `com.gradle.plugin-publish` slots into the tag-driven release.
6. **Formatter discovery** — committed `lathe-style.json` + bundled fallback (recommended) vs reading
   Spotless internals; see [Formatter](#formatter-stylejson).

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

Not covered: Gradle 7.x, JPMS (modular main and whitebox test), GradleX `java-module-testing`, toolchains
other than the daemon JDK.

## Non-goals (this design)

- Android Gradle Plugin, and non-Java (Kotlin/Groovy/Scala) compilation.
- Any change to the server, runner, MCP server, clients, or the `.lathe/` schema — the whole point is
  that they are untouched; the changes they need land first in the
  [build-agnostic step](lathe-build-agnostic-core.md).
