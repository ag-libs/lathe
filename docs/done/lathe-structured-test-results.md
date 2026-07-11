# Lathe — Structured Per-Test Results

> **Forward-looking stance superseded** by
> [planned/lathe-neotest-experience.md](../planned/lathe-neotest-experience.md).
> This document remains the accurate record of what shipped (real per-method pass/fail/skip,
> behavior R3). Its as-built decision to keep the JUnit→Lathe id mapping in Lua is **revisited** by
> that spec's Phase 2 (thin-adapter split).

## Problem

`lathe.run.test` (and the neotest adapter built on it) can only report one aggregate status per
replay run. `ReplayOutcome` (`lathe-server/.../run/ReplayOutcome.java`) carries `launched`,
`blockedReasons`, `exitCode`, and raw console `output` — no per-test breakdown. When the run
covers more than one test (a class, or — since `lathe.neotest`'s `build_spec` binds file-run to
class-run and directory-run to package-run — a whole class or package), the neotest adapter has no
way to know which specific test(s) failed.

Concretely, `lathe.neotest.lua`'s `M.results()` fans the *same* aggregate status out to every
descendant position in the run (`tree:get_key(ctx.position_id):iter_nodes()`) — a design forced by a
different, already-shipped fix: `neotest.Client:run_tree` marks every position in a run's subtree as
"running" up front, but only clears whichever ids `results()` returns, so *some* status has to reach
every descendant or their gutter/summary glyphs spin forever. The fan-out means **one failing method
in a 20-method class currently marks all 20 as failed.**

The data to fix this properly already exists and is being thrown away: `LatheTestRunner`
(`lathe-test-runner/.../LatheTestRunner.java`) already drives JUnit Platform with a
`SummaryGeneratingListener`, which internally builds a full `TestExecutionSummary` — every test's
identity, pass/fail, and exception — but the runner only ever reads
`listener.getSummary().getTotalFailureCount()` to pick an exit code and discards the rest.

## Goal

Surface real per-test results from a class/package run, so `lathe.neotest.lua` can mark exactly the
tests that failed (and exactly the ones that passed), instead of stamping every descendant with one
aggregate status.

## Non-Goals

* Streaming/live results while a run is in flight (`docs/planned/lathe-run-test-debug.md` §15 already
  tracks NDJSON streaming as a separate, deferred item — this design produces one complete result set
  per replay, read after the process exits, same shape as today's `output` capture).
* Fixing the underlying "stuck running forever" mitigation itself — that fix stays; this replaces
  *what* gets fanned out (one aggregate result vs. real per-test results), not the fan-out safety net.
  A test genuinely missing from the structured result (e.g. an id-mapping failure, see Risks) should
  still fall back to the aggregate status rather than being left unresolved.
* A coverage report or any output beyond pass/fail/skipped + failure message per test.

## Proposed Design

### 1. Capture every test's result in the runner, not just the count

Replace (or supplement) `SummaryGeneratingListener` in `LatheTestRunner.run` with a listener that
records **every** `executionFinished(TestIdentifier, TestExecutionResult)` call, not just failures —
`SummaryGeneratingListener`'s own `TestExecutionSummary.getFailures()` only gives failures; "passed"
has to be inferred as "in the `TestPlan` but not in the failures list," which is fragile across
container-level (class) identifiers mixed in with method-level ones. A small custom
`TestExecutionListener` sidesteps that: track method-level `TestIdentifier`s only (skip containers),
record `{uniqueId, className, methodName, methodParameterTypes, status, failureMessage}` for each.

### 2. Map JUnit's test identity to Lathe's own `RunTarget` id format

This is the one genuinely risky part (see Risks below). Lathe's id
(`RunnableScanner.methodTarget`): `"%s#%s(%s)".formatted(enclosingBinaryName, methodName,
erasedParams)`, where `erasedParams` comes from javac's `Types.erasure(param.asType()).toString()`
joined by `,` with no space. JUnit's `MethodSource` (from `TestIdentifier.getSource()`) carries
`getClassName()` / `getMethodName()` / `getMethodParameterTypes()` — a comma-and-space-joined string
built from reflection, not javac's erasure. These *should* align for ordinary cases (both ultimately
derive from `Class.getName()`-style binary names for nested classes), but need real verification
across signature shapes before being trusted: varargs, generics, arrays, primitives vs. boxed types.
Normalize both sides to the same format (strip spaces, confirm binary-name `$`-nesting matches) in
one small pure function, unit-tested directly against `RunnableScanner`'s own id-building logic
rather than assumed to match.

