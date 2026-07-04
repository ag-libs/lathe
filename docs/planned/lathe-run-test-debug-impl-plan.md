# Lathe — Run/Test/Debug Implementation & Testing Plan

Companion to [`lathe-run-test-debug.md`](lathe-run-test-debug.md) (the design).
The design doc is the source of truth for *what* and *why*; this is *how*, *in what order*, and *how it is tested*.

Read the design first — especially §3 (capture), §4 (replay), §7 (run/exec), §12 (fragility/guardrails), §15 (rollout).

---

## Conventions (from `CLAUDE.md`)

- Build order: `lathe-core` → `lathe-compiler` → `lathe-server` → `lathe-maven-plugin`.
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

- **Ordering:** stage-1 critical path below; `lathe-core` (step 1) and the discovery AST walk (step 7) are independent
  and may proceed in parallel.
- **Spike first:** run **S1** (Surefire capture) as a throwaway to produce a *real* captured bundle, genericized to
  `com.example`, and vendor it as the parser's test fixture — so the parser is tested against reality, not a guess.
- **Shim test** lives in `lathe-maven-plugin` (it owns process/integration testing), driven via `ProcessBuilder` with a
  stub `LATHE_REAL_JAVA`.
- **Integration fixture:** reuse the **existing modular fixture under `lathe-maven-plugin/src/it`**; add a small
  run/test project or goal there rather than inventing a new fixture tree.
- **Deferred from stage 1:** resource-currency refresh and the exec "compose" fallback (§4.4, §7.3). Stage 1 falls back
  to Maven on a stale resource.

## Where to start next session

1. **Run S1** (throwaway, see Stage 0) → capture a real modular Surefire bundle → genericize → commit as a test
   resource for step 3.
2. **Lock the `lathe-core` API** (step 1) via a design summary — `TestLaunchData`, `LatheLayout` constants, the JDK
   argfile tokenizer — await "Approved", then implement with unit tests.
3. Proceed through steps 2–8.

---

## Stage 0 — gating spikes (throwaway, not production code)

| Spike | Question | Pass criterion | Priority |
|-------|----------|----------------|----------|
| **S1** | `-Djvm=<pure-shell shim>` intercepts the modular Surefire fork on the supported version; bundle is replayable | A hand-replay of the parsed launch runs one modular test green | **First — blocks step 3** |
| S2 | `<systemPropertyVariables>` land in the argfile; configured env in `capture.env` (§12.1) | Both observed | before "full fidelity" claim |
| S3 | `.lathe/<rel>/test-classes` carries filtered test resources + `META-INF/services` (§12.2) | resource-reading test passes under replay | before resource work |
| S4 | Orphan `.class` removal leaves nothing stale (§12.3) | deleted-source class absent from `.lathe/` | before completeness gate sign-off |

Exec-classpath spike is **already done** (§7.1, verified July 2026): classpath run + JDWP confirmed; `%modulepath`
broken through exec-maven-plugin 3.6.3.

---

## Stage 1 — build order with per-step tests

### 1. `lathe-core` foundations — *approval gate*

- `LatheLayout` constants: `capture.argv`, `capture.argfile.<i>`, `capture.env`, `capture.ready`, `test-launch.json`.
- `TestLaunchData` record + compact constructor (immutable defensive copies, invariants).
- JDK argfile tokenizer (whitespace/quote rules; single-level `@`).
- **Tests (unit, TDD-friendly):** tokenizer against known argfiles incl. quoted/spaced paths (positive + edge);
  `TestLaunchData` invariant + rejects-bad-input.

### 2. Capture shim + capture-JDK synthesis

- Ship `bin/java` (pure POSIX shell, `LATHE_CAPTURE_MODE` surefire|exec); `lathe:sync`/server synthesize
  `~/.cache/lathe/capture-jdk/` (`bin/java` + `release` symlink), keyed to the active build JDK (§3.2).
- **Tests (`lathe-maven-plugin`, `ProcessBuilder` + stub `LATHE_REAL_JAVA` that records argv):**
  probe → transparent passthrough, no bundle; modular fork → correct bundle; `exec` mode → snapshot unconditionally;
  spaced paths survive. Plus: synthesis produces an executable `bin/java` + valid `release`.

### 3. Bundle parser (server) — *approval gate*

