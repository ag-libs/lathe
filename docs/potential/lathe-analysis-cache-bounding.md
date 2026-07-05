# Lathe — Bounding the Source-Analysis Cache

**Status: deferred (potential).** The unbounded-retention *issue* is accepted (see the memory gap in
[gaps.md](../gaps/gaps.md)), but this shared-cache-with-eviction design is deferred: the OOM only
reproduced under an abusive synthetic load, and eviction pulls in recompile-on-miss, cross-thread
locking, and request-plumbing changes that are heavy for the real risk. A lighter mitigation
(heap-pressure warning + the M3 `LATHE_JVM_OPTS` heap knob) is being pursued first; revisit this
design only if a hard memory ceiling proves necessary.

## Goal

Bound the memory held by attributed source analyses so a large or bulk-access session cannot
exhaust the heap and take the whole server down, while keeping interactive features (hover,
definition, completion, and especially semantic tokens) fast.

## Symptom

Under sustained load the server terminates the whole process.
A fatal `Error` (heap exhaustion class) during a compile reaches
`CompilationWorker`'s worker task, which by design calls `processTerminator.accept(FATAL_EXIT_STATUS)`
(the "treat `Error` as fatal, restart clean" policy).
The result is a lost LSP session — all features gone until the client relaunches and re-warms.

## Evidence

Measured against a large private validation workspace (~250 files across ~20 modules), driven by an
automated request sweep:

- Retained (post-full-GC) heap grows ~linearly with the number of **open** files, ~29–31 MB per
  file, for both the open-mode compile and realistic member-access completion.
- Thread count stays bounded (~one worker per module), so threads are not the problem.
- The server died at ~210 open files on a ~16 GB ergonomic heap, during an ~18.7 s GC-thrashing
  compile — i.e. the retained per-open-file baseline plus transient search/compile allocation
  crossed the ceiling.

The dominant retained objects are all javac `Context` state pinned per open file:
`[B` (class-file bytes), `jdk.nio.zipfs.ZipFileSystem$IndexNode`, `com.sun.tools.javac` symbol/type/
name-table objects.

Two figures were investigated and set aside:
- An earlier "~106 MB/file" figure was an artifact of probing completion at package-qualified-name
  dots; realistic member-access completion is ~31 MB/file. A separate, narrower anomaly
  (package/qualified-name completion retaining ~3x) is noted as a follow-up, not addressed here.

## Root Cause

`SourceAnalysisSession` keeps a per-module `Map<String, CachedFileAnalysis> cache` that is
**unbounded**: an entry is added on every open/change compile and on completion, and is evicted
**only on `didClose`**.
So N open files retain N javac `Context`s (~31 MB each), one per open file.

Each entry is a full attributed `Context` because the compiler produces a fresh `Context`
(`JavacRunner.createTask` → new `JavacTask`) per compile, and `AttributedFileAnalysis`
(`Trees`/`Elements`/`Types`) pins it.
This cannot be reduced by sharing the file manager (it is already shared per module via
`ModuleSourceCompiler.fm`): javac symbols are per-`Context` by design and cannot be shared across
cached analyses.

Cache access is also inconsistent, which is how the risk hid:
- Most readers use `ensureAttributedAnalysis(uri, content, version)`, which **recompiles on miss**.
- `semanticTokens(uri, expectedVersion)` reads the cache **only** and returns `null` on a miss —
  no recompile. It works today solely because open files are never evicted; recovery after a
  version race relies on `DiagnosticPublisher.refreshTokensIfCurrent` → `client.refreshSemanticTokens()`
  re-driving the request after the next compile, not on recompile-on-miss.

## Non-Goals / Rejected Alternatives

- **Reuse one `Context`/`Symtab` per module (`JavacTaskPool`).** Would amortize the classpath
  universe, but `com.sun.tools.javac.api.JavacTaskPool` is an internal package. Importing
  `com.sun.tools.javac.*` is a hard prohibition. Rejected.
- **Reduce per-entry weight.** There is no distinct "heavy" analysis type — open-mode and
  reattribution produce the same `AttributedFileAnalysis` of the same size for the same content.
  Nothing to trim without the forbidden `Context` reuse.
