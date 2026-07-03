# Lathe — Run, Test, and Debug

Post-M3 design.
Builds on `lathe-design.md` (especially §5 Compiler Shim and §6 Module Model).
Adopted only after the Neovim-focused Maven Central release is implemented and stable.

---

## 1. Principle

Lathe runs and debugs tests and main classes **against the bytecode it already maintains in `.lathe/`**, with
no Maven recompilation and no reactor rebuild per invocation.

It does this by **capturing** the exact JVM launch that Maven's Surefire (or Failsafe, or the exec plugin) would
use — the modular command line, JPMS flags, classpath/modulepath partition, and system properties — and then
**replaying** a fresh JVM from that captured template, with reactor output paths rewritten from Maven's `target/`
to Lathe's `.lathe/<rel>/`.

This is the same capture-by-interposition philosophy as the compiler shim (`lathe-design.md` §5), applied to
execution: Lathe does not reimplement JPMS module-path resolution, `--patch-module`/`--add-reads`/`--add-opens`
computation, or Surefire's provider wiring — it lets Maven compute all of that once, records it, and reuses it.

### Why capture-replay and not "just run Maven"

Delegating each run to `mvnd -pl <mod> test` forces a choice with no good answer:

- **without `-am`** — Maven does not rebuild reactor dependencies, so the test resolves upstream modules from their
  `target/classes`, which Maven only refreshes on an explicit build. Risk of **stale** reactor classes.
- **with `-am`** — Maven rebuilds the entire upstream reactor from source on every run. Correct, but pays a full
  **recompilation** cost each time.

Capture-replay dissolves the dilemma. By rewriting reactor output paths to `.lathe/<dep-rel>/classes` — the copies
Lathe keeps current incrementally (shim copy on every build, LS save passes per file, lock protocol guarding
mid-copy reads) — a run gets `-am`-level freshness **without** `-am`'s recompilation and **without** reading stale
`target/`. Test execution and compilation then share one bytecode image and one freshness guarantee.

Freshness boundary, stated plainly: `.lathe/<dep-rel>/classes` is exactly as fresh as Lathe's *compilation* view of
that module. It is current for anything edited/saved in the editor or built by Maven, and only behind for a reactor
dependency changed **on disk out-of-band with no build and no editor touch** — the same staleness surface the LS's
own diagnostics already have, covered by the same stale-detection (lock files, POM fingerprints,
`didChangeWatchedFiles`). A replayed run is therefore never *more* stale than the diagnostics the user is already
trusting in the editor.

### Scope of the approach by execution kind

| Kind | Capture source | Replay | Notes |
|------|----------------|--------|-------|
| Unit test (Surefire) | `-Djvm` shim on `surefire:test` | Lathe JVM against `.lathe/` | Primary case; fully validated by PoC |
| Integration test (Failsafe) | `-Djvm` shim on `failsafe:integration-test` | Lathe JVM against `.lathe/` **iff** self-provisioning | Lifecycle-provisioned IT falls back to Maven `verify` — see §9 |
| Main class (run/debug) | `-Dexec.executable` shim on `exec:exec` | Lathe JVM against `.lathe/` | Conditional on the project configuring exec — see §7 |

`mvnd` delegation survives only as an explicit fallback (§10), not as the default path.

---

## 2. Architecture overview

Three stages, decoupled in time.

```
CAPTURE (rare, hidden, freshness-gated)          REPLAY (per run/debug, Maven-free)
─────────────────────────────────────           ─────────────────────────────────────
Lathe drives:                                    Lathe reads .lathe/<rel>/test-launch.json
  mvnd -pl <mod> surefire:test \                   → rewrite reactor target/ → .lathe/
       -Djvm=<capture-jdk>/bin/java                 → strip jacoco agent + ForkedBooter
  capture-jdk/bin/java (the shim):                  → keep JPMS flags verbatim
    probe?        → exec real java (transparent)     → add Lathe launcher + test selection
    ForkedBooter? → exec real java running           → java … (fresh JVM)
                    CaptureMain, which writes            • JDWP-attachable (debug)
                    test-launch.json and exits 0         • real-time results via NDJSON
  output discarded, exit code ignored;
  success = a fresh test-launch.json exists

                              DISCOVERY (source-derived, no Maven)
                              ─────────────────────────────────────
                              AST walk over cached CompilationUnitTree
                              → runnables (main / test-class / test-method)
```

