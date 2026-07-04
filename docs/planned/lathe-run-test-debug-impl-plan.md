# Lathe — Run/Test/Debug Implementation & Testing Plan

Companion to [`lathe-run-test-debug.md`](lathe-run-test-debug.md) (the design).
The design doc is the source of truth for *what* and *why*; this is *how*, *in what order*, and *how it is tested*.

Read the design first — especially §3 (capture), §4 (replay), §7 (run/exec), §12 (fragility/guardrails), §15 (rollout).

---

## Conventions (from `CLAUDE.md`)

- Build order: `lathe-core` → `lathe-test-runner` → `lathe-compiler` → `lathe-server` → `lathe-maven-plugin` (the new
  `lathe-test-runner` leaf slots after core — see **Module design**).
- Tests: JUnit 5 + AssertJ; `@TempDir`; `methodName_condition_result`; **no** `@Nested`; a positive **and** an edge case
  per behaviour. No Mockito in `lathe-compiler` (`lathe-server` is fine).
- `lathe-maven-plugin` owns the maven-invoker integration tests (`src/it`).
- In `lathe-server` tests, **reuse the existing compile-pipeline helpers** — never hand-roll `javac`; read ≥2
  neighbouring test classes first.
- Async assertions: `Mockito.verify(client, timeout(N))`; **never** `Thread.sleep`.
- **Approval gate:** any step introducing a public type or new abstraction — STOP, output a design summary, wait for
  "Approved" before writing code.
- Run `mvn spotless:apply` after any `.java` change.

## Decisions locked (design-review session)

- **Ordering:** stage-1 critical path below; `lathe-core` (step 1), the `lathe-test-runner` module (step 2), and the
  discovery AST walk (step 8) are mutually independent and may proceed in parallel.
- **Module split:** one new module (`lathe-test-runner`); the capture/replay engine stays in `lathe-server`; the
  `java-debug` dependency is deferred to stage 2 — see **Module design**.
- **Spike first:** run **S1** (Surefire capture) as a throwaway to produce a *real* captured bundle, genericized to
  `com.example`, and vendor it as the parser's test fixture — so the parser is tested against reality, not a guess.
- **Shim test** lives in `lathe-maven-plugin` (it owns process/integration testing), driven via `ProcessBuilder` with a
  stub `LATHE_REAL_JAVA`.
- **Integration fixture:** reuse the **existing modular fixture under `lathe-maven-plugin/src/it`**; add a small
  run/test project or goal there rather than inventing a new fixture tree.
- **Deferred from stage 1:** resource-currency refresh and the exec "compose" fallback (§4.4, §7.3). Stage 1 falls back
  to Maven on a stale resource.

## Module design (locked)

This feature adds **one** module — `lathe-test-runner` — and routes everything else into existing modules. A new module
is justified **only** by a hard deployment boundary; everything else is dependency-gating or in-module cohesion (per
`CLAUDE.md` KISS).

```
lathe-core → lathe-test-runner → lathe-compiler → lathe-server → lathe-maven-plugin
```

| Module | This feature adds | Notes |
|--------|-------------------|-------|
| `lathe-core` | `TestLaunchData` (schema), JDK argfile tokenizer, `LatheLayout` capture / `capture-jdk` constants, **capture-JDK synthesis helper**, **shim shell script (resource)** | shared by server + plugin; no new external deps |
| `lathe-test-runner` (**NEW**, leaf) | bootstrap `main`: JUnit Platform `Launcher` + `TestExecutionListener` → NDJSON | **zero runtime deps**; `junit-platform-launcher` `provided`; **no `lathe-core` dep** (reimplements NDJSON); **no `module-info`** (runs as unnamed module in the replay JVM) |
| `lathe-server` | parser, transform, freshness/completeness gates, capture driver, discovery, commands, streaming, debug | embeds the runner artifact (below); `java-debug` is a **stage-2-only** dep |
| `lathe-maven-plugin` | `lathe:sync` calls the `lathe-core` synthesis helper; invoker + shell-shim tests | unchanged deps |

**Runner → replay JVM.** `lathe-server` takes `lathe-test-runner` as a build dependency (enforces reactor order +
packaging), embeds its jar as a resource, and **materializes it to `~/.cache/lathe/lathe-test-runner.jar` on first
use** — the same lazy-materialize pattern as capture-jdk synthesis. No runtime coordinate resolution, no JPMS `requires`
edge to the runner.

**Not extracted** (deliberately, no forcing function): the capture/replay engine (coupled to the compile pipeline,
`.lathe/` layout, lock protocol), debug (in-process in the server JVM — gated by *deferring the `java-debug` dependency
to stage 2*, not by a module), and parallel-run orchestration (additive server logic).

