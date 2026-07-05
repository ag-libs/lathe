# Lathe — Run, Test, and Debug

Design for running and debugging tests — and, later, `main` classes — against the bytecode Lathe
already maintains in `.lathe/`, with no Maven recompilation per invocation.
Builds on `lathe-design.md` (especially §5 Compiler Shim and §6 Module Model).

**Scope.**
This document is the design.
It goes deep on the three load-bearing parts — **capture**, **constructing correct runtime arguments**
(for tests and for `main`), and **resource refresh** — and treats the rest as derivable later:
the exact LSP command/streaming schemas, the debug adapter wiring, editor integration, module
packaging, and the implementation/testing plan are deliberately out of scope here.

---

## 1. Principle and invariants

Lathe runs a test by **capturing** the exact JVM launch the build already computed for the test fork —
the modular command line, the module-path/class-path partition, the JPMS flags, the system properties —
and then **replaying** a fresh JVM from that captured template, with reactor output paths rewritten
from Maven's `target/` to Lathe's `.lathe/<rel>/`.

Three invariants shape everything:

- **Lathe never runs Maven.**
  Capture rides the user's normal `mvn test`; replay is a plain JVM the server spawns against `.lathe/`.
  Maven is the sole source of truth for the launch line, and the server stays a pure reader of `.lathe/`
  (`lathe-design.md` §6).
  Every execution Lathe cannot faithfully capture-and-replay is a **documented limitation** whose escape
  hatch is "run Maven yourself" (§7) — never a server-driven Maven delegation.

- **The default fork is the faithful launch.**
  Surefire's default (`forkCount=1`) forks a real JVM, and for a modular project that fork is a correct
  modular launch — exactly what replay needs.
  Riding the fork the build already makes is both free and JPMS-correct.
  `forkCount=0` (in-process) has no launch line and is not replayable (§7).

- **Capture reads the launch from inside the fork, via public JVM APIs.**
  Lathe does not intercept or reconstruct Surefire's command line from the outside.
  A JUnit Platform listener runs *inside* the already-launched test JVM and reads the effective launch
  back out of the JVM's own runtime view (§3).
  This keeps the dependency on the **supported** junit-platform provider contract, not on Surefire
  internals.

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
  lathe-junit LauncherSessionListener runs:         → strip jacoco + booter main/args
    reads java.class.path / jdk.module.* /          → keep JPMS flags verbatim
    getInputArguments(), writes test-launch.json     → add launcher + test selection
    via lathe-core (tests run normally)              → java … (fresh JVM)
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

---

## 3. Capture

### 3.1 The in-fork listener

Capture is a `org.junit.platform.launcher.LauncherSessionListener` (JUnit Platform 1.8+, stable through
JUnit 6), shipped in the `lathe-junit` module (§3.4).
Its `launcherSessionOpened` fires once, before discovery and execution, with the JVM fully up — the
earliest point at which the effective launch is readable.
It reconstructs the launch from **public JVM introspection**, never from Surefire's argfile:

| Template field | Source inside the fork |
|---|---|
| `classPath` | `System.getProperty("java.class.path")` |
| `modulePath` | `jdk.module.path` |
| `patchModules` | `jdk.module.patch.N` |
| `addOpens` / `addExports` / `addReads` / `addModules`, and `-D`/`-X` | `RuntimeMXBean.getInputArguments()` |
| main class (recorded only to strip it) | `sun.java.command` |

`getInputArguments()` returns arguments **after** the JVM has expanded any `@argfile`, so there is no
argfile to tokenize — Lathe reads the already-interpreted `--add-opens`/`--module-path`/`-D` list
directly.
The classpath is read from the `java.class.path` property (the one field `getInputArguments()` handles
inconsistently across JDKs), and the module directives are cross-checked against the `jdk.module.*`
properties.

This makes the captured template the **interpreted** launch, which is what replay needs — Lathe cares
about the resulting module graph, not about how Surefire tokenized its command line.

### 3.2 Writing the template

The listener writes the final `TestLaunchData` (§3.3) straight into `.lathe/<rel>/test-launch.json`,
using `lathe-core` — the same way the compiler shim writes `lsp-params-*.json`.
There is no intermediate snapshot and no server-side parse: the listener already holds the interpreted
values (§3.1), so it maps them into `TestLaunchData` and serializes with `lathe-core`'s `Json`,
resolving paths through `LatheLayout` and writing through `FileUtil.writeAtomically`.
Reusing those helpers is not merely convenient — it is required: locating `.lathe/<rel>/` and writing
there is exactly the layout and path logic CLAUDE.md forbids reimplementing, which is the reason
`lathe-junit` depends on `lathe-core` (§3.4).

The write is **atomic** (temp file + rename), so the server never observes a partial file — the same
discipline as the compiler shim.

