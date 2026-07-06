# Lathe — Bounding the Source-Analysis Cache

**Status: deferred (potential); approach decided.**
The unbounded-retention *issue* is accepted (EG-040 in [gaps.md](../gaps/gaps.md)) and mitigated by the shipped open-file-count warning;
the hard cap remains deferred until real users hit OOM.
This revision replaces the earlier shared-`AnalysisCache` design
(see [Rejected Alternatives](#non-goals--rejected-alternatives))
with an event-loop LRU that delegates eviction to the owning module worker,
after review showed the shared cache fought the server's thread-confinement model.
Step 0 below fixes a live retention leak and should ship independently of the deferral.

## Goal

Bound the memory held by attributed source analyses
so a large or bulk-access session cannot exhaust the heap and take the whole server down,
while keeping interactive features (hover, definition, completion, and especially semantic tokens) fast.

## Symptom

Under sustained load the server terminates the whole process.
A fatal `Error` (heap exhaustion class) during a compile reaches
`CompilationWorker`'s worker task, which by design calls `processTerminator.accept(FATAL_EXIT_STATUS)`
(the "treat `Error` as fatal, restart clean" policy).
The result is a lost LSP session — all features gone until the client relaunches and re-warms.

## Evidence

Measured against a large private validation workspace (~250 files across ~20 modules),
driven by an automated request sweep:

- Retained (post-full-GC) heap grows ~linearly with the number of **open** files,
  ~29–31 MB per file, for both the open-mode compile and realistic member-access completion.
- Thread count stays bounded (~one worker per module), so threads are not the problem.
- The server died at ~210 open files on a ~16 GB ergonomic heap,
  during an ~18.7 s GC-thrashing compile —
  i.e. the retained per-open-file baseline plus transient search/compile allocation crossed the ceiling.

The dominant retained objects are all javac `Context` state pinned per open file:
`[B` (class-file bytes), `jdk.nio.zipfs.ZipFileSystem$IndexNode`,
`com.sun.tools.javac` symbol/type/name-table objects.

Two figures were investigated and set aside:
an earlier "~106 MB/file" figure was an artifact of probing completion at package-qualified-name dots;
realistic member-access completion is ~31 MB/file.
A separate, narrower anomaly (package/qualified-name completion retaining ~3x)
is noted as a follow-up, not addressed here.

## Root Cause

`SourceAnalysisSession` keeps a per-module `Map<String, CachedFileAnalysis> cache` that is **unbounded**:
an entry is added on every open/change compile and on completion, and is evicted **only on `didClose`**.
So N open files retain N javac `Context`s (~31 MB each), one per open file.

Each entry is a full attributed `Context` because the compiler produces a fresh `Context`
(`JavacRunner.createTask` → new `JavacTask`) per compile,
and `AttributedFileAnalysis` (`Trees`/`Elements`/`Types`) pins it.
This cannot be reduced by sharing the file manager
(it is already shared per module via `ModuleSourceCompiler.fm`):
javac symbols are per-`Context` by design and cannot be shared across cached analyses.

Two secondary defects compound the risk:

- **Cache-only readers.** Most readers use `ensureAttributedAnalysis(uri, content, version)`,
  which recompiles on miss.
  But `resolve()` (hover, definition, declaration, signature help, hierarchies)
  and `semanticTokens(uri, expectedVersion)` read the cache **only** and return `null` on a miss.
  They work today solely because open files are never evicted;
  they also go dark for feature requests arriving in the debounce window after a `didChange`
  (new content, stale cache entry).
- **Disk-candidate retention leak (live bug, not gated on eviction).**
  `WorkspaceSession.methodImplementationFuture` calls the *caching* `methodImplementations`
  for **disk candidates** — files never opened in the editor (`version 0`, content read from disk).
  That path runs `ensureAttributedAnalysis` → `compile(OPEN)` → `cache.put`,
  and the only cache removal trigger is `didClose`, which never fires for a file that was never opened.
  Each "find implementations" sweep can therefore permanently pin ~31 MB per disk candidate it touches,
  outside EG-040's open-file accounting.
  The sibling search paths (`searchReferencesTransient`, `searchIncomingCallsTransient`)
  already use non-caching FAST compiles for disk candidates; `methodImplementations` is the only offender.

## Design

**Share the policy, not the storage.**

- **Storage stays where it is.** Each `SourceAnalysisSession` keeps its private `HashMap` cache,
  thread-confined to its module worker. No locks, no ownership metadata, no session API migration.
- **Policy lives on the server event loop.** `WorkspaceSession` runs on the single-threaded
  `ServerEventLoop`, already sees every LSP request, and already owns `DocumentRegistry`.
  It maintains a global access-ordered LRU of URIs — plain, unsynchronized, event-loop-confined.
- **Eviction is delegated to the owner.** On overflow the event loop calls the *existing*
  `WorkspaceModuleRegistry.dropFromAllCaches(uri)` —
  the same primitive `onClose` uses today —
  which posts `dropFromCache(uri)` onto each worker's own single-threaded executor.
  The drop executes on the owner thread, serialized with every other use of that entry.
  javac objects never cross a thread, not even as references.

### Step 0 — fix the disk-candidate retention leak (ship independently)

Add a transient variant of `methodImplementations`
(FAST compile, no cache write — mirroring `searchReferencesTransient`),
and route `methodImplementationFuture`'s disk-candidate branch through it.
Open documents keep the caching path.

This is a standalone bug fix, and it restores the invariant the rest of this design depends on:
**cache keys ⊆ open documents.**

### Phase A — recompile-on-miss for the two cache-only readers

- `resolve()` switches from `currentCache` to `ensureAttributedAnalysis`,
  which requires adding `version` to `SourceFeatureRequest`
  (every construction site in `WorkspaceSession` already holds `doc.version()`).
  All resolve-based features gain recompile-on-miss with no per-feature edits:
  hover, definition, declaration, signature help, `resolveTarget`, `resolveContractTarget`,
  type implementations, and both hierarchy prepares.
- `semanticTokens` gains `content` and goes through `ensureAttributedAnalysis`
  instead of returning `null` on a miss or version mismatch.
  `WorkspaceSession.semanticTokensFuture` already holds the `OpenDocument` (via `openDocFeature`)
  and passes `doc.content()`; `CompilationWorker.semanticTokens` threads it through.
- Completion is already miss-safe: `CompletionEngine` reattributes when `req.cached()` is null.
- The `ensureAttributedAnalysis`-based features
  (references, incoming calls, method implementations, outgoing calls, code actions)
  are already miss-safe.

After Phase A **every** reader survives eviction.
Phase A is independently valuable even without the cap:
it also fixes features returning `null` during the post-change debounce window.

### Phase B — event-loop LRU with delegated eviction

A small event-loop-confined class (`AnalysisLru`, `io.github.aglibs.lathe.server`):
an access-ordered `LinkedHashMap` keyed by URI with `touch(uri)` (returns the evicted URI or `null`)
and `remove(uri)`.
No locks — it is only ever called from the event loop, like `DocumentRegistry`.

`WorkspaceSession` touches the LRU at **every event-loop site that hands an open document's
content to a worker** — the load-bearing rule, since (after Step 0) every session cache write
originates from exactly one of these sites:

| Touch site | Covers |
|---|---|
| `submitCompile` | open/change/save-triggered compiles, post-save module rewarms |
| `openDocFeature` | all per-document features: hover, definition, completion, code actions, semantic tokens, … |
| open-document sweep enumerations (`searchFutures`, incoming-calls equivalent) | reference / call-hierarchy searches over open documents |

On `touch` overflow: `workspace.dropFromAllCaches(evictedUri)`.
On `onClose`: `lru.remove(uri)` (the existing `dropFromAllCaches` call already handles the cache).
The evicted document **stays open** in `DocumentRegistry`;
its next tracked request recompiles (~250 ms on the module thread) and re-enters the LRU.

A missed touch site would mean an entry that can be re-created after eviction without being re-tracked —
when adding a new worker call site that passes open-document content, add it to the touch set.

### Behavior under bulk sweeps

The OOM scenario was ~210 open files plus back-to-back reference searches.
Per worker, the event loop posts search tasks and (as the LRU overflows) drop tasks
into the same single queue, in order:
by the time the sweep enumeration has touched entry N,
the drop for entry N−cap is already queued behind at most ~cap intervening searches.
Per-worker retained entries therefore hover around the cap plus in-flight work —
the sweep self-regulates instead of accumulating all 210 analyses.

One accepted side effect: a global sweep floods the LRU,
so the user's hot buffers may be evicted and cost one recompile on next use.
(The superseded shared-cache design had the identical property —
its `getOrRecompute` promoted sweep entries in the same global LRU.)

## Cap value: 64

A single global `MAX_CACHED_ANALYSES = 64` on the `AnalysisLru`.
Sized against the binding constraint —
the ~8 GB ergonomic heap on a 32 GB-RAM workstation (≈25% of RAM) —
and ~31 MB per entry (realistic open + member-access completion):

| Cap | Budget @ ~31 MB | Share of 8 GB heap | Share of 16 GB heap |
|---|---|---|---|
| **64** | ~2.0 GB | ~25 % | ~12 % |
| 100 | ~3.1 GB | ~39 % | ~19 % |
| 128 | ~4.0 GB | ~50 % | ~25 % |

64 leaves ~6 GB of the 8 GB heap for the type/candidate indexes, per-module file managers,
and the transient spikes of reference searches —
the allocations that actually pushed the crash over the ceiling.
It comfortably covers a realistic working set (developers juggle ~10–40 buffers),
and recompile-on-miss makes the tail beyond 64 graceful rather than a cliff.
Being count-based, it also absorbs the occasional heavier entry
(the package/qualified-name completion anomaly, a separate follow-up) without blowing the budget.

Bump to 100 only if the target is reliably a ≥16 GB heap (≥64 GB RAM).
Heap-scaling the cap (`maxMemory × fraction / avgEntrySize`) is deliberately avoided for KISS —
it adds complexity and leans on a per-entry-size estimate that varies.
Keep it a single named constant so it is trivial to tune or make configurable later.

## Thread Model and Race Analysis

Much shorter than in the superseded design, because nothing is shared:

- The LRU is confined to the event-loop thread; `DocumentRegistry` already relies on the same confinement.
- Session caches stay confined to their worker threads; drops execute on the owner's queue,
  serialized with every read and write of that entry.
- The cap is **soft**: drops are asynchronous, so retention can transiently exceed the cap
  by the per-worker queue lag (see the sweep analysis above) — a few entries, ~31 MB each,
  against a ~2 GB budget with ~6 GB headroom.
- Every race degrades to one extra recompile, never a wrong or missing answer (given Phase A):
  a drop landing after a fresh re-cache discards a fresh entry;
  a touch arriving after the eviction decision drops a recently-used entry.
  Both cost ~250 ms once.

## Non-Goals / Rejected Alternatives

- **Reuse one `Context`/`Symtab` per module (`JavacTaskPool`).** Would amortize the classpath
  universe, but `com.sun.tools.javac.api.JavacTaskPool` is an internal package.
  Importing `com.sun.tools.javac.*` is a hard prohibition. Rejected.
- **Reduce per-entry weight.** There is no distinct "heavy" analysis type —
  open-mode and reattribution produce the same `AttributedFileAnalysis` of the same size
  for the same content. Nothing to trim without the forbidden `Context` reuse.
- **Per-module cap.** A cap of N per module across M modules is really an N×M ceiling — not a bound.
- **Shared `AnalysisCache` with a global lock (the previous revision of this design).**
  One shared map holding every module's analyses, guarded by a single lock,
  with per-entry ownership tags, a `requireOwner` fail-fast, `valuesForModule`/`removeModule`
  scans, a compile-outside-the-lock gap resting on the URI→module routing invariant,
  and an ~11-method migration of `SourceAnalysisSession`.
  Rejected: it shares *storage* when only the *policy* needs sharing,
  moves thread-confined javac object references into cross-thread state,
  and puts a global lock on every module's hot path
  (including a full-map scan under the lock during completion).
- **Shared eviction ledger (URI → evictor callback behind its own lock).**
  An intermediate shape: per-session storage plus a locked global LRU ledger the sessions report into.
  Superseded by the event-loop LRU — every policy input already serializes on the event loop,
  so the ledger's lock, its `touched()` calls inside the sessions,
  and the worker→session evictor wiring are all unnecessary.
- **`SoftReference`-held analyses.** GC-pressure-driven eviction with no code —
  but eviction then happens only at the heap ceiling, exactly where the measured 18.7 s
  GC-thrash lives, with no LRU ordering and no observability. Rejected.
- **`MemoryMXBean` panic-drop (clear all caches on a heap threshold).**
  No per-touch bookkeeping, but it thrashes under sustained bulk load
  (full drop, full re-warm, repeat) and the threshold is GC-implementation-dependent.
  Rejected in favor of a smooth LRU; Phase A would be required either way.

## Tests

- **Step 0:** `methodImplementations_diskCandidate_doesNotCacheAnalysis`
  (transient path leaves no entry behind), plus the positive case
  `methodImplementations_openDocument_cachesAnalysis`.
- **`AnalysisLru`:** touch below cap evicts nothing; touch beyond cap returns the eldest;
  re-touch promotes (access order); `remove` untracks.
- **`WorkspaceSession`:** overflow posts `dropFromAllCaches` for the eldest URI;
  a feature request on an evicted-but-open file still answers (recompile), verified with
  `Mockito.verify(client, timeout(N))` per the async-test rule.
- **`SourceAnalysisSession`:** `semanticTokens_afterEviction_recompilesAndReturnsTokens` —
  the regression proving the strategy; `semanticTokens_versionMismatch_recompiles`;
  `hover_afterEviction_recompiles`.

## Files Touched

- **New:** `AnalysisLru` (+ test) — small, event-loop-confined, no locks.
- **`SourceAnalysisSession`** — `resolve()` over `ensureAttributedAnalysis`;
  `semanticTokens` gains `content` and recompiles on miss;
  `methodImplementations` split into caching/transient variants (Step 0).
  **The cache map itself is untouched.**
- **`SourceFeatureRequest`** — gains `version`.
- **`CompilationWorker`** — `semanticTokens` signature gains `content`;
  transient `methodImplementations` passthrough.
- **`WorkspaceSession`** — constructs the `AnalysisLru`;
  touches at `submitCompile`, `openDocFeature`, and the sweep enumerations;
  overflow → `dropFromAllCaches`; `onClose` → `lru.remove`;
  `semanticTokensFuture` passes `doc.content()`;
  `methodImplementationFuture` disk branch → transient variant.
- **Unchanged:** cache storage and its single-threaded access, `WorkspaceModuleRegistry`
  (`dropFromAllCaches` reused as-is), session `close()`, all locking (there is none anywhere).

## Rollout / Risk

- Step 0 is a live-leak bug fix and ships first, independent of the deferral.
- Phase A is independently valuable (features no longer go dark during the debounce window
  or after a version race) and carries the only signature ripple (`version`, `content`).
- Phase B behavior change: switching to an open file that fell out of the LRU costs one
  recompile (~250 ms, on the module thread), then it is hot again.
  At cap 64, normal sessions (≤ cap hot files) never evict a truly-hot file.
- No public API or wire-format change; internal to `lathe-server`.
- Observability: `FINE` log on eviction at the event loop (`[evict] uri open=N`)
  and on a recompute triggered by a miss in the session,
  so churn from an undersized cap is diagnosable via `LATHE_DEBUG=1`.
- The heap ceiling itself remains governed by JVM ergonomics / the planned `LATHE_JVM_OPTS`
  (see [lathe-launcher-jvm-opts.md](../planned/lathe-launcher-jvm-opts.md));
  this design bounds growth so the ceiling is not reached under normal use.
