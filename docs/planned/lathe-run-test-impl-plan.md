# Lathe Run/Test/Debug — Specification & Implementation Plan

Companion to `docs/planned/lathe-run-test-debug.md`.
That document holds the design *intent* and rationale; this one is the *specification*:
the data flows, the on-disk artifacts, the module/class architecture, the runner contract, and the
build order.
It is written after a validating spike (see §11) and supersedes the class sketches in the design doc.

---

## 1. Specification

### 1.1 Goal

Run and debug a project's tests — and later its `main` classes — from the editor, against the bytecode
Lathe already maintains in `.lathe/`, with **no Maven recompilation and no reactor rebuild** per
invocation.

### 1.2 Invariants (from the design doc, restated as hard rules)

1. **Lathe never runs Maven.**
   Capture rides the user's own `mvn test`; replay is a plain `java` process the server spawns against
   `.lathe/`.
   Anything Lathe cannot capture-and-replay is a documented limitation whose escape hatch is "run Maven
   yourself" — never a server-driven Maven call.
2. **Capture is read from inside the fork**, via public JVM introspection, not by intercepting a command
   line.
3. **Replay is verbatim** except for two deterministic edits: the reactor `target/`→`.lathe/` path
   rewrite, and the capture-jar-out / runner-jar-in classpath swap (§5.3).
   No argument stripping (agents included).
4. **The server is a pure reader** of `.lathe/`; it holds no persistent launch state and reads templates
   fresh per request.

### 1.3 Scope

- **In:** JUnit Platform tests (JUnit 5/6; JUnit 4 via the vintage engine), modular (JPMS) and classpath
  projects, single named class/method runs and package-/module-wide runs, and `main` classes.
- **Out (documented limitations):** non-JUnit-Platform providers (pure TestNG), `forkCount=0`,
  lifecycle-provisioned integration tests, faithful Surefire include/exclude/tag filtering for wide runs,
  coverage reports.

---

## 2. Actors & on-disk artifacts

| Artifact | Written by | Read by | Purpose |
|---|---|---|---|
| `.lathe/<rel>/test-launch.json` | `lathe-junit` capture listener (in the test fork) | server | The captured test-fork launch template (§4) |
| `.lathe/<rel>/main-launch.json` *(optional cache)* | server | server | Derived `main` launch (§6); may be recomputed on demand instead |
| `.lathe/<rel>/lsp-params-{classes,test-classes}.json` | compiler shim (existing) | server | Compile params + completeness signal |
| `.lathe/<rel>/{classes,test-classes}` | compiler shim (existing) | replay JVM | The bytecode replay runs against |
| `workspace.json` | `lathe:sync` (plugin) | server | Adds per-module **runtime classpath** + `resourceRoots` |
| `~/.cache/lathe/lathe-test-runner.jar` | server (materialize on first use) | replay JVM | The replay executor (§7) |
| Parent POM: `lathe-junit` test dep + Surefire pin | `lathe:init` (plugin) / committed | Maven build | Activates capture on the build |

`<rel>` is the module path relative to the workspace root (`workspaceRoot.relativize(moduleDir)`), the
same key the compiler shim uses.

---

## 3. Data flows

### 3.1 Capture (push — rides `mvn test`)

```
user: mvn test           (Surefire pinned to a modern version; lathe-junit is a test dependency)
  └─ Surefire forks the test JVM (modular: --module-path/--patch-module/--add-*)
       └─ JUnit Platform opens a LauncherSession
            └─ CaptureLauncherSessionListener.launcherSessionOpened   (ServiceLoader, auto-registered)
                 ├─ WorkspaceLocator.findWorkspaceRoot(CWD)   (CWD = module dir; walk up to .lathe/)
                 │     └─ not found (no Lathe / CI) → no-op, return
                 ├─ LaunchCapture.toLaunchData(java.home, java.class.path, getInputArguments())
                 └─ write .lathe/<rel>/test-launch.json   (atomic: Json + FileUtil.writeAtomically)
       └─ tests then run normally   (capture is a side effect; fail-open never breaks the run)
```