### 3.3 `TestLaunchData` and freshness

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
the reactor→`.lathe/` rewrite happens at replay so that logic lives in one place (§4.1).
The module-path/class-path partition is recorded **exactly as the JVM reported it** — an automatic
module promoted to the module path by a `requires` lands on `modulePath` while its transitive plain jars
land on `classPath`, with no Lathe-side heuristic reproducing this; capture inherits it.

**Freshness.**
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
It carries the capture `LauncherSessionListener`, the capture-only `PostDiscoveryFilter` (§3.5), and the
replay-side launcher bootstrap and result listener (§4.1) — one small jar for all in-fork roles, with a
guard so the capture listener no-ops during replay and the result listener no-ops when no results sink is
configured.

Because it is a committed `test` dependency, `lathe-junit` runs on the user's test classpath, so its
`lathe-core` dependency ships there too.
That is safe as long as `lathe-core` stays what it is today — small and free of external dependencies —
so it drags nothing transitive onto user classpaths and its `module-info` is simply ignored on the
classpath.
The constraint this imposes: `lathe-core` must remain test-classpath-safe (no server-only bulk).
If that ever becomes a concern, the escape is a tiny `lathe-schema` leaf shared by both `lathe-core` and
`lathe-junit` — not warranted now.

It reaches the test classpath as **one committed `test`-scope dependency** in the parent POM, added by
`lathe:init`:

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
- **The replay runner comes for free.**
  Because `lathe-junit` is an ordinary test dependency, its jar is already on the captured `classPath`
  (an external `~/.m2` entry, kept verbatim through the reactor→`.lathe/` rewrite).
  Replay simply points `java` at the bootstrap main class inside it; the **server needs no build
  dependency on `lathe-junit`** — only its main-class fully-qualified name, held as a `lathe-core`
  constant.

A committed tooling dependency on the build is a deliberate, visible cost, consistent with the "material
and committed" philosophy of the compiler-shim setup.
It is inert without `.lathe/`, and the listener it registers is instantiated per fork but returns
immediately when no workspace is found.

**Scope.**
Capture is **JUnit-Platform-only**.
JUnit 4 via the vintage engine still opens a launcher session and is captured;
pure TestNG or other non-platform providers are not — a stated limitation (§7), and the natural seam for
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

### 4.1 Tests

**Keep verbatim:**
external (`~/.m2`, JDK) `--module-path`/`--class-path` entries, `--add-modules`, `--add-reads`,
`--add-opens`, `--add-exports`, and the `-D`/`-X` `jvmArgs`.

**Rewrite reactor outputs to `.lathe/`** (reuse `lathe-design.md` §6 remap):

- `patchModules[mod] = <ws>/<rel>/target/test-classes` → `.lathe/<rel>/test-classes`;
- any `modulePath`/`classPath` entry equal to a reactor module's main `outputDir` →
  `.lathe/<dep-rel>/classes`, and a test `outputDir` → `.lathe/<dep-rel>/test-classes`.

**Strip:** the JaCoCo agent (`-javaagent:*jacoco*`) and the captured main class with its trailing booter
arguments (recorded in §3.1 only so they can be removed).

**Substitute the executor:** append Lathe's launcher bootstrap and the test selection.

Why `--add-reads`/`--add-opens` need no rewrite:
Surefire runs its booter and the JUnit provider from the **classpath** (the unnamed module), so its
directives target `ALL-UNNAMED`.
Lathe's bootstrap also runs from the classpath, so it inherits those grants unchanged; the directives are
applied verbatim, not re-pointed at a named module.

**Launcher and selection.**
The captured `classPath` already contains `junit-platform-launcher` (the provider depends on it), so
Lathe drives the JUnit Platform `Launcher` from the bootstrap main in `lathe-junit` (§3.4).
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

### 4.2 `main`

A `main` run needs the module's **runtime** launch, which is neither the test launch nor the compile
launch — and, unlike `test-launch.json`, it is **not captured**: no `main` launch happens during a
normal build to ride.
It is **derived by the server** from two inputs — one for *membership* (which artifacts), one for
*placement* (the JPMS partition) — either on demand or cached to `main-launch.json`:

- **Membership — from Maven, authoritatively.**
  `lathe:sync` records the module's runtime-scoped classpath via
  `MavenProject.getRuntimeClasspathElements()` into `workspace.json`.
  This is the correct `{compile, runtime}` set, with `provided` and `test` excluded.
  Do **not** try to recover it by set arithmetic over the test and compile captures:
  scopes cannot be reconstructed from bare jar-path lists — `provided` sits in both captures and must be
  dropped, and `runtime` and `test` entries are indistinguishable once reduced to paths.

- **Placement — borrowed from the test capture.**
  Test scope is a superset of runtime scope, so **every runtime dependency already appears in
  `test-launch.json`**, placed on module-path or class-path exactly as the JVM resolves it.
  So Lathe **filters** the captured partition down to the runtime membership set rather than
  reconstructing a partition itself — the placement is real, not inferred.
  It then drops `--patch-module` (test-classes) and the junit/booter entries, rewrites the main module's
  output to `.lathe/`, and launches `-m <mainModule>/<MainClass>`.

