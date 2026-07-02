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
is confusing (why 500 and not 200 or 2,000?) and cost is addressed directly instead, via parallel
resolution (see Technical Design).

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
- **`WorkspaceSymbol` lazy-resolve (LSP 3.17).** Not attempted here: cost is addressed by
  parallelizing `declarationRange` resolution instead (see Technical Design). This does not fully
  eliminate the cost at large-reactor scale — see the Known Limitation in Performance Model.
  Lazy-resolve remains the fallback if that residual cost turns out to be unacceptable in practice.

## Technical Design

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

### Parallel declaration-range resolution

The result-count cap was the original way to bound `declarationRange`'s per-entry
`SourceParser.parseFile` cost. Removing it means that cost has to be addressed directly instead.

The existing `resolve` implementation shares one `SourceParser` (and its one
`StandardJavaFileManager`) across every entry via a single `try (var parser = new SourceParser())`
wrapping a sequential `.stream().map(...)`. `StandardJavaFileManager` is not documented as safe for
concurrent use from multiple threads, so switching that `.stream()` to `.parallelStream()` as-is
would share that one file manager across threads — a real correctness bug, not just a performance
change.

The fix is to stop sharing the file manager instead of pooling it. Constructing a fresh
`StandardJavaFileManager` was measured at ~0.042ms — negligible next to the ~0.5ms/file parse cost
it is used for — so each parallel task can safely own a short-lived `SourceParser` instead of
reusing one across threads:

```java
return typeIndex.search(query, SEARCH_LIMIT).parallelStream() // or browseWorkspace() for blank query
    .map(entry -> {
      try (var parser = new SourceParser()) {
        return toSymbolInformation(entry, sourceDirs, parser);
      }
    })
    .filter(Objects::nonNull)
    .toList();
```

This removes the cross-task shared state, so parallelizing is safe. It does not make the cost
disappear — see the Known Limitation below.

## Progress Reporting

Without a cap, worst case is a multi-second synchronous operation (see Performance Model) with no
feedback to the user — that is a bad experience on its own, independent of the raw cost. LSP
work-done progress addresses the *experience* (visible status, cancellable) without changing the
underlying cost, and is a natural complement to — not a substitute for — the parallel resolution
above.

### What already exists

A generic `ProgressReporter` was designed in [lathe-lsp-progress.md](lathe-lsp-progress.md)
(lambda-wrapper shape, `begin`/`report`/`end`) but was never built as a standalone class. Instead,
find-references shipped a concrete implementation, `ReferenceProgressReporter`/
`ReferenceProgressReporter.Task`, when it needed progress and cancellation for the same reason this
document does — an operation whose duration scales with workspace size. It is real, tested,
already-wired infrastructure — and despite the name, it is **already shared across four features**,
not reference-search-only. `WorkspaceSession` threads a `ReferenceProgressReporter.Task` through
`referencesFuture` (`:204`), `methodImplementationFuture` (`:680`, goto-implementation),
`outgoingCallsFuture` (`:701`), and `incomingCallsFuture` (`:725`) — find-references,
goto-implementation, and both call-hierarchy directions all already route through the same class.
Workspace-symbol browsing would be a fifth caller of an already-proven-generic mechanism, not the
second.

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
`"Finding references to %s".formatted(target)` — and all four existing callers go through this
same code path. That means **goto-implementation and both call-hierarchy directions already show a
progress bar titled "Finding references to X" today**, which is wrong for three of the four
features it's used by. This is a pre-existing cosmetic bug, not something introduced by this
document — but it is a concrete example of exactly the coupling that needs to be undone to add a
fifth caller with its own correct title (`"Loading workspace symbols"`) instead of a wrong one.

Workspace-symbol browsing also needs a simpler message than the existing
`completed/total/attributed/hits` format — `attributed`/`hits` are reference-search-specific
concepts (a candidate can be a text match without being attributed, etc.) that don't apply here.

Rather than write a second, near-duplicate reporter class, generalize the existing one:

- Rename `ReferenceProgressReporter` → `ProgressReporter` (it already isn't reference-only in
  practice).
