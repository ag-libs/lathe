# Lathe — Test Framework: Fewer Cold Compiles, Same Coverage

## Status

**Proposed.** Measured on 2026-10-11 against `main` at `972bb1c8`; since then 7a (invoker correctness) shipped in `bfec20a6`, and slices 1, 2, 3, and 5 are done, plus parallel test classes in `lathe-server` (see [Results](#results)).
Each step below is independent and is re-measured on its own.
It covers `lathe-server`'s unit tests (sections 1–6) and the `lathe-maven-plugin` invoker suite (section 7).

## Why

The test suite grows with every feature, and `lathe-server`'s tests are most of its cost.
A full `mvn verify` takes about 1:12 wall-clock under mvnd; `lathe-server`'s tests (about 40 s) and the invoker suite (about 30 s) are nearly all of it, and they run one after the other.
Running test classes in parallel was tried and failed.

The goal is less CPU for the same tests and the same coverage, with a framework that keeps the next tests cheap by default.

## What the measurements show

Setup, reproducible with no code change (see [Measuring](#measuring)): plain Maven, `lathe-server` tests on committed code, the test JVM profiled with Java Flight Recorder.
The run took about 44 s wall-clock and 160 s of CPU across all JVMs.

### Where the test JVM's CPU goes

| Category | Share |
|---|---|
| javac: file manager — reading the JDK image, jars, and classpath | 32.5% |
| javac: name table and symbol table setup | 14.3% |
| javac: other setup and internals | ~15% |
| javac: parse, attribute, and generate (the actual compile) | ~9% |
| Lathe code under test | 7.6% |
| JaCoCo coverage instrumentation | 6.7% |
| Mockito | 6.2% |

About 60% of the CPU is javac rebuilding its view of the JDK and the classpath, not compiling test code.

### Compiles

One run of 1,220 tests creates **1,528 javac tasks and 1,390 file managers**: more than one cold compiler per test.

### The largest single waste

`SampleFixture` recompiles the same 150-line `Sample.java` in a `@BeforeEach`, so 116 tests across 6 classes each pay a full compile of identical, read-only source.
That is about 17% of all server test CPU.

### Every test compile sees the whole test classpath

A compile with no explicit classpath inherits the test JVM's: 18 jars plus all of `test-classes`.
A fresh file manager opens and indexes all of them, though test snippets need only the JDK.
A standalone benchmark (one small snippet, 200 compiles per mode, steady state) isolates the cost:

| Mode | CPU per compile |
|---|---|
| Fresh file manager, inherited test classpath (today) | 14.3 ms |
| Fresh file manager, empty classpath | 6.3 ms |
| One shared file manager, empty classpath | 5.4 ms |

### Why parallel test classes failed

`CompletionTestSupport` holds its fixture in static fields of an abstract base class (`static CompletionFixture fixture`, `@TempDir static Path sharedTmp`).
A static field in a base class is one slot shared by every subclass, so with classes running in parallel one completion class closes the fixture or deletes its directory while another uses it:
173 of the 199 failures in a parallel run were completion tests, almost all `NoSuchFileException`.
Even when correct, parallel classes trade CPU for wall-clock (44 s → 27 s wall, 160 s → 246 s CPU), so parallelism is not the lever for CPU.

### Why the invoker suite waits for the server's tests

`lathe-maven-plugin` depends on `lathe-server` (`provided`) because the invoker suite installs the server into its local repository.
Maven 3.9, which mvnd 1.0.3 runs, starts a downstream module only after its upstream's whole lifecycle, tests included,
so the invoker suite cannot start until the server's tests finish, although it only needs the server jar.

### Fragile end-to-end tests

`LatheTextDocumentServiceTest` and `LatheEngineTest` drive the real server's worker threads and wait with wall-clock timeouts.
They are about 10% of CPU, but they are the classes that time out under load, and every timeout costs its full duration.

### The invoker suite

Measured the same way, unit tests skipped, starting from an empty invoker local repository (as after `mvn clean`): about 56 s, its two projects run one after the other.

| Project | Time | Where it goes |
|---|---|---|
| `multi-module` | 39.6 s | smoke tests ~24 s; `lathe:sync` 6.5 s; Maven start and compiles ~7 s |
| `formatters` | 16.5 s | two builds of one project (one per formatter): ~8 s building, ~8 s starting two cold Maven JVMs |

Inside `multi-module`:

- `LspSmokeTest` 17 s, about 3.5 s of it starting its server; `McpSmokeTest` 6.4 s, about 2.6 s starting its own.
- Two tests mostly wait for the server's file watcher, which polls every 2 s (`workspaceReload_…` 3.2 s, `pomChange_…` 1.8 s).
- `lathe:sync`: installing the server about 3.1 s (mostly downloading its ~55 dependencies into the empty local repository), dependency sources and type indexes about 1.9 s, JDK sources 0.8 s.

Most of the suite's CPU is Maven itself — the outer build and three cold invoked builds — which is what an invoker suite is for; the smoke tests' own JVM is comparatively light.

**The suite does not reliably test the server just built.**
The invoked builds see the developer's `~/.m2` as a repository (`local-m2` in `src/it/settings.xml`, snapshots enabled),
and in the measured run the fixture's sync resolved `lathe-server` from there, not from the reactor's fresh build.
Unless `mvn install` ran first, the suite tests whichever server was last installed — the "invoker runs an old server" failure seen before.
Why the reactor's copy loses (what `invoker:install` puts in the local repository for a `provided` dependency, versus snapshot metadata from `local-m2`) is not yet confirmed.

## Goals

- Cut `lathe-server` test CPU substantially with the same tests and the same coverage.
- Make the cheap path the default, so new tests stay cheap without each author knowing why.
- One way to compile Java in tests (DRY), with fixtures whose lifetime is explicit (KISS).
- The invoker suite always tests the reactor's own artifacts, and spends less wall-clock waiting.

## Non-goals

- ~~Parallel test execution as a goal.~~ Reversed on 2026-10-18: once slices 1–3 removed what blocked it, parallel classes halved `lathe-server`'s wall-clock (see [Results](#results)).
- Changing what any test asserts.
- Mocking javac: tests keep compiling real source.

## Design

### 1. One test compiler: `TestJavac`

Today tests compile through several paths: `TestCompiler.parse` and `compileToDir`, `TempSourceCompiler`, `SourceParser`, `CompletionFixture`, each creating its own file manager.
They route through one small per-JVM helper instead:

- It owns one file manager per classpath configuration, reused across compiles.
- The default configuration is **JDK only**, never the inherited test classpath.
- A test that needs a classpath states it; file managers are cached by that exact list of paths, so a configuration is indexed once per JVM.
- Its public surface is the compile and parse operations tests already use, so call sites change, not assertions.

Reusing a `StandardJavaFileManager` across tasks is what the server's own `ModuleSourceCompiler` already does.
The benchmark above puts this alone at about 2.6× less CPU per compile.

### 2. Compiled fixtures cached by content

Read-only fixtures — `Sample.java` first — are compiled once per JVM, keyed by their source text, and shared by every test that only reads the result (trees, elements, positions).
A test that edits a source compiles its own copy; that becomes the explicit, rare case.
`SampleFixture` keeps its API, so its subclasses do not change.

### 3. Fixtures owned by instances

Class-wide fixture state moves from static fields in base classes to instance fields with JUnit's per-class lifecycle (`@TestInstance(PER_CLASS)`).
Each test class then owns its fixture; no class can close or delete another's.
This removes the cause of the parallel failures without making parallelism a goal.

### 4. Two tiers, documented

| Tier | Shape | Cost | Holds |
|---|---|---|---|
| Analysis | one synchronous compile through `TestJavac`; no threads, no timeouts | ~5 ms per compile | most behavior: completion, hover, references, diagnostics, code actions |
| Workspace | the real server with worker threads, files, and timeouts | seconds | end-to-end wiring only: open/save/reconcile flows, sync prompts |

New behavior is tested in the analysis tier; a workspace test proves the wiring once.
The workspace tier is tagged so it can be run or skipped as a group.
The testing section of `CLAUDE.md` gains a short rule: which tier a new test belongs in, and that read-only tests use the cached fixture.

### 5. Coverage only when asked for

JaCoCo (6.7% of test CPU, plus slower class loading) moves into a `coverage` profile, run on demand (`mvn verify -Pcoverage`).
CI does not enable it until something uses the report.

### 6. Build ordering (optional, structural)

Without Maven 4's finer-grained scheduling, letting the invoker suite start before the server's tests finish needs the server's tests in their own test-only module:
the server jar is then ready within seconds, and the invoker suite runs alongside the server's tests.
Wall-clock for a full verify becomes about the longer of the two instead of their sum, roughly 30 s less.

The cost: the server's tests run today inside the server's Java module, using package-private code.
A separate module runs them on the classpath with the same package names, which still reaches package-private code but changes the test runtime setup.
This is the largest change here, and it is independent of the CPU work; it is weighed on its own.

### 7. Invoker suite

**a. Test the reactor's server, not `~/.m2`'s (correctness, first).** *Done (`bfec20a6`).*
The cause was the invoker's own caches: `target/local-repo` and the unpacked launchers in `target/it-home` kept the previous SNAPSHOT between runs.
A `maven-clean-plugin` execution in `pre-integration-test`, ordered before the invoker, now drops Lathe's artifacts and the `servers/` and `latest/` launcher caches each run; the JDK, dependency, and type-index caches stay.
The original analysis follows.
Lathe's own artifacts — `lathe-server` and `lathe-mcp-server` included — are installed from the reactor into the invoker's local repository, and the invoked builds prefer them over `local-m2`.
The first step is confirming why the reactor's copy currently loses; the fix follows from that.
With it, a plain `mvn verify` tests the code being verified, with no prior `mvn install`.

**b. Run the two invoker projects in parallel.**
`maven-invoker-plugin`'s `parallelThreads` lets `formatters` (16.5 s) overlap `multi-module` (39.6 s): about 16 s less wall-clock, the same CPU, one line of configuration.
The projects build in separate directories; their shared local repository is what to check under concurrency.

**c. A test-only watcher poll interval.**
The server's file watcher polls every 2 s; a system property that only the smoke tests set (for example 200 ms) removes most of the 4–5 s two tests spend waiting.
Production keeps 2 s. This saves wall-clock, not CPU.

**d. Third-party artifacts straight from `~/.m2`.**
The invoker's local repository lives in `target/`, so every `mvn clean verify` downloads about 80 artifacts again (dependency sources, the server's dependencies).
Resolving third-party artifacts from `~/.m2` while Lathe's own come from the reactor avoids that, and is decided together with 7a, since both shape what the invoked builds resolve and from where.

**Considered and rejected:**

- *One server shared by both smoke classes* — they speak different protocols (LSP and MCP), and sharing couples the two classes, for about 3 s.
- *mvnd for the invoked builds* — it would save about 4 s per cold start (about 12 s in total), but a warm daemon caches extension classloaders;
  when the code under test is Lathe's own Maven extension, the daemon could quietly run a stale one.

### 8. Gradle

[Gradle support](lathe-gradle-support.md) (Gradle 8.8+) adds a TestKit suite, cross-tool contract tests, and a Gradle version matrix.
`lathe-server`'s unit tests are unaffected — `.lathe/` is the seam — so sections 1–6 apply as they are;
the cost that grows is end-to-end, where every test is a real build and the matrix multiplies them.

- **One rule for artifacts.** The Gradle design consumes Lathe's artifacts through `mavenLocal()`, which is `~/.m2` — the same trap as 7a.
  Both end-to-end suites consume a repository the reactor fills during the build (like the invoker's `target/local-repo`), never `~/.m2`;
  7a and the Gradle wiring are one decision, and a plain `mvn verify` then needs no prior `mvn install`.
- **Server smoke tests run once, not per front-end.** Front-end suites — the Maven invoker suite and Gradle TestKit — assert only the `.lathe/` they write (cheap, like `MultiModuleTest`);
  one set of server smoke tests runs against a `.lathe/` from either tool; the cross-tool contract test proves the two `.lathe/`s equivalent.
  Copying `LspSmokeTest` and `McpSmokeTest` into a Gradle fixture, per Gradle version, would repeat about 24 s and two server starts each time.
- **Parallel with the plugin.** The Gradle module depends on `lathe-core`, `lathe-test-runner`, and `lathe-junit`, not on `lathe-server`,
  so under mvnd it runs alongside the server's tests and the invoker suite — wall-clock, not CPU, since the builds share cores.
  It stays parallel only while its tests do not boot the server; the server smoke tests living in one place (above) keep it that way.
- **Matrix in CI only.** Locally, one Gradle version; CI runs the matrix (8.8, latest 8.x, latest 9.x), gated like the planned `-Dlathe.gradle.skip`, the same tiering as section 4.
- **TestKit like the cached fixtures.** Build each fixture once per test class and assert many things on the result; reuse one TestKit directory and daemon per test JVM, avoiding a cold start per test.

## Expected effect

| Step | Estimated CPU saved (server tests) |
|---|---|
| `TestJavac`: empty default classpath and shared file managers | ~25–35% |
| Cached read-only fixtures, `SampleFixture` first | ~15% |
| JaCoCo in an on-demand profile | ~7% |

Together roughly half of the server tests' CPU, same tests and coverage.
These are estimates from the profile shares and the benchmark, not end-to-end measurements; each step is measured after it lands.

| Invoker step | Estimated wall-clock saved |
|---|---|
| Parallel invoker projects (7b) | ~16 s |
| Test-only watcher interval (7c) | ~4–5 s |
| Third-party artifacts from `~/.m2` (7d) | a few seconds plus network, on clean builds |

The invoker suite's CPU stays largely as is: it is Maven running real builds.

## Slicing

1. **`TestJavac`** — the helper, routing `TestCompiler`, `TempSourceCompiler`, and the completion fixture through it; JDK-only default classpath. *Done.*
2. **Cached fixtures** — `SampleFixture` compiles once per JVM; other read-only fixtures follow. *Done.*
3. **Instance-owned fixtures** — `CompletionTestSupport` and any other static base-class fixture. *Done.*
4. **Tiers** — tag the workspace tests; add the tier rule to `CLAUDE.md`.
5. **Coverage profile** — JaCoCo moves to an on-demand profile. *Done.*
6. **Build ordering** — optional, decided separately: the server's tests in a test-only module.
7. **Invoker: reactor artifacts** (7a) — confirm why the reactor's server loses to `~/.m2`, then fix it, with 7d decided alongside. Independent of slices 1–6, and the first to do. *Done (`bfec20a6`); 7d is still open.*
8. **Invoker: parallel projects** (7b).
9. **Invoker: test-only watcher interval** (7c).
10. **Gradle test layout** (8) — adopted as the Gradle front-end lands: reactor-filled repository, `.lathe/`-only front-end assertions, server smoke tests in one place, matrix in CI only.

## Verification

- Every slice keeps the test count and the assertions; the full `mvn verify` passes.
- Each slice is re-measured with the same setup as above (wall-clock, CPU, and the JFR categories), and the numbers recorded here.
- Slice 3 is checked by running test classes in parallel once: no fixture-sharing failures remain.
- Slice 7 is checked by building a deliberately different `lathe-server` into `~/.m2` and confirming the invoker suite still runs the reactor's own.

## Results

**Slice 1 — `TestJavac` (2026-10-18).** File managers are shared per thread, classpath, and option set
(options are part of the key because javac applies path options to the file manager; per thread because a file manager is not thread-safe), and `ParsedSource` is no longer closeable.
No test needed a classpath it had not declared; 1,226 tests pass unchanged.

| Measure | Before | After |
|---|---|---|
| `mvn test -pl lathe-server` under mvnd, wall-clock | 35.1 s | 32.0 s |
| File managers / javac tasks | 1,425 / 1,547 | 866 / 1,547 |
| Test-JVM CPU samples | 1,928 | 1,652 (−14%) |
| Samples reading the JDK or classpath | 1,055 | 775 |

Less than the 25–35% estimate. The remaining reads are mostly not in test helpers:
production compiles in workspace-tier tests (`CompilationWorker`, `JavacRunner`, about 280 samples), `SampleFixture` (83, slice 2),
and completion's per-request JDK module lookups (`ImportCompletionProvider.isModuleAccessible`, 94).
The other 866 file managers are mostly `SourceParser`'s, which read only source files and stay cheap.

**Slices 2, 3, 5 (2026-10-18).** `SampleFixture` compiles `Sample.java` once per JVM (a read-only static, its file in a temp dir deleted on exit);
the completion fixtures are instance fields under `@TestInstance(PER_CLASS)`, with the class-wide temp dir injected as a `@BeforeAll` parameter;
JaCoCo runs only with `-Pcoverage`, on demand; CI does not pass it, since nothing read the report it produced.

| Measure | Original | After slice 1 | After slices 1–3, 5 |
|---|---|---|---|
| `mvn test -pl lathe-server` under mvnd, wall-clock | 35.1 s | 32.0 s | 26.5 s |
| Test-JVM CPU samples | 1,928 | 1,652 | 1,343 (−30%) |
| File managers / javac tasks | 1,425 / 1,547 | 866 / 1,547 | 751 / 1,432 |

**Parallel test classes (2026-10-18).** `lathe-server/src/test/resources/junit-platform.properties` runs test classes concurrently, one thread per core, and the methods of a class in order.
What made it safe:

- `TestJavac` and `SampleFixture` keep their javac state per thread: javac is not thread-safe, and it fills in symbols lazily.
- `CompileLog` hears every compile in the JVM, so the three classes that count compiles (`SaveTest`, `RenameTest`, `WritePassTest`) are `@Isolated`: JUnit runs them while no other class runs. No assertion changed.
- `LoggingConfig` reads the logging configuration once per JVM; re-reading it before every class reset the loggers, dropping handlers other classes had attached.
- `SaveTest` waits one 1.5 s quiet window for a duplicate refresh instead of one per file (saves about 3 s in either mode).

Ten runs in random class and method order all passed, in 14.7–17.5 s (26.5 s sequential), with the same 1,226 tests.
The machine is busy at 72–97% during the main phase; the rest is Maven startup (about 3 s, one core) and the isolated classes (about 3.5 s, one core).
`-Djunit.jupiter.execution.parallel.enabled=false` runs the suite sequentially, for debugging.
A 200 ms server scan interval (instead of 2 s) made the suite slower (28.6 s), so workspace tests do not wait on the scan.

## Measuring

The numbers above came from a throwaway worktree with no code change:

- **CPU and wall-clock:** plain Maven, not the mvnd client, so the forked test JVM is a child process: `/usr/bin/time` around `<maven>/bin/mvn -o test -pl lathe-server -am -Dspotless.check.skip=true`.
- **Profile:** `JDK_JAVA_OPTIONS="-XX:StartFlightRecording=settings=profile,filename=/tmp/<label>-%p.jfr"`, which reaches every forked JVM; Surefire's `argLine` does not, because JaCoCo's `prepare-agent` rewrites it. `%p` keeps one recording per process.
- **Compile counts:** add `method-timing=com.sun.tools.javac.api.JavacTaskImpl::<init>;com.sun.tools.javac.file.JavacFileManager::<init>` to the recording (JDK 25+), then `jfr view method-timing`.
- **Per-class attribution:** record with `-XX:FlightRecorderOptions:stackdepth=1024` and charge each sample to the outermost test-class frame.
- **Parallel trial:** `-Djunit.jupiter.execution.parallel.enabled=true -Djunit.jupiter.execution.parallel.mode.classes.default=concurrent` in `JDK_JAVA_OPTIONS`.
- **Invoker step timings:** `-Dorg.slf4j.simpleLogger.showDateTime=true -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS` in `JDK_JAVA_OPTIONS` timestamps every Maven log line, in the invoked builds too;
  the gap between consecutive `--- plugin:goal ---` lines in `target/it/<project>/build.log` is that step's duration, and the smoke tests' Surefire reports give per-test times.
  Run with `-Dmaven.test.skip.exec=true` to skip unit tests while the invoker suite still runs, after deleting `target/it`, `target/local-repo`, and `target/it-home` for a cold start.

## Decisions

1. **CPU over wall-clock** — the levers are fewer cold compiles and less repeated work, not parallelism.
   *Revised 2026-10-18:* both — the CPU slices first, then parallel test classes for wall-clock.
2. **JDK-only default classpath for test compiles** — a test that needs a classpath declares it.
3. **Build ordering is optional** — slice 6 is decided on its own, after the CPU slices.
4. **Invoker correctness before invoker speed** — the suite must test the reactor's artifacts (7a) before it is made faster.
5. **No mvnd and no shared server for the invoked builds** — both trade a few seconds for staleness or coupling (section 7).
6. **Gradle 8.8+** — the first version that captures through the standard plugin API with no fallback; the matrix (8.8, latest 8.x, latest 9.x) runs in CI only.
7. **Server smoke tests run once, front-end suites assert `.lathe/` only** — keeps the Gradle module free of a `lathe-server` dependency, so it runs in parallel.
