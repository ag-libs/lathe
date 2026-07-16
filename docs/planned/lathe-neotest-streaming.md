# Lathe — Neotest Streaming and Thin-Adapter (Phase 2 Design)

Implementation design of record for Phase 2 of the neotest experience.
The behavioral targets and decisions live in
[lathe-neotest-experience.md](lathe-neotest-experience.md); this document is *how* they are built.
It delivers O1 (streaming), O2 (follow), O3 (stdout/stderr color), O4/O5 (single fresh surface),
R2 (file output), and the id-mapping relocation.

Streaming is **approach A** (experience-spec Decision 1): the server keeps owning the replay JVM and
pushes output lines to the client; neotest never runs the process.

---

## 1. Grounding facts

- `RunTarget{id, parentId, kind, label, moduleRel, uri, range}`; `TestSelection{kind, value}`; kinds
  are `CLASS / METHOD / PACKAGE / MODULE` — there is no file/multi-class selector today.
- `WorkspaceSession` already holds a `LanguageClient` and reaches the run path
  (`runTestFuture` → `launchReplay` → `ReplayLauncher.launch` → `ReplaySession`).
- The server builds a stock `LanguageClient` proxy in `LatheServer`
  (`LSPLauncher.createServerLauncher`), so a custom notification needs a small remote-interface
  extension (§3.1).
- The dropped prototype (`51480c3`) already wired `client.handlers["lathe/testOutput"]` on the nvim
  LSP client with `{line}` — that mechanism is resurrected here, now with a server counterpart plus a
  token and a stream tag.

## 2. Resolved decisions

1. **Transcript shape:** `ReplayOutcome.output` becomes one ordered list of tagged lines
   (`{stream, text}`), not two lists — the transcript stays a single readable stream with stderr
   distinguishable.
2. **Multi-select API:** `lathe.run.test` accepts `selections: [{kind, value}, …]`; a file run passes
   several `CLASS` selections in one launch.