- **Per-module cap.** A cap of N per module across M modules is really an N×M ceiling — not a bound.
- **Per-module maps + a shared `AtomicInteger` count for the cap.** A module can only safely evict
  its own map (other maps live on other threads), so the eviction victim is the active module's
  own LRU, not the global LRU. An idle module that filled the budget hoards it and starves the
  module currently being edited. Rejected on eviction correctness.
- **`assert` for the ownership invariant.** Disabled without `-ea`; no-op in production. Use an
  explicit fail-fast instead.

## Design

Introduce a single, shared, thread-safe `AnalysisCache` with a **global** LRU cap, and route
**every** cache read through a get-or-recompute path so eviction is safe for all features.

### `AnalysisCache` (new, `io.github.aglibs.lathe.server.analysis`)

- One instance, created at the workspace level and injected into every `SourceAnalysisSession`.
  Keyed by URI (globally unique). Each entry carries its owning `moduleKey`.
- Global access-ordered LRU (`LinkedHashMap(…, true)` + `removeEldestEntry`) with a global cap.
- Thread-safe via a single private lock object, used **consistently** as `synchronized (lock)`
  blocks (no `synchronized` methods, no `ReentrantLock` — none of its features are needed).
- The compile (`produce`) runs **outside** the lock.
- Ownership is enforced with a fail-fast `IllegalStateException` (`requireOwner`), not `assert`.

```java
final class AnalysisCache {

  private record Entry(String owner, CachedFileAnalysis value) {
    private Entry {
      ValidCheck.check().notBlank(owner, "owner").notNull(value, "value").validate();
    }
  }

  private final Object lock = new Object();
  private final int maxEntries;
  private final LinkedHashMap<String, Entry> entries;

  AnalysisCache(final int maxEntries) {
    this.maxEntries = maxEntries;
    this.entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<String, Entry> eldest) {
            return size() > maxEntries;
          }
        };
  }

  CachedFileAnalysis getOrRecompute(
      final String uri,
      final String moduleKey,
      final String content,
      final Supplier<CachedFileAnalysis> produce) {
    synchronized (lock) {
      final var entry = entries.get(uri);
      if (entry != null) {
        requireOwner(uri, moduleKey, entry);
        if (entry.value().content().equals(content)) {
          return entry.value();
        }
      }
    }

    // Compiles outside the lock: safe because each URI is owned by one single-threaded module
    // worker, so no other caller can recompute or mutate this URI's entry during this gap.
    final var fresh = produce.get();

    synchronized (lock) {
      entries.put(uri, new Entry(moduleKey, fresh));
      return fresh;
    }
  }

  void store(final String uri, final String moduleKey, final CachedFileAnalysis value) {
    synchronized (lock) {
      requireOwner(uri, moduleKey, entries.get(uri));
      entries.put(uri, new Entry(moduleKey, value));
    }
  }

  CachedFileAnalysis peek(final String uri, final String moduleKey) {
    synchronized (lock) {
      final var entry = entries.get(uri);
      if (entry == null) {
        return null;
      }

      requireOwner(uri, moduleKey, entry);
      return entry.value();
    }
  }

  List<CachedFileAnalysis> valuesForModule(final String moduleKey) {
    synchronized (lock) {
      return entries.values().stream()
          .filter(entry -> entry.owner().equals(moduleKey))
          .map(Entry::value)
          .toList();
    }
  }

  void remove(final String uri) {
    synchronized (lock) {
      entries.remove(uri);
    }
  }

  // Session close must drop only its own module's entries, never the shared map.
  void removeModule(final String moduleKey) {
    synchronized (lock) {
      entries.values().removeIf(entry -> entry.owner().equals(moduleKey));
    }
  }

  private static void requireOwner(final String uri, final String moduleKey, final Entry entry) {
    if (entry != null && !entry.owner().equals(moduleKey)) {
      throw new IllegalStateException(
          "cached analysis for %s is owned by module %s but was accessed as module %s"
              .formatted(uri, entry.owner(), moduleKey));
    }
  }
}
```

