# Lathe — Reference Search Reliability

## Status

Planned for M1 — Internal Preview, the next milestone.

This design replaces the former Reference Search Resource Safety, Request Cancellation and Compilation Admission, and Streaming References designs.
It defines one focused reliability change for workspace-wide Find References: bounded transient javac analysis, visible work-done progress, and optional cooperative cancellation.

The separate [LSP Work-Done Progress](lathe-lsp-progress.md) design continues to cover workspace initialization and reload.
Both features should share protocol-level progress plumbing, but their operation lifecycles remain independent.

## Incident

A June 2026 `textDocument/references` request for `java.lang.String` against Helidon exhausted the language-server heap.
The workspace contained 206 Maven modules, 332 module source configurations, and 4,149 indexed Java files.
The log recorded reference analysis for roughly 1,900 unique files before several module workers failed with `OutOfMemoryError` in javac and zipfs.

The server continued running after the first failure.
Some workers terminated, while another javac failure wrapped `OutOfMemoryError` in `IllegalStateException` and was treated as a recoverable compiler failure.
The process continued compiling and publishing reference results from a corrupted runtime.

## Root Cause

`SourceAnalysisSession.searchReferences()` obtains attributed analysis through the normal `CompileMode.OPEN` path.
`SourceAnalysisSession.compile()` caches every non-`FULL` result as `CachedFileAnalysis`.

That behavior is correct for editor-open documents because completion, hover, definition, semantic tokens, and other interactive features reuse their attributed state.
It is incorrect for closed files read only as reference candidates.

Each cached candidate retains source content and javac-backed state, including syntax trees, symbols, `Trees`, `Elements`, `Types`, and task context.
Closed disk candidates never receive `didClose`, so their entries remain until workspace reload or shutdown.

Peak pressure is amplified by one single-threaded `CompilationWorker` per module source configuration.
Per-worker serialization therefore allows many module workers to execute javac concurrently without a process-wide bound.

The candidate-future list and accumulated `Location` results consume additional temporary memory, but the incident does not identify either as a primary cause.
The initial fix does not redesign dispatch or result aggregation.

## Priorities

In order:

1. Prevent heap exhaustion and eliminate retained closed-file javac state.
2. Show visible, accurate progress for a long reference search.
3. Honor optional LSP cancellation before starting unnecessary javac work.
4. Preserve complete and exact results when a search finishes.
5. Keep the implementation small and reuse existing workers, LSP4J cancellation, and shared progress plumbing.

## Goals

- Never cache analysis created solely for a closed reference candidate.
- Bound active javac work across all module and external-source workers.
- Show candidate completion progress for every long-running reference search when the client supports work-done progress.
- Propagate optional request-ID and work-done-progress cancellation to queued candidate work.
- Release admission after success, failure, or cancellation.
- Terminate the process if direct or wrapped `Error` still occurs.
- Preserve the existing module-worker confinement model and exact javac validation.
- Record enough aggregate measurements to validate memory use and throughput on Helidon.
- Verify that editor shutdown or transport EOF during an active search does not leave a Lathe process running.

## Non-goals

- Increasing or recommending an increased JVM heap.
- Caching reference results or closed-file javac analysis.
- Building a semantic reference index.
- Streaming partial reference results.
- Batching multiple source files into one javac task.
- Adding a scheduler, priority queue, workflow framework, or Lathe-specific cancellation token.
- Interrupting javac while attribution is running.
- Guaranteeing interactive-request priority during a broad search.
- Applying request cancellation to LSP notifications.

## Request Flow

```text
textDocument/references
  -> resolve the target symbol
  -> obtain token-index candidates
  -> begin work-done progress
  -> submit candidates to existing module workers
  -> check cancellation
  -> acquire process-wide javac admission
  -> compile, attribute, and locate exact references
  -> convert matches to immutable Location values
  -> discard closed-file javac state
  -> release admission
  -> update aggregate counters and progress
  -> return the complete Location list
  -> end progress
```

Progress ends on success, cancellation, and failure.
Compilation admission is released before progress notification or result aggregation.

## Analysis Lifetime

The caller already knows whether a candidate is an `OpenDocument` or a file read from disk.
That ownership distinction selects the analysis lifetime explicitly.
The implementation must not infer lifetime from version `0` or from the presence of a cache entry.

### Open documents

Open candidates continue to use the existing interactive cache.
If an attributed analysis for the current content already exists, the search reuses it.

### Closed candidates

Closed candidates use a transient reference operation:

1. create and attribute a javac task under shared compilation admission;
2. locate exact references;
3. convert matches to immutable request-owned values;
4. return without inserting anything into `SourceAnalysisSession.cache`.

After the operation returns, no request-owned object may retain source content, trees, symbols, `Trees`, `Elements`, `Types`, `JavacTask`, or compiler context.

