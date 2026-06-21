# Lathe — Request Cancellation and Compilation Admission

## Status

Planned M1 reliability work.

This design defines process-wide limits for javac-backed work and cooperative cancellation for LSP requests.
Feature designs such as [Reference Search Resource Safety](lathe-reference-search-resource-safety.md) build on these
runtime guarantees.

## Context

Lathe executes javac-backed feature work on independent single-threaded `CompilationWorker`s.
There is one worker per `ModuleSourceConfig`, so a large reactor can execute many javac tasks concurrently even though
each individual module is serialized.

The June 2026 Helidon `String` reference search demonstrated that per-module serialization is not a process-wide
resource bound.
The request exhausted an approximately 15.2 GiB heap while many module workers were active.
Increasing the heap is not an acceptable remedy.

LSP also permits clients to cancel any request through `$/cancelRequest`.
Neovim supports that notification, but cancellation of an outer `CompletableFuture` does not automatically remove
Lathe's queued worker tasks or propagate through composed futures.
LSP4J cancels the exact future returned by the request handler and already maps that cancellation to the protocol's
`RequestCancelled` response.

## Goals

- Never execute more javac-backed tasks concurrently than logical processors available to the JVM.
- Apply the limit across all module workers, external-source workers, and simultaneous requests.
- Propagate LSP cancellation from the service boundary into queued and composed feature work.
- Avoid starting work after its request has been cancelled.
- Release admission reliably after success, failure, or cancellation.
- Preserve the existing worker-thread confinement model.
- Keep interactive requests responsive while workspace-wide operations are running.
- Distinguish cancellation, legitimate empty results, and operation failures at the LSP boundary.

## Non-goals

- Increasing or recommending an increased JVM heap.
- Interrupting javac at arbitrary points inside a running attribution pass.
- Applying `$/cancelRequest` to LSP notifications.
- Building a generic workflow or task-execution framework.
- Introducing a Lathe-specific cancellation token or request-state abstraction.
- Adding progress reporting or partial-result streaming.
- Guaranteeing that every inexpensive request stops immediately after cancellation.

## Request Categories

### Workspace fan-out requests

Find References and method implementation search can inspect many files across many module workers.
Their active javac work is bounded by process-wide compilation admission.
Queued work must observe cancellation before entering javac.

### Single-file javac requests

Completion, hover, signature help, definition, code actions, and semantic tokens normally operate on one file.
Cancellation is still valuable because superseded requests can accumulate behind a module worker.
Neovim already cancels some stale completion and semantic-token requests.

For these operations, Lathe must skip a cancelled task before it enters javac and discard its result if cancellation
arrives during javac.

### Inexpensive requests

Document symbols, folding ranges, type hierarchy queries, formatting, and workspace symbols are normally bounded and
short.
They should use the common request-cancellation boundary, but adding cancellation checks inside their synchronous
implementation is unnecessary until measurements show a problem.

### Notifications

`didOpen`, `didChange`, `didSave`, `didClose`, and watched-file notifications have no request ID and cannot be cancelled
with `$/cancelRequest`.
Lathe must continue to handle stale notification work through document generations, debounce cancellation, and
supersession checks.
Request cancellation must not replace those mechanisms.

## Process-wide Compilation Admission

Lathe creates one process-wide compilation admission limit when the server runtime starts.
Its permit count is:

```text
max(1, Runtime.getRuntime().availableProcessors())
```

`availableProcessors()` is the workstation CPU signal exposed by the JVM and respects supported container and process
CPU constraints.
The value is captured once and remains stable for the process lifetime.

Every operation that creates or analyzes a `JavacTask` must acquire admission before entering javac and release it in a
`finally` block.
This includes:

- open and full compilation;
- completion re-attribution;
- reference and method-implementation candidate attribution;
- module and external-source analysis.

The limit is shared across requests and workspaces within the process.
Two simultaneous workspace-wide requests cannot each consume one processor-sized allocation.

Admission must be fair.
When a broad search occupies all permits, a later interactive request must be eligible for the next released permit
rather than waiting for the entire search to finish.
Waiting for admission must never block `ServerEventLoop` or a JSON-RPC transport thread.

Processor count is an execution ceiling, not a complete memory bound.
Feature implementations must also avoid retaining completed javac analyses unnecessarily.

## Cancellation Model

### Protocol behavior

The client sends `$/cancelRequest` with the ID of an outstanding request.
Lathe responds to successful cancellation with LSP `RequestCancelled` (`-32800`), not a successful empty result.
Cancellation is cooperative: work already inside javac may finish before Lathe can release its resources.

Neovim exposes `Client:cancel_request(request_id)` and returns a cancellation function from
`vim.lsp.buf_request_all()`.
Its built-in `vim.lsp.buf.references()` currently discards that function, so editor integration may separately expose
a cancellable references command.
Server correctness must not depend on that UI being present.

