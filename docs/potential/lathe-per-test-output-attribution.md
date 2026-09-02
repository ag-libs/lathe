# Per-Test Output Attribution ("Output Split") — Spike Plan

Status: **potential — not planned.** Parked idea; not scheduled for implementation.

This is a de-risking spike, not a feature commitment.
Its purpose is to prove — cheaply, with the existing neotest path still running — that Lathe can
attribute test `System.out`/`System.err` output to the individual test that produced it, at the
source, and to characterize the boundary where attribution stops being trustworthy.
If the spike fails its go/no-go gate, nothing downstream (the from-scratch panel, the explorer)
is worth building.

## Why this matters

Splitting output per test is the one capability the entire Neovim Java ecosystem lacks:

- `neotest-java` runs via the JUnit Platform Console Standalone JAR, so all `System.out` lands in
  one combined stream; it maps *results* to methods via XML reports, never output.
- `vscode-java-test` attributes output by regex-parsing the runner console — described by its own
  maintainers as fragile, with long-standing "output not showing per test" bugs.

The only correct approach is to bracket output **inside the test JVM**, at the JUnit listener level,
the way IntelliJ does. Lathe already runs tests through its own in-JVM runner
(`lathe-test-runner`), so it is uniquely positioned to do this. This is a differentiator, not just
a fix.

## Current pipeline (verified)

Results and output travel on two different transports, which is the root reason output cannot be
split today:

- **Results** (`lathe/testEvent`): `lathe-test-runner`'s `ResultsListener`
  (`implements TestExecutionListener`) writes NDJSON records to a sink file named by the
  `lathe.results.sink` system property. `ResultsListener` implements only `executionFinished` /
  `executionSkipped` — it does **not** track the currently-running test. The server
  (`LaunchSession.tailResults`) polls the sink every 25ms, parses each line into `TestResult`, and
  `WorkspaceSession.resultConsumer` forwards it as `lathe/testEvent`.
- **Output** (`lathe/testOutput`): the replay JVM's raw process stdout/stderr pipes are drained
  line-by-line by `LaunchSession.drain`, wrapped in `TranscriptLine(stream, text)`, and forwarded
  by `WorkspaceSession.streamConsumer` as `lathe/testOutput`. **No notion of which test is active
  exists at the point a line is read.**

Key identifier: `positionId` = `"<binaryClassName>#<methodName>(<erasedParams>)"`, computed
server-side by `TestId.positionId(...)`. It is the shared id space we will tag output with.

## Design decision

**Attribute at the source, in-JVM, and unify output into the results sink.**

Rejected alternative: keep output on the process pipes and correlate to tests server-side by
timing/boundaries. This is exactly the fragile approach `vscode-java-test` regrets — the 25ms sink
poll and the pipe drain race, and timing correlation misattributes under any scheduling jitter.
We will not do this.

Chosen approach:

1. In `lathe-test-runner`, track the **active-identifier stack** via `executionStarted` /
   `executionFinished` on `TestIdentifier`. The innermost active identifier determines scope:
   - innermost is a test (method) → `scope=method`, `positionId` = that method
   - innermost is a container (class) → `scope=class`, `positionId` = the class id
   - nothing active → `scope=session`
2. Redirect `System.out` / `System.err` (via `System.setOut` / `setErr`) to a line-buffering
   `PrintStream` that, per completed line, writes a **tagged output record** to the same NDJSON sink
   the results already use. This unifies both streams onto one ordered channel, so results and
   output interleave in true program order and the timing race disappears.
3. The server reads output records from the sink (a new NDJSON `type` discriminator distinguishes
   `result` from `output`) and forwards them as `lathe/testOutput` carrying the new `testId` +
   `scope` fields.
4. The raw process stdout/stderr pipes are **still drained** (so the child never blocks on a full
   pipe buffer, and so JVM-level noise emitted outside our redirect — uncaught-exception traces,
   pre/post-test-plan launcher lines — is still captured), but that residual output is tagged
   `scope=session`.

## Schema changes (the public-API surface)

- **NDJSON sink**: add a `type` field to every record: `"result"` (existing shape) or `"output"`.
  Output records carry `{ type:"output", stream:"stdout"|"stderr", text, positionId, scope }`.
- **`TranscriptLine`**: add `testId` (String, may be empty for session scope) and
  `scope` (enum `METHOD | CLASS | SESSION`).
