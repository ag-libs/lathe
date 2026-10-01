# Lathe — Interactive Request Priority & Reaction Coalescing

## Status

**Proposed.** Discovered while probing [OpenJDK support](lathe-openjdk-support.md) (2026-10-01): on a large
module (`java.base`, ~3400 sources) the editor felt "20× slower, recompiling constantly, completion takes
10–20s." Investigation showed the cost is **not** OpenJDK-specific and **not** a completion/codeAction bug —
it is a server-wide reaction model that lets **multi-second background recompiles block interactive
requests** on a single serial worker. It is invisible on normal Maven/Gradle projects only because their
compiles are ~30ms; a module whose compile is seconds makes it pathological.

This document is grounded in a live Neovim session against `java.base` and in reproduction probes on the
small `multi-module` invoker fixture.

## Symptom

- Completion / hover / codeAction take seconds-to-tens-of-seconds, intermittently, even though the feature
  computation itself is milliseconds (measured: completion `14ms`, `items=57`, from cache).
- The server logs a near-continuous stream of full `compile:open` passes (java.base: ~3s each), back-to-back,
  with few or no intervening edits; published diagnostic counts oscillate between two values.
- Heavy client request volume amplifies it (one session: 35,268 `codeAction` requests — the editor issues
  code actions on scroll/cursor movement — and 4,799 `didChange` events).

## What is NOT the cause (ruled out by probe)

- **codeAction does not re-attribute on unchanged content.** On the invoker project, 10 `codeAction`
  (and 10 hover, 10 completion) requests on unchanged content → **0 extra compiles**; all reuse the cache.
- **Completion's synthetic/baseline compile does not evict the cache.** Interleaved completion + codeAction
  on an unchanged buffer → **0 extra compiles** (`completeTransient` never writes the shared cache).
- **Perpetual staleness** (an earlier false lead) was a probe-setup artifact: a hand-crafted `.lathe` with
  only 2 of 3400+ compile-stamps made the 2s `[stale]` scan always report the module stale. Writing a full
  stamp set (what `lathe:sync` does) fixed it (`stale=0`). Real `sync` writes complete stamps, so this is not
  a product bug — but it confirms **complete stamps are load-bearing for usability**, not just freshness.

## Root causes (confirmed)

1. **The workspace-sync reaction recompiles *open* documents on external disk changes, uncoalesced.**
   `WorkspaceSession.reconcileChangedSources → afterChangedBatch → refreshOpenDependents →
   scheduleOpenFile → compileAndPublish(openDoc, CompileMode.OPEN)`. **Reproduced:** with a file open and
   carrying an unsaved edit, touching a *sibling* source on disk triggered **one unnecessary recompile of the
   open document**. One cheap compile on the invoker project; **~3s on java.base, per external change, per
   open doc.** This reaction is the shipped in-process-sync feature
   ([In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md)) — correct in intent (keep open
   diagnostics fresh when a dependency changes) but too eager and uncoalesced when compiles are expensive.

2. **One serial analysis worker, no prioritization.** A multi-second *background* recompile blocks a
   millisecond-scale *interactive* request queued behind it. This is the dominant driver of the felt latency:
   a 14ms completion stuck behind a 3s background compile presents as a 3s (or, when several stack, 10–20s)
   completion.

3. **Single-entry, content-keyed analysis cache.** `SourceAnalysisSession.cache` is `Map<uri,
   CachedFileAnalysis>` (one entry per file), and every compile does `cache.put(uri, …)`. A background compile
   of **disk** content can evict the **open buffer**'s analysis; the next interactive request for the buffer
   content then misses and recompiles. Secondary to (1)/(2), but it is what produces the two-content
   (buffer-vs-disk) diagnostic oscillation seen in the log.

4. **Client over-eagerness (amplifier, not root).** The editor issues `codeAction` on scroll/cursor even when
   nothing is shown. Harmless when it hits the cache (~1ms); expensive only when it lands on a miss that
   triggers a 3s compile. Addressable in the client independently.

## Proposed design (ranked)

- **A. Prioritize interactive requests; make background recompiles cancellable (highest value).**
  Interactive requests (completion, hover, signatureHelp, codeAction, definition) run ahead of background
  reaction recompiles, and an in-flight background recompile is cancelled when a newer edit or an interactive
  request for the same document arrives. Keeps the editor responsive even when a single compile is seconds.
  This directly targets root cause (2).

- **B. Coalesce / debounce the reconcile's open-doc recompiles.** Batch external changes and refresh open
  dependents **once after a quiet window**, not per change and not per 2s tick. Targets root cause (1).

- **C. Don't let background/disk compiles evict the live buffer's analysis.** Key the cache by
  `(uri, content)` with a small bound, or protect the open-document entry, so interactive requests on the
  buffer keep hitting the cache across a background disk-content compile. Targets root cause (3).

- **D. Client-side throttle.** Stop issuing `codeAction` on scroll / idle cursor; debounce to an explicit
  request or a short post-settle delay. Independent of the server; removes most of the request volume.

- **E. Reduce the compile cost itself (separate track).** The deeper lever for large modules is the
  [OpenJDK](lathe-openjdk-support.md) Slice-2 performance work (compiled-deps model measured at ~0.7s vs ~3s,
  or a warm/persistent symbol cache). Out of scope here; this document is about not letting *any* expensive
  compile block interactive work.

## Scope

- `WorkspaceSession` — reaction scheduling, coalescing, and request/worker prioritization + cancellation.
- `SourceAnalysisSession` — cache keying so background compiles cannot evict the live buffer's analysis.
- The analysis worker / executor — priority lanes and cancellation of superseded background work.

**Not** OpenJDK-specific: every project benefits (correctness and responsiveness); large modules benefit most.

## Risks & non-goals

- **Correctness must not regress:** an open document must still pick up a dependency's on-disk change — the
  reaction is **debounced/deprioritized/cancellable, never dropped**. A superseded background recompile is
  only cancelled when a newer one (or a newer edit) will replace it.
- **No busy-looping:** coalescing must converge (reconcile must update stamps so a change is not re-detected
  as stale on the next tick).
- **Non-goal:** reducing the per-compile cost (that is track E / Slice-2).
- **Non-goal:** parallelizing javac itself.

## Test plan (extends the reproduction probes)

Against the `multi-module` invoker fixture, with an artificially slowed compile where needed to expose
ordering:

1. **Reaction coalescing:** N external sibling changes in quick succession → open-document recompiles
   coalesce to **1** (not N).
2. **Interactive priority:** an interactive completion returns within a small bound **while a background
   recompile is in flight** (background work does not block it).
3. **Cache stability:** the open buffer's analysis survives a background disk-content compile — a subsequent
   completion/codeAction on the buffer is a **cache hit (0 extra compiles)**.
4. **Convergence:** after one external change and no further activity, the module settles to **0 ongoing
   compiles** (no reconcile busy-loop).

## Open decisions

1. **Priority vs. cancellation (or both).** Is a simple two-lane worker (interactive ahead of background)
   enough, or do we also need to cancel in-flight background compiles on a newer interactive request?
2. **Cache bound.** `(uri, content)` with an LRU of small N, vs. "protect the open-doc entry only." The
   latter is cheaper; the former also helps rapid undo/redo.
3. **Where to coalesce.** In the existing reconcile tick, or a dedicated debounced queue keyed by module.
4. **Client throttle ownership.** Fix per-editor (Neovim client config) vs. advertise/limit server-side
   codeAction so any client benefits.
