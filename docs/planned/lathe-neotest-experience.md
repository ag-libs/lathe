# Lathe — Neotest Experience (IntelliJ-Parity Acceptance Spec)

This is the single forward-looking design of record for the Neovim neotest experience.
It defines the **target behavior** — what running and following tests should feel like — as
reviewable acceptance criteria, not as Lua implementation detail.
It supersedes the forward-looking stances of the shipped records it consolidates (see
[Supersession](#supersession)).

Its purpose is to fix a specific difficulty: the neotest adapter is Lua, the maintainer's guidance
is strongest at the behavioral and Java level, and there is no automated signal over the adapter
today.
So this document lets guidance be given as *behavior a user can judge* rather than as Lua code,
and it becomes the contract the end-to-end harness (Phase 1) tests against.

---

## 1. Goal

Match the IntelliJ run-tests experience for neotest users:

- discovery is reliable and current;
- runs stream their output live and the view follows along;
- exactly the failing tests are marked, and a failure jumps to its line;
- there is one obvious output surface, not a confusing mix.

Non-negotiable framing carried over from
[lathe-run-test-debug.md](lathe-run-test-debug.md): the client never constructs Java command
lines, never runs Maven, and stays as thin as possible.
Decision logic belongs in the server (javac-backed, unit-testable) so the Lua surface the
maintainer cannot easily review stays minimal — see [Phase 2](#phase-2--thin-adapter-split).

---

## 2. How to read this spec

Each behavior has a stable id (used by harness tests and any gaps filed against it), a one-line
statement of the target, current state, and acceptance criteria.

Status legend:

- ✅ **works** — meets the criteria today.
- 🟡 **partial** — works in the common case but fails an edge or a stated criterion.
- ❌ **broken** — a listed user-visible issue.
- ⬜ **not built** — no implementation yet.

Current state is as of the initial writing and is authoritative only until the harness (Phase 1)
can assert it mechanically; where the two disagree, the harness wins and this table is corrected.

---

## 3. Acceptance criteria

### 3.1 Discovery

**D1 — Cold-open discovery.** ✅
Opening a test file as the *first* buffer in a session still discovers its runnable tests.
*As built:* the server publishes a `$/progress` work-done report (title "Lathe: indexing workspace")
around initial workspace load and reload; the adapter suspends `discover_positions` on that
readiness signal (racing a ~30s timeout that then tries anyway) and re-triggers discovery for open
buffers when the signal ends. Discovery invoked before the client has attached now resolves to the
real tree once the server is ready instead of caching "no tests".
*Criteria:* with a cold server, opening a test file and waiting for attach yields the full position
tree with no manual re-trigger; discovery invoked before the server is ready resolves once it is,
rather than caching "no tests". *Validated:* e2e driver's eager-discover-before-attach check and a
real-project cold open (5 positions resolved).

**D2 — Discovery stays current.** 🟡
Adding, renaming, or removing a test updates the runnable set.
*Criteria:* after an edit that adds a `@Test` method and a save, the new method appears as a
position without reopening the file.

**D3 — All runnable kinds.** ✅
Everything IntelliJ would offer a gutter run icon for is discoverable: `@Test`,
`@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, JUnit 4 and TestNG `@Test`, nested test
classes, and `main`.
*Current:* server recognizes these (run-test-debug §7); adapter maps method/class/package.
`main` replay is not yet wired (POSITION_TYPE has no entry for kind 0).
*Criteria:* a fixture covering each kind produces the expected position for each; `main` is tracked
as an explicit deferral, not a silent miss.

### 3.2 Running

**R1 — Run at every level.** 🟡
Run nearest test (method), run class, run file, run package/directory — each launches and reports.
*Current:* method / class / directory work; file-run launches but see R2.
*Criteria:* each level launches exactly one logical run and reports a status for the invoked
position.

**R2 — File run shows output.** ✅
Running a whole file yields visible, consolidated output for the file.
*Shipped (Deliverable 5):* a file run is one consolidated launch — `run.test` takes a list of
selections, so all the file's classes run in one JVM and the single result (with output) attaches
to the file position.
*Criteria:* running a file produces output reachable from the file position, covering every class
that ran.

**R3 — Exact per-test status.** ✅
Only the tests that failed are marked failed; passed and skipped are marked accordingly; one
failing method in a 20-method class does not mark all 20.
*Current:* shipped via structured per-test results.
*Criteria:* mixed pass/fail/skip class marks each method correctly, including `@ParameterizedTest`
rolled up worst-status-wins.

**R4 — Re-run and re-run-failed.** 🟡
The IntelliJ staple: repeat the last run, and repeat only the failures.
*Criteria:* after a run with failures, re-run-failed launches exactly the failed set and nothing
else.

**R5 — Cancel.** ❌
A running test can be stopped.
*Current:* the server can destroy the replay process, but the run executes inside a synchronous
`build_spec` call, so neotest's stop may not reach it.
*Criteria:* stopping a run terminates the replay JVM and clears the running state promptly.

**R6 — Live per-test status.** ✅
Tests mark pass/fail one by one *as the run proceeds*, not all at once when it finishes — the
IntelliJ progress feel.
*Shipped (Deliverable 4):* the server tails the results sink and emits `lathe/testEvent` per method
as it finishes (4a); the adapter's async run model fires the run without blocking and yields each
result to neotest via `spec.stream`, so positions mark live (4b). The final authoritative
`testResults` still reconcile in `results()`.
*Criteria:* during a multi-method run, each method's gutter/summary status updates as that method
completes.

### 3.3 Output and following

**O1 — Live streaming.** ✅
Output appears incrementally while the test runs, not only after it exits.
*Shipped:* the server splits and streams each drained line via `lathe/testOutput` (Deliverables 1,
2a); the adapter appends it to the live docked buffer (2b).
*Criteria:* lines are visible in the output surface while the JVM is still running.

**O2 — Follow.** ✅
The output view auto-scrolls to the tail as new output arrives (IntelliJ console follow), until the
user scrolls up.
*Shipped:* `live_append` moves the cursor to the tail while the window is open but unfocused (2b).
*Criteria:* a long-running test keeps the newest line in view without manual scrolling.

**O3 — stdout vs stderr distinguished.** ✅
The two streams are visually separable (color).
*Shipped:* `ReplayLauncher` no longer merges the streams; each `TranscriptLine` carries its stream
tag (Deliverable 1) and the adapter highlights stderr lines (2b).
*Criteria:* stderr lines render in a distinct highlight from stdout lines.

**O4 — One docked surface.** ✅
There is a single, obvious output window — a docked terminal, not a floating one.
*Shipped:* neotest's float is off the path; `open_output` toggles the one docked live buffer (2b).
*Criteria:* the docked surface is the only output window the run flow ever opens.

**O5 — Fresh across runs.** ✅
A docked output window left open shows the *latest* run, not the first.
*Shipped:* the live buffer is reset at the start of each run and appended to as it streams, so it is
current by construction (2b).
*Criteria:* running a different test updates the docked window's content without the user closing
and reopening it.

**O6 — Navigable stack frames.** ✅
Stack frames in output jump to source (`<CR>`/`gF`).
*Current:* shipped; terminal-wrap-aware; jumps to the last-focused Java window.
*Criteria:* a frame for a workspace class underlines the `File.java:line` span and jumps correctly.

**O7 — Fold output regions.** ⬜
Output can be collapsed (e.g. per-test sections, or a run command with a fold option).
*In scope* ([Decision 3](#4-decisions)).
*Criteria:* the docked output supports collapsing output into foldable regions; folding must key off
structure the stream already carries (per-run / per-test boundaries, stdout-vs-stderr) rather than
re-parsing transcript text.

### 3.4 Failure navigation

**F1 — Inline failure diagnostics.** ⬜
Failures show as `vim.diagnostic` on the failing line, always fresh, with no output window needed —
the jdtls / standard-adapter pattern (option D from the folded-in diagnostics doc).
*Current:* `testResults` carry `failureLine`/`failureMessage`; the adapter never populates
`result.errors`, which neotest's built-in diagnostic consumer reads.
*Criteria:* a failing test sets a diagnostic at its failure line, replaced (not appended) on the
next run; unblocked for class/package runs now that per-test results ship.

**F2 — Jump to failure.** 🟡
From a failure, reach the exact assertion line.
*Criteria:* satisfied by F1 (diagnostic `]d`/quickfix) and/or O6; at least one path lands on the
failing line.

**F3 — Failure message visible.** ✅
The failure message is surfaced (neotest `short`).
*Criteria:* a failed position shows its assertion message without opening full output.

### 3.5 Run configuration (later phase)

**C1 — Named run configs.** ⬜
`.lathe-run.json` configs via a picker (run-test-debug §8), server-validated, client passes the
selected object only.
*Criteria:* deferred to Phase 3; schema already drafted in run-test-debug §8.

**C2 — Overlay args/env/cwd.** ⬜
Program args, JVM args, env, cwd, debug flag overlay the captured launch.
*Criteria:* deferred with C1.

**C3 — Debug attach.** ⬜
Debug a test (JDWP attach), the other half of the IntelliJ verb set.
*Deferred, but the design must not preclude it* ([Decision 4](#4-decisions)): the run command shape
and the streaming/token model chosen for O1 must leave room for a later "launch suspended + attach
JDWP" path without a redesign. Tracked by run-test-debug §12.9.

---

## 4. Decisions

Resolved. Recorded here so Phase 2 starts from a fixed target.

1. **Streaming architecture — A (server streams via notifications).**
   The server keeps owning the replay JVM. As lines drain, it pushes each to the client over a
   custom `lathe/testOutput` notification carrying a **run token**, the **line text**, and a
   **stream tag** (`stdout` | `stderr`); the adapter feeds those into neotest's live output. This
   resurrects the notification shape dropped in commit `51480c3` (which was removed only because it
   had no server counterpart) — now with one. Chosen over B (neotest owning the process) to keep the
   replay JVM, classpath, and sink server-side, consistent with run-test-debug's "client never
   constructs command lines" invariant.
   *Implied work:* (a) `ReplayLauncher` must stop `redirectErrorStream(true)` and drain stdout and
   stderr separately so each line can be tagged (this is also what unblocks O3); (b) `lathe.run.test`
   must associate a run token up front and deliver the final `ReplayOutcome` as a completion for that
   token, rather than only returning the whole blob at exit; (c) the client bridges notification
   lines into neotest's own output surface (exact mechanism is Phase-2 design, and is the "fiddly"
   part of approach A).

2. **The float — removed (O4).** Neotest's floating output is taken off the path entirely; the
   docked surface is the only output window.

3. **Output folding — in scope (O7).** Delivered as part of this effort, keyed off stream structure
   (run/test boundaries, stdout/stderr), not text re-parsing.

4. **Debug — deferred, design must allow it (C3).** Not built in the first pass, but the run-command
   and streaming/token model from Decision 1 must leave room for a later suspended-launch + JDWP
   attach without redesign.

5. **stdout/stderr split — in scope (O3).** The server stops merging the two streams (Decision 1a),
   so lines are tagged at the source and rendered in distinct highlights.

---

## 5. Plan

Phased so guidance is precise and iteration is safe before any risky Lua changes.

### Phase 0 — this spec
The behavioral contract. Reviewed by the maintainer as behavior, not code.

### Phase 1 — end-to-end headless harness
Extend the `dev/check-nvim.sh` idiom to drive the *real* adapter + neotest + nio against a small
committed fixture Maven project with a prebuilt `.lathe/` (reusing the invoker fixture machinery),
asserting discovery / run / results / output.
Today's `*_spec.lua` tests only exercise pure functions against a mocked client — they never touch
the real LSP round-trip, real neotest Tree, or the async race where the bugs live.
Seed the harness with the ❌/🟡 criteria above as failing tests.
This is the red/green signal that makes later Lua iteration safe and keeps fixed bugs fixed.

### Phase 2 — thin-adapter split
Move decision logic out of Lua into Java: `runnables.list` returns a neotest-ready tree plus the
per-node run plan; relocate the JUnit→Lathe id mapping (currently in Lua, per the structured-results
as-built note) to the server, which alone has javac; and design the streaming surface fixed by
[Decision 1](#4-decisions) — the `lathe/testOutput` notification (run token + line + stream tag),
the separate stdout/stderr drain in `ReplayLauncher`, and the token-correlated completion carrying
the final `ReplayOutcome` — with the command shape kept open for a later JDWP attach (Decision 4).
This is a public-API change → **STOP-and-design**: a structured summary and explicit approval before
any code. The design is settled in
[lathe-neotest-streaming.md](lathe-neotest-streaming.md).

### Phase 3 — implement behaviors against the harness
Each a small series, gated green by Phase 1:
D1 discovery-race → O1/O2 streaming + follow → O3 stdout/stderr color → O4/O5 single fresh surface →
R2 file output → F1 inline diagnostics → (later) C1–C3 run config and debug.
Use `neotest-java` / `nvim-jdtls` as a behavior oracle where a target is ambiguous.

---

## 6. Non-goals

- Per-`@ParameterizedTest`-invocation UI nodes (server-synthesized dynamic positions) — roll-up
  stays (structured-results as-built).
- Coverage reporting — `mvn verify` owns that (run-test-debug §9).
- A Lathe-only output experience that duplicates neotest's surfaces instead of building on them
  (output-streaming §5) — still holds; streaming must reuse neotest's UI, not fork it.
- Non-forking / non-JUnit-Platform run models — the capture-replay limitations in run-test-debug §9
  apply unchanged.

---

## Supersession

This document is the forward-looking authority for the neotest experience. It consolidates and
replaces the planning stance of:

- **`planned/lathe-test-diagnostics-and-refresh.md`** — folded in (its option D is F1 here; its
  docked-split staleness is O5; its options-without-a-decision are resolved by Decision 1/2).
  **Deleted** on adoption of this spec.

It supersedes only the *forward-looking* parts of these shipped records, which remain accurate as
history of what is built:

- **`done/lathe-test-output-streaming.md`** — its "no live streaming / no custom notification"
  non-goals are reversed here (O1–O3); its shipped stack-nav (O6) and docked split (O4/O5) stand.
- **`done/lathe-structured-test-results.md`** — its shipped per-test results (R3) stand; its
  id-mapping-in-Lua as-built decision is revisited by Phase 2.

The server-side design of record — capture/replay, `.lathe-run.json` schema (§8), and the Surefire
3.5.5 regression note (§14) — stays in **`planned/lathe-run-test-debug.md`**, referenced, not
replaced.