- **Capture** produces a per-module **template** (`.lathe/<rel>/test-launch.json`) recording Maven's computed launch.
  It is captured once per module and reused for every run/debug in that module until invalidated.
- **Replay** is the hot path: no Maven, no compilation, sub-second, driven entirely from the template plus Lathe's
  `.lathe/` bytecode.
- **Discovery** is independent and always source-derived.

---

## 3. Capture — the `jvm` shim

### 3.1 Surefire's `jvm` contract (validated against surefire 3.5.5)

Surefire lets the forked test JVM be overridden with `-Djvm=<path>`. The plugin validates and inspects that path
before forking (`org.apache.maven.plugin.surefire.AbstractSurefireMojo#getEffectiveJvm`,
`org.apache.maven.surefire.booter.SystemUtils`):

1. **`SystemUtils.endsWithJavaPath`** — the path's file name must start with `java` and its parent directory must be
   named `bin`. The shim must therefore live at `<home>/bin/java`.
2. **`toJdkHomeFromJvmExec`** — the JDK home is derived by walking up from `bin/java`.
3. **`toJdkVersionFromReleaseFile`** — Surefire reads `<home>/release` (`JAVA_VERSION`/`JAVA_RUNTIME_VERSION`) to
   decide **modular vs. classpath forking** (`isJava9AtLeast`). A missing/unreadable `release` file degrades the
   fork to non-modular, which would defeat the capture. `<home>/release` **must** be present and report a Java 9+
   version.

Consequence: the shim cannot be a bare script anywhere on disk. It must be installed inside a **synthesized JDK
home** that mirrors the *active* build JDK closely enough to pass validation.

### 3.2 The synthesized capture JDK home

Installed under the user cache (created/refreshed by `lathe:sync`, or lazily by the server before the first capture):

```
~/.cache/lathe/capture-jdk/
├── bin/
│   └── java            ← the shim launcher (real file; shell on POSIX, java.cmd/.exe on Windows later)
└── release             ← symlink to $JAVA_HOME/release  (Surefire reads this for the version → modular decision)
```

Minimally only `release` is required (confirmed by PoC). For robustness against future Surefire probes, `sync` may
symlink the remaining top-level entries of `$JAVA_HOME` into `capture-jdk/` and override only `bin/java`.

The capture JDK home is keyed to the **active build JDK** (`$JAVA_HOME` at capture time). If the project builds under
a different JDK than the server runs, the capture invocation must use that same JDK, and `release` must mirror it.

### 3.3 The shim — decide, then delegate

The shim is a thin launcher whose only job is to distinguish the real test fork from probe invocations. All real
work happens in a Java class (`CaptureMain`) run on the real JVM, so parsing and serialization reuse `lathe-core`.

Detection rule (validated): **the invocation is the real fork iff `org.apache.maven.surefire.booter.ForkedBooter`
appears in argv — including inside an `@argfile`.** In modular forks Surefire passes *everything*, main class
included, via an `@argfile`, so the shim must expand argfiles when scanning. Probe invocations (e.g. the initial
`@args` version/vm-info call observed in the PoC) do **not** contain `ForkedBooter` and are passed straight through.

Reference POSIX shim (`bin/java`):

```sh
#!/bin/sh
# $LATHE_REAL_JAVA and $LATHE_CAPTURE_JAR are provided by the driving invocation
# (env), or resolved relative to $0.
for a in "$@"; do
  case "$a" in
    *ForkedBooter*)
      exec "$LATHE_REAL_JAVA" -cp "$LATHE_CAPTURE_JAR" \
        io.github.aglibs.lathe.capture.CaptureMain "$@" ;;
    @*)
      f="${a#@}"
      if [ -f "$f" ] && grep -q ForkedBooter "$f" 2>/dev/null; then
        exec "$LATHE_REAL_JAVA" -cp "$LATHE_CAPTURE_JAR" \
          io.github.aglibs.lathe.capture.CaptureMain "$@"
      fi ;;
  esac
done
exec "$LATHE_REAL_JAVA" "$@"     # probe or anything else → fully transparent
```

The shim never runs tests. For the fork case it hands the **original argv** (including the live `@argfile`, which
still exists during the fork) to `CaptureMain` and lets that exit early.

### 3.4 `CaptureMain` and the template