---

## Where to start next session

1. **Run S1** (throwaway, see Stage 0) → capture a real modular Surefire bundle → genericize → commit as a test
   resource for the parser (step 4).
2. **Lock the `lathe-core` API** (step 1) via a design summary — `TestLaunchData`, `LatheLayout` constants, the JDK
   argfile tokenizer, the capture-JDK synthesis helper — await "Approved", then implement with unit tests.
3. **Lock the `lathe-test-runner` module** (step 2) — new leaf module, bootstrap `main`, NDJSON record schema — await
   "Approved", then implement. Independent of step 1; may proceed in parallel.
4. Proceed through steps 3–9.

---

## Stage 0 — gating spikes (throwaway, not production code)

| Spike | Question | Pass criterion | Priority |
|-------|----------|----------------|----------|
| **S1** | `-Djvm=<pure-shell shim>` intercepts the modular Surefire fork on the supported version; bundle is replayable | A hand-replay of the parsed launch runs one modular test green | **First — blocks step 4** |
| S2 | `<systemPropertyVariables>` land in the argfile; configured env in `capture.env` (§12.1) | Both observed | before "full fidelity" claim |
| S3 | `.lathe/<rel>/test-classes` carries filtered test resources + `META-INF/services` (§12.2) | resource-reading test passes under replay | before resource work |
| S4 | Orphan `.class` removal leaves nothing stale (§12.3) | deleted-source class absent from `.lathe/` | before completeness gate sign-off |

Exec-classpath spike is **already done** (§7.1, verified July 2026): classpath run + JDWP confirmed; `%modulepath`
broken through exec-maven-plugin 3.6.3.

---

## Stage 1 — build order with per-step tests

### 1. `lathe-core` foundations — *approval gate*

- `LatheLayout` constants: `capture.argv`, `capture.argfile.<i>`, `capture.env`, `capture.ready`, `test-launch.json`,
  and the `~/.cache/lathe/capture-jdk/` paths (`bin/java`, `release`).
- `TestLaunchData` record + compact constructor (immutable defensive copies, invariants).
- JDK argfile tokenizer (whitespace/quote rules; single-level `@`).
- **Capture-JDK synthesis helper** — create the home, symlink `release`, install `bin/java`; idempotent, keyed to the
  active build JDK (§3.2). Consumed by both `lathe:sync` and the server.
- **Shim shell script** shipped as a `lathe-core` resource, materialized by the synthesis helper.
- **Tests (unit, TDD-friendly):** tokenizer against known argfiles incl. quoted/spaced paths (positive + edge);
  `TestLaunchData` invariant + rejects-bad-input; synthesis produces an executable `bin/java` + valid `release` under
  `@TempDir`.

### 2. `lathe-test-runner` module (NEW, leaf) — *approval gate*