`SourceAnalysisSession` exposes explicit cached-open and transient-closed reference methods.
Both methods reuse the same existing reference-location logic; no lifetime strategy interface is introduced.

## Process-Wide Compilation Admission

`WorkspaceModuleRegistry` owns one concrete `CompilationAdmission` and supplies it to every module and external compiler.
The permit count is captured once at startup:

```text
max(1, min(16, Runtime.getRuntime().availableProcessors()))
```

The cap of sixteen allows full utilization of a 16-core workstation while preventing reactor size from creating hundreds of concurrent javac tasks.
Processor count is an execution signal rather than a memory bound, so Helidon qualification must validate the resulting peak heap after transient analysis removes long-lived candidate state.
The constructor accepts an explicit permit count for deterministic tests; the production default is not user configurable in M1.

`JavacRunner` acquires admission immediately around javac task creation, parsing, attribution, and result extraction.
It releases admission in a `finally` block.
Cached analysis lookup, source reads, token-index work, progress reporting, and result aggregation do not hold a permit.

Permit acquisition occurs only on module-worker threads.
It must never block `ServerEventLoop` or a JSON-RPC transport thread.

Admission uses a short timed wait so queued work can observe cancellation.
The initial implementation does not add request priority.
An interactive request may wait behind broad-search work, and the visible progress and cancellation controls make that trade-off explicit.

## Optional Cancellation

Cancellation uses LSP4J's `CancelChecker`.
No Lathe-specific cancellation abstraction is introduced.

The references handler returns the response `CompletableFuture` observed by LSP4J.
Its checker throws `CancellationException` after LSP4J cancels that response in reaction to `$/cancelRequest`.
The checker is propagated through the existing asynchronous composition to every candidate owned by the request.

Work-done progress provides a second standard cancellation path.
Lathe sets `cancellable=true` on the operation's `WorkDoneProgressBegin` notification.
Clients such as progress UIs can then send `window/workDoneProgress/cancel` with the operation token.

`LatheLanguageServer.cancelProgress()` delegates that notification to the reference-progress owner.
The progress owner maintains an operation-scoped association from each active work-done token to the same response future used by request-ID cancellation.
Cancelling by progress token cancels that response future, so both protocol paths activate the same LSP4J `CancelChecker` and candidate checks.
The token association is removed on success, cancellation, and failure.
Unknown or already-completed tokens are ignored.

A candidate checks cancellation:

1. before submission where practical;
2. after reaching the head of its module-worker queue;
3. before waiting for compilation admission;
4. immediately after acquiring admission;
5. after javac returns;
6. before aggregation or any cache update.

Cancellation before javac completes the candidate without compiler work.
Cancellation during javac does not interrupt the compiler; the pass finishes, admission is released, and its result is discarded.

The request completes with LSP `RequestCancelled` (`-32800`), not a successful empty result.
Clients that send neither `$/cancelRequest` nor `window/workDoneProgress/cancel` receive normal complete-search behavior.
Cancellation of one request does not affect notifications or unrelated requests sharing the same module worker.

Cancelled tasks are not physically removed from module executor queues in M1.
They fail their checker when they reach the queue head and complete without entering javac.
Queue removal requires measurements demonstrating a material responsiveness problem.

## Work-Done Progress

Reference searches use LSP work-done progress, not partial-result streaming.
The begin notification advertises `cancellable=true` whenever the operation is backed by a cancellable response future.
The operation begins after the candidate count is known and reports completed candidates:

```text
Finding references to String
1,250 / 4,149 candidates (30%)
```

The operation tracks:

- total candidates;
- completed candidates;
- candidates requiring attribution;
- accumulated hits.

Progress completion is monotonic and safe when candidates finish concurrently.
Reports are throttled to at most one every 200 ms.
A final report is not required before `end`, but `end` must always contain the terminal outcome: completed, cancelled, or failed.

Progress notifications occur after releasing compilation admission and must not retain candidate analysis or result futures.
Progress failure must not fail the references request.

The shared `ProgressReporter` owns protocol details and creates operation-scoped state.
It supports asynchronous work by attaching terminal progress to the returned `CompletableFuture`.
Begin/end guards and throttling state belong to one operation, not to a reporter shared across concurrent requests.

If the request supplies a work-done token, Lathe uses it.
Otherwise, Lathe may create a server-initiated token when the client advertised work-done progress support.
If progress is unsupported, the same search runs with a no-op progress task.
Request-ID cancellation remains available independently of progress support.

## Result Aggregation and Partial Results

M1 retains eager candidate futures and returns one complete `List<Location>`.
The returned collection is immutable.

Partial-result streaming is deferred because it changes response semantics and does not address retained javac state.
Eager dispatch and aggregation may be reconsidered only if post-fix measurements show material queue memory, result memory, or latency.