`CaptureMain` mirrors the role of `ParamsWriter` in the compiler shim. It:

1. Expands the `@argfile`, honoring Surefire's quoting (values are wrapped in `"`), and tokenizes the full launch.
2. Extracts, verbatim, the fields below.
3. Walks up from `user.dir` to `.lathe/` (reuse `WorkspaceDetector`) and derives the module relative path.
4. Writes `.lathe/<rel>/test-launch.json` via `lathe-core` `Json` in the `TestLaunchData` schema.
5. Exits `0`.

`test-launch.json` records paths **as captured** (Maven's `target/` locations). The reactor-output rewrite to
`.lathe/` happens at replay time, so the template stays a faithful record of Maven's decision and the rewrite logic
lives in one place (§4.1), reusing the §6 classpath-remap.

`TestLaunchData` schema (new record in `lathe-core.schema`, defensively-copied immutable collections, compact
constructor validating invariants):

```json
{
  "schemaVersion": "1",
  "kind": "surefire",
  "javaHome": "/opt/amazon-corretto-26/",
  "mainModule": "com.example.jpms",
  "modulePath": [
    "/ws/mod/target/classes",
    "/home/u/.m2/.../validcheck-0.11.0.jar",
    "/home/u/.m2/.../guava-33.4.0-jre.jar"
  ],
  "classPath": [
    "/home/u/.m2/.../surefire-booter-3.5.5.jar",
    "/ws/mod/target/test-classes",
    "/home/u/.m2/.../junit-platform-launcher-1.13.4.jar",
    "..."
  ],
  "patchModules": { "com.example.jpms": "/ws/mod/target/test-classes" },
  "addOpens":   ["com.example.jpms/com.example.jpms=ALL-UNNAMED"],
  "addReads":   ["com.example.jpms=ALL-UNNAMED"],
  "addExports": [],
  "addModules": ["ALL-MODULE-PATH"],
  "jvmArgs":    ["-Dfoo=bar", "-Xmx512m"],
  "forkedMainClass": "org.apache.maven.surefire.booter.ForkedBooter"
}
```

Field notes:

- `modulePath` / `classPath` — the partition **exactly as Surefire computed it**. This is the whole point of
  capturing: for a mixed graph, an automatic module (e.g. guava, promoted to the module path because a
  `module-info` `requires` it) lands on `modulePath`, while its transitive plain jars and non-modular test
  dependencies land on `classPath`. No Lathe-side heuristic reproduces this correctly; capture inherits it.
- `patchModules` — Surefire's `--patch-module <mod>=target/test-classes`. The value is repointed at replay (§4.1).
- `addReads` / `addOpens` — observed to target **`ALL-UNNAMED`**, because Surefire runs its booter and the
  JUnit provider from the classpath (the unnamed module), not as named modules. This is why replaying from the
  classpath needs **no rewrite** of these directives (§4.2).
- `jvmArgs` — `argLine`-derived JVM options and `-D` system properties (including Surefire
  `<systemPropertyVariables>`). The JaCoCo agent, if present, is stripped at replay (§4.1).
  **Open verification item:** confirm `<systemPropertyVariables>` and configured environment variables actually
  appear here before claiming full fidelity (§11).
- `forkedMainClass` — the booter class; its position marks the split between JVM args and Surefire's booter args.
  The trailing booter args (surefire temp dir, jvmRun id, two `*tmp` property files) are **discarded** at replay.

### 3.5 Driving the capture

Lathe spawns the capture invocation itself and owns its I/O:

```
mvnd -pl <moduleRel> surefire:test \
     -Djvm=~/.cache/lathe/capture-jdk/bin/java \
     -DfailIfNoTests=false
# env: LATHE_REAL_JAVA=$JAVA_HOME/bin/java, LATHE_CAPTURE_JAR=<lathe-capture.jar>,
#      LATHE_CAPTURE_OUT=.lathe/<rel>/test-launch.json
```

- **Hidden.** The shim exits early after writing the template, so Surefire reports "the forked VM terminated without
  properly saying goodbye" and the build fails. Lathe **discards stdout/stderr and ignores the exit code**; success
  is defined solely as "a fresh `test-launch.json` now exists." No wire-protocol handshake is implemented.
- **`surefire:test` goal directly**, not the `test` phase — the test-classes already exist, so recompilation is
  avoided even for capture.
- **`forkCount=0`** projects run tests in the Maven JVM with no separate fork to intercept. Detect this (no
  `test-launch.json` produced) and fall back to Maven delegation (§10), or force `-DforkCount=1` for capture only.

### 3.6 Freshness gating

`test-launch.json` is a cached template validated like the params files:

- **POM fingerprint** — the module's POM (and inherited parents) via the existing `workspace.json` `pomPaths`
  stale-detection. Surefire config, `argLine`, dependency set, and module graph all derive from the POM.
- **Test params-file mtime** — `lsp-params-test-classes.json`.

If both are unchanged, the launch line cannot have changed → **skip the capture invocation** and use the cached
template. Re-capture only on first touch or when a fingerprint moves. This keeps the (Maven-spawning) capture rare;
the inner loop never touches Maven.

---

## 4. Replay — execute against `.lathe/`

Replay reads the template, applies a deterministic transform, and launches a fresh JVM. No Maven, no compilation.

### 4.1 The transform

**Keep verbatim:** `--module-path` and `--class-path` entries that are external (`~/.m2/…`, JDK), `--add-modules`,
`--add-reads`, `--add-opens`, `--add-exports`, and `jvmArgs` `-D`/`-X` options.

**Rewrite reactor outputs to `.lathe/` (reuse the `lathe-design.md` §6 remap):**

- `patchModules[mod] = <ws>/<rel>/target/test-classes` → `.lathe/<rel>/test-classes`.
- Any `modulePath`/`classPath` entry equal to a reactor module's main `outputDir` → `.lathe/<dep-rel>/classes`.
- Any entry equal to a reactor module's test `outputDir` → `.lathe/<dep-rel>/test-classes`.
- The module's own `target/classes` / `target/test-classes` → its `.lathe/<rel>/classes` / `test-classes`.

**Strip:**

- The JaCoCo agent from `jvmArgs` (`-javaagent:*jacoco*`) — coverage is not reproduced for inner-loop runs.
- `forkedMainClass` and its trailing Surefire booter args.

**Substitute the executor:** append Lathe's test launcher to the classpath (or reuse the `junit-platform-launcher`
already present in `classPath` — see §4.3) and add the test selection.