- **`TestOutputParams`** / `lathe/testOutput`: unchanged shape at the params level (still
  `{ token, line }`), but `line` now carries the two new fields. No backward-compat concerns
  (no external adopters).

## The parallel-execution risk (the crux the spike must settle)

Sequential execution (JUnit's default) is the primary target and should attribute exactly.

Parallel execution shares one `System.out`, so correct attribution requires mapping the **writing
thread** to its active test. That only works if JUnit invokes `executionStarted` for a test on the
same thread that runs the test body and its `System.out` calls. **This is not guaranteed by the
JUnit Platform API and must be verified empirically in the spike.**

Policy: under parallel execution, if the thread-identity assumption holds we use a `ThreadLocal`
active-test map; if it does not, we **fall back to `scope=session` (unattributed) rather than
misattribute.** Misattributed output is worse than merged output. A `log()`-equivalent note must
make the fallback visible, never silent.

## Spike steps

Each step ends with an explicit, assertable check. Steps 1–3 are the go/no-go core; step 4 is the
client proof.

1. **Active-test tracking (runner).**
   Extend `ResultsListener` (or add a sibling listener) to maintain the active-identifier stack and
   expose the current `{ positionId, scope }`.
   *Check:* a unit test drives synthetic `executionStarted`/`Finished` sequences (class→method
   nesting, `@BeforeAll` between, empty between classes) and asserts the derived scope at each point.

2. **Output redirect → tagged sink records (runner).**
   Redirect `System.out`/`err` to a line-buffering `PrintStream` that writes `type:"output"` NDJSON
   records tagged with the current `{ positionId, scope }`.
   *Check:* an in-JVM test prints from a method, from `@BeforeAll`, and before the test plan; assert
   the sink contains output records with the expected scope/positionId for each, in order, with no
   lost lines.

3. **Server ingest + forward (server).**
   Teach `LaunchSession` to parse `type:"output"` records from the sink and route them through the
   existing output consumer with `testId`/`scope` populated; keep draining the process pipes as
   `scope=session`.
   *Check:* an integration test runs a real 2-method class (each method prints a distinct line, plus
   a `@BeforeAll` print) and asserts each `lathe/testOutput` notification carries the correct
   `testId`/`scope`.

4. **Parallel characterization (experiment, not feature).**
   Run the same fixture with JUnit parallel execution enabled; measure whether thread-identity holds
   between `executionStarted` and the test body.
   *Check:* either attribution is correct per method, **or** all concurrent output degrades cleanly
   to `scope=session` with the visibility note — never cross-attributed.

## Go / no-go gate

Proceed to the from-scratch panel/explorer work only if:

- Steps 1–3 pass: sequential per-method attribution is exact, `@BeforeAll`/static output is
  `scope=class`/`session` as designed, ordering is preserved, and no output is lost.
- Step 4 shows parallel output is either correctly attributed or safely degraded to `session` —
  never misattributed.

If sequential attribution cannot be made trustworthy, stop: the whole premise fails here, cheaply.

## Test fixture

A dedicated invoker/integration fixture module with one test class:

- two `@Test` methods, each printing a unique marker to `System.out` and one to `System.err`
- a `@BeforeAll` and a static initializer that each print a marker
- a variant configured for parallel execution

Assertions live in the runner unit tests and one server integration test, following the existing
`lathe-server` compile/replay test patterns (reuse, do not reinvent, the current harness).

## Files in scope (for the approval decision)

- `lathe-test-runner` — `ResultsListener` (+ possibly a small output-redirect helper); NDJSON
  writer schema.
- `lathe-core` — `LatheFlags` if a new flag is needed; NDJSON record shape shared constants.
- `lathe-server/run` — `TranscriptLine`, `TestOutputParams`, `LaunchSession` (sink output ingest +
  pipe drain tagging), `WorkspaceSession` output consumer.
- Test fixtures + unit/integration tests as above.
- **Out of scope for this spike:** the Lua client, the panel/explorer, dropping neotest. Output
  split is proven first; the UI consumes it later.

## Open questions

1. Do we route *all* test output through the sink, or keep a copy on the real stdout for users who
   tail the raw process? (Leaning: sink-only for tagged output; raw pipe carries only untagged
   residual.)
2. Should `scope=class` output attach to the class node only, or also visually roll up under each of
   its methods in the eventual panel? (Deferred to the UI phase.)
3. Line-buffering boundary: how do we handle a test that writes without a trailing newline before the
   next test starts? (Flush active buffer on `executionFinished`.)