## Failure Handling

Cancellation, legitimate empty results, ordinary failures, and fatal JVM errors are distinct outcomes.

- An unresolved target or completed search with no matches returns a successful empty list.
- Source-read, compiler, worker, and aggregation failures complete the request exceptionally and are logged once at the request boundary.
- Cancellation completes with `RequestCancelled` and is not logged as a failure.
- Direct or wrapped `Error` is fatal.

`Error` must never be swallowed by javac recovery code or converted into partial analysis.
The module-worker boundary detects it, including a wrapped cause, and terminates the process immediately with a non-zero status.
Continuing with a partially failed worker registry is unsupported because compiler and file-manager state may already be corrupted.

## Logging and Measurements

A completed request logs once at `INFO`:

```text
[references] <uri> target=<name> candidates=<n> attributed=<n> <ms> hits=<n>
```

A cancelled request logs once at `INFO`:

```text
[references] <uri> target=<name> candidates=<n> attributed=<n> <ms> cancelled
```

An ordinary failed request logs once at `SEVERE` with the throwable:

```text
[references] <uri> target=<name> candidates=<completed>/<total> <ms> failed
```

Per-file matches remain `FINE` and never include source content.
The Helidon qualification run records candidate count, attributed count, elapsed time, peak concurrent javac tasks, peak heap, and final interactive-cache size.

## Implementation Boundaries

Expected ownership:

- `LatheLanguageServer`: receives `window/workDoneProgress/cancel` and delegates by token;
- `LatheTextDocumentService`: response future, response-backed cancellation checker, and progress lifecycle;
- `WorkspaceSession`: candidate ownership, open-versus-closed routing, aggregation, and counters;
- `CompilationWorker`: queued cancellation checks and fatal-memory boundary;
- `SourceAnalysisSession`: cached-open and transient-closed reference operations;
- `WorkspaceModuleRegistry`: ownership of shared `CompilationAdmission`;
- `CompilationAdmission`: permit acquisition, cancellation-aware waiting, and release only;
- `JavacRunner`: admission boundary immediately around javac;
- `ProgressReporter`: shared protocol plumbing, active-token associations, and operation-scoped asynchronous progress state.

`CompilationAdmission` is a concrete class.
It must not absorb scheduling, caching, feature orchestration, progress, or aggregation.

## Testing

Before adding tests, read at least two existing test classes in every affected test package and follow their fixtures, compilation pipeline, and asynchronous assertion patterns.
All new tests follow the repository naming and formatting rules and include positive and negative or edge cases.

### Test ownership and redundancy

Before writing tests for each implementation slice, perform a read-only test-ownership audit:

1. search for existing assertions covering each planned behavior;
2. identify the lowest reliable layer that can prove the behavior;
3. extend the nearest existing test class where that layer already has an owner;
4. add a new test class only for a genuinely new production abstraction;
5. avoid repeating the same successful scenario at analysis, worker, workspace, and service layers.

The implementation summary for each slice must include a small coverage map showing the behavior, existing coverage, proposed change, and owning test class.
If an existing test already proves the behavior, reuse it unchanged.

Default ownership for this design is:

| Behavior | Test owner |
|---|---|
| Cache lifetime and exact transient reference results | `SourceAnalysisSessionTest` |
| Open-versus-closed candidate routing | `WorkspaceSessionTest` |
| Permit calculation, concurrency, and release | `CompilationAdmissionTest` |
| Queued cancellation and fatal-memory boundary | `CompilationWorkerTest` |
| Work-done cancellation notification routing | `LatheLanguageServerTest` |
| LSP cancellation response and progress protocol | `LatheTextDocumentServiceTest` |
| Real JSON-RPC wiring and Helidon qualification | Explorer validation |

Higher-layer tests verify only routing, protocol, or integration behavior that lower layers cannot establish.
They must not duplicate detailed compiler or cache assertions already owned by lower-layer tests.

### Analysis lifetime tests

- A closed-file reference scan returns exact matches and leaves `SourceAnalysisSession.cache` unchanged.
- Repeated closed-file scans do not grow the cache.
- An open-file reference scan reuses current cached analysis.
- Open and closed paths produce identical locations for identical content.
- A transient compilation failure does not insert partial analysis.
- Returned locations remain usable after all compiler-backed references are released.

### Compilation admission tests

- Production permit calculation returns one for one processor, the processor count below sixteen, and sixteen above the cap.
- Concurrent work across separate module and external workers never exceeds the configured permit count.
- Two simultaneous requests share the same admission instance.
- Admission is released after success, ordinary failure, cancellation after acquisition, and fatal propagation.
- Cancellation while waiting exits without acquiring or leaking a permit.
- Notification compilation uses the same admission with a no-op checker.
- Admission waits occur on worker threads, not `ServerEventLoop`.