### 4.2 Why `--add-reads`/`--add-opens` need no rewrite

Surefire runs its booter and the JUnit Platform provider from the **classpath** (the unnamed module), so the
directives it emits target `ALL-UNNAMED`:

```
--add-reads  com.example.jpms=ALL-UNNAMED
--add-opens  com.example.jpms/com.example.jpms=ALL-UNNAMED
```

Lathe's launcher also runs from the classpath (unnamed module), so it inherits those grants unchanged — it can read
the test module and reflect into the patched test classes exactly as Surefire's provider did. This was verified by
running a real modular test with the captured directives applied verbatim. **Do not** rewrite these to a named
module; keep them as captured.

### 4.3 Launcher, selection, and results

- **Launcher.** The captured `classPath` already contains `junit-platform-launcher` (Surefire's provider depends on
  it), so Lathe can drive the JUnit Platform `Launcher` API from a small bootstrap main it ships on the classpath,
  without bundling or version-matching a launcher. If a future capture lacks it, fall back to a bundled launcher.
- **Selection.** Lathe controls selection directly via the `Launcher` API (`selectClass` / `selectMethod`), so a
  named run executes exactly the requested class/method — no dependency on Surefire's scan. Package/module-wide
  runs select all discovered tests (see the selection-fidelity gap in §8).
- **Results.** The bootstrap main registers a `TestExecutionListener` that emits one NDJSON record per test event on
  stdout. Lathe forwards these as `testResult` `lathe/sessionEvent`s in real time. This replaces the
  `surefire-reports/*.xml` `WatchService` tailing of the previous design entirely — no XML, no watch race.

### 4.4 Completeness requirement on `.lathe/`

Running is less forgiving than compiling: compilation can resolve against a partial `.lathe/classes` plus open
buffers, but a launched JVM needs a **complete, runnable image** on disk.

- All classes for the module (and reactor deps) present in `.lathe/<rel>/classes` / `test-classes`, with orphans
  removed (§6 orphan handling) so no stale `.class` shadows current source.