**The one real gap** is that this needs a test capture to exist:
a module that has been test-run at least once has a partition to borrow from.
A thin launcher module with no tests has none — for those, the fallback is a documented limitation
("run it via Maven") until a partition source exists.
A non-modular (classpath) `main` is trivial and needs no borrowed partition.

Two details to settle when this lands:
which captured JVM arguments carry over to a `main` run (an application's own `--add-opens`/`argLine`
should be kept, test-only directives dropped), and confirming that the test fork's placement of a shared
runtime modular dependency matches a plain `-m` launch.

### 4.3 Completeness — gate before launch

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

## 5. Resource refresh

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

### 5.1 Editor default — copy on change

The LSP **always byte-copies** a changed resource into `.lathe/<rel>/{classes,test-classes}` on save —
no filtering detection, no plain/filtered gate.
It watches each module's resource roots via **`workspace/didChangeWatchedFiles`** — a *workspace*
capability, independent of the client's `java`-only document registration, and **not** `textDocument/did*`
(which never fires for non-`java` buffers; Lathe must not register for resource filetypes).
So a `.properties`/`.sql`/`.yaml` save is seen without treating it as an opened document, and it fires on
**disk save**, matching the "replay reflects last-saved state" semantics.

`lathe:sync` records each module's resource-root directories in `workspace.json` so the LSP watches the
right places (default `src/{main,test}/resources`).
Lathe already uses this channel for deleted `.java` sources (`lathe-design.md` §6).

- For a **plain** (unfiltered) resource — the common case, and the one hit when editing a config file and
  re-running a test — the byte copy is **exact**, because Maven copies plain resources verbatim too.
- For a **filtered** resource (Maven `${…}`/`@…@` substitution, `targetPath`, `includes`/`excludes`), the
  copy is the *unfiltered* source — last-saved bytes, not Maven's substituted output.

This requires client support for `didChangeWatchedFiles` dynamic registration (modern Neovim / VS Code);
without it, currency degrades to the manual refresh below.

### 5.2 Manual faithful refresh — `lathe:refresh-resources`

When faithful filtering matters, the user runs **`lathe:refresh-resources`**:
a Lathe goal that reuses Maven's own `MavenResourcesFiltering` to filter the module's resources
**directly into `.lathe/<rel>/{classes,test-classes}}`** — lock-guarded like the shim, with **no
compilation and no lifecycle phase**.
It is far faster than `process-test-classes`, and faithful because the filtering is Maven's, not
reconstructed.
The *user* invokes it, keeping the server free of Maven.
A normal build refreshes the same content via the shim.

---

## 6. Discovery

Discovery is source-derived and needs no Maven.
A one-pass AST walk over the cached `CompilationUnitTree` returns runnables:
`public static void main(String[])` for `main`;
`@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, and JUnit 4 / TestNG `@Test` for tests.
Each runnable carries a stable id (with erased parameter types for methods), a kind, a label, the module
rel path, and its source range.

Routing unit vs. integration tests is by convention (`*Test`/`Test*`/`*Tests` → Surefire;
`*IT`/`IT*`/`*ITCase` → Failsafe);
custom include/exclude patterns are a fidelity gap (§7).

---

## 7. Documented limitations

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
- **Filtered resources** — a filtered resource edited in the editor is copied *unfiltered* (§5.1);
  for faithful substitution, run `lathe:refresh-resources` (§5.2) or a normal build.
- **`main` without a test capture** — a module with no test run has no partition to borrow (§4.2).
- **Package/module-wide selection** — exact for a named class/method; Surefire include/exclude/tag
  filtering for wide runs is not reproduced.
- **Coverage** — the JaCoCo agent is stripped from replay; *use `mvn verify`* for coverage.

---

## 8. Fragility and guardrails

Capture depends on the **supported junit-platform provider contract** — that Surefire forks a JVM, runs
the JUnit Platform in it, and honors ServiceLoader-registered launcher listeners and filters — plus the
`jdk.module.*` launcher properties, which are internal but stable across JDKs.
It deliberately does **not** depend on Surefire's command line, argfile format, `jvm`-path validation, or
any fork-internal class name.

Guardrails:

- a **`schemaVersion` stamp** in the snapshot / `TestLaunchData` forces a clean read-or-refuse across the
  committed-`lathe-junit`-vs-server split (§3.3);
- **atomic snapshot completion** (§3.2), so "captured" is unambiguous;
- the **completeness gate before launch** (§4.3), refusing rather than running the wrong bytecode;
- a **pinned Surefire/Failsafe version matrix** in CI, so a provider-behavior change fails in Lathe's own
  tests rather than silently in a user's editor.

There is no delegation fallback to guard: cases capture-replay cannot handle are documented limitations
(§7), not server-driven Maven runs.