### Producer split in `SourceAnalysisSession`

The cache becomes the single owner of caching. Each (re)compile runs `compiler.compile(...)`
**exactly once** — there are two producer paths, and neither double-compiles:

- **Diagnostics compile** (`compile()`, on open/change) already needs both the diagnostics and the
  analysis. It runs the compiler once, **write-throughs** the analysis
  (`cache.store(uri, moduleKey, new CachedFileAnalysis(content, version, result.fileAnalysis()))`),
  and returns the diagnostics. It no longer caches via a raw `cache.put` side effect.
- **Feature reads / recompute-on-miss** use a pure producer (diagnostics not needed):

```java
private CachedFileAnalysis attribute(String uri, String content, int version, CompileMode mode) {
  return new CachedFileAnalysis(
      content, version, compiler.compile(uri, content, mode, ...).fileAnalysis());
}
```

  invoked as `cache.getOrRecompute(uri, moduleKey, content, () -> attribute(uri, content, version, OPEN))`.

### Reader migration

| Current | Becomes |
|---|---|
| `ensureAttributedAnalysis` (recompile-on-miss) | thin wrapper over `getOrRecompute` |
| `semanticTokens(uri, version)` (cache-only → `null` on miss) | `getOrRecompute(...).analysis().semanticTokens()` — gap closed by construction |
| `currentCache(uri, content)` | deleted (folded into `getOrRecompute`) |
| completion initial snapshot (`cache.get(uri)`) | `cache.peek(uri, moduleKey)` |
| completion fresh-analysis write | `cache.store(uri, moduleKey, ...)` |
| `cachedTypeEntries` iteration | `cache.valuesForModule(moduleKey)` (module-scoped so unsaved cross-module types are not offered) |
| `dropFromCache(uri)` | `cache.remove(uri)` |
| `close()` → **`cache.clear()`** | `cache.removeModule(moduleKey)` — **a shared cache must never be cleared by one session** |

The five reference/hierarchy/code-action features (`searchReferences`, `searchIncomingCalls`,
`locateReferences`, `outgoingCalls`, `codeAction`) change behavior (recompile-on-miss) with **no code
edits**: they already call `ensureAttributedAnalysis`, which is reworked internally, and `moduleKey`
comes from a `SourceAnalysisSession` field rather than a new parameter.

### Ripple

`semanticTokens` needs the buffer content to recompute, so `content` is threaded through
`WorkspaceSession.semanticTokensFuture` (has `doc.content()`) → `CompilationWorker.semanticTokens`
→ `SourceAnalysisSession.semanticTokens`.

### Cap value: 64

A single global `MAX_CACHED_ANALYSES = 64`, passed to the `AnalysisCache` constructor.
Sized against the binding constraint — the ~8 GB ergonomic heap on a 32 GB-RAM workstation
(≈25% of RAM) — and ~31 MB per entry (realistic open + member-access completion):

| Cap | Budget @ ~31 MB | Share of 8 GB heap | Share of 16 GB heap |
|---|---|---|---|
| **64** | ~2.0 GB | ~25 % | ~12 % |
| 100 | ~3.1 GB | ~39 % | ~19 % |
| 128 | ~4.0 GB | ~50 % | ~25 % |

64 leaves ~6 GB of the 8 GB heap for the type/candidate indexes, per-module file managers, and the
transient spikes of reference searches — the allocations that actually pushed the crash over the
ceiling. It comfortably covers a realistic working set (developers juggle ~10–40 buffers), and
recompile-on-miss (~250 ms on the module thread) makes the tail beyond 64 graceful rather than a
cliff. Being count-based, it also absorbs the occasional heavier entry (the package/qualified-name
completion anomaly, a separate follow-up) without blowing the budget.

Bump to 100 only if the target is reliably a ≥16 GB heap (≥64 GB RAM). Heap-scaling the cap
(`maxMemory × fraction / avgEntrySize`) is deliberately avoided for KISS — it adds complexity and
leans on a per-entry-size estimate that varies. Keep it a single named constant so it is trivial to
tune or make configurable later.