- `Task.begin` takes the fully-composed title from the caller instead of formatting
  `"Finding references to %s"` internally — all five call sites (the four existing ones plus
  workspace-symbol) now pass their own title string. This also fixes the existing mislabeled-title
  bug for goto-implementation and call hierarchy as a side effect of the change this document
  needs anyway.
- Keep `attributed`/`hits` tracking in reference search's own call pattern (it can still call
  `advance` with its existing counters); workspace-symbol browsing calls a simpler
  `advance(completedDelta)` path that only needs `completed`/`total` for its message and
  percentage.

This is a shared-class change, not a new abstraction — four existing features already depend on
this class, so the generalization needs its own regression coverage to confirm it doesn't change
their existing progress behavior beyond the title-text fix.

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

Progress applies to the **blank-query browse path only** — the capped, ~50ms `search()` path for
non-blank queries stays fast enough that a progress bar would just flicker, matching the existing
"fast operations don't need progress" principle from `lathe-lsp-progress.md`. Inside the parallel
`browseWorkspace()` resolution loop, each completed entry calls `task.advance(1)`, and the loop
checks `cancelChecker.checkCanceled()` periodically so a user-cancelled browse actually stops
early instead of running to completion in the background.

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
without a numeric cap.

Parallel resolution (previous section) was measured against the same file set:

| threads | total time |
|---|---|
| 1  | 2,124ms |
| 4  | 1,411ms |
| 8  | 1,231ms |
| 16 | 1,996ms (worse than 8 — javac's parser has internal contention that caps scaling) |

### Known limitation

`LatheTextDocumentService.worker` is a single-threaded `ServerEventLoop` that serializes every LSP
request. Even with parallel resolution, a blank-query browse on a Helidon-scale reactor blocks
that one thread — and therefore every other in-flight request — for roughly **1.2 seconds** in the
best case (8 threads), not the sub-300ms a numeric cap would have guaranteed. Progress reporting
(above) makes this visible and cancellable instead of a silent freeze, but it does not shrink the
1.2s figure itself — cancelling still has to wait for the current in-flight parse to finish before
the `CancelChecker` is next observed. This is the accepted tradeoff for not having a confusing,
arbitrarily-chosen limit. If this turns out to be a real problem in practice (large reactors,
frequent picker use), the fallback options are, in order of preference: (1) move `workspace/symbol`
resolution off the single event-loop thread entirely, or (2) reintroduce `WorkspaceSymbol`
lazy-resolve so the initial response skips `declarationRange` altogether and only resolves it for
the entry the user actually selects. Both are out of scope for this document; they are the next
step if the residual cost proves unacceptable.

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

New cases in `WorkspaceTypeIndexTest`:

- `browseWorkspace_reactorOnly_excludesJdkAndDependencyTypes`
- `browseWorkspace_sortedAlphabetically`
- `browseWorkspace_emptyIndex_returnsEmpty`

Resolver-level coverage (`WorkspaceSymbolTest` or equivalent):

- blank query returns reactor-owned entries instead of an empty list
- non-blank query behavior is unchanged (regression guard against accidentally routing through
  `browseWorkspace`)
- `resolve_manyEntries_parallelResolutionProducesCompleteStableResults` — run `resolve` against a
  fixture with enough entries to force real parallelism (not just 1-2 elements where the JIT/JVM
  may run the parallel stream on a single thread in practice) and assert the full result set comes
  back correctly and deterministically across repeated runs. This is a regression guard against the
  exact shared-`SourceParser` bug this document's design specifically avoids.

Progress/cancellation coverage (mirroring existing `ReferenceProgressReporter` test patterns —
check that test class first before writing new fixtures, per the testing rules in `CLAUDE.md`):

- workspace-symbol browse sends `begin`/`report`/`end` in order when the client supports progress
- cancelling mid-browse stops the operation and the response completes exceptionally with
  `CancellationException`
- renaming `ReferenceProgressReporter` → `ProgressReporter` does not change find-references',
  goto-implementation's, or call-hierarchy's progress *mechanics* (regression run of their
  existing test suites) — their progress-bar *title text* is expected to change (from the shared
  "Finding references to %s" to each feature's own correct title), which is an intentional fix,
  not a regression

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