Key property: the listener fires only inside a real test fork, and only writes when `.lathe/` exists —
so CI and non-Lathe checkouts are inert with the dependency merely present.

### 3.2 Discovery (source-derived — no Maven, no launch)

```
editor: "what can I run in this file?"  →  RunnableScanner.scan(uri)
  └─ AST walk over the cached CompilationUnitTree
       ├─ public static void main(String[])            → kind=main
       ├─ @Test / @ParameterizedTest / @TestFactory /… → kind=test-method
       └─ enclosing test classes                        → kind=test-class
  →  List<Runnable> { id, kind, label, moduleRel, uri, range }   (id carries erased param types)
```

### 3.3 Replay — test (Maven-free hot path)

```
editor: lathe.run(runnableId)
  └─ resolve runnableId → { moduleRel, TestSelection(kind,value) }
  ├─ LaunchTemplateReader.read(moduleRel) → TestLaunchData          (fresh, waits on lathe.lock)
  │     └─ missing OR LaunchFreshness.isStale(...) → notice "run `mvn test` to (re)capture" → STOP
  ├─ CompletenessGate.verify(data, wsRoot)                          (§8) → fail → refuse, notice → STOP
  ├─ ReplayLauncher.launch(data, selection):
  │     ├─ RunnerJarProvider.runnerJar()                            (materialize lathe-test-runner.jar)
  │     ├─ ReplayTransform.forTest(data, wsRoot, selection) → argv  (rewrite + jar-swap + append runner)
  │     └─ spawn `java …` → ReplaySession
  └─ ReplaySession: LatheTestRunner drives the JUnit Platform Launcher in the replay JVM
        ├─ per-test results → (NDJSON, later) → SessionEvents("testResult")
        ├─ program output    → SessionEvents("output")
        └─ process exit      → SessionEvents("exit", code, elapsedMs)
```

### 3.4 Replay — `main` (derived, not captured)

```
editor: lathe.run(mainRunnableId)
  ├─ membership: workspace.json runtime classpath for the module      (recorded by lathe:sync)
  ├─ placement:  LaunchTemplateReader.read(moduleRel) → TestLaunchData (from a prior test run)
  │                 └─ no template → notice "no launch yet — run `mvn test`, or run via Maven" → STOP
  ├─ MainLaunchResolver.resolve(module, mainClass, runtimeCp, data):
  │     ├─ keep only partition entries whose artifact is in runtimeCp  (drops provided/test/junit/booter)
  │     ├─ drop --patch-module; reactor target/→.lathe/ rewrite
  │     └─ launch `-m <module>/<mainClass>`   (classpath main: no -m, no borrowed partition)
  └─ ReplayLauncher spawns `java …`; program output → SessionEvents
```

### 3.5 Resource currency (deferred)

```
editor saves foo.properties  →  ResourceWatcher (workspace/didChangeWatchedFiles)
  └─ byte-copy src/…/resources/<x> → .lathe/<rel>/{classes,test-classes}/<x>   (plain resources: exact)
     filtered resources → user runs `lathe:refresh-resources`  (Maven filtering straight into .lathe/)
```

---

## 4. Capture specification

The capture listener runs inside the test fork and reads the **interpreted** launch from public JVM APIs
(never the argfile, never a Surefire internal):

| `TestLaunchData` field | Source | Notes |
|---|---|---|
| `schemaVersion` | `LatheLayout.TEST_LAUNCH_SCHEMA_VERSION` | writer/reader contract stamp |
| `javaHome` | `System.getProperty("java.home")` | replay uses `<javaHome>/bin/java` |
| `classPath` | `System.getProperty("java.class.path")` | unnamed-module classpath, `File.pathSeparator`-split |
| `modulePath` | `--module-path=` in `getInputArguments()` | corroborated by `jdk.module.path` |
| `patchModules` | `--patch-module=<mod>=<path>` | one entry for the tested module |
| `mainModule` | key of `patchModules` | `""` ⇒ classpath (non-modular) fork |
| `addOpens/Reads/Exports` | `--add-opens/reads/exports=` | target `ALL-UNNAMED` for the provider |
| `addModules` | `--add-modules=` (comma-split) | e.g. `ALL-MODULE-PATH` |
| `jvmArgs` | remaining `-D`/`-X` tokens | from `<argLine>` |