## Thread Model and Race Analysis

- Every javac object is created and **used** only on its owning module's single worker thread.
  Requests route by URI → module config → that one worker, so `getOrRecompute(uri, …)` for a URI is
  never concurrent (the load-bearing invariant).
- The shared cache only moves **references** across threads (`put`, eviction) under the lock; it
  never dereferences a foreign `Context`. `valuesForModule` filters to the caller's module so any
  dereference stays on the owner thread.
- `getOrRecompute`'s check-then-act gap is safe under the invariant: while the owner thread compiles
  in the gap it runs no other task for the URI, and no other thread owns it; a cross-thread eviction
  during the gap only drops a reference (harmless); all structural map ops are serialized by the
  lock; values are safely published under the lock and used only on the owner thread.
- The only residual is the invariant itself. If routing ever delivers a URI to two modules
  (overlapping source roots or a future bug), the gap would become a TOCTOU — but `requireOwner`
  turns that into an immediate, localized `IllegalStateException` (caught by the feature's
  `.exceptionally`, logged `SEVERE`, server stays up), never silent corruption.

## Correctness Invariants Gained

1. Bounded: every write goes through the cache → the global LRU cap is always enforced → no
   unbounded growth.
2. Safe eviction: every freshness-needing read recompiles on miss → evicting an open file never
   yields `null`/stale (closes the `semanticTokens` hole and the pre-existing version race).
3. One place to reason about caching and locking.

## Tests

- `AnalysisCache`: `getOrRecompute` miss invokes producer and stores; stale-content recompute;
  `store` beyond cap evicts the global LRU; access-order keeps hot entries; `valuesForModule`
  filters by owner; `removeModule` drops only that module's entries; `requireOwner` throws on
  cross-module access; a two-thread concurrency test driving two module keys (AssertJ assertions in
  the test, not runtime `assert`).
- Session: `semanticTokens_afterEviction_recompilesAndReturnsTokens` — the regression proving the
  strategy; `semanticTokens_versionMismatch_recompiles`.

## Files Touched

- **New:** `AnalysisCache` (+ test).
- **`SourceAnalysisSession`** — edited methods: constructor (take shared cache + `moduleKey`),
  `compile` (write-through + new pure `attribute()` producer, stop self-caching), `complete`
  (`peek`/`store`), `semanticTokens` (**+`content`**, recompile-on-miss), `dropFromCache`,
  `cachedTypeEntries` (`valuesForModule`), `ensureAttributedAnalysis` (over `getOrRecompute`),
  `resolve`, and `close` (**`clear()` → `removeModule(moduleKey)`** — the shared-cache catch);
  `currentCache` deleted. `searchReferences` / `searchIncomingCalls` / `locateReferences` /
  `outgoingCalls` / `codeAction` gain recompile-on-miss with no edits.
- **`CompilationWorker`** — `semanticTokens` signature gains `content`; module/external factories
  thread the shared `AnalysisCache` + a stable `moduleKey` into the session.
- **`WorkspaceSession`** — `semanticTokensFuture` passes `doc.content()`; constructs and injects the
  single `AnalysisCache`.

Roughly 11 edited methods + 1 new producer in `SourceAnalysisSession`, 2 signature changes across
the worker/session layers, plus the one-time cache construction/wiring.

## Rollout / Risk

- Behavior change: switching to an open file that fell out of the global LRU costs one recompile
  (~250 ms, on the module thread) for its first feature/tokens request, then it is hot again. At the
  chosen cap, normal sessions (≤ cap hot files) never evict a truly-open file.
- No public API or wire-format change; internal to `lathe-server`.
- Observability: log a `FINE` line on a recompute triggered by eviction (miss on a URI that was
  previously cached) so churn from an undersized cap is diagnosable via `LATHE_DEBUG=1`.
- The heap ceiling itself remains governed by JVM ergonomics / the planned `LATHE_JVM_OPTS`
  (see [lathe-launcher-jvm-opts.md](lathe-launcher-jvm-opts.md)); this design bounds growth so the
  ceiling is not reached under normal use.
