# Lathe — Run, Test, and Debug

Design and specification for running and debugging tests and `main` classes against the bytecode Lathe
already maintains in `.lathe/`, with no Maven recompilation per invocation.
Builds on `lathe-design.md` (especially §5 Compiler Shim and §6 Module Model).

**Scope.**
In: JUnit Platform tests (JUnit 5/6; JUnit 4 via the vintage engine), modular (JPMS) and classpath
projects, single named class/method runs and package-/module-wide runs, and `main` classes.
Out (documented limitations, §9): non-JUnit-Platform providers (pure TestNG), `forkCount=0`,
lifecycle-provisioned integration tests, faithful Surefire include/exclude/tag filtering for wide runs,
coverage reports.

This document merges design intent (why capture-replay, why in-fork introspection) with specification
(on-disk artifacts, data flows, module/class architecture, the runner contract, and the build order).
It goes deep on the three load-bearing parts — **capture**, **constructing correct runtime arguments**
(for tests and for `main`), and **resource refresh** — and treats the exact LSP command/streaming
schemas, the debug adapter wiring, and editor integration as derivable later, sketched only at the level
needed to scope the server-side commands (§10).
It is written after a validating spike (§14) and is the authoritative reference — no separate class
sketches or implementation plan exist elsewhere.

---

## 1. Principle and invariants

Lathe runs a test by **capturing** the exact JVM launch the build already computed for the test fork —
the modular command line, the module-path/class-path partition, the JPMS flags, the system properties —
and then **replaying** a fresh JVM from that captured template, with reactor output paths rewritten
from Maven's `target/` to Lathe's `.lathe/<rel>/`.

Four invariants shape everything:

- **Lathe never runs Maven.**
  Capture rides the user's normal `mvn test`; replay is a plain JVM the server spawns against `.lathe/`.
  Maven is the sole source of truth for the launch line, and the server stays a pure reader of `.lathe/`
  (`lathe-design.md` §6).
  Every execution Lathe cannot faithfully capture-and-replay is a **documented limitation** whose escape
  hatch is "run Maven yourself" (§9) — never a server-driven Maven delegation.

- **The default fork is the faithful launch.**
  Surefire's default (`forkCount=1`) forks a real JVM, and for a modular project that fork is a correct
  modular launch — exactly what replay needs.
  Riding the fork the build already makes is both free and JPMS-correct.
  `forkCount=0` (in-process) has no launch line and is not replayable (§9).

- **Capture reads the launch from inside the fork, via public JVM APIs.**
  Lathe does not intercept or reconstruct Surefire's command line from the outside.
  A JUnit Platform listener runs *inside* the already-launched test JVM and reads the effective launch
  back out of the JVM's own runtime view (§3).
  This keeps the dependency on the **supported** junit-platform provider contract, not on Surefire
  internals.

- **Replay is verbatim except for two deterministic edits.**
  The reactor `target/`→`.lathe/` path rewrite, and appending Lathe's own runner jar and entry point
  (§4.3).
  No argument stripping, agents included.
  The server holds no persistent launch state and reads templates fresh per request.

### Why capture-replay and not "just run Maven"

Delegating each run to `mvn -pl <mod> test` forces a bad choice:
without `-am` the test resolves upstream modules from a `target/classes` Maven only refreshes on an
explicit build (**stale**);
with `-am` Maven rebuilds the whole upstream reactor every run (**slow**).
Capture-replay dissolves this by rewriting reactor output paths to `.lathe/<dep-rel>/classes` — the
copies Lathe keeps current incrementally (shim copy on every build, LS save passes per file, lock
protocol guarding mid-copy reads).
A replayed run gets `-am`-level freshness without `-am`'s recompilation, and is never *more* stale than
the diagnostics the user already trusts.

---

## 2. Architecture overview

Three stages, decoupled in time.

```
CAPTURE (push — rides the user's mvn test)        REPLAY (per run/debug, Maven-free)
──────────────────────────────────────────       ───────────────────────────────────────
user runs: mvn test                               server reads .lathe/<rel>/test-launch.json
  Surefire forks the test JVM →                     → rewrite reactor target/ → .lathe/
  lathe-junit LauncherSessionListener runs:         → keep JPMS flags + jvmArgs verbatim
    reads java.class.path / jdk.module.* /          → append lathe-test-runner.jar + entry point
    getInputArguments(), writes test-launch.json     → java … (fresh JVM)
    via lathe-core (tests run normally)
                                                          • JDWP-attachable (debug)
                                                          • real-time results via NDJSON
                                DISCOVERY (source-derived, no Maven)
                                ───────────────────────────────────────
                                AST walk over the cached CompilationUnitTree
                                → runnables (main / test-class / test-method)
```

Capture rides the fork Surefire already makes and never disturbs it — the tests run normally after the
snapshot is written.
Replay is the hot path: no Maven, no compilation, sub-second, driven from the template plus `.lathe/`
bytecode.
Discovery is independent and always source-derived.

### 2.1 On-disk artifacts

| Artifact | Written by | Read by | Purpose |
|---|---|---|---|
| `.lathe/<rel>/test-launch.json` | `lathe-junit` capture listener (in the test fork) | server | The captured test-fork launch template (§3) |
| `.lathe/<rel>/main-launch.json` | `lathe:sync` (plugin), every module, every build | server | Derived runtime launch template, main-class-agnostic (§4.2) |
| `.lathe/<rel>/lsp-params-{classes,test-classes}.json` | compiler shim (existing) | server | Compile params + completeness signal |
| `.lathe/<rel>/{classes,test-classes}` | compiler shim (existing) | replay JVM | The bytecode replay runs against |
| `workspace.json` | `lathe:sync` (plugin) | server | Adds per-module `resourceRoots` (runtime classpath lives in `main-launch.json`) |
| `.lathe-run.json` | user / editor | Neovim + server | Named run/debug configs; thin overlay only (§8) |
| `~/.cache/lathe/lathe-test-runner.jar` | server (materialize on first use) | replay JVM | The replay executor (§5) |
| Parent POM: `lathe-junit` test dep + Surefire pin | `lathe:init` (plugin) / committed | Maven build | Activates capture on the build |

`<rel>` is the module path relative to the workspace root (`workspaceRoot.relativize(moduleDir)`), the
same key the compiler shim uses.

---

## 3. Capture

### 3.1 The in-fork listener