### Handler integration

Each cancellable LSP handler creates the response `CompletableFuture` that it returns to LSP4J.
The handler also creates an LSP4J `CancelChecker` whose `checkCanceled()` implementation throws
`CancellationException` when that response future is cancelled.

The checker is passed through asynchronous composition to every task owned by the request.
The internal work future completes the response future with its result or failure.
If Neovim sends `$/cancelRequest`, LSP4J cancels the response future directly, the checker begins reporting
cancellation, and LSP4J emits `RequestCancelled` (`-32800`).

This explicit response-future bridge is necessary because cancelling the returned future does not automatically cancel
child futures created by `thenCompose` or tasks already submitted to module workers.
Lathe uses LSP4J's existing `CancelChecker`; it does not introduce a second cancellation-token type.

The LSP services should share a small private helper for response-future creation and completion so every request does
not reproduce the same bridge logic.
The helper is plumbing local to the service boundary, not a task framework.

### Worker behavior

A worker task checks cancellation:

1. before it is submitted or enqueued where possible;
2. after reaching the head of its module queue;
3. before waiting for compilation admission;
4. immediately after acquiring admission;
5. after javac returns and before publishing or caching its result.

If cancellation is observed before javac starts, the task completes as cancelled without invoking the compiler.
If cancellation arrives during javac, Lathe allows javac to return, discards the result, releases admission, and
completes as cancelled.

Lathe must not use `Thread.interrupt()` to abort javac unless the JDK documents the relevant operation as safely
interruptible.

Cancellation of one request must not cancel unrelated diagnostics, notification work, or requests sharing the same
module worker.

Cancelled tasks are not initially removed from module executor queues.
When a cancelled task reaches the queue head, its checker fails before javac admission and the task completes without
compiler work.
Physical queue removal is deferred unless measurements show that draining cancelled no-op tasks materially delays
subsequent requests.

## Failure Handling

Cancellation and failure are distinct terminal states.
An unresolved cursor or an operation with no matches is a successful empty result.
An IO, compiler, worker, or aggregation failure completes exceptionally and is logged once at the nearest operation
boundary.

`OutOfMemoryError` is not recoverable at a module-worker boundary.
Lathe must not continue with a partially failed worker registry.
The process-wide admission limit and feature-specific lifetime bounds are the prevention mechanism; if exhaustion still
occurs, the process must terminate cleanly so the editor can restart it.

## Implementation Boundaries

The implementation is expected to touch multiple classes and introduce a concrete compilation-admission component.
It requires a separate approved implementation design summary before code is written.

Expected ownership:

- server runtime: owns the process-wide compilation admission limit;
- `WorkspaceModuleRegistry`: supplies the shared admission instance to every worker;
- `CompilationWorker`: accepts `CancelChecker`, checks cancellation, and applies admission around javac-backed work;
- LSP services: create the returned response future and its associated `CancelChecker`;
- feature orchestration: propagates `CancelChecker` through composed futures.

The admission component must be a concrete class.
It must not absorb feature-specific scheduling, caching, or result aggregation.

## Verification

### Compilation admission

- The permit count is one when the supplied processor count is one.
- Concurrent work across different module and external workers never exceeds the configured limit.
- Two simultaneous requests share one limit.
- Admission is released after success, ordinary failure, cancellation before javac, and cancellation during javac.
- A waiting interactive request receives admission before a broad operation can monopolize every subsequent permit.
- No admission wait blocks `ServerEventLoop`.

### Request cancellation

- LSP4J cancellation of the returned response future is observed by its `CancelChecker`.
- Internal success and failure complete the response future correctly when it has not been cancelled.
- A task cancelled while queued never invokes javac.
- A task cancelled during javac discards its result and does not update an analysis cache.
- The same checker reaches all composed work owned by the request.
- Cancellation returns `RequestCancelled` rather than an empty result.
- Cancelling one request does not affect another request on the same worker.
- Notification generation and supersession behavior remains unchanged.

### End-to-end behavior

- A Neovim-compatible test client sends `$/cancelRequest` and receives the cancellation response.
- The server answers a subsequent hover request after cancelling a long request.
- Instrumented tests assert that peak concurrent javac work does not exceed
  `Runtime.getRuntime().availableProcessors()`.

## Acceptance Criteria

- Lathe never executes more active javac tasks than processors reported by the JVM.
- Queued cancelled requests do not enter javac.
- Running javac work releases admission and discards results after cancellation.
- Cancellation uses LSP4J's `CancelChecker` rather than a Lathe-specific token.
- Workspace fan-out operations share the same admission and cancellation mechanisms as single-file requests.
- LSP request cancellation does not alter notification supersession behavior.
- No documentation or diagnostic recommends increasing the heap as the solution.