- **Test resources included** — config files, `META-INF/services`, fixtures. The shim copies the entire output
  directory (`lathe-design.md` §5 step 5), and `process-test-resources` runs before the test-compile shim fires, so
  filtered resources are present in `target/test-classes` when copied. Verify this holds (§11).
- A replayed run reflects **last-saved/compiled** state — the LS save pass writes `.class` to `.lathe/` on
  `didSave`, so a saved buffer is reflected; unsaved buffer content is not. This is the expected "save, then run"
  semantics.

---

## 5. LS command surface

Unchanged in shape from the delegation design — five `workspace/executeCommand` commands and one streaming
notification — but the execution engine underneath is capture-replay.

### `lathe.runnables.list`

Given a file URI, return everything runnable/debuggable in scope. One-pass AST walk over the cached
`CompilationUnitTree`: `public static void main(String[])` for `main`; `@Test`, `@ParameterizedTest`,
`@TestFactory`, `@RepeatedTest`, JUnit 4 `@Test`, TestNG `@Test` for tests.

```json
{
  "runnables": [
    {
      "id": "test-method:module-a:com.example.FooTest#bar",
      "kind": "test-method",
      "label": "FooTest.bar()",
      "moduleRel": "module-a",
      "uri": "file:///ws/module-a/src/test/java/com/example/FooTest.java",
      "range": { "start": { "line": 20, "character": 7 }, "end": { "line": 20, "character": 10 } }
    }
  ]
}
```

`kind` is `"main"`, `"test-class"`, or `"test-method"`. `id` is opaque-but-stable; editors round-trip it.

### `lathe.run` / `lathe.debug`

Accept a runnable `id` (or `{ module, testPattern }` for package/module-wide test runs). The server:

1. Ensures a fresh `test-launch.json` (or run-launch for `main`) exists for the module — capturing if the freshness
   gate (§3.6) says the template is missing or stale; otherwise reusing the cached template.
2. Applies the replay transform (§4.1) and selection.
3. For `lathe.debug`, additionally injects JDWP (§8-debug) and starts the in-process DAP adapter.
4. Spawns the JVM, returns `{ sessionId, kind }` (debug also returns `{ dapHost, dapPort }`), and streams output,
   `testResult`, and lifecycle via `lathe/sessionEvent`.

If capture is impossible for this target (forkCount=0, unrecognized fork, provisioned IT), the command falls back to
Maven delegation (§10) and says so in the first `sessionEvent`.

### `lathe.session.cancel` / `lathe.session.list`

`cancel` destroys the replay JVM process by `sessionId` (a plain child process now — no `mvnd` client to detach
from, no forked-VM orphans to reason about in the common path). `list` returns
`{ sessionId, kind, runnableId, startedAt, status }` for live sessions. On LS shutdown all sessions are cancelled.

---

## 6. Streaming events — `lathe/sessionEvent`

Server-to-client notification carrying output and lifecycle for a session.

- `"started"` — `{ pid, jdwpPort? }`. Emitted once the JVM is up and (for debug) JDWP is listening.
- `"output"` — `{ channel: "stdout"|"stderr", data }`. The replayed program's own output, verbatim. There is no
  `"maven"` channel and no Maven log filtering — Lathe launches the JVM directly, so the previous design's output
  filtering section is obsolete.
- `"testResult"` — `{ testId, status: "passed"|"failed"|"skipped", durationMs, message?, stackTrace? }`. Emitted in
  real time from the bootstrap main's `TestExecutionListener` NDJSON stream (§4.3), per test as it finishes.
- `"exit"` — `{ exitCode, elapsedMs }`. Final event.

---

## 7. Run — main classes via exec capture

Running a `main` class uses the **same executable-override trick**, applied to the exec plugin:

```
mvnd -pl <moduleRel> exec:exec -Dexec.executable=~/.cache/lathe/capture-jdk/bin/java -Dexec.args="…"
```

The shim intercepts and captures the resolved command line; Lathe replays it against `.lathe/` (repointing reactor
outputs, adding JDWP for debug).

**Correct-by-construction constraint.** Unlike Surefire for tests, **no standard Maven component computes a modular
application launch** — `exec:exec` only expands `%classpath`/`%modulepath` from whatever the project configured in
`exec.args`; it does not derive `--add-modules` or `-m module/Main`. Lathe therefore **does not synthesize** a run
launch. Run support is conditional on the project configuring an exec invocation (which Lathe captures faithfully).
A project with no run configuration has nothing faithful to capture, and Lathe must not invent a partition — that
would be the reconstruction the whole design avoids.