- Raw bundle → `TestLaunchData`, reusing the tokenizer and `lathe-core` `Json`.
- **Tests (unit, fixture-driven):** the vendored real bundle (from S1) in `@TempDir` → assert module/classpath
  partition, `patchModules`, `add-*`, `jvmArgs`, `ForkedBooter` split, `javaHome`/`surefireVersion` derivation.
  Malformed bundle → fail-closed.

### 4. Freshness + completeness gates (server)

- Freshness: POM + `module-info.java` content fingerprints → re-capture decision (§3.6).
- Completeness: the concrete 4-step gate (§4.4) over the module set.
- **Tests (unit, `@TempDir`):** freshness — unchanged skips, each moved re-captures; completeness — lock
  present/stale/absent, missing params, empty output dir → launch vs fall-back.

### 5. Replay transform (server)

- `TestLaunchData` + reactor layout → `java` argv (§4.1).
- **Tests (unit, deterministic):** `target/→.lathe/` rewrite, JaCoCo + `ForkedBooter` stripped, `add-*` kept verbatim,
  runner + selector appended.

### 6. Capture driver + bootstrap runner (server; runner shipped) — *integration*

- Driver: private temp dir → `mvnd surefire:test -Djvm=` → poll `capture.ready` → parse → `test-launch.json` (§3.5).
- Runner: JUnit Platform `Launcher` + `TestExecutionListener` → NDJSON file (§4.3 default sink).
- **Tests (`lathe-maven-plugin` invoker, existing `src/it` modular fixture):** capture → replay one test green;
  NDJSON records match; `forkCount=0` → fall-back.

### 7. Discovery + LS commands + streaming (server) — *approval gate for command shapes*

- `runnables.list` AST walk over the cached `CompilationUnitTree` (reuse compile helpers); `id` carries erased param
  types.
- `lathe.run` / `lathe.session.*` / `lathe/sessionEvent`.
- **Tests (server, reuse compile pipeline):** discovery — compiled fixture yields `main`/`@Test`/`@ParameterizedTest`
  runnables (positive) and nothing for a non-test class (negative); run — `verify(client, timeout()).sessionEvent(...)`
  for `started`/`testResult`/`exit`; `cancel` kills the process.

### 8. CI guardrail (`lathe-maven-plugin` invoker)

- **Pinned Surefire/Failsafe version matrix** — capture+replay green on each supported version (§12). Non-negotiable
  protection for the private-protocol dependency; an internals change fails here, not in a user's editor.

---

## Stage 2 / 3 — test shape (not stage 1)

- **Debug (§8):** unit-test JDWP-arg injection; integration-test the suspend → scan-stdout → JDI-attach handshake
  (assert a breakpoint is hit). Gate as a slower integration test with a generous timeout — the flakiest area.
- **Failsafe (§9):** reuse the Surefire invoker harness with `failsafe:integration-test` + a self-provisioning
  (Testcontainers/embedded) IT.
- **Classpath run/debug (§7.1):** invoker test capturing `exec:exec` with no declaration → replay runs + JDWP attaches
  (mirrors the completed spike).

---

## Testing layers (summary)

| Layer | Where | Covers |
|-------|-------|--------|
| Unit (pure) | `lathe-core`, `lathe-server` | tokenizer, `TestLaunchData`, parser, gates, transform, discovery |
| Shell harness | `lathe-maven-plugin` | the shim (probe/fork/exec/spaces) via `ProcessBuilder` |
| Invoker integration | `lathe-maven-plugin/src/it` | capture→replay end-to-end, `forkCount=0`, CI version matrix |
| Server-request | `lathe-server` | commands + streaming (`verify(client, timeout())`) |
| Real-world e2e | `dropwizard` / `helidon` | large-codebase smoke |

## Tricky areas to watch

- **Real fixtures over hand-written ones** — vendor the S1-captured bundle so the parser meets reality.
- **Shim fail-closed** — a harness case must assert that a forced failure writes *no* `capture.ready` (→ fall-back).
- **Async, not sleeps** — `verify(client, timeout())` for all `sessionEvent` assertions.
- **Debug handshake flakiness** — real port, generous timeout, integration-only.
- **Mockito boundary** — allowed in `lathe-server`, forbidden in `lathe-compiler`; capture/parse logic lives in
  `lathe-server`/`lathe-core`, so we stay clear.