Capture is a `org.junit.platform.launcher.LauncherSessionListener` (JUnit Platform 1.8+, stable through
JUnit 6), shipped in the `lathe-junit` module (§3.4).
Its `launcherSessionOpened` fires once, before discovery and execution, with the JVM fully up — the
earliest point at which the effective launch is readable.
It reconstructs the launch from **public JVM introspection**, never from Surefire's argfile:

| `TestLaunchData` field | Source | Notes |
|---|---|---|
| `schemaVersion` | `LatheLayout.TEST_LAUNCH_SCHEMA_VERSION` | writer/reader contract stamp |
| `javaHome` | `System.getProperty("java.home")` | replay uses `<javaHome>/bin/java` |
| `classPath` | `System.getProperty("java.class.path")`, `File.pathSeparator`-split | **excludes the listener's own jar** (see below) |
| `modulePath` | `--module-path=` in `getInputArguments()` | corroborated by `jdk.module.path` |
| `patchModules` | `--patch-module=<mod>=<path>` | one entry for the tested module |
| `mainModule` | key of `patchModules` | `""` ⇒ classpath (non-modular) fork |
| `addOpens`/`addReads`/`addExports` | `--add-opens`/`--add-reads`/`--add-exports=` | target `ALL-UNNAMED` for the provider |
| `addModules` | `--add-modules=` (comma-split) | e.g. `ALL-MODULE-PATH` |
| `jvmArgs` | remaining `-D`/`-X` tokens | from `<argLine>` |

`getInputArguments()` returns arguments **after** the JVM has expanded any `@argfile`, so there is no
argfile to tokenize — Lathe reads the already-interpreted `--add-opens`/`--module-path`/`-D` list
directly.
The classpath is read from the `java.class.path` property (the one field `getInputArguments()` handles
inconsistently across JDKs), and the module directives are cross-checked against the `jdk.module.*`
properties.

**The listener excludes its own jar from the recorded `classPath`.**
It resolves its own location via `getClass().getProtectionDomain().getCodeSource().getLocation()` and
drops that entry before writing `TestLaunchData`.
This means `test-launch.json` never records `lathe-junit` as part of the launch — replay does not need to
find-and-remove it later (§4.3); it only ever *appends* the runner jar.
See §11 for why this is a structural guarantee rather than a runtime-checked one.

This makes the captured template the **interpreted** launch, which is what replay needs — Lathe cares
about the resulting module graph, not about how Surefire tokenized its command line.

**Known capture gap.**
`<systemPropertyVariables>` are set by Surefire via a booter properties file (`System.setProperty` inside
the fork) and do **not** appear in `getInputArguments()`.
They are therefore *not* captured from introspection.
Resolution (deferred, §15): `lathe:sync` records the module's configured `systemPropertyVariables` into
`workspace.json`, and replay merges them as `-D` args.

**Precondition:** a modern Surefire (JPMS-capable, e.g. 3.5.5) — an unpinned/old Surefire runs the fork
non-modularly and yields an empty `getInputArguments()`/`jdk.module.*` (§14).

### 3.2 Writing the template

The listener writes the final `TestLaunchData` straight into `.lathe/<rel>/test-launch.json`,
using `lathe-core` — the same way the compiler shim writes `lsp-params-*.json`.
There is no intermediate snapshot and no server-side parse: the listener already holds the interpreted
values (§3.1), so it maps them into `TestLaunchData` and serializes with `lathe-core`'s `Json`,
resolving paths through `LatheLayout` and writing through `FileUtil.writeAtomically`.
Reusing those helpers is not merely convenient — it is required: locating `.lathe/<rel>/` and writing
there is exactly the layout and path logic CLAUDE.md forbids reimplementing, which is the reason
`lathe-junit` depends on `lathe-core` (§3.4).

The write is **atomic** (temp file + rename), so the server never observes a partial file — the same
discipline as the compiler shim.

### 3.3 Freshness

`test-launch.json` is a **peer of `lsp-params-*.json`**:
on disk in `.lathe/<rel>/`, lock-guarded (the server waits while `lathe.lock` exists), and read fresh
per request (no in-memory cache).
The server holds no persistent launch state.

```json
{
  "schemaVersion": "1",
  "kind": "surefire",
  "javaHome": "/opt/jdk",
  "mainModule": "com.example.jpms",
  "modulePath": ["/ws/mod/target/classes", "/home/u/.m2/.../guava-33.4.0-jre.jar"],
  "classPath": ["/home/u/.m2/.../junit-platform-launcher-1.11.jar", "/ws/mod/target/test-classes", "..."],
  "patchModules": { "com.example.jpms": "/ws/mod/target/test-classes" },
  "addOpens":   ["com.example.jpms/com.example.jpms=ALL-UNNAMED"],
  "addReads":   ["com.example.jpms=ALL-UNNAMED"],
  "addExports": [],
  "addModules": ["ALL-MODULE-PATH"],
  "jvmArgs":    ["-Dfoo=bar", "-Xmx512m"]
}
```

