# Lathe — Reference Search Resource Safety

## Status

Planned M1 reliability work.

This design addresses the June 2026 Helidon crash caused by a project-wide Find References request for
`java.lang.String`.
It supplements the completed [Find References](../done/lathe-find-references.md) design and depends on
[Request Cancellation and Compilation Admission](lathe-request-cancellation-and-compilation-admission.md).
Optional progress and partial results remain in [Streaming References](lathe-streaming-references.md).

## Incident

A `textDocument/references` request for `String` in Helidon started javac attribution across many module source trees.
The log contained at least 2,419 compile events covering 1,477 unique Java source files before the server exhausted its
heap.
Independent module worker threads then failed in javac and zipfs with `OutOfMemoryError`.

The server had no explicit heap limit and the JVM selected an estimated maximum heap of approximately 15.2 GiB.
Increasing that limit is not an acceptable remedy.
The search must release request-only analysis promptly and respect process-wide compilation admission.

## Root Cause

`SourceAnalysisSession.searchReferences()` calls `ensureAttributedAnalysis()` for every candidate.
When no matching cache entry exists, that method uses the normal `CompileMode.OPEN` path.
`SourceAnalysisSession.compile()` then stores this value:

```text
CachedFileAnalysis(content, version, run.fileAnalysis())
```

That is correct for an open editor document because hover, definition, semantic tokens, and other features reuse its
attributed analysis.
It is incorrect for a closed file read solely as a reference candidate.

Every cached candidate retains its source and javac-backed analysis state, including trees, symbols, `Trees`, and task
context.
Closed disk candidates never receive `didClose`, so those entries remain until workspace reload or server shutdown.
The broad `String` search therefore converted transient workspace scanning into long-lived module-session state.

Per-module workers also execute concurrently without a process-wide ceiling.
The general compilation-admission design addresses that independent source of peak memory pressure.

## Futures and Aggregation

`WorkspaceSession.referencesFuture()` currently creates candidate futures eagerly.
Each queued task temporarily retains its source content and request data, and each completed future retains its result
while the aggregation chain remains reachable.

This can produce noticeable temporary overhead for a very large candidate set, but it does not retain javac analysis
after the request by itself.
Reference-match lists are also much smaller than attributed compiler state.
The incident evidence does not establish eager future creation as a primary cause of the heap exhaustion.

The initial fix therefore keeps eager future creation and the existing aggregation structure.
Lazy candidate dispatch or different aggregation should be considered only if measurements after the cache and
concurrency fixes show material queue memory or latency.

## Goals

- Never cache an analysis created only to scan a closed reference candidate.
- Preserve interactive analysis caching for open documents.
- Run candidate attribution under the process-wide compilation admission limit.
- Ensure queued reference work observes LSP4J cancellation before entering javac.
- Preserve complete, exact results when a request is allowed to finish.
- Record aggregate candidate, attribution, timing, and result measurements.

## Non-goals

- Increasing or recommending an increased JVM heap.
- Replacing eager candidate futures in the initial fix.
- Reworking result aggregation without measurements showing a problem.
- Silently truncating results or changing project-wide reference semantics.
- Replacing javac attribution with textual matches.
- Duplicating general request cancellation or compilation admission inside the references feature.
- Adding partial-result streaming in the same change.

## Analysis Lifetime

Open documents continue using the interactive cache because other features reuse their attributed analysis.

Closed-file candidates use a transient compile-and-scan operation:

1. compile and attribute the candidate under process-wide compilation admission;
2. locate exact references;
3. convert matches to request-owned values;
4. return without inserting the analysis into `SourceAnalysisSession.cache`.

The transient operation must not call the cache-inserting `CompileMode.OPEN` path.
It may reuse the module's `StandardJavaFileManager`, but it must not retain `CachedFileAnalysis`, source content, trees,
elements, `Trees`, or task context after matches are returned.

The caller already knows whether a candidate came from `OpenDocument` or from disk.
That ownership distinction should select cached or transient analysis explicitly.
The implementation must not infer lifetime from version `0` or from whether a cache entry happens to exist.

## Cancellation Behavior

Reference futures use the response-backed LSP4J `CancelChecker` defined by the general cancellation design.
Queued tasks check cancellation before waiting for compilation admission and again before entering javac.
If cancellation arrives during javac, that pass may finish, but its result is discarded and never cached.

The initial implementation does not require removing every cancelled task from module executor queues.
Cancelled tasks may reach the head of their queue and complete without invoking javac.
Queue removal can be added later if measurements show that draining cancelled no-op tasks delays subsequent work.

Cancellation completes the LSP request with `RequestCancelled` (`-32800`).
It does not return a successful empty reference list or affect unrelated requests and notifications.

## Failure Handling

Source-read, compiler, worker, and aggregation failures propagate to the references request boundary.
That boundary logs one `SEVERE` record with the request URI, target, elapsed time, completed candidate count, and total
candidate count, then completes the request exceptionally.
A legitimate unresolved target or completed search with no matches remains a successful empty result.

## Logging and Measurements

One successful request emits:

```text
[references] <uri> target=<name> candidates=<n> attributed=<n> <ms> hits=<n>
```

A cancelled request emits one `INFO` record:

```text
[references] <uri> target=<name> candidates=<n> attributed=<n> <ms> cancelled
```

Per-file reference hits remain `FINE` and must not include source content.
Measurements should include peak process memory before considering lazy dispatch or aggregation changes.

## Implementation Boundaries

The implementation touches multiple classes and requires an approved implementation design summary before code is
written.

Expected ownership:

- `WorkspaceSession`: distinguishes open documents from disk candidates and propagates `CancelChecker`;
- `CompilationWorker`: runs candidate work under shared admission and observes `CancelChecker`;
- `SourceAnalysisSession`: exposes a transient closed-file reference scan that bypasses the interactive cache;
- `LatheTextDocumentService`: creates the response-backed `CancelChecker`.

No reference-specific scheduler, executor, or coordination abstraction is introduced.

## Verification

### Focused tests

- A closed-file reference scan leaves `SourceAnalysisSession.cache` unchanged.
- Repeated closed-file scans do not grow the cache.
- An open-file scan can reuse its existing cached analysis.
- Closed and open candidates produce identical exact matches for identical content.
- A queued cancelled candidate never enters javac.
- A candidate cancelled during javac does not update the cache or publish results.
- Cancellation returns `RequestCancelled`, while no matches returns successful empty results.
- A candidate failure reaches the LSP boundary exceptionally and is logged once.

### End-to-end tests

- Extend the multi-module LSP smoke test with a cancellable `textDocument/references` request.
- Verify that the client receives cancellation and the server remains responsive to a subsequent hover request.
- Run a broad `String` reference search against Helidon and record candidates, attributed files, peak concurrent
  compilations, elapsed time, results, and process memory.
- Verify that peak concurrent compilation respects the process-wide admission limit.
- Verify that completed closed-file scans do not leave candidate entries in module analysis caches.

## Acceptance Criteria

- A Helidon-wide `String` search completes or can be cancelled without exhausting the JVM heap.
- No closed-file candidate analysis remains in an interactive cache after the request.
- Lathe never has more active javac tasks than the process-wide admission limit.
- Queued cancelled candidates do not enter javac.
- Complete searches retain exact project-wide reference semantics.
- Eager futures remain unless post-fix measurements justify additional complexity.
- No documentation or diagnostic recommends increasing the heap as the solution.