- New Maven module after `lathe-core` in the reactor: **zero runtime deps**, `junit-platform-launcher` at `provided`,
  **no `module-info`** (runs as the unnamed module in the replay JVM), **no `lathe-core` dependency** (reimplement the
  minimal NDJSON writing so nothing bleeds into the user's test classpath).
- Bootstrap `main`: build selectors from the runnable `id` (class/method + erased param types), drive the JUnit
  Platform `Launcher`, register a `TestExecutionListener` that writes one NDJSON record per event to the sink path
  passed as a system property (§4.3).
- **Tests (runner, real `junit-platform-launcher`):** a sample in-module test class → expected NDJSON records for
  pass/fail/skip (positive); a malformed selector fails cleanly (edge).

### 3. Capture-JDK synthesis wiring + shim behavior

- Wire `lathe:sync` (and lazy server-side synthesis) to call the step-1 helper; `LATHE_CAPTURE_MODE` surefire|exec;
  keyed to the active build JDK (§3.2).
- **Tests (`lathe-maven-plugin`, `ProcessBuilder` + stub `LATHE_REAL_JAVA` that records argv):**
  probe → transparent passthrough, no bundle; modular fork → correct bundle; `exec` mode → snapshot unconditionally;
  spaced paths survive; a forced failure writes **no** `capture.ready` (→ fall-back). Plus: `lathe:sync` synthesis
  produces an executable `bin/java` + valid `release`.

### 4. Bundle parser (server) — *approval gate*

- Raw bundle → `TestLaunchData`, reusing the tokenizer and `lathe-core` `Json`.
- **Tests (unit, fixture-driven):** the vendored real bundle (from S1) in `@TempDir` → assert module/classpath
  partition, `patchModules`, `add-*`, `jvmArgs`, `ForkedBooter` split, `javaHome`/`surefireVersion` derivation.
  Malformed bundle **or a missing structural landmark** (no `ForkedBooter` split, no `patchModules` key) → fail-closed.

### 5. Freshness + completeness gates (server)

- Freshness: POM + `module-info.java` content fingerprints → re-capture decision (§3.6).
- Completeness: the concrete 4-step gate (§4.4) over the module set.
- **Tests (unit, `@TempDir`):** freshness — unchanged skips, each moved re-captures; completeness — lock
  present/stale/absent, missing params, empty output dir → launch vs fall-back.

### 6. Replay transform (server)

- `TestLaunchData` + reactor layout → `java` argv (§4.1); append the materialized runner jar + selector.
- **Tests (unit, deterministic):** `target/→.lathe/` rewrite, JaCoCo + `ForkedBooter` stripped, `add-*` kept verbatim,
  runner + selector appended.

### 7. Capture driver + replay launch (server) — *integration*

- Driver: private temp dir → `mvnd surefire:test -Djvm=` → poll `capture.ready` → parse → `test-launch.json` (§3.5).
- **Materialize the `lathe-test-runner` jar (step 2)** to `~/.cache/lathe/` on first use (same lazy pattern as
  capture-jdk); append it to the replay classpath; launch the replay JVM; line-read the NDJSON sink (§4.3).
- **Tests (`lathe-maven-plugin` invoker, existing `src/it` modular fixture):** capture → replay one test green;
  NDJSON records match; `forkCount=0` → fall-back.

### 8. Discovery + LS commands + streaming (server) — *approval gate for command shapes*

- `runnables.list` AST walk over the cached `CompilationUnitTree` (reuse compile helpers); `id` carries erased param
  types.
- `lathe.run` / `lathe.session.*` / `lathe/sessionEvent`.
- **Tests (server, reuse compile pipeline):** discovery — compiled fixture yields `main`/`@Test`/`@ParameterizedTest`
  runnables (positive) and nothing for a non-test class (negative); run — `verify(client, timeout()).sessionEvent(...)`
  for `started`/`testResult`/`exit`; `cancel` kills the process.

### 9. CI guardrail (`lathe-maven-plugin` invoker)

- **Pinned Surefire/Failsafe version matrix** — capture+replay green on each supported version (§12). Non-negotiable
  protection for the private-protocol dependency; an internals change fails here, not in a user's editor.

---

## Stage 2 / 3 — test shape (not stage 1)

- **Debug (§8):** this is where the `java-debug` dependency is **first added** to `lathe-server` (stage-2-only); JDWP-arg
  injection needs no library (a plain string on the replay argv) and could even land in stage 1. Unit-test JDWP-arg
  injection; integration-test the suspend → scan-stdout → JDI-attach handshake (assert a breakpoint is hit). Gate as a
  slower integration test with a generous timeout — the flakiest area. **JPMS check:** verify `java-debug` resolves
  against `lathe-server`'s `module-info` (automatic-module by filename vs. classpath treatment).
- **Failsafe (§9):** reuse the Surefire invoker harness with `failsafe:integration-test` + a self-provisioning
  (Testcontainers/embedded) IT.
- **Classpath run/debug (§7.1):** invoker test capturing `exec:exec` with no declaration → replay runs + JDWP attaches
  (mirrors the completed spike).

---

## Testing layers (summary)

| Layer | Where | Covers |
|-------|-------|--------|
| Unit (pure) | `lathe-core`, `lathe-server` | tokenizer, `TestLaunchData`, synthesis, parser, gates, transform, discovery |
| Runner | `lathe-test-runner` | bootstrap `Launcher` drives a sample test → NDJSON records |
| Shell harness | `lathe-maven-plugin` | the shim (probe/fork/exec/spaces) via `ProcessBuilder` |
| Invoker integration | `lathe-maven-plugin/src/it` | capture→replay end-to-end, `forkCount=0`, CI version matrix |
| Server-request | `lathe-server` | commands + streaming (`verify(client, timeout())`) |
| Real-world e2e | `dropwizard` / `helidon` | large-codebase smoke |

## Tricky areas to watch

- **Real fixtures over hand-written ones** — vendor the S1-captured bundle so the parser meets reality.
- **Runner classpath hygiene** — `lathe-test-runner` must stay zero-dep (no `lathe-core`); a stray transitive dep on the
  user's test classpath can shadow the code under test.
- **Shim fail-closed** — a harness case must assert that a forced failure writes *no* `capture.ready` (→ fall-back).
- **Async, not sleeps** — `verify(client, timeout())` for all `sessionEvent` assertions.
- **Debug handshake flakiness** — real port, generous timeout, integration-only.
- **Mockito boundary** — allowed in `lathe-server`, forbidden in `lathe-compiler`; capture/parse logic lives in
  `lathe-server`/`lathe-core`, so we stay clear.