> Status: the exec-capture path is designed but **not yet validated by PoC** (the Surefire path is). The implementing
> agent should spike `exec:exec -Dexec.executable=<shim>` on a modular main before relying on it, mirroring §3's
> Surefire spike.

---

## 8. Debug — JDWP and the in-process DAP adapter

Because Lathe builds the replay command line itself, debug is trivial: inject
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<port>` directly into the JVM args. No
threading through `-Dmaven.surefire.debug`, no scanning Maven stdout.

Lathe bundles Microsoft's `java-debug` (JDI-based) and hosts it **in-process** inside the Lathe JVM, mirroring how
jdtls hosts it. Attach-only is sufficient — Lathe launches the target JVM, and `java-debug` translates DAP requests
into JDI operations on the already-running target.

```
nvim-dap ◄──DAP/socket──► [Lathe JVM: java-debug in-process] ──JDI/JDWP/localhost──► [replay JVM]
```

Handshake:

1. Editor calls `lathe.debug`.
2. LS allocates a JDWP port and a DAP port.
3. LS builds and launches the replay JVM with `suspend=y` on the JDWP port.
4. LS scans the replay JVM's stdout for `Listening for transport dt_socket at address: <port>`.
5. On match, LS starts the in-process `java-debug` DAP server and attaches via JDI on the JDWP port.
6. LS responds with `{ sessionId, kind: "debug", dapHost, dapPort }`.
7. Editor connects `nvim-dap` to `127.0.0.1:<dapPort>`; JDI attach resumes the JVM from suspend.

`suspend=y` guarantees no breakpoints are missed before the debugger attaches. A 30-second timeout on step 4 emits
`"exit"` with captured stderr if the JVM fails to start.

---

## 9. Failsafe and integration tests

**Capture transfers for free.** Failsafe is Surefire's twin — same `ForkStarter`/`ForkConfiguration`/`ForkedBooter`,
same `jvm` parameter. `mvnd -pl <mod> failsafe:integration-test -Djvm=<shim>` intercepts identically; the shim's
`ForkedBooter` discriminator fires the same way. No new capture code.

**The lifecycle is the problem, not the capture.** IT tests run between `pre-integration-test` and
`post-integration-test`: Maven-managed servers, containers (docker-maven-plugin), reserved ports, provisioned
databases. A standalone replay does not run those phases, so a lifecycle-provisioned IT hits a dead environment.
Correctness forbids pretending otherwise, so IT splits by kind:

- **Self-provisioning IT** (Testcontainers, embedded servers started in `@BeforeAll`) — the test owns its
  environment; capture-replay works. Confirm with a self-contained IT in the spike.
- **Lifecycle-provisioned IT** — capture-replay is incorrect by construction. Fall back to Maven's `verify`
  lifecycle (§10) so pre/post phases run. This is the one place delegation is chosen for **correctness**, not as a
  degraded fast path.

Lathe cannot statically distinguish the two cheaply, so the safe default for `*IT` is to offer the Maven-lifecycle
run and treat standalone replay as an explicit opt-in.

---

## 10. Test discovery and routing

Three separable concerns:

1. **What exists** — the AST walk (`runnables.list`, §5). Source-derived, no Maven.
2. **Unit vs. IT (which plugin owns it)** — the authoritative source is each plugin's effective
   `<includes>`/`<excludes>`, which Lathe does not parse (that is Maven-model reasoning). Instead, **route by
   convention** (`*Test`/`Test*`/`*Tests` → Surefire, `*IT`/`IT*`/`*ITCase` → Failsafe) and **let the plugin be the
   judge**: capture through the chosen goal; the plugin forks only if the class actually matches its filter. If it
   reports "no tests matched," surface that rather than guess. Custom include/exclude patterns are a documented
   fidelity gap.
3. **Selection fidelity** — exact for a named class/method (Lathe drives the `Launcher` selection directly). The only
   gap is package/module-wide runs, where matching Surefire's include/exclude/tag filtering would require reading its
   config; not reproduced — documented.

---

## 11. Fallback to Maven delegation

Delegation is the escape hatch, not the default. Fall back to `mvnd -pl <mod> test -Dtest=<sel> -DfailIfNoTests=false`
(or `verify` for provisioned IT) when:

- the shim was not invoked or produced no `test-launch.json` (e.g. `forkCount=0`, in-process execution),
- the captured argfile shape is unrecognized (e.g. a Surefire version whose internals changed),
- the target is a lifecycle-provisioned IT (§9).

Fallback is a **semantically different mode**: it recompiles (`-am` or stale-`target/` tradeoff of §1), reads
Maven's `target/` rather than `.lathe/`, and is slower. Lathe must state this in the first `sessionEvent` so the user
knows they are in Maven mode, not `.lathe/` mode. `mvnd` availability is assumed for fallback; if absent, warn once
(as in the prior design) and use `mvn`.

---

## 12. Fragility, guardrails, and open verification

This mechanism depends on **Surefire/Failsafe internals**, not a public API. That is the primary risk and must be
managed explicitly.

Internals relied upon (all validated on surefire 3.5.5, none contractual):

- the `org.apache.maven.surefire.booter.ForkedBooter` class name (the fork discriminator),
- the `@argfile` format and `"`-quoting,
- `jvm`-path validation (`endsWithJavaPath`) and `release`-file version detection,
- the modular argfile layout (`--module-path`/`--class-path`/`--patch-module`/`--add-*`).