**Known capture gap (spike §11):** `<systemPropertyVariables>` are set by Surefire via a booter
properties file (`System.setProperty` inside the fork) and do **not** appear in `getInputArguments()`.
They are therefore *not* captured from introspection.
Resolution (deferred): `lathe:sync` records the module's configured `systemPropertyVariables` into
`workspace.json`, and replay merges them as `-D` args.

**Precondition:** a modern Surefire (JPMS-capable, e.g. 3.5.5) — an unpinned/old Surefire runs the fork
non-modularly and yields an empty `getInputArguments()`/`jdk.module.*` (spike §11).

---

## 5. Replay transform specification

`ReplayTransform` is pure (template + workspace layout → argv; no I/O, no process spawn).

### 5.1 Keep verbatim
External (`~/.m2`, JDK) module-path/class-path entries, `--add-modules`, `--add-reads`, `--add-opens`,
`--add-exports`, and all `jvmArgs` (`-D`/`-X`, **including any `-javaagent`**).

### 5.2 Reactor rewrite (pure prefix swap)
Any path of the form `<ws>/<rel>/target/(classes|test-classes)` → `<ws>/.lathe/<rel>/\1`, applied to
module-path entries, class-path entries, and `--patch-module` values.
External entries pass through unchanged.

### 5.3 Classpath jar-swap (the split, §12 of the design doc)
- **Remove** the `lathe-junit` capture jar from the class path (so its ServiceLoader listener cannot
  re-fire and clobber the template during replay).
- **Add** `~/.cache/lathe/lathe-test-runner.jar` (so the runner `main` is loadable).

### 5.4 Executor
Append `LatheLayout.TEST_RUNNER_MAIN_CLASS` followed by the selection (`TestSelection.toRunnerArgs()`).
The runner runs from the class path (unnamed module) exactly as Surefire's booter did; the captured
`--add-reads … =ALL-UNNAMED` is what lets the module reflect into the test classes.

### 5.5 `main` (§3.4)
As above, minus `--patch-module`, filtered to the runtime membership set, launching `-m
<module>/<mainClass>` instead of the runner.

---

## 6. Runner contract (`lathe-test-runner`)

A standalone, zero-transitive-dependency jar; `junit-platform-launcher` is `provided` (supplied at
replay time by the captured class path).

**Invocation:** `java <jvm args> <module/class path> io.github.aglibs.lathe.runner.LatheTestRunner <selectors…>`

**Selectors** (`--select-<kind> <value>` pairs → JUnit `DiscoverySelector`s):

| Flag | Selector | Scope |
|---|---|---|
| `--select-class <fqcn>` | `selectClass` | one class (exact) |
| `--select-method <fqcn#method>` | `selectMethod` | one method (exact; erased params) |
| `--select-package <pkg>` | `selectPackage` | all discovered tests in a package |
| `--select-module <moduleName>` | `selectModule` | all discovered tests in a JPMS module |

**Behaviour:** drives `LauncherFactory.create()` + a result listener; **exit code** `0` = all passed,
`1` = failures, `2` = no selectors.
**Fidelity note:** package-/module-wide runs execute everything JUnit discovers — they do **not**
reproduce Surefire include/exclude/tag filtering (design §9).
**Results:** a summary today; a `TestExecutionListener` emitting NDJSON to a sink path (system property)
in the streaming stage.

---

## 7. Module & class architecture

Build order: `lathe-core → lathe-junit → lathe-test-runner → lathe-compiler → lathe-server →
lathe-maven-plugin`.