3. **Run completion:** `lathe.run.test` still resolves with the full `ReplayOutcome` at process exit;
   streaming notifications are additive. `build_spec`/`results()`'s current await is untouched — the
   smallest, proven diff.
   **↳ Revised during Deliverable 4 — see [§8 As-built](#8-as-built-reconciliation).** This
   "untouched await" assumption did not survive contact with neotest's runner: it blocks on
   `build_spec` and only polls `spec.stream` afterwards, so a live gutter update is impossible while
   the run happens inside `build_spec`. The run model was restructured to be non-blocking instead.

**Caveat carried by decision 1 (documented, not hidden):** splitting stdout and stderr into two OS
pipes means cross-stream *interleaving* is best-effort by arrival order — exact within each stream,
approximate between them. Perfect interleave and per-line stream tags are mutually exclusive for
arbitrary child `println` output; tagging (for color) is the chosen trade.

## 3. Design A — Streaming

### 3.1 Server

- **Run token.** `build_spec` mints a unique `runToken` per run and passes it in the `run.test`
  args. It is the correlation key so lines from concurrent runs (a file's classes, or several
  gutter-runs at once) route to the right client buffer.
- **Split the streams.** `ReplayLauncher` drops `redirectErrorStream(true)`; `ReplaySession` drains
  `getInputStream()` and `getErrorStream()` on two threads, recording each line as a tagged
  `TranscriptLine{Stream stream, String text}` (`Stream` = `STDOUT | STDERR`) into one
  arrival-ordered synchronized list.
- **Emit.** Each drained line is handed to a `Consumer<TranscriptLine>` supplied by
  `WorkspaceSession`; that consumer sends a `lathe/testOutput` notification `{token, stream, text}`.
  Passing a callback (not the LSP client) keeps the `run` package free of lsp4j-client specifics.
- **Custom notification.** Add `LatheLanguageClient extends LanguageClient` with
  `@JsonNotification("lathe/testOutput")`; build the launcher in `LatheServer` with that remote
  interface and cast in `connect`. This is the idiomatic lsp4j remote-proxy pattern, not an
  implementation interface.
- `ReplayOutcome.output` (now `List<TranscriptLine>`) is still accumulated and returned at exit, so
  the existing output-file path and stack-navigation (O6) keep working.

### 3.2 Client

- `build_spec` generates the token, registers the `lathe/testOutput` handler once per client, and
  routes lines into a live docked buffer keyed by token — appended as they arrive, so the surface is
  inherently fresh (O5) and follows the tail (O2).
- stderr lines render in a distinct highlight (O3).
- The floating output surface is removed; the docked buffer is the only one (O4).
- On `run.test` completion, `results()` finalizes exactly as today.

### 3.3 Debug-openness (experience-spec Decision 4)

`run.test` args are a JSON object; a later optional `debug`/`suspend` field does not disturb the
token model or the streaming path. Noted, not built.

### 3.4 A second channel — live per-test results

Verified against the runner: a passing replay run emits *no* console output — `LatheTestRunner`
writes to stdout only for failing tests (display name + stack trace), while every test's pass/fail
is written to the NDJSON results sink, flushed as each method finishes. So the console stream
(§3.1–3.3) carries test `println`s and failure traces but is empty on a clean green run, and the
IntelliJ-defining "tests light up one by one as the run proceeds" comes from the *sink events*, not
stdout.

The server therefore streams a second, result channel alongside the console one, over the same run
token: `ReplaySession` tails the sink as records are appended and emits each as a `lathe/testEvent`
notification `{token, <TestResult>}` (carrying the server-side `positionId` from Design B, so the
client knows which position to mark). The adapter marks that position live, mid-run, instead of
stamping every result at once when `run.test` returns. The final `ReplayOutcome.testResults` read at
exit stays the authoritative reconciliation, so a missed or late-flushed event still resolves. This
channel fires on every test regardless of pass/fail, so it is also the deterministically testable
half of streaming — unlike the console channel, which is empty on green runs.

## 4. Design B — Relocate id-mapping (thin-adapter)

Add `positionId` to `TestResult`, computed server-side when the NDJSON results are read, by
normalizing JUnit's `MethodSource` identity to `RunnableScanner`'s `RunTarget` id format — moving
today's fragile Lua `test_result_position_id` into a unit-tested Java helper, tested against
`RunnableScanner`'s own id-building. Same algorithm, but where it is testable and guidable, and
upgradeable to true erasure-matching later. The adapter consumes `positionId` directly and drops the
Lua reconstruction.

## 5. Design C — File-run consolidation (R2)

`ReplayTransform.forTest` and `run.test` take a `List<TestSelection>` instead of one; the runner's
`TestSelectorParser` already loops selectors, so multiple `--select-class` flags launch in one JVM.
`build_spec`'s file branch stops fanning out one spec per class and emits a single spec whose
`selections` are all classes in the file — one launch, one transcript reachable from the file
position (R2). `dir → package` stays in Lua (it works and is not the pain point).

## 6. Reviewable deliverables

Each is its own commit series and is gated green by extending the Phase-1 harness
(`dev/neotest-e2e.sh`) with a spec for the behavior it lands.

1. **stdout/stderr split** *(done — `d6037c3`)* — `ReplayLauncher` stops merging; `ReplaySession`
   dual-drains into `List<TranscriptLine>`; `ReplayOutcome.output` reshaped; server tests and the Lua
   `results()` adapt. Server-only, unit-testable; prerequisite for tagged streaming and the O3 data.
2. **Console streaming notification + live buffer** *(done — `a427c60` server, `ee4fb1e` client)* —
   `runToken` arg, `LatheLanguageClient`, `lathe/testOutput` emission, and the adapter's handler +
   live docked buffer + stderr color + float removal. Delivers O1/O2/O3/O4/O5.
3. **id-mapping → server** *(done — `2efde09`)* — `positionId` on `TestResult`; adapter
   simplification (Design B). Prerequisite for deliverable 4.
4. **Live per-test result events** *(done — `feab926` server, `0fefc76` client)* — `ReplaySession`
   tails the sink and emits `lathe/testEvent` `{token, <TestResult>}` as each method finishes; the
   adapter marks that position live, mid-run (§3.4, §8). Delivers the IntelliJ progress feel —
   experience-spec criterion R6.
5. **file-run consolidation** *(done — `e361661`)* — `List<TestSelection>` through
   `run.test`/`ReplayTransform`; single file-run launch (Design C, fixes R2).

## 7. Risks and open items

- **Interleave order** (§2 caveat) — accept best-effort arrival order; document at the code site.
- **`ReplayOutcome.output` shape ripple** — every reader changes shape (server replay tests, the
  stack-frame scanner's line source, the adapter's transcript concat). Deliverable 1 must sweep them
  together.
- **`positionId` normalization fidelity** — the known signature-shape edge cases (generics, arrays,
  varargs, `@ParameterizedTest`) carry over from the structured-results risks; the Java helper gets
  the same unit-test matrix, and unmapped results still fall back to the aggregate as today.
- **Sink tailing** (deliverable 4) — watching the NDJSON sink for appends mid-run must tolerate
  partial/last-flushed lines and reconcile against the authoritative whole-file read at exit, so a
  dropped or half-written event never leaves a position stuck or mis-marked.

## 8. As-built reconciliation

Phase 2 shipped (Deliverables 1–5, all green against the harness). Where the build diverged from the
plan above:

**The run model became asynchronous (revises Decision 3).** Reading neotest's runner
(`client/runner.lua`, `client/strategies/init.lua`) showed it calls `build_spec` and *blocks* on it,
then runs the spec's process and only *afterwards* polls `spec.stream` — and `ProcessTracker:run`
returns as soon as the no-op `"true"` process exits, firing `stream_processor` in a detached
`nio.run` it never awaits. So the original "run inside `build_spec`, keep the blocking return, leave
`results()` untouched" plan left no window for live gutter marks. As built:

- `build_spec` no longer runs the test. `run_spec(position_id, module_rel, selections, client)` mints
  a token, fires `run.test` on `nio.run` (not awaited), and returns immediately with
  `command = {"true"}`, a `result_future`, and a `stream` function.
- The `lathe/testEvent` handler pushes each result onto a per-token `nio.control.queue`; the spec's
  `stream` iterator drains it and yields `{[positionId] = result}` to neotest, which marks each
  position the moment its method finishes. A `STREAM_DONE` sentinel (a queue cannot carry `nil`)
  closes the stream once the run completes.
- `results()` now *awaits* `result_future` before reading the outcome (neotest calls it as soon as
  the no-op process exits, i.e. before the real run finishes), then does the same authoritative
  fan-out and output-file write. It runs in neotest's async context, so the wait yields.

This brings the adapter into line with neotest's intended model (`build_spec` describes, neotest
runs); the old inline-blocking call was the real deviation.