Guardrails the implementation must include:

- **Pinned Surefire/Failsafe version matrix in CI** — capture + replay integration tests across the supported
  Surefire versions, so an upgrade that changes internals fails loudly in Lathe's own tests, not silently in a
  user's editor.
- **Graceful fallback (§11)** whenever capture does not produce a well-formed template.
- **Version stamp** in `test-launch.json` recording the Surefire version it was captured from, so replay can detect
  a mismatch after a POM change and force re-capture.

Open verification items before/at implementation (spike-level checks):

1. `<systemPropertyVariables>` and configured environment variables appear in the captured `jvmArgs`/env (fidelity).
2. `.lathe/<rel>/test-classes` contains filtered **test resources**, and a test that reads
   `src/test/resources/…` passes under replay (§4.4).
3. Orphan `.class` removal leaves no stale classes visible to a replayed run (§4.4).
4. The **exec-capture** path for `main` works on a modular project (§7).
5. Failsafe capture works, and a **self-provisioning** IT replays green (§9).

---

## 13. Editor integration

The LS exposes data and execution; editors own UI translation. All five commands and `lathe/sessionEvent` are
standard LSP; no editor-specific assumptions live in the protocol.

### Neovim (primary target)

- A `neotest-lathe` adapter (~250 lines Lua) drives `neotest`: `root`/`filter_dir`/`is_test_file`,
  `discover_positions` (calls `lathe.runnables.list`), `build_spec` (calls `lathe.run` with a runnable `id`, or
  `{ module, testPattern }` for directory nodes), and `results` (consumes `testResult` events for per-test
  pass/fail as they arrive).
- `nvim-dap` (~30 lines Lua) consumes `lathe.debug`'s `dapPort` and connects directly to Lathe's in-process DAP
  server.
- An optional companion plugin places gutter run buttons from `lathe.runnables.list`.

`filter_dir` keeps neotest out of `src/main/`, `target/`, and `.lathe/`; interactive levels are module → package
directory → test class → test method.

### VS Code (deferred, post-Neovim)

An extension calls the same five commands and maps `testResult` events into VS Code's `TestItem`/`TestRun` API. No
protocol changes are needed to support VS Code alongside Neovim.

---

## 14. Not in scope

- **Hot code replace.** `java-debug` supports it; Lathe does not actively integrate it. Future work.
- **Coverage UI.** JaCoCo is stripped from replay by design; coverage rendering is out of scope. Users who need
  coverage run `mvn verify` (fallback mode).
- **Test sharding / fork parallelism control.** Replay launches a single JVM per invocation; `forkCount`,
  `reuseForks`, and `parallel` are not reproduced. For single-test/single-class inner-loop runs this is usually
  preferable; a suite-wide run does not match Surefire's parallelism (documented).
- **Reproducing Maven's `<includes>`/`<excludes>`/tags for package-wide runs** (§10 selection-fidelity gap).
- **Launch-configuration UI.** `argLine`, profiles, and exec configuration live in `pom.xml`; Lathe captures them,
  it does not synthesize launch JSON the user must maintain.
```