Paths are recorded **as captured** (Maven's `target/`);
the reactor→`.lathe/` rewrite happens at replay so that logic lives in one place (§4.2).
The module-path/class-path partition is recorded **exactly as the JVM reported it** — an automatic
module promoted to the module path by a `requires` lands on `modulePath` while its transitive plain jars
land on `classPath`, with no Lathe-side heuristic reproducing this; capture inherits it.

The launch line depends only on **structural** inputs, so a captured template is refreshed only when one
moves:

- the **POM fingerprint** (module + inherited parents, via `workspace.json` `pomPaths`) — Surefire
  config, `argLine`, and the dependency set derive from the POM;
- the **`module-info.java` fingerprint** — a changed `requires` can move a dependency between module
  path and class path, i.e. change the very partition capture records.

Both are **content fingerprints, not mtimes**.
Editing a test or a main class changes `.lathe/` bytecode but not the launch line, so the template stays
valid and the inner loop never re-captures.
*Known gap:* an upstream reactor dependency **modularized in place** (its `module-info` edited with no
version bump) can re-partition this module's fork without tripping either fingerprint — rare; it
surfaces as a launch/classpath error a manual re-capture clears, not a silent green.

**Version coordination.**
`test-launch.json` is written by the user's *committed* `lathe-junit` and read by their *server* — two
independently updated artifacts.
The `schemaVersion` stamp guards the split: the server tolerates and warns on a mismatch it can still
read, and refuses one it cannot.

### 3.4 `lathe-junit` — a published module, injected as one test dependency

`lathe-junit` is a normal published Maven Central artifact:
a small jar that depends only on `lathe-core` (for `LatheLayout`, `FileUtil`, `Json`, and
`TestLaunchData`), with `junit-platform-launcher` as a `provided` dependency and no `module-info` (it
runs as an unnamed-module helper inside the test JVM).
It carries **only** the capture-side roles: the `LauncherSessionListener` and the capture-only
`PostDiscoveryFilter` (§3.5).
The replay-side executor is a **separate** module, `lathe-test-runner` (§5, §10.3) — `lathe-junit`
carries no replay code, and no runtime mode-detection lives inside it (§11).

Because it is a committed `test` dependency, `lathe-junit` runs on the user's test classpath, so its
`lathe-core` dependency ships there too.
That is safe as long as `lathe-core` stays what it is today — small and free of external dependencies —
so it drags nothing transitive onto user classpaths and its `module-info` is simply ignored on the
classpath.
The constraint this imposes: `lathe-core` must remain test-classpath-safe (no server-only bulk).
If that ever becomes a concern, the escape is a tiny `lathe-schema` leaf shared by both `lathe-core` and
`lathe-junit` — not warranted now.

It reaches the test classpath as **one committed `test`-scope dependency** in the parent POM, added by
**the user**, alongside the compiler shim and plugin declaration blocks (`lathe-design.md` §3 —
Lathe already requires hand-editing the parent POM for those, so this is a third line in that same
one-time edit, not a new category of friction):

```xml
<dependency>
  <groupId>io.github.ag-libs</groupId>
  <artifactId>lathe-junit</artifactId>
  <version>${lathe.version}</version>
  <scope>test</scope>
</dependency>
```

This is the whole footprint.
It collapses the machinery a launch-line interceptor would need:

- **No Surefire configuration.**
  The listener and filter **auto-register via ServiceLoader** off the test classpath (JUnit Platform's
  default `LauncherConfig`).
  This relies on Surefire's junit-platform provider honoring ServiceLoader-registered launcher listeners
  and filters — the documented default.
- **Activation gates on `.lathe/` presence**, not a flag.
  The listener walks up from the fork's working directory (Surefire forks with CWD = module basedir) to
  find `.lathe/<rel>/`, exactly as the compiler shim locates the workspace.
  A checkout without Lathe has no `.lathe/`, so the listener no-ops — CI and teammates are inert with no
  per-machine setup, even though the dependency is committed.

A committed tooling dependency on the build is a deliberate, visible cost, consistent with the "material
and committed" philosophy of the compiler-shim setup.
It is inert without `.lathe/`, and the listener it registers is instantiated per fork but returns
immediately when no workspace is found.
`lathe:init` never writes to or inspects `pom.xml` for this dependency — a Maven plugin silently
rewriting (or even auditing) a committed, user-owned file is a materially bigger blast radius than
generating files under the disposable `.lathe/` directory, and setup already documents this snippet
alongside the compiler shim and plugin declaration (`lathe-design.md` §3).

**Scope.**
Capture is **JUnit-Platform-only**.
JUnit 4 via the vintage engine still opens a launcher session and is captured;
pure TestNG or other non-platform providers are not — a stated limitation (§9), and the natural seam for
a future sibling module, since the snapshot contract is about the JVM launch, not the test framework.

### 3.5 Capture-only mode

For "refresh the launch template without paying for a test run," `lathe-junit` also registers a
`PostDiscoveryFilter` that returns `FilterResult.excluded(...)` for every descriptor when
`-Dlathe.capture.only=true` is set (read via `System.getProperty`, which the fork sees reliably through
Surefire's booter properties file).
The listener still writes the snapshot; discovery runs; then zero tests execute and the fork completes
Surefire's normal handshake with an empty result set.
The invoking command passes `-DfailIfNoTests=false` so an empty plan does not fail the build.

This is user-invoked (e.g. `mvn test -Dlathe.capture.only=true`), never a server command — the server
never runs Maven.
It is close to free (one small filter class) and mostly a convenience, since capture already rides every
real `mvn test`.

This flag's blast radius if misconfigured is contained to "ran/didn't run tests this round" — visible
immediately in the build output.
That is a different, much safer failure mode than a hypothetical replay-side mode flag would have (§11).

### 3.6 Fail-open

The listener runs in-process, so a thrown exception in `launcherSessionOpened` could break the user's
test session.
Every capture path is wrapped in a catch-all: any failure skips the snapshot (and its marker) and lets
the test run proceed untouched.
A capture failure means "no fresh template this round," never a broken build.

---

## 4. Constructing runtime arguments

Replay reads the template, applies a deterministic transform, and launches a fresh JVM.
No Maven, no compilation.
`ReplayTransform` is pure (template + workspace layout → argv; no I/O, no process spawn).

### 4.1 Tests — what to keep, what to rewrite

**Keep verbatim:**
external (`~/.m2`, JDK) `--module-path`/`--class-path` entries, `--add-modules`, `--add-reads`,
`--add-opens`, `--add-exports`, and the `-D`/`-X` `jvmArgs`.

**Strip nothing.** Replay keeps every captured argument verbatim — including any `-javaagent`
(coverage, Mockito/ByteBuddy, profilers). Lathe cannot tell a coverage agent from a test-required one
at the argument level, and stripping is an optimization, not a correctness need, so the faithful choice
is to replay exactly what the build launched. A user who wants an agent absent from Lathe replays
removes it **at capture time** via a Lathe-specific Maven profile, so it never enters the fork and thus
never the template — provenance the build owns, not a heuristic Lathe guesses. There is no captured
booter main class to strip either: the listener never records `sun.java.command` (§3.1).

Why `--add-reads`/`--add-opens` need no rewrite:
Surefire runs its booter and the JUnit provider from the **classpath** (the unnamed module), so its
directives target `ALL-UNNAMED`.
Lathe's runner also runs from the classpath, so it inherits those grants unchanged; the directives are
applied verbatim, not re-pointed at a named module.

### 4.2 Reactor rewrite (pure prefix swap)

**Rewrite reactor outputs to `.lathe/`** (reuse `lathe-design.md` §6 remap):
any path of the form `<ws>/<rel>/target/(classes|test-classes)` → `<ws>/.lathe/<rel>/\1`, applied to
`patchModules` values, and any `modulePath`/`classPath` entry equal to a reactor module's `outputDir`
(main → `.lathe/<dep-rel>/classes`, test → `.lathe/<dep-rel>/test-classes`).
External entries pass through unchanged.
The module-path/class-path partition is not re-derived here — the rewrite only substitutes the prefix on
entries the template already placed, so the placement decision captured in §3.1 is preserved exactly.

### 4.3 Runner jar addition and entry point

Because capture already excludes its own jar from the recorded `classPath` (§3.1), replay never needs to
search the class path for `lathe-junit` and remove it — it only **appends**
`~/.cache/lathe/lathe-test-runner.jar` (materialized on first use by `RunnerJarProvider`) and the entry
point: `LatheLayout.TEST_RUNNER_MAIN_CLASS` followed by the test selection
(`TestSelection.toRunnerArgs()`).

The captured `classPath` already contains `junit-platform-launcher` (the provider depends on it), so
Lathe drives the JUnit Platform `Launcher` from `lathe-test-runner`'s bootstrap main (§5).
It drives `selectClass`/`selectMethod` directly, so a named run executes exactly the requested
class/method;
discovery emits **erased parameter types** in the runnable id so overloaded and `@ParameterizedTest`
methods select correctly.

**Results.**
The bootstrap registers a `TestExecutionListener` that emits one NDJSON record per event to a dedicated
results sink (path supplied via a system property) — never stdout, so program output cannot corrupt the
stream.
The server line-reads the sink for near-real-time results.

**Debug** is one more argument on the same command line:
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<port>`.
Attach-only suffices; the DAP adapter wiring is out of scope here.

### 4.4 `main`

A `main` run needs the module's **runtime** launch, which is neither the test launch nor the compile
launch — and, unlike `test-launch.json`, it is **not captured**: no `main` launch happens during a
normal build to ride.
Lathe is JPMS-first, so the supported design starts with the modular case instead of treating it as an
edge case.
The server still must not guess a module graph from jar names.

The launch is therefore **derived on the Maven side, not the fork side**:
`lathe:sync` writes `.lathe/<rel>/main-launch.json` for **every** module, on every build
(it is auto-bound to `process-test-classes`, so the file is never staler than the last build —
the same freshness contract as `workspace.json`, with no fingerprint dance).
Each side of the derivation uses the actor that holds authoritative inputs:

- **Membership — from Maven scopes.**
  `MavenProject.getRuntimeClasspathElements()` is the correct `{compile, runtime}` set,
  with `provided` and `test` excluded.
  Do **not** try to recover it by set arithmetic over the test and compile captures:
  scopes cannot be reconstructed from bare jar-path lists — `provided` sits in both captures and must be
  dropped, and `runtime` and `test` entries are indistinguishable once reduced to paths.
  This is also why the in-fork capture listener can never write this file: the fork sees the effective
  JVM but is scope-blind.

- **Placement — computed with `plexus-java`, the same library Surefire uses.**
  `lathe:sync` partitions the runtime membership into module path and class path via
  `LocationManager.resolvePaths` — the exact code that partitioned the captured test fork —
  so the derived placement is consistent with the captured one **by construction**,
  not borrowed and filtered from it.

The template is **main-class-agnostic**: one file per module, holding only the runtime launch shape
(paths and JPMS directives).
The main class comes from the runnable or run config at launch time, appended by the server as
`-m <mainModule>/<MainClass>`.
A module with several mains needs no extra files, and per-service launch templates fall out of the
per-module layout for free.

A non-modular (classpath) `main` is the same artifact with an empty module path,
launched as `java -cp … <MainClass>` — no special-casing.

No test-derived JVM arguments carry over: the template contains only what Maven knows,
and user-supplied JVM args, program args, env, and cwd come from the run-config overlay (§8).
Like `test-launch.json`, the file carries a `schemaVersion` stamp —
the plugin version is committed in the POM while the server updates independently,
so it is the same writer/reader split as §3.3.

Applying the transform to a `main` template needs nothing to filter or strip: apply the reactor rewrite
(§4.2) to its module/class path and launch `-m <module>/<mainClass>` (or `java -cp … <MainClass>` when
the module path is empty), with the `.lathe-run.json` overlay (§8) applied last.

**To validate when this lands:** that the `plexus-java`-derived partition launches identically to a
real modular run — asserted against the `jpms` invoker fixture (this is the slice's GO/NO-GO, §12 slice
6).

### 4.5 Completeness — gate before launch

A launched JVM needs a **complete, runnable image** on disk (unlike compilation, which tolerates a
partial `.lathe/` plus open buffers).
Replay **must verify completeness before launching** and refuse if it cannot — a run against an
incomplete image can report a green test for the wrong bytecode, the most dangerous failure mode.

For every module the transform points into `.lathe/` (the target and each reactor dependency on a
rewritten path):

1. **Build settled** — `lathe.lock` absent (or stale ≥ 2 min); else wait, then re-check.
2. **Shim has run** — `lsp-params-classes.json` (and `-test-classes.json` for the target) exist.
3. **Output present** — each rewritten `.lathe/<dep-rel>/classes` / `test-classes` exists and is
   non-empty.
4. **Mirror, not merge** — `.lathe/<rel>/classes` is a full copy of the last compile with orphans already
   removed (`lathe-design.md` §5–6), so once (1)–(3) hold there is no partial-image state left; the
   server does not diff class-by-class.

This gate is about **presence**, not currency — whether the bytecode reflects the latest *saved* source
is the "save, then run" concern handled by the LS save pass.

---

## 5. Runner contract (`lathe-test-runner`)

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
reproduce Surefire include/exclude/tag filtering (§9).
**Results:** a summary today; a `TestExecutionListener` emitting NDJSON to a sink path (system property)
in the streaming stage (§15).

---

## 6. Resource refresh

Replay reads resources from `.lathe/`, so they must be current there.
There are two paths: an automatic editor default, and a manual faithful refresh.

**Build-time freshness is already handled.**
The compiler shim's bulk copy of `target/classes` / `target/test-classes` includes Maven's
*fully-processed* resources — filtered, `targetPath`-mapped, everything — because `process-resources` ran
before the compile the shim rode.
Faithful for every configuration, with no new code.

**The gap is a resource-only edit.**
No `.java` is stale, so `maven-compiler-plugin` skips compilation, the shim never fires, and `.lathe/`
keeps the old resource.
(The completeness gate concerns *presence*; this is *currency*.)

### 6.1 Editor default — copy on change

The LSP **always byte-copies** a changed resource into `.lathe/<rel>/{classes,test-classes}` on save —
no filtering detection, no plain/filtered gate.
It watches each module's resource roots via **`workspace/didChangeWatchedFiles`** — a *workspace*
capability, independent of the client's `java`-only document registration, and **not** `textDocument/did*`
(which never fires for non-`java` buffers; Lathe must not register for resource filetypes).
So a `.properties`/`.sql`/`.yaml` save is seen without treating it as an opened document, and it fires on
**disk save**, matching the "replay reflects last-saved state" semantics.

```
editor saves foo.properties  →  ResourceWatcher (workspace/didChangeWatchedFiles)
  └─ byte-copy src/…/resources/<x> → .lathe/<rel>/{classes,test-classes}/<x>   (plain resources: exact)
     filtered resources → user runs `lathe:refresh-resources`  (Maven filtering straight into .lathe/)
```

`lathe:sync` records each module's resource-root directories in `workspace.json` so the LSP watches the
right places (default `src/{main,test}/resources`).
Lathe already uses this channel for deleted `.java` sources (`lathe-design.md` §6).

- For a **plain** (unfiltered) resource — the common case, and the one hit when editing a config file and
  re-running a test — the byte copy is **exact**, because Maven copies plain resources verbatim too.
- For a **filtered** resource (Maven `${…}`/`@…@` substitution, `targetPath`, `includes`/`excludes`), the
  copy is the *unfiltered* source — last-saved bytes, not Maven's substituted output.

This requires client support for `didChangeWatchedFiles` dynamic registration (modern Neovim / VS Code);
without it, currency degrades to the manual refresh below.

### 6.2 Manual faithful refresh — `lathe:refresh-resources`

When faithful filtering matters, the user runs **`lathe:refresh-resources`**:
a Lathe goal that reuses Maven's own `MavenResourcesFiltering` to filter the module's resources
**directly into `.lathe/<rel>/{classes,test-classes}}`** — lock-guarded like the shim, with **no
compilation and no lifecycle phase**.
It is far faster than `process-test-classes`, and faithful because the filtering is Maven's, not
reconstructed.
The *user* invokes it, keeping the server free of Maven.
A normal build refreshes the same content via the shim.

---

## 7. Discovery

Discovery is source-derived and needs no Maven.

```
editor: "what can I run in this file?"  →  RunnableScanner.scan(uri)
  └─ AST walk over the cached CompilationUnitTree
       ├─ public static void main(String[])            → kind=main
       ├─ @Test / @ParameterizedTest / @TestFactory /… → kind=test-method
       └─ enclosing test classes                        → kind=test-class
  →  List<Runnable> { id, kind, label, moduleRel, uri, range }   (id carries erased param types)
```

`@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, and JUnit 4 / TestNG `@Test` are all
recognized.
Each runnable carries a stable id (with erased parameter types for methods), a kind, a label, the module
rel path, and its source range.

Routing unit vs. integration tests is by convention (`*Test`/`Test*`/`*Tests` → Surefire;
`*IT`/`IT*`/`*ITCase` → Failsafe);
custom include/exclude patterns are a fidelity gap (§9).

---

## 8. User run configuration

Lathe uses a user-authored JSON file at the workspace root for named run and debug configurations:
`.lathe-run.json`.
It is intentionally **outside** `.lathe/`, because `.lathe/` is generated and disposable.
The Neovim integration keeps management minimal:
open/create the file, list named configs with `vim.ui.select`, and pass the selected object to the
server.
Neovim does not construct Java command lines.

Example:

```json
{
  "schemaVersion": "1",
  "configs": [
    {
      "name": "Run JPMS app",
      "kind": "main",
      "moduleRel": "app",
      "mainClass": "com.example.app.Main",
      "args": ["--port", "8080"],
      "jvmArgs": ["-Xmx1g"],
      "env": {
        "APP_ENV": "dev"
      },
      "cwd": "app"
    },
    {
      "name": "Debug HelloTest",
      "kind": "test",
      "moduleRel": "jpms",
      "selector": {
        "type": "method",
        "value": "com.example.jpms.HelloTest#greet_returnsExpectedMessage"
      },
      "debug": true
    }
  ]
}
```

```
Neovim: :LatheRunConfig
  ├─ read workspace .lathe-run.json
  ├─ choose config via vim.ui.select
  └─ workspace/executeCommand lathe.run(config)
       ├─ server validates module / selector / cwd
       ├─ base launch comes from test-launch.json or main-launch.json
       ├─ reactor target/→.lathe/ rewrite
       └─ overlay user args, jvmArgs, env, cwd, debug
```

Configuration is a thin overlay on top of captured or Maven-derived launch data.
The server accepts user-owned fields:
program args, additional JVM args, environment variables, working directory, debug mode, and the
selected test or main target.
It does **not** allow the JSON file to mutate launch-correctness fields:
module path, class path, `--patch-module`, captured `--add-*` directives, dependency placement, or
reactor rewrite behavior.

Overlay semantics, pinned to avoid ambiguity at the runner:

- `env` **merges into** the inherited process environment; it never replaces it.
- `cwd` is resolved **relative to the workspace root** (absolute paths allowed).
- `jvmArgs` are **appended after** the template's JVM args, so on duplicate `-D`/`-X` flags the
  user's value wins (last-wins JVM semantics).

For tests, precedence is:
captured Maven launch, reactor rewrite, runner substitution, then user overlay.
For `main`, precedence is:
runtime launch metadata, reactor rewrite, then user overlay.

---

## 9. Documented limitations

The escape hatch for every case below is the user running Maven directly — never a server-driven Maven
run.

- **Non-JUnit-Platform providers** — pure TestNG and other non-platform providers open no launcher
  session, so they are not captured.
  JUnit 4 via the vintage engine is captured.
- **`forkCount=0`** — in-process execution builds no command line and no module layer; not a replayable
  model.
  *Run/debug requires forking (the Surefire default); use `mvn test`.*
- **No template yet** — a fresh checkout, a module never test-run, or habitual `-DskipTests`
  (`skipTests` compiles tests but skips execution → no fork → no template) / `-Dmaven.test.skip` (skips
  both).
  Run/debug is unavailable until one real `mvn test`; a captured template then survives later skip builds
  (the launch line is structural).
- **Lifecycle-provisioned integration tests** — tests depending on `pre-/post-integration-test`
  (Maven-managed servers, containers, reserved ports) are incorrect under standalone replay.
  Self-provisioning ITs (Testcontainers, embedded servers in `@BeforeAll`) replay correctly.
  *Use `mvn verify`* for the former.
- **Filtered resources** — a filtered resource edited in the editor is copied *unfiltered* (§6.1);
  for faithful substitution, run `lathe:refresh-resources` (§6.2) or a normal build.
- **`main` before the first build** — `main-launch.json` is written by `lathe:sync`, so a fresh
  checkout needs one build (any build that reaches `process-test-classes`) before `main` runs are
  available; no test run is required (§4.4).
- **Package/module-wide selection** — exact for a named class/method; Surefire include/exclude/tag
  filtering for wide runs is not reproduced.
- **Coverage** — replay keeps whatever agents the build captured (including a coverage agent) but does
  not produce a coverage report; *use `mvn verify`* for coverage. To keep the coverage agent out of
  replays, disable it at capture via a Lathe-specific profile.

---

## 10. Module & class architecture

Build order: `lathe-core → lathe-junit → lathe-test-runner → lathe-compiler → lathe-server →
lathe-maven-plugin`.

### 10.1 `lathe-core` — schema + pure launch logic

| Class | Role | Key methods |
|---|---|---|
| `schema.TestLaunchData` (record) | Captured test-fork template | validating compact ctor |
| `schema.MainLaunchData` (record) | Sync-derived runtime launch template (main-class-agnostic) | validating compact ctor |
| `LatheLayout` (edit) | `TEST_LAUNCH_FILE`, `MAIN_LAUNCH_FILE`, `TEST_RUNNER_MAIN_CLASS`, `TEST_LAUNCH_SCHEMA_VERSION`, `MAIN_LAUNCH_SCHEMA_VERSION` | — |
| `WorkspaceLocator` | Walk up to `.lathe/` | `findWorkspaceRoot(Path): Optional<Path>` |
| `launch.ReplayTransform` | Pure template → argv | `forTest(data, wsRoot, sel)`, `forMain(data, wsRoot, module, mainClass)` |
| `launch.ReactorRewrite` | Pure path rewrite | `toLathe(path, wsRoot)` |
| `launch.TestSelection` (record) | `{kind, value}` → runner flags | `toRunnerArgs(): List<String>` |

`TestLaunchData` and `MainLaunchData` share the same JPMS runtime shape (`modulePath`, `classPath`,
`addOpens`/`addReads`/`addExports`/`addModules`); factor the shared field validation so the two records'
compact constructors do not duplicate the same `List.copyOf` + null-check invariants.

### 10.2 `lathe-junit` — capture (on the user's test class path)

| Class | Role | Key methods |
|---|---|---|
| `CaptureLauncherSessionListener` | ServiceLoader hook; `.lathe/`-gated; fail-open; writes `test-launch.json` | `launcherSessionOpened`, `capture` |
| `LaunchCapture` | Pure introspection → `TestLaunchData`; excludes own jar from `classPath` | `toLaunchData(javaHome, classPath, inputArgs, ownJarLocation)` |
| `CaptureOnlyPostDiscoveryFilter` *(deferred)* | `-Dlathe.capture.only` → exclude all | `apply` |

### 10.3 `lathe-test-runner` — replay executor

| Class | Role | Key methods |
|---|---|---|
| `LatheTestRunner` | `main` → JUnit Platform `Launcher`; class/method/package/module | `main`, `parseSelectors` |
| `NdjsonExecutionListener` *(deferred)* | `TestExecutionListener` → NDJSON sink | event callbacks |

### 10.4 `lathe-maven-plugin` — push-side setup

`lathe-junit` is a user-added, documented dependency (`lathe-design.md` §3), not something `InitMojo`
writes or inspects.

| Class | Role | Key methods |
|---|---|---|
| `SyncMojo` → `MainLaunchWriter` | Membership (`getRuntimeClasspathElements`) + placement (`plexus-java` `LocationManager`) → `main-launch.json`, every module, every build | `write(project)` |
| `SyncMojo` → `ResourceRootsRecorder` | Record `resourceRoots` into `workspace.json` | `record(project)` |
| `RefreshResourcesMojo` → `ResourceFilterer` *(deferred)* | Filter resources into `.lathe/` | `filterInto` |

### 10.5 `lathe-server` — LSP orchestration (reader only)

| Class | Role | Key methods |
|---|---|---|
| `run.RunnableScanner` | Source-derived discovery | `scan(uri): List<Runnable>` |
| `run.Runnable` (record) | `{id, kind, label, moduleRel, uri, range}` | — |
| `run.LaunchTemplateReader` | Read `test-launch.json` fresh, lock-aware | `read(moduleRel): Optional<TestLaunchData>` |
| `run.LaunchFreshness` | POM + `module-info.java` fingerprints | `isStale(data, moduleRel): boolean` |
| `run.CompletenessGate` | Pre-launch presence checks (§4.5) | `verify(data, wsRoot): Result` |
| `run.MainLaunchReader` | Read `main-launch.json` fresh, lock-aware | `read(moduleRel): Optional<MainLaunchData>` |
| `run.RunnerJarProvider` | Materialize embedded runner jar | `runnerJar(): Path` |
| `run.ReplayLauncher` | Build argv + spawn | `launch(data, sel): ReplaySession` |
| `run.ReplaySession` | Own the replay process + result stream | `start`, `cancel`, `pid` |
| `run.RunConfig` (record) | `.lathe-run.json` / command payload overlay | validating compact ctor |
| `run.RunConfigReader` | Read root `.lathe-run.json` for editor commands | `read(): List<RunConfig>` |
| `run.RunCommands` | `executeCommand`: `runnables.list`, `config.list`, `run`, `debug`, `session.*` | one handler each |
| `run.SessionEvents` | Emit `lathe/sessionEvent` | `emit` |
| `debug.DebugAdapter` *(stage 2)* | JDWP inject + in-process `java-debug` DAP | `attach` |
| `resource.ResourceWatcher` *(deferred)* | `didChangeWatchedFiles` → copy into `.lathe/` | `onChange` |

---

## 11. Guardrails

Capture depends on the **supported junit-platform provider contract** — that Surefire forks a JVM, runs
the JUnit Platform in it, and honors ServiceLoader-registered launcher listeners and filters — plus the
`jdk.module.*` launcher properties, which are internal but stable across JDKs.
It deliberately does **not** depend on Surefire's command line, argfile format, `jvm`-path validation, or
any fork-internal class name.

- a **`schemaVersion` stamp** in `TestLaunchData` / `MainLaunchData` forces a clean read-or-refuse across
  the committed-`lathe-junit`-vs-server split (§3.3);
- **atomic snapshot completion** (§3.2), so "captured" is unambiguous;
- the **completeness gate before launch** (§4.5), refusing rather than running the wrong bytecode;
- **capture fail-open** (§3.6) — any capture error skips the template and never breaks the test run;
- a **pinned Surefire/Failsafe version matrix** in CI, so a provider-behavior change fails in Lathe's own
  tests rather than silently in a user's editor.

**No replay re-capture, guaranteed structurally.**
`lathe-junit`'s capture listener is never present on a replayed JVM's class path — capture excludes its
own jar from the recorded `classPath` at write time (§3.1), so replay only *appends* the runner jar
(§4.3), never searches for and strips an entry.
This was chosen over a single reused jar gated by a runtime system property (e.g. "capture listener
no-ops when `-Dlathe.replay=true`"): a property-guarded design makes correctness depend on the guard
always being set and always being honored, and its failure mode is silent and severe — a mis-propagated
property lets the listener re-fire during replay and overwrite `test-launch.json` with a self-referential
template (already-rewritten `.lathe/` paths, no real Surefire-derived `patchModules` context), corrupting
the one file every future replay trusts, with nothing in the output to flag it.
Excluding the jar from the classpath at capture time makes that failure mode structurally impossible: a
class that is not on the class path cannot be instantiated by `ServiceLoader`, regardless of any system
property.
This is a different situation from capture-only mode (§3.5), whose runtime-flag failure mode is confined
to "ran/didn't run tests" and is immediately visible.

There is no delegation fallback to guard: cases capture-replay cannot handle are documented limitations
(§9), not server-driven Maven runs.

---

## 12. Reviewable deliverables

Each deliverable is intended to be reviewed and committed separately.
The order below is the implementation order unless a later discovery forces a design update.

### 12.1 Core launch schema and pure transform

**Scope:**

- Add `lathe-core` records for `TestLaunchData`, `MainLaunchData`, and `TestSelection`.
- Add `LatheLayout` constants for launch file names, schema versions, and the test-runner main class.
- Add `WorkspaceLocator`.
- Add pure launch helpers: `ReactorRewrite`, `ReplayTransform.forTest(...)`, and
  `ReplayTransform.forMain(...)`.

**Review focus:**

- Schema shape and validation invariants.
- Reactor `target/` to `.lathe/` rewrite correctness.
- No process spawning, Maven integration, or JUnit dependency in this slice.

**Verification:**

- Unit tests in `lathe-core`.

**Commit prefix:** `feat: add launch schema and replay transforms`

### 12.2 JUnit capture module

**Scope:**

- Add the `lathe-junit` module.
- Implement `CaptureLauncherSessionListener`.
- Implement pure `LaunchCapture` introspection logic.
- Register the listener with ServiceLoader.
- Write `.lathe/<rel>/test-launch.json` atomically.
- Fail open on capture errors.
- Exclude `lathe-junit`'s own jar from the captured class path.

**Review focus:**

- Capture does not break user test runs.
- Captured JVM args and JPMS fields match this design.
- `.lathe/` activation gate is correct.

**Verification:**

- Unit tests for `LaunchCapture`.
- Small integration or invoker assertion that the listener fires and writes `test-launch.json`.

**Commit prefix:** `feat: capture junit test launch templates`

### 12.3 Test runner module

**Scope:**

- Add the `lathe-test-runner` module.
- Implement `LatheTestRunner`.
- Parse selectors for classes, methods, packages, and modules.
- Drive the JUnit Platform launcher.
- Return stable exit codes.

**Review focus:**

- Runner is independent from capture.
- No runtime mode flag is shared with `lathe-junit`.
- Selector parsing is deterministic.

**Verification:**

- Unit tests for selector parsing.
- Runner smoke tests with a tiny fixture.

**Commit prefix:** `feat: add junit replay test runner`

### 12.4 Maven wiring and capture verification

**Scope:**

- Update the build order.
- Document `lathe-junit` as a user-added test-scope dependency (`lathe-design.md` §3) — `lathe:init`
  neither writes nor inspects `pom.xml` for it.
- Pin Surefire version as required.
- Extend the invoker fixture to assert that `test-launch.json` exists.
- Assert that modular launch fields are captured.
- Assert classpath launch fields where relevant.

**Review focus:**

- Surefire pin.
- Generated and committed user-facing setup.

**Verification:**

- `mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module`

**Commit prefix:** `feat: wire junit launch capture into maven setup`

### 12.5 Server test replay

**Scope:**

- Add server-side readers and launch orchestration:
  `LaunchTemplateReader`, `CompletenessGate`, `RunnerJarProvider`, `ReplayLauncher`, and
  `ReplaySession`.
- Add an initial command path for running one selected test from a captured template.
- Materialize or locate `lathe-test-runner.jar`.
- Spawn the replay JVM against `.lathe/`.

**Review focus:**

- Server remains Maven-free.
- Completeness gate refuses unsafe launches.
- Process lifecycle and cancellation behavior are explicit.

**Verification:**

- Server unit tests for readers and gates.
- Invoker or integration smoke test: captured `HelloTest` replays green from `.lathe/`.

**Commit prefix:** `feat: replay captured tests from lathe bytecode`

### 12.6 Runnable discovery

**Scope:**

- Add `RunnableScanner`.
- Add the `Runnable` record.
- Discover `public static void main(String[])`.
- Discover JUnit test classes and methods.
- Expose a command for listing runnables in a file or module.

**Review focus:**

- Uses javac/tree APIs, not text parsing.
- Stable runnable IDs, especially method selectors.
- Source ranges and labels.

**Verification:**

- Server tests using existing compilation and analysis fixtures.

**Commit prefix:** `feat: discover runnable tests and main classes`

### 12.7 Main launch metadata

**Scope:**

- Add Maven-side `MainLaunchWriter`.
- Write `.lathe/<rel>/main-launch.json` during `lathe:sync`.
- Use Maven runtime classpath membership.
- Use `plexus-java` placement for module path and class path.
- Add server-side `MainLaunchReader`.
- Integrate `ReplayTransform.forMain(...)`.

**Review focus:**

- Runtime scope correctness.
- JPMS/classpath partition correctness.
- No server-side classpath guessing.

**Verification:**

- Invoker fixture for modular `HelloMain`.
- Invoker fixture for classpath `Main`.
- Provided dependency is excluded.
- Reactor dependency rewrites to `.lathe/`.

**Commit prefix:** `feat: derive and replay main launches`

### 12.8 Run config overlay

**Scope:**

- Add `.lathe-run.json` schema reader.
- Add the `RunConfig` record.
- Apply overlays for args, JVM args, env, cwd, and debug mode.
- Add commands for listing and executing named configs.

**Review focus:**

- Overlay cannot mutate launch-correctness fields.
- Path and cwd validation.
- Duplicate JVM arg behavior is documented and tested.

**Verification:**

- Unit tests for config parsing and overlay semantics.
- Server command tests.

**Commit prefix:** `feat: add named run configuration overlays`

### 12.9 Initial debug attach support

**Scope:**

- Add debug mode to replay launch.
- Allocate or accept a JDWP port.
- Inject the JDWP agent arg.
- Surface process and session metadata needed by editor integration.
- Do not implement a full DAP adapter in this slice unless the design is explicitly updated.

**Review focus:**

- Debug is a thin launch-mode change.
- JDWP args are correctly ordered.
- Session lifecycle remains cancellable.

**Verification:**

- Unit test command construction.
- Manual smoke: JVM suspends and accepts attach.

**Commit prefix:** `feat: add jdwp debug launch mode`

### 12.10 Deferred fidelity and UX work

Each deferred item should be its own later commit or small series:

- NDJSON streaming test events: `feat: stream replay test events`
- `lathe/sessionEvent` notifications.
- Capture-only filter: `feat: add capture-only junit refresh mode`
- Resource watcher copy-on-save.
- `lathe:refresh-resources`: `feat: refresh resources for replay`
- `systemPropertyVariables` merge.
- Neovim picker and commands: `feat: add neovim run config commands`

---

## 13. Test fixtures (invoker `multi-module`)

- **`jpms`** — modular (`module-info`, `requires validcheck`), a JUnit test, and (to be added) a
  `HelloMain` → exercises modular test capture *and* modular `main` (the `plexus-java` placement path).
- **`app`** — classpath `Main`, depends on reactor `core` (compile) + a `provided` processor → classpath
  `main`, provided-exclusion, reactor rewrite.
- **`verify`** — reads `.lathe/` and spawns the replay via `ProcessBuilder`; asserts capture fidelity and
  green replay (test + main).
- **Harness requirement:** the reactor must pin a modern Surefire (§14), currently 3.5.4.
- **Transient run/test/debug spike:** `MultiModuleTest` currently asserts the JPMS test params and
  `.lathe/` bytecode mirror contain the replay-critical shape (`--patch-module`, `--add-reads`,
  `ALL-UNNAMED`, main/test bytecode).
  Promote or replace this with `test-launch.json` and replay assertions once capture code exists.

---

## 14. Spike outcomes (validated / learned)

- ServiceLoader `LauncherSessionListener` **fires under Surefire with zero config**; a non-modular jar
  lands on the class path (unnamed module) and reads `java.management`/JUnit fine.
- Modular forking **requires a modern Surefire** (pin 3.5.5); unpinned/old runs the fork non-modularly
  (flat class path, empty `getInputArguments()`/`jdk.module.*`).
- Modular `getInputArguments()` yields `--module-path=`/`--patch-module=`/`--add-*` plus `argLine`
  `-D`/`-X`, `@argfile`-expanded; `java.class.path` yields the class path.
- **`<systemPropertyVariables>` is not visible** to `getInputArguments()` (booter properties file) →
  capture gap (§3.1).
- **Resolved — Surefire 3.5.5 forked-VM exit-handshake regression (not JPMS-specific).**
  Surefire 3.5.5 hangs on **any** forked test run (`Tests run: 0`, "VM terminated without properly
  saying goodbye", `Process Exit Code: 0`) whenever the enclosing `mvn` process is a
  freshly-launched, standalone JVM rather than a long-running `mvnd` daemon. Bisected with a
  throwaway single-module project (no reactor, no JPMS, no Lathe code at all): Surefire 3.1.0
  through 3.5.4 all pass; 3.5.5 fails 100% of the time under a direct `mvn` invocation while
  passing consistently through the `mvnd` client. The forked child always parks in
  `ForkedBooter.acknowledgedExit` → `Semaphore.tryAcquire` (confirmed via
  `surefire-reports/*-jvmRun1.dump`), meaning it sent its final "bye" event but the **parent**
  Maven process never sent back the acknowledgement. A parent-side `jstack` while hung showed the
  fork's event-consumer thread alive but the acknowledgement never arriving — consistent with
  `maven-surefire-common`'s `ThreadedStreamConsumer.Pumper` (new in 3.5.5, diffed against 3.5.4)
  now calling `MDC.setContextMap(...)`/`MDC.getCopyOfContextMap()` at the top of the event-pump
  thread's `run()`, ahead of the loop that would otherwise deliver that final event; this remains
  a lead, not a fully proven cause. Ruled out: JDK 25 vs 26, JPMS/module-path vs plain classpath,
  `lathe-compiler`'s own logic (no-op passthrough with no `.lathe/` workspace still hung), TTY vs
  pipe, Surefire's legacy-pipe vs TCP fork channel (`<forkNode>`), and stale `mvnd` daemons.
  **Fix:** pinned `surefire.version` back to `3.5.4` in the root `pom.xml` (see the comment there).
  The `jpms` fixture's `<skipTests>` workaround has been removed — JPMS tests execute normally
  again under 3.5.4.

---

## 15. Open questions

1. ~~**Exit-handshake crash**~~ — resolved, see §14: Surefire 3.5.5 regression, pinned back to
   3.5.4.
2. **`systemPropertyVariables`** — record plugin-side and merge at replay (confirm mechanism).
3. **`.lathe-run.json` schema** — exact field names and whether Neovim or the server creates the
   skeleton file.
4. **`lathe-server` package layout** — do the `run.*` classes above fit the existing command/discovery
   structure, or fold into current classes?
5. **Runner delivery** — server-materialized from an embedded jar (current plan) vs. published coordinate.

Resolved:
*`main` JVM args* — none carry over from the test capture; `main-launch.json` holds only what Maven
knows, and user JVM args come from the `.lathe-run.json` overlay.
*`main-launch.json` provenance* — written by `lathe:sync` for every module on every build
(membership from runtime scope, placement via `plexus-java`), replacing both the server-derived
cache and the borrowed-test-partition design.
*Replay executor delivery mechanism* — a separate `lathe-test-runner` jar, appended to the class path at
replay time, rather than reusing `lathe-junit`'s jar under a runtime mode flag (§11).