### 3. Get the structured data out of the child JVM

Today the only channel back to the parent server process is `ReplaySession`'s line-by-line
stdout/stderr drain into a raw `List<String>` (`lathe-server/.../run/ReplaySession.java`). Two
options:

* **Structured stdout block** — the runner prints one recognizable, delimited JSON block (e.g.
  between marker lines) after JUnit Platform finishes; the parent's existing line-reading loop
  recognizes and extracts it, leaving ordinary test `System.out` output untouched in the surrounding
  transcript.
* **Sink file** — the runner writes a JSON report to a fresh temp file (path passed via a system
  property, matching `docs/planned/lathe-run-test-debug.md` §4.3's already-planned NDJSON sink
  convention) and the parent reads it after the process exits.

Leaning toward the sink-file approach since it doesn't require parsing structured data out of a
stream that also carries arbitrary test `println` output, and it reuses the same delivery mechanism
already planned for streaming (§4.3 of the run/test/debug design) rather than introducing a second,
different convention.

### 4. Thread it through `ReplayOutcome`

Extend `ReplayOutcome` (`lathe-server/.../run/ReplayOutcome.java`) with a new field, e.g.
`List<TestResult> testResults` (empty when unavailable — an older runner jar, a crash before any
test ran, or a run that isn't test-shaped at all), alongside the existing `output`/`exitCode`. No
existing field changes shape, so this is additive to anything already reading `ReplayOutcome`.

### 5. Consume real results in `lathe.neotest.lua`

`M.results()` currently does:

```lua
local results = { [ctx.position_id] = result }
local subtree = tree and tree:get_key(ctx.position_id)
if subtree then
  for _, node in subtree:iter_nodes() do
    results[node:data().id] = results[node:data().id] or result
  end
end
```

With real per-test data available on `ctx.outcome.testResults`, walk that list first, mapping each
entry to its neotest position id and setting its *real* status — then run the existing subtree
fan-out only as a fallback for any descendant id the structured list didn't cover (an id-mapping
miss, a test that didn't map cleanly, or the outcome predating this feature). This preserves the
existing safety net instead of replacing it outright.

## Risks

* **Id-format mismatch (§2) is the main risk.** A silent mismatch doesn't error — it just
  misattributes a result to the wrong neotest position, or fails to match at all (falling back to
  the aggregate fan-out, which is at least not wrong, just imprecise). Needs a unit test matrix
  covering: plain methods, overloaded methods, generic parameter types, varargs, arrays,
  `@ParameterizedTest` (JUnit gives parameterized invocations distinct dynamic ids per invocation,
  not one id per method — needs a decision: roll invocations up to the method id, or don't attempt
  per-invocation granularity yet).
* **`@ParameterizedTest`/`@TestFactory` granularity** — JUnit Platform's `TestIdentifier` tree has
  dynamic/container nodes for these that don't map 1:1 to a single Lathe `RunTarget` id. Simplest
  first cut: only report results for identifiers whose source is a plain `MethodSource` matching a
  known static test method; let dynamic children fall through to the aggregate fallback.

## Reviewable deliverables

1. **Runner-side capture** — custom `TestExecutionListener` in `lathe-test-runner`, unit-tested
   against a small JUnit fixture (mixed pass/fail/skipped). No server or client changes yet; verify
   the listener's captured data independently first.
2. **Id-mapping function** — pure, unit-tested translation from JUnit `TestIdentifier`/`MethodSource`
   to Lathe's `RunTarget` id format, tested directly against fixtures shared with (or copied from)
   `RunnableScannerTest`'s existing method-signature coverage.
3. **Transport + `ReplayOutcome` extension** — sink-file write (runner) and read (server), new
   `TestResult`/`testResults` field, `ReplayOutcomeTest` coverage for the new field being empty on
   older/incompatible outcomes.
4. **`lathe.neotest.lua` consumption** — `M.results()` reads `ctx.outcome.testResults` first, falls
   back to the existing aggregate fan-out for anything uncovered; spec coverage mirroring the
   existing "stuck running" reproduction in `neotest_spec.lua`, but asserting *distinct* statuses
   for sibling methods instead of one shared one.

## As-built decisions (updates to the plan above)

Recording where the implementation settled, so this doc reflects the code rather than the
pre-implementation plan:

* **Transport: sink file (§3, Open Question 1 resolved).** The runner writes NDJSON — one
  `TestRecord` per line, appended and flushed as each method finishes — to a temp file whose path
  the server passes via `-Dlathe.results.sink`. The server reads it after the process exits and the
  stdout drain completes, then deletes it. Chosen over the stdout-block option so structured data
  never has to be parsed back out of arbitrary test `println` output.

* **Id-mapping (§2) deferred out of Java, into the adapter.** The runner ships JUnit's raw
  `MethodSource` identity (`className` / `methodName` / `methodParameterTypes`) verbatim; it does
  **not** translate to Lathe's `RunTarget` id format. That mapping is `lathe.neotest.lua`'s job when
  it consumes `testResults` (deliverable 4). This keeps the runner analysis-free and avoids a
  javac-shaped concern in a module that has no javac.

* **`failureLine` added** (not in the original field list) — the topmost stack frame declared by the
  test's own class, or `-1`. It exists to feed the `result.errors`/`vim.diagnostic` work in the
  sibling design `lathe-test-diagnostics-and-refresh.md` (§4 there), which this design unblocks for
  class/package runs.

* **`@ParameterizedTest`/`@RepeatedTest` (Risks, Open Question 2 resolved) — method-level roll-up,
  no per-invocation granularity.** The runner skips container identifiers (`TestIdentifier.isContainer()`),
  so the parameterized *template* container produces no record; only the per-invocation results are
  written. All invocations of one method carry identical `MethodSource` identity, so they reconstruct
  to the method's single position id — Lathe discovers exactly one position per method from
  compile-time analysis and can't know the runtime invocation count, so there is nowhere to hang
  per-invocation nodes. The adapter therefore collapses them worst-status-wins: a method with any
  failing invocation shows failed, independent of invocation order. Per-invocation UI granularity is
  a separate, larger feature (server-synthesized dynamic positions) and remains out of scope.

* **The runner depends on JUnit Platform only — deliberately no `lathe-core`.** The runner rides the
  user's own test classpath inside the replay fork, so any dependency it drags in (gson and
  validcheck, via `lathe-core`) would pollute that classpath and risk shadowing the user's own
  versions. Its `resolveRunnerJar` resolution is non-transitive, so `lathe-core` is not present in
  the fork regardless. Consequences:
  * `ResultsListener`/`TestRecord` hand-roll NDJSON (no gson) and validate with plain JDK checks
    (no ValidCheck).
  * The two cross-process wire contracts lathe-core would otherwise own are **duplicated by value**
    in the runner and pinned by a drift-guard unit test (`inlinedWireLiterals_…`, which reads
    lathe-core at *test* scope only): the results-sink property name (`LatheTestRunner.RESULTS_SINK`
    ↔ `LatheFlags.RESULTS_SINK`) and the four selector flags (`TestSelectorParser.SELECT_*` ↔
    `TestSelectionKind.runnerFlag()`). This is a deliberate exception to the "shared keys live only
    in `LatheFlags`/`LatheLayout`" rule, forced by classpath isolation and the same in kind as the
    already-duplicated `TestRecord`/`TestResult` field names.

## Open Questions

1. Sink-file vs. structured-stdout-block (§3) — leaning sink-file, not yet decided against the
   streaming design's own eventual NDJSON sink shape.
2. `@ParameterizedTest` granularity (Risks) — punt to aggregate fallback for v1, or worth the extra
   mapping work up front?
3. Does a failing id-mapping lookup log anything (for diagnosing a mismatch later), or fail
   silently into the fallback with no trace at all?
