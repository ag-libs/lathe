# Lathe — Batching

## Status

**Batch write, save coalescing, and batch analysis for the dependents refresh implemented**; batch analysis for open bursts and reload is not.
This document names the two batching primitives the server needs and maps every multi-file burst onto them.
[Rename Transactions](lathe-rename-transactions.md) is the first consumer: its commit uses the chained batch write, which reconcile now uses too.
Its as-built version needs no batch analysis (members are republished by the commit's dependents refresh), so batch analysis waits for the dependents refresh, open bursts, and reload.

## Problem

Many events reach the server in bursts that belong together, but the server compiles most of them one file at a time.
Each per-file compile costs a javac task (hundreds of milliseconds for a save compile), a publish, and — through the per-publish `workspace/semanticTokens/refresh` — a repaint of every visible editor window.

What is batched today:

- **External changes** (git, agents, code generators, a client writing files directly).
  Reconcile runs every 2 s, takes the stale sources whose mtime held across two ticks, and compiles them as **one FULL batch per source tree** (`compileChangedBatch`).
  A change that lands at once (a `git checkout`) becomes one batch per tree.
- **Cross-file resolution** of a single open file: the widen step compiles it with its siblings when it fails to resolve a type they declare.

What is not:

- **A trickle of changes** becomes many small batches, because a batch is "what settled this tick", not "the whole change".
  An equalsverifier session at 18:15 shows about 15 batches of 1–15 files over 25 s for one logical change.
- **Batch ordering.** Reconcile submits batches upstream-first, but each source tree has its own worker and compile admission allows one compile per CPU, so batches run in parallel.
  A downstream batch that compiles before its upstream batch has written the mirror fails, stays unstamped, and is retried on the next pass: an extra compile and ~2 s of delay, never a wrong mirror.
- **Saves.** Every `didSave` runs its own FULL compile; the drain only coalesces the dependents refresh that follows.
- **The dependents refresh** re-analyzes **every open file** in the downstream modules, one `OPEN` compile per file, whether or not the file uses anything that changed.
  In a 108-file rename log it accounts for most of the compile activity (~680 `[dependents]` entries).
- **Opening many documents** (a rename opening every touched file, a restored editor session) and **reloading after a sync** analyze each open document separately, cycling the 100-entry analysis cache.

## Goal

Two primitives that every burst uses, so the cost of a burst grows with the number of source trees it touches rather than the number of files:

1. **Batch write** — persist many sources into the mirror, one compile per source tree, in dependency order.
2. **Batch analysis** — analyze many open documents in memory, one compile per source tree, publishing each document's diagnostics once.

Both coalesce by **load, not by timers**: work that arrives while its tree's worker is busy joins the next batch, so a lone event is handled at once and a burst forms batches as large as the backlog.

## Non-goals

- Changing how a single interactive edit is analyzed: the per-keystroke `FAST` path keeps its own priority and latency.
- Raising the analysis cache cap (it would not scale to large projects).
- Batching across reactor boundaries or for non-reactor (external) files.

## Design

### Batch write

The existing `compileChangedBatch` — one FULL compile over a tree's changed set, then stamps, prune, and the reactor index shard — with one addition: **chaining**.
A tree's batch starts only after the batches of every upstream tree it depends on have completed (the main tree counts as upstream of its module's test tree); independent trees still run in parallel under compile admission.

Callers:

| Caller | Members of the batch |
|---|---|
| Reconcile (external changes) | the stable stale sources of the pass, as today |
| Rename transaction commit | the transaction's members |
| Save coalescing | the saves queued while the tree's worker was busy |

**Save coalescing.**
A save whose tree has no save compile in flight compiles immediately, as today.
A save that arrives while one is in flight joins the tree's **pending save set**; when the in-flight compile completes, the whole set compiles as one batch write.
A lone save never waits, a sequential `:wa` forms batches of whatever accumulated during the previous compile, and a near-simultaneous Save All (VS Code) collapses into one or two batches per tree.
The existing per-save bookkeeping (the in-flight `saving` map that reconcile respects, the dependents drain) carries over per batch.

**Fewer small reconcile batches.**
When a pass finds sources still settling (some stale files not yet stable), it may hold the stable ones for one more tick instead of compiling them now, so a trickle forms fewer, larger batches.
Bounded to one extra tick, so external changes are never delayed by more than ~2 s; adopted only if a measurement shows the fragmentation matters once save coalescing and the rename transaction exist.

### Batch analysis

One in-memory compile per source tree over several open documents, publishing each target's diagnostics:

- **Targets** — the open documents to analyze in that tree.
- **Extra source inputs** — changed files from upstream trees that are not yet in the mirror (a rename's unsaved members), compiled for resolution only and not reported.
  Empty for callers whose upstream changes are already written.
- **Per-target results** — a target with a syntax error reports its own diagnostics and does not suppress the others.

This extends `diagnoseInBatch`, which today reports a single target over sibling sources of one module.

Callers:

| Caller | Targets |
|---|---|
| Dependents refresh | the open dependents of the changed modules, per downstream tree |
| Rename transaction apply | the transaction's members, with upstream members as extra inputs |
| Open bursts | `didOpen`s queued while the tree's worker was busy |
| Reload after a sync | every open document |

Rules that keep batch analysis from hurting interactive use:

- **Background only.** It runs on the background path, is cancellable, and yields to the `FAST` path: a keystroke never waits behind a batch.
  An edit to a target while its batch runs cancels that target's result; the edit's own `FAST` compile publishes instead.
- **Capped.** At most 50 targets per javac task; a larger set is split into chunks per tree.
- **Diagnostics, not trees.** A batch publishes every target's diagnostics but keeps in the analysis cache only the trees of targets that are visible or have a pending request.
  Caching every target would move the eviction thrash, not remove it.
- **One token refresh per batch**, not per target.

### Narrowing the dependents refresh

Separate from batching, and cheaper than it: the refresh need not touch an open dependent that references nothing that changed.
The server already keeps a token-to-file index for reference search (`ReferenceCandidateIndex`: every identifier and import spelling, mapped to the files containing it).
A refresh limited to open files containing the simple name of a type declared in the changed sources would skip most of a large set of open buffers; a token match over-approximates, which is safe.
For a type rename, the members already cover every referencing file, so the rename commit's refresh would usually find nothing left to do.

Token matching alone misses **transitive** effects: when `B` changes and `C extends A extends B`, `C` may never spell `B` yet inherit a new abstract method from it.
Narrowing is therefore sound only with a closure over the changed types' subtypes (the reactor type index knows supertypes), or by limiting it to changes that cannot propagate that way — see Open questions.

### Semantic-token refresh

Every primitive sends **one** `workspace/semanticTokens/refresh` when its batch completes, instead of one per publish.
Rename Transactions' slice 0 measures whether per-publish refreshes are what slow an editor's sequential saves; if so, a server-wide "one refresh when the compile queue drains" stopgap ships first and the primitives inherit it.

## Bursts and how they map

| Burst | Today | With the primitives |
|---|---|---|
| `git checkout` / rebase / stash | reconcile, one batch per tree | + chaining |
| Agent or tool writing files one by one | reconcile, many small batches | + chaining; optionally fewer small batches |
| MCP `rename_symbol` | reconcile; over 50 files defers to a sync | rename commit (batch write) |
| Editor rename + save all | per-file open, edit, and save compiles | rename transaction: batch analysis on apply, batch write on commit |
| Hand multi-file edit + save (`:cdo … \| update`, search-and-replace + Save All) | one FULL compile per save | save coalescing |
| Dependents refresh after any of the above | one `OPEN` compile per open downstream file | narrowing + batch analysis |
| Editor session restore, many tabs opened | one `OPEN` compile per document | batch analysis of queued opens |
| Reload after a sync | one compile per open document | batch analysis |

## Order

1. **Rename Transactions slice 0** — the save-pacing measurement and, if confirmed, one token refresh per drained queue.
   *Measured:* with per-member compiles and publishes suppressed, Neovim's `:wa` over 108 files fell from ~59 s to ~6 s; one token refresh per drained queue is still open.
2. **Rename Transactions slice 1** — chained batch writes (reconcile benefits at once) and the engine-applied commit. *Done.*
3. **Save coalescing** — small: it reuses the batch write and covers Save All in every editor. *Done:* a save arriving while its tree has a save compile running is queued; when that compile ends, the queued files compile as one batch write, started before the running compile stops counting as in flight so the dependents refresh still runs once; that refresh republishes them.
4. **Narrowing the dependents refresh** — independent, and removes most dependents work before any batch analysis exists; small only if open question 1 resolves to a simple closure.
5. **Rename Transactions slice 2** — the editor-applied transaction. *Done*, without batch analysis (see its as-built notes).
6. **Batch analysis for the dependents refresh, open bursts, and reload.** *Done for the dependents refresh:* its open files are grouped per source tree and analyzed in one OPEN-mode task per tree (at most 50 files), each cached and diagnosed as its own OPEN compile would be, with semantic tokens scanned; each result publishes if still current and the batch refreshes semantic tokens once. A lone file, or one outside a module, keeps its per-file compile; a failed batch falls back to it. Batched files resolve each other from their editor text rather than the mirror. On the equalsverifier rename: 116 -> 18 compiles, 108 -> 9 token refreshes, 8.3 s -> 5.5 s after the last save.
7. **Fewer small reconcile batches** — only if measurement still shows fragmentation.

## Tests

- **Batch write:** a downstream tree's batch starts only after its upstream batches complete, independent trees in parallel; a reconcile pass over an upstream and a downstream change compiles each once with no failed retry.
- **Save coalescing:** a lone save compiles immediately; saves arriving during an in-flight save compile form one batch for their tree; N back-to-back saves produce at most two batch compiles per tree and one dependents refresh; reconcile still skips files in a pending save set.
- **Batch analysis:** each target gets its own diagnostics, including when one target has a syntax error; extra source inputs resolve a type absent from the mirror and are not published; more than 50 targets are chunked; an edit to a target mid-batch publishes from its `FAST` compile, not the batch; only visible or requested targets stay cached.
- **Narrowing:** an open dependent that references no changed type is not refreshed; one that does is; one that reaches a changed type only through an inheritance chain is still refreshed.
- **Measurement:** the equalsverifier rename and a VS Code Save All of the same files, comparing compile counts, publishes, token refreshes, and time-to-quiet before and after each step.

## Open questions

1. **Soundness of narrowing.** Which closure makes token matching safe: the changed types' simple names plus all their reactor subtypes may be enough, or effects that reach a file through an unchanged intermediate type (an inherited member's changed signature, a constant's changed value) may force refreshing on any doubt.
   Narrowing ships only with tests for each such path, or not at all.
2. **Chunk size.** Whether 50 targets per javac task is right for memory and latency on large modules; measure on Helidon.
3. **Open-burst detection.** Whether queued `didOpen`s should batch immediately, or only the ones that are not visible (a visible document may deserve its own fast compile first).