### 7.1 `lathe-core` — schema + pure launch logic
| Class | Role | Key methods |
|---|---|---|
| `schema.TestLaunchData` (record) | Captured test-fork template | validating compact ctor |
| `LatheLayout` (edit) | `TEST_LAUNCH_FILE`, `MAIN_LAUNCH_FILE`, `TEST_RUNNER_MAIN_CLASS`, `TEST_LAUNCH_SCHEMA_VERSION` | — |
| `WorkspaceLocator` | Walk up to `.lathe/` | `findWorkspaceRoot(Path): Optional<Path>` |
| `launch.ReplayTransform` | Pure template → argv | `forTest(data, wsRoot, sel)`, `forMain(data, runtimeCp, wsRoot, module, mainClass)` |
| `launch.ReactorRewrite` | Pure path rewrite + jar-swap | `toLathe(path, wsRoot)`, `swapLatheJars(cp, runnerJar)` |
| `launch.TestSelection` (record) | `{kind, value}` → runner flags | `toRunnerArgs(): List<String>` |

### 7.2 `lathe-junit` — capture (on the user's test class path)
| Class | Role | Key methods |
|---|---|---|
| `CaptureLauncherSessionListener` | ServiceLoader hook; `.lathe/`-gated; fail-open; writes `test-launch.json` | `launcherSessionOpened`, `capture` |
| `LaunchCapture` | Pure introspection → `TestLaunchData` | `toLaunchData(javaHome, classPath, inputArgs)` |
| `CaptureOnlyPostDiscoveryFilter` *(deferred)* | `-Dlathe.capture.only` → exclude all | `apply` |

### 7.3 `lathe-test-runner` — replay executor
| Class | Role | Key methods |
|---|---|---|
| `LatheTestRunner` | `main` → JUnit Platform `Launcher`; class/method/package/module | `main`, `parseSelectors` |
| `NdjsonExecutionListener` *(deferred)* | `TestExecutionListener` → NDJSON sink | event callbacks |

### 7.4 `lathe-maven-plugin` — push-side setup
| Class | Role | Key methods |
|---|---|---|
| `InitMojo` → `PomSetup` | Add committed `lathe-junit` test dep + Surefire pin | `ensureCaptureDependency` |
| `SyncMojo` → `RuntimeClasspathRecorder` | Record runtime classpath + `resourceRoots` into `workspace.json` | `record(project)` |
| `RefreshResourcesMojo` → `ResourceFilterer` *(deferred)* | Filter resources into `.lathe/` | `filterInto` |

### 7.5 `lathe-server` — LSP orchestration (reader only)
| Class | Role | Key methods |
|---|---|---|
| `run.RunnableScanner` | Source-derived discovery | `scan(uri): List<Runnable>` |
| `run.Runnable` (record) | `{id, kind, label, moduleRel, uri, range}` | — |
| `run.LaunchTemplateReader` | Read `test-launch.json` fresh, lock-aware | `read(moduleRel): Optional<TestLaunchData>` |
| `run.LaunchFreshness` | POM + `module-info.java` fingerprints | `isStale(data, moduleRel): boolean` |
| `run.CompletenessGate` | Pre-launch presence checks (§8) | `verify(data, wsRoot): Result` |
| `run.MainLaunchResolver` | Derive `main` launch (membership ∩ placement) | `resolve(module, mainClass): List<String>` |
| `run.RunnerJarProvider` | Materialize embedded runner jar | `runnerJar(): Path` |
| `run.ReplayLauncher` | Build argv + spawn | `launch(data, sel): ReplaySession` |
| `run.ReplaySession` | Own the replay process + result stream | `start`, `cancel`, `pid` |
| `run.RunCommands` | `executeCommand`: `runnables.list`, `run`, `debug`, `session.*` | one handler each |
| `run.SessionEvents` | Emit `lathe/sessionEvent` | `emit` |
| `debug.DebugAdapter` *(stage 2)* | JDWP inject + in-process `java-debug` DAP | `attach` |
| `resource.ResourceWatcher` *(deferred)* | `didChangeWatchedFiles` → copy into `.lathe/` | `onChange` |

---

## 8. Gates & guards

- **Freshness** (§7.5 `LaunchFreshness`) — re-capture prompt when the POM (module + inherited parents,
  via `pomPaths`) or `module-info.java` **content fingerprint** moves; editing test/main code never
  invalidates a template (the launch line is structural).
