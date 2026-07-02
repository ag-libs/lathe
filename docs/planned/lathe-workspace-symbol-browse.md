# Lathe — Workspace Symbol Browsing (Blank-Query Support)

## Problem

`workspace/symbol` currently returns nothing for a blank or empty query.
Both `WorkspaceSymbolResolver.resolve` and `WorkspaceTypeIndex.search` short-circuit on an empty
string before any lookup happens.

This blocks a whole class of client usage.
Telescope's `lsp_workspace_symbols` picker sends a single `workspace/symbol` request with
`query = opts.query or ""` when the picker opens, then fuzzy-filters the returned result set
locally on every keystroke via its own sorter.
Against Lathe today, that request always comes back empty — Telescope even prints its own hint
for this exact situation ("Maybe try a different query: `Telescope lsp_workspace_symbols
query=example`").

Neovim already has a working path for narrowing by prefix: `lsp_dynamic_workspace_symbols`
(mapped to `<leader>ws`) re-sends `workspace/symbol` to the server on every keystroke, so it
works today as long as the user types something. It has no way to *browse* — there is no query
that returns "everything" to seed a local fuzzy filter.

This document is scoped to the blank-query gap only.
It does not address CamelCase or infix matching for non-blank queries — that is tracked
separately as [EG-005](gaps/gaps.md) (accepted, targeted at M2) and is independent of blank-query
support.

## Goal

A blank `workspace/symbol` query returns the full list of **workspace-owned (reactor) types**, so
a client can fetch once and fuzzy-filter locally (Telescope's `lsp_workspace_symbols`, or an
equivalent picker in another editor). No artificial cap on the result count — an earlier revision
of this document proposed a `BROWSE_LIMIT` constant, but an arbitrary cutoff on "browse everything"
is confusing (why 500 and not 200 or 2,000?). Resolution stays sequential (see Technical Design);
the cost that a cap would have bounded is instead made visible and cancellable via progress
reporting rather than reduced.

## Non-Goals

- **CamelCase/infix matching for non-blank queries.** Separate, already-tracked gap (EG-005).
  This document does not touch `search()`'s existing prefix-match contract for non-blank queries.
- **Including JDK or dependency types in the blank-query result.** The index also carries JDK and
  dependency source (`typeSourceDirs()` in `WorkspaceSession` folds in
  `manifest.jdkModuleSourceDirs()` and `manifest.depSourceDirs()` alongside
  `workspace.allSourceRoots()`). Returning all of that on a blank query would bury the user's own
  code under standard-library and dependency noise, and would scale the per-result cost
  (see Performance below) with the entire transitive universe instead of just the workspace.
- **Widening the index to methods/fields/constants.** Out of scope; would require changes to
  `ClassFileTypeScanner` (bytecode member extraction), `SourceAnalysisSession` (source-side member
  extraction), and the `TypeIndexEntry`/`TypeIndexFile` on-disk shard schema. A separate design if
  ever pursued.
- **`WorkspaceSymbol` lazy-resolve (LSP 3.17).** Not attempted here: resolution stays sequential
  (parallelism was considered and rejected — see Technical Design) and the resulting cost is
  addressed via progress reporting and cancellation, not reduced. Lazy-resolve remains the fallback
  if that cost turns out to be unacceptable in practice — see the Known Limitation in Performance
  Model.
- **Parallelizing `declarationRange` resolution.** Considered and rejected — see Technical Design.
  The measured benefit (~1.5–1.7x, capped by javac's internal parser contention, not by core count)
  was judged not worth the added complexity for a first-class change to a shared code path.

## Technical Design

### Implementation slices

This feature lands in two slices.

**First slice — shipped.** The semantic behavior change: blank `workspace/symbol` queries return
reactor-owned types, while non-blank queries keep the existing prefix-search behavior.
`WorkspaceTypeIndex.browseWorkspace()` and the `resolve()` branch are implemented, unit-tested
(`WorkspaceTypeIndexTest`, `WorkspaceSymbolTest`), and verified end-to-end against a real workspace
(910 correctly-sorted, reactor-only symbols returned for a blank query against Dropwizard via
`dev/explore.py`; non-blank prefix search confirmed unaffected). Declaration-range resolution stays
sequential — permanently, not just for this slice (see "Declaration-range resolution stays
sequential" below; parallelism in any form was evaluated and rejected).

**Second slice — next up.** Work-done progress/cancellation for blank-query browsing, so a long
browse is visible and cancellable instead of a silent freeze — this is the accepted mitigation for
the ~2.1s Helidon-scale worst case, in place of making resolution faster. It is cross-cutting
(generalizes `ReferenceProgressReporter`, a class another feature already depends on) and needs its
own design sign-off separate from the first slice's correctness change — see "Reuse plan" below.

### Why not change `search()`

`WorkspaceTypeIndex.search(prefix, limit)` is shared infrastructure, not private to workspace
symbols. It is also called by:

- `ImportQuickFixProvider` (candidate lookup for an unresolved simple name)
- `MemberAccessCompleter` and `CompletionEngine` (prefix-based completion filtering)

All three callers currently rely on an empty prefix returning nothing — completion in particular
must not suddenly return every candidate type when nothing has been typed yet. Changing `search()`
itself to serve blank queries would change completion and import-quickfix behavior as a side
effect. Blank-query browsing therefore needs its own method, not a change to `search()`'s
contract.

### New method: `WorkspaceTypeIndex.browseWorkspace()`

Reuses data already tracked by the index — no new plumbing, and no limit parameter:

- `reactorBinaryNames` (`Set<String>`) already identifies reactor-owned entries; it is already
  used as a sort-key boost inside `search()`.
- `bySimpleNameLower` already holds the full merged, deduplicated entry set.

```java
public List<TypeIndexEntry> browseWorkspace() {
  return bySimpleNameLower.values().stream()
      .flatMap(List::stream)
      .filter(e -> reactorBinaryNames.contains(e.binaryName()))
      .sorted(Comparator.comparing(TypeIndexEntry::binaryName))
      .toList();
}
```

### `WorkspaceSymbolResolver.resolve`

```java
public static List<SymbolInformation> resolve(
    final String query, final WorkspaceTypeIndex typeIndex, final List<Path> sourceDirs) {
  final List<TypeIndexEntry> entries =
      query.isBlank() ? typeIndex.browseWorkspace() : typeIndex.search(query, SEARCH_LIMIT);
  // unchanged from here: map entries -> SymbolInformation via toSymbolInformation, see below
}
```

Non-blank queries are untouched — same `search(query, limit)` prefix match as today.

### Declaration-range resolution stays sequential

The result-count cap was the original way to bound `declarationRange`'s per-entry
`SourceParser.parseFile` cost. Parallelizing that loop was explored as the replacement once the cap
was dropped, and rejected — kept here so it isn't re-proposed without the context of why.

The existing `resolve` implementation shares one `SourceParser` (and its one
`StandardJavaFileManager`) across every entry via a single `try (var parser = new SourceParser())`
wrapping a sequential `.stream().map(...)`. `StandardJavaFileManager` is not documented as safe for
concurrent use from multiple threads, so a naive `.stream()` → `.parallelStream()` swap on the
existing code would share that one file manager across threads — a real correctness bug, not just a
performance change. That bug is avoidable: constructing a fresh `StandardJavaFileManager` per task
was measured at ~0.042ms, negligible next to the ~0.5ms/file parse cost, so each parallel task could
safely own a short-lived `SourceParser` instead of reusing one.

Measured against Helidon's ~4,185 main-source files (a stand-in for reactor-owned top-level types),
using the JVM common pool (`.parallelStream()`, no custom executor):

| threads | total time |
|---|---|
| 1  | 2,124ms |
| 4  | 1,411ms |
| 8  | 1,231ms |
| 16 | 1,996ms (worse than 8 — javac's parser has internal contention that caps scaling) |

So the bug is fixable and the approach would be strictly faster than sequential at every measured
thread count. It was still rejected: the ceiling is ~1.7x, capped by javac's own internal
contention rather than core count, so it does not scale the way "more threads" usually implies —
and a raw `.parallelStream()` (JVM common pool) has no tuning available if that ceiling turns out
to matter on a different machine, while a custom bounded pool reintroduces exactly the kind of
arbitrary constant (`WORKSPACE_SYMBOL_BROWSE_THREADS`) this document already rejected once for
`BROWSE_LIMIT`. A ~1.5–1.7x win on an operation that progress reporting (below) already makes
visible and cancellable was judged not worth either the custom-constant tradeoff or the added
complexity of a second correctness property (per-task `SourceParser` isolation) in a shared code
path. Resolution stays a plain sequential `.stream()`, unchanged from the first slice.

## Progress Reporting

Without a cap, worst case is a multi-second synchronous operation (see Performance Model) with no
feedback to the user — that is a bad experience on its own, independent of the raw cost. LSP
work-done progress addresses the *experience* (visible status, cancellable) without changing the
underlying cost. Since parallel resolution was rejected (above), progress reporting is the only
mitigation for that cost, not a complement to a faster resolution path.

### What already exists

A generic `ProgressReporter` was designed in [lathe-lsp-progress.md](lathe-lsp-progress.md)
(lambda-wrapper shape, `begin`/`report`/`end`) but was never built as a standalone class. Instead,
find-references shipped a concrete implementation, `ReferenceProgressReporter`/
`ReferenceProgressReporter.Task`, when it needed progress and cancellation for the same reason this
document does — an operation whose duration scales with workspace size. It is real, tested,
already-wired infrastructure — and despite the name, it is not inherently reference-search-only.
At the time of this plan, `LatheTextDocumentService.references` and
`LatheTextDocumentService.callHierarchyIncomingCalls` open reporter tasks and thread them into
`WorkspaceSession.referencesFuture` and `WorkspaceSession.incomingCallsFuture`.
Workspace-symbol browsing would become another caller of the same proven mechanism, after the
reporter is generalized.

- **Capability negotiation** — `LatheLanguageServer.initialize` reads `workDoneProgress` from
  `InitializeParams` and calls `textDocumentService.setWorkDoneProgressSupported(...)`.
- **Token lifecycle** — `Task.open(requestedToken, response)` either reuses a client-supplied token
  or mints one server-side, tracks it in a `ConcurrentMap<token, CompletableFuture<?>>`, and is a
  no-op (`token == null`) when the client doesn't support progress at all.
- **begin/report/end** — `Task.begin(title, total)`, `Task.advance(...)` (200ms-throttled
  `WorkDoneProgressReport`), `Task.finish(failure)` (`WorkDoneProgressEnd`, distinguishes
  Completed/Cancelled/Failed).
- **Cancellation** — `window/workDoneProgress/cancel` → `LatheLanguageServer.cancelProgress` →
  `LatheTextDocumentService.cancelProgress` → `progressReporter.cancel(token)`, which cancels the
  `CompletableFuture` the request is racing against. The session-side loop observes this via a
  `CancelChecker` (`cancelChecker.checkCanceled()`), already the pattern in
  `WorkspaceSession.referencesFuture`.
- **Call-site wiring** (`LatheTextDocumentService.references`, `:176-198`) — construct `response`,
  build a `CancelChecker` bound to it, `open()` a `Task`, submit the session work with the task and
  checker threaded through, and call `task.finish(failure)` in `whenComplete`.

### Reuse plan

`ReferenceProgressReporter` is reference-search-specific only in naming. Its `Task.begin(target,
total)` (`ReferenceProgressReporter.java:153-169`) unconditionally formats the title as
`"Finding references to %s".formatted(target)`.
At the time of this plan, references and incoming call hierarchy are wired through the reporter;
outgoing call hierarchy and implementation are not wired through progress at the service boundary.
Incoming call hierarchy therefore already shows a progress bar titled "Finding references to X",
which is a pre-existing cosmetic bug, not something introduced by this document.
It is a concrete example of the coupling that needs to be undone before adding workspace-symbol
browse progress with its own correct title (`"Loading workspace symbols"`).

Workspace-symbol browsing also needs a simpler message than the existing
`completed/total/attributed/hits` format — `attributed`/`hits` are reference-search-specific
concepts (a candidate can be a text match without being attributed, etc.) that don't apply here.

Rather than write a second, near-duplicate reporter class, the follow-up slice should generalize the
existing one:

- Rename `ReferenceProgressReporter` → `ProgressReporter`.
- `Task.begin` takes the fully-composed title from the caller instead of formatting
  `"Finding references to %s"` internally.
  Existing references and incoming-call callers pass their own title strings, and workspace-symbol
  browse can pass `"Loading workspace symbols"`.
  This also fixes the existing mislabeled-title bug for incoming call hierarchy as a side effect of
  the change this document needs anyway.
- Keep `attributed`/`hits` tracking in reference search's own call pattern (it can still call
  `advance` with its existing counters); workspace-symbol browsing calls a simpler
  `advance(completedDelta)` path that only needs `completed`/`total` for its message and
  percentage.

This is a shared-class change, not a new abstraction, and it needs its own regression coverage to
confirm it doesn't change existing progress behavior beyond the title-text fix.

### Wiring for `workspace/symbol`

`WorkspaceSymbolParams extends WorkDoneProgressAndPartialResultParams` (confirmed against the
lsp4j 1.0.0 jar) — it already carries `getWorkDoneToken()`, so there is no protocol gap to close.
`LatheWorkspaceService.symbol()` currently has no `CancelChecker` or progress at all; it needs the
same shape `references()` already has:

```java
final var response = new CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>();
final CancelChecker cancelChecker = new CompletableFutures.FutureCancelChecker(response);
final var progress = progressReporter.open(params.getWorkDoneToken(), response);
// submit to worker, passing cancelChecker and progress into WorkspaceSession.workspaceSymbol
// progress.finish(failure) in whenComplete, same as references()
```

Progress applies to the **blank-query browse path only** in the follow-up slice — the capped, ~50ms
`search()` path for non-blank queries stays fast enough that a progress bar would just flicker,
matching the existing "fast operations don't need progress" principle from
`lathe-lsp-progress.md`. Inside the sequential `browseWorkspace()` resolution loop, each completed
entry calls `task.advance(1)`, and the loop checks `cancelChecker.checkCanceled()` between entries
so a user-cancelled browse actually stops early instead of running to completion in the background.

This also meaningfully softens the Known Limitation below: the operation still costs what it costs,
but the user sees a live percentage instead of an unexplained freeze, and can cancel out of it.

## Performance Model

Each result costs one `SourceParser.parseFile` call (`WorkspaceSymbolResolver.declarationRange`)
to compute an exact declaration position. `SourceParser` calls `task.parse()` only — a syntax
parse, not a full compile with attribution — so the per-file cost is bounded by file size, not by
the workspace's dependency graph. Measured against Helidon's ~4,185 main-source files (a stand-in
for reactor-owned top-level types) using the same parse-only path: **~0.5ms/file, ~2.1s total
sequential.**

Restricting the blank-query path to reactor-owned types already keeps the candidate pool bounded
to the current project instead of the full JDK+dependency universe — that boundary stays even
without a numeric cap. Resolution is sequential (parallelism considered and rejected — see
Technical Design), so the ~2.1s Helidon-scale figure above is the actual cost, not a best case.

### Known limitation

`LatheTextDocumentService.worker` is a single-threaded `ServerEventLoop` that serializes every LSP
request — confirmed directly in code (`ServerEventLoop.java:22-23`,
`Executors.newSingleThreadScheduledExecutor`), and `WorkspaceSymbolResolver.resolve` never hands
any part of its work to a module's own `CompilationWorker` thread, so there is no incidental
cross-module parallelism happening today either. A blank-query browse on a Helidon-scale reactor
blocks that one thread — and therefore every other in-flight request — for roughly **2.1 seconds**,
not the sub-300ms a numeric cap would have guaranteed. Progress reporting (above) makes this visible
and cancellable instead of a silent freeze, but it does not shrink the 2.1s figure itself —
cancelling still has to wait for the current in-flight parse to finish before the `CancelChecker` is
next observed. This is the accepted tradeoff for not having a confusing, arbitrarily-chosen limit.
If this turns out to be a real problem in practice (large reactors, frequent picker use), the
fallback options are, in order of preference: (1) move `workspace/symbol` resolution off the single
event-loop thread entirely, or (2) reintroduce `WorkspaceSymbol` lazy-resolve so the initial response
skips `declarationRange` altogether and only resolves it for the entry the user actually selects.
Both are out of scope for this document; they are the next step if the residual cost proves
unacceptable — ahead of revisiting parallelism, since the rejection above was about the added
complexity for a ~1.7x ceiling, not about parallelism being unsafe once done correctly.

## Client Wiring (Neovim)

Once the server change lands, add a static picker mapping alongside the existing dynamic one in
`dotfiles/nvim/init.lua`:

```lua
vim.keymap.set('n', '<leader>Ws', function()
  tel_builtin.lsp_workspace_symbols({ query = "" })
end, { buffer = ev.buf, desc = "Workspace Symbols (browse, local fuzzy)" })
```

This is additive — `<leader>ws` (`lsp_dynamic_workspace_symbols`, per-keystroke server queries)
keeps working as-is. Its built-in `<C-Space>` → `actions.to_fuzzy_refine` hybrid (freeze the
current server results, then fuzzy-filter them locally) already works today without any server
change and remains useful independently of this document.

## Testing

First-slice cases in `WorkspaceTypeIndexTest`:

- `browseWorkspace_reactorOnly_excludesStaticTypes`
- `browseWorkspace_sortedAlphabetically`
- `browseWorkspace_emptyIndex_returnsEmpty`

First-slice resolver-level coverage (`WorkspaceSymbolTest` or equivalent):

- blank query returns reactor-owned entries instead of an empty list
- non-blank query behavior is unchanged (regression guard against accidentally routing through
  `browseWorkspace`)

Resolution stays sequential permanently (no follow-up parallel-resolution slice — see Technical
Design), so there is no parallel-correctness test to add.

Follow-up progress/cancellation coverage (mirroring existing `ReferenceProgressReporter` test patterns —
check that test class first before writing new fixtures, per the testing rules in `CLAUDE.md`):

- workspace-symbol browse sends `begin`/`report`/`end` in order when the client supports progress
- cancelling mid-browse stops the operation and the response completes exceptionally with
  `CancellationException`
- renaming `ReferenceProgressReporter` → `ProgressReporter` does not change find-references' or
  incoming-call-hierarchy progress *mechanics*.
  Their progress-bar *title text* is expected to change where the current shared
  "Finding references to %s" title is wrong, which is an intentional fix, not a regression.

## Related Work

- [EG-005](gaps/gaps.md) — CamelCase and infix workspace-symbol matching for non-blank queries.
  Deferred to M2, independent of this document.
- [Type Index](lathe-type-index.md) and [Reactor Type Index](lathe-reactor-type-index.md) — the
  underlying `WorkspaceTypeIndex` design this document builds on.
- [LSP Work-Done Progress](lathe-lsp-progress.md) — the original generic `ProgressReporter` design;
  superseded in practice by `ReferenceProgressReporter`, which this document reuses/generalizes
  instead of building the generic version from scratch.
- [Reference Search Reliability](../done/lathe-reference-search-reliability.md) — where
  `ReferenceProgressReporter` and its cancellation model were originally implemented.