**One token per run, not per class.** With file runs consolidated (Deliverable 5) each user run is a
single `run_spec` with one token, so the "concurrent file classes each need a token" concern from
§3.1 no longer applies — the classes run in one JVM under one token.

**Reference check (why this shape is worth it).** Confirmed against the sources: `neotest-java`
(rcasia) collects results only after the process exits by parsing `TEST-*.xml` reports — no `stream`,
no live status; `nvim-jdtls` streams JUnit socket events into its *own* UI (dap-repl + `vim.diagnostic`
+ quickfix), not a neotest surface. No Java neotest adapter does live per-test streaming, so this
async-`stream` model is novel and ahead of both references.

**Held to plan:** the transcript split and tagging (§2.1, §3.1), the `lathe/testOutput` console
channel (§3.2), the sink-tailed `lathe/testEvent` result channel (§3.4), server-side `positionId`
(Design B), and the multi-selection file consolidation (Design C) all shipped as described. The
best-effort tailer + authoritative exit read (§7) held.

**Shipped since (tracked in the experience spec):** F1 (inline `vim.diagnostic` from `failureLine`,
mapped to `neotest.Result.errors`), R5 (cancel/stop), and O8 (the replay command shown as the first
output line).
**Deferred:** O7 (output folding) and R4 re-run-failed.