Concurrency tests use latches, barriers, and bounded AssertJ or Mockito asynchronous assertions.
They must not use `Thread.sleep`.

### Cancellation tests

- Cancelling the returned response future is observed by the propagated checker.
- `$/cancelRequest` and `window/workDoneProgress/cancel` cancel the same response future and checker.
- Cancelling an active work-done token removes its association after termination.
- Cancelling an unknown or completed work-done token is a no-op.
- A candidate cancelled while queued never invokes javac.
- A candidate cancelled while waiting for admission exits without compiler work.
- A candidate cancelled during javac releases admission and discards its result.
- Cancellation returns `RequestCancelled`, while no matches returns successful empty results.
- Cancelling one request does not cancel another request on the same worker.
- Notification generation and document supersession behavior remain unchanged.

### Progress tests

- A supported client receives one cancellable begin, monotonic reports, and one completed end.
- A cancelled request receives one cancelled end.
- A failed request receives one failed end and preserves the original request failure.
- An unsupported client receives no progress notifications and the same final locations.
- Concurrent requests use distinct tokens and independent begin/end guards.
- Cancelling one progress token does not affect another active operation.
- Rapid candidate completions are throttled without suppressing terminal end.
- Progress notification failure does not fail or cancel the reference request.
- No progress object retains compiler analysis after candidate completion.

Progress throttling tests use an injected or existing controllable time source rather than wall-clock sleeps.

### Fatal-error tests

- Direct `Error` is never converted into partial analysis.
- A runtime exception wrapping `Error` is classified as fatal.
- The fatal boundary invokes an injectable termination action in tests instead of terminating the test JVM.
- Ordinary compiler exceptions remain non-fatal request failures.

### Emulated LSP request tests

Automated tests invoke the normal text-document service boundary with `ReferenceParams` and a fake `LanguageClient`.
They do not launch Neovim or a separate editor process as part of the Maven build.

- A multi-module references request returns complete exact locations and emits progress notifications to the fake client.
- Cancelling by request ID or work-done token produces `RequestCancelled` and leaves the service able to answer a subsequent emulated hover request.
- A request without cancellation or progress support retains normal protocol behavior.
- An ordinary candidate failure reaches the service boundary and is logged once.
- Request, progress, and cancellation parameters use the same LSP4J types used by the production JSON-RPC path.

### Explorer validation

The real protocol workflow is validated manually with `dev/explore.py` and its existing `dev/lsp.py` client rather than by running Neovim in the build.
The explorer client may be extended only as needed to display `$/progress` notifications and issue `$/cancelRequest` or `window/workDoneProgress/cancel` for its active references request.

Explorer validation covers:

- a completed references request with visible monotonic progress;
- cancellation of an active references request through its progress UI token;
- a successful hover request after completion or cancellation;
- identical final locations when progress is enabled or unsupported;
- process exit within a bounded timeout after LSP `shutdown` and `exit` during an active search;
- process exit within a bounded timeout after the explorer closes server stdin without an LSP shutdown.

### Helidon qualification

Run a broad `String` reference search against Helidon through the explorer and record:

- workspace module, source-configuration, and candidate counts;
- completed and attributed candidates;
- exact hit count;
- elapsed time;
- peak concurrent javac tasks;
- peak and post-search heap;
- interactive-cache size before and after the search.

This is a manual milestone qualification step, not a Maven integration test.
The run passes only if it completes or can be cancelled without heap exhaustion, active javac never exceeds sixteen, closed candidates do not remain cached, and the server answers a subsequent interactive request.
Repeat the search once to verify stable post-search memory rather than relying on one successful run.

## Acceptance Criteria

- A Helidon-wide `String` search completes or can be cancelled without exhausting the JVM heap.
- No closed-file candidate analysis remains in an interactive cache after the request.
- Lathe executes at most sixteen javac tasks concurrently across the process.
- Queued cancelled candidates never enter javac.
- Cancellation during javac never publishes or caches that result.
- Supported clients see monotonic progress and one terminal outcome.
- Cancellable progress and request-ID cancellation stop the same operation.
- Editor shutdown and transport EOF during an active search leave no Lathe process running.
- Unsupported clients retain normal complete-result behavior.
- Complete searches retain exact project-wide reference semantics.
- Any residual direct or wrapped `Error` terminates the process.
- Partial-result streaming, result caching, and new scheduling abstractions remain deferred.
- Focused unit and emulated request tests, the full Maven verification suite, Spotless, explorer validation, and Helidon qualification pass.

## Implementation Approval

The implementation touches multiple classes and introduces `CompilationAdmission` plus asynchronous progress plumbing.
No production code may be written until an implementation design summary based on this document is explicitly approved.