- **Completeness** (`CompletenessGate`) before every launch — refuse rather than run wrong bytecode:
  (1) build settled (`lathe.lock` absent or stale ≥ 2 min); (2) shim ran (`lsp-params-*.json` exist);
  (3) rewritten `.lathe/<dep-rel>/{classes,test-classes}` exist and are non-empty.
- **Version stamp** — `schemaVersion` reconciles the user's committed `lathe-junit` with their server.
- **Capture fail-open** — any capture error skips the template and never breaks the test run.
- **No replay re-capture** — guaranteed structurally by the class-path jar-swap (§5.3), not a runtime flag.

---

## 9. Build slices (each a reviewable commit)

1. **`lathe-core`** — `TestLaunchData`, `WorkspaceLocator`, `LatheLayout`, `ReactorRewrite` +
   `ReplayTransform`, `TestSelection` (pure, fully unit-tested).
2. **`lathe-junit`** — capture listener + `LaunchCapture`.
3. **`lathe-test-runner`** — runner + selectors.
4. **Invoker wiring + capture assert** *(GO/NO-GO #1)* — Surefire pin, `lathe-junit` on `jpms`, `verify`
   asserts `test-launch.json`; **root-cause the exit-handshake crash here (§11)**.
5. **Server replay (test)** — `LaunchTemplateReader`, `CompletenessGate`, `ReplayLauncher`,
   `RunnableScanner`, `run` command; smoke: `HelloTest` green from `.lathe/`.
6. **`main` run** *(GO/NO-GO #2)* — `RuntimeClasspathRecorder` + `MainLaunchResolver`; modular
   (`jpms` `HelloMain`) + classpath (`app` `Main`) green.
7. **Deferred** — freshness refinement, capture-only filter, NDJSON streaming + `sessionEvent`, debug,
   resource watch, `systemPropertyVariables` capture.

---

## 10. Test fixtures (invoker `multi-module`)

- **`jpms`** — modular (`module-info`, `requires validcheck`), a JUnit test, and (to be added) a
  `HelloMain` → exercises modular test capture *and* modular `main` (the borrowed-placement path).
- **`app`** — classpath `Main`, depends on reactor `core` (compile) + a `provided` processor → classpath
  `main`, provided-exclusion, reactor rewrite.
- **`verify`** — reads `.lathe/` and spawns the replay via `ProcessBuilder`; asserts capture fidelity and
  green replay (test + main).
- **Harness requirement:** the reactor must pin a modern Surefire (spike §11).

---

## 11. Spike outcomes (validated / learned)

- ServiceLoader `LauncherSessionListener` **fires under Surefire with zero config**; a non-modular jar
  lands on the class path (unnamed module) and reads `java.management`/JUnit fine.
- Modular forking **requires a modern Surefire** (pin 3.5.5); unpinned/old runs the fork non-modularly
  (flat class path, empty `getInputArguments()`/`jdk.module.*`).
- Modular `getInputArguments()` yields `--module-path=`/`--patch-module=`/`--add-*` plus `argLine`
  `-D`/`-X`, `@argfile`-expanded; `java.class.path` yields the class path.
- **`<systemPropertyVariables>` is not visible** to `getInputArguments()` (booter properties file) →
  capture gap (§4).
- **Open — must fix before slice 5:** Surefire 3.5.5 + JDK 26 exit-handshake crash (`Tests run: 0`,
  "VM terminated without properly saying goodbye"). Root-cause before relying on replay.

---

## 12. Open questions

1. **Exit-handshake crash** — harness, Surefire/JDK-26, or the modular fork? (blocks slices 4–5)
2. **`systemPropertyVariables`** — record plugin-side and merge at replay (confirm mechanism).
3. **`main` JVM args** — which captured `--add-*`/`argLine` carry to a `main` run vs. are test-only.
4. **`main-launch.json`** — persist a cache, or always derive on demand?
5. **`lathe-server` package layout** — do the `run.*` classes above fit the existing command/discovery
   structure, or fold into current classes?
6. **Runner delivery** — server-materialized from an embedded jar (current plan) vs. published coordinate.
