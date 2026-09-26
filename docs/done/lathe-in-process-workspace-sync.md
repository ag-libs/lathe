# Lathe — In-Process Workspace Sync (IDE-grade external-change reaction)

Status: done — MVP shipped (commits 3f9fd1cd deletion cleanup, 1845b3f1 updated-file reaction,
fb42c783 reconcile seam + e2e, 5adc2023 cleanup). Verified end-to-end against the multi-module invoker
workspace: change / add / delete / cross-module / open-dependent refresh handled in-process, POM and
bulk (>50) changes still prompt. Deferred follow-up: batch-FULL-per-module compile (per-file MVP shipped).
Supersedes the parked [In-Process External-Change Recompilation](../potential/lathe-external-change-recompilation.md)
and revises the source half of the shipped
[External-Change Detection → Sync Prompt](../done/lathe-external-change-detection.md):
source and resource changes are now handled **in-process**, and the Maven sync prompt is reserved for
**POM / module-structure** changes.

## Problem

Lathe compiles the open file live and otherwise trusts the `.lathe/` mirror that Maven produced at the
last `mvn process-test-classes`.
Anything that changes on disk outside the editor's save path — a branch switch, a `git pull`, or an AI
agent editing files — leaves the mirror and the reactor type index silently stale until the next Maven
build.

Today that staleness is only ever **detected and prompted**: the user is nudged to run Maven.
That has two concrete failures the complaint that started this work exposed:

- **Deletions are invisible and unfixable by the prompt.**
  The staleness scan walks only *existing* sources and reduces them to a `newestMtime` high-water mark
  (`WorkspaceSession.newestStaleInModule`), so a deleted source produces no signal and no prompt.
  Worse, even if the user syncs, `mvn process-test-classes` does **not** remove a class whose source was
  deleted (only `mvn clean` does), and `syncOutput` mirrors `target/classes` verbatim
  (`LatheCompiler.syncOutput` → `FileUtil.replaceDir`), so the ghost `.class` survives the sync and stays
  visible to every other class in the module.
- **Ordinary edits interrupt with a prompt** instead of just working, which is not how an IDE behaves.

## Decision — the model

One clean split, with a robust escape hatch:

- **POM / module-structure change → Maven sync prompt.**
  Dependencies, module add/remove, and non-javac generated sources genuinely need the real build; only
  Maven can produce them. This is the *one* thing the prompt now means.
- **Everything else — source/resource add, edit, delete → Lathe reacts in-process, silently.**
  No prompt. The mirror and indices are brought current without Maven.
- **A full Maven build is always available and always authoritative.**
  It is the "fix everything" reset the in-process path leans on: the reaction is deliberately
  *best-effort*, not provably complete, because the full build is the ground-truth fallback for any case
  it cannot reproduce (see *Where in-process cannot win*).

This is a **simpler** mental model than today's (which prompts for both POM and source changes): the
prompt now has a single meaning — "the build graph changed; only Maven knows how."

## The key realization — the reaction already exists

`afterModuleSave` (`WorkspaceSession`) already runs the entire reaction pipeline after a save:

```
deleteStaleClassOutputs    prune stale inner/anonymous classes for the compiled file
recordCompileStamp         write the per-source compile stamp (CompiledStamps.record)
scheduleAstRefresh         refresh the compiled file's own analysis
scheduleDownstreamOpenFiles recompile OPEN docs in downstream modules (via WorkspaceModuleGraph)
refreshReactorShard        rebuild the module's reactor type-index shard
```

An externally changed file needs exactly this pipeline.
The only missing inputs are a **FULL compile that reads from disk** (not from an editor buffer) and a
**trigger from the idle reconcile** (not from a save event).
We are not inventing a reaction; we are feeding the existing one a new source of work.

## Why cross-module is tractable (the parked design's blocker, dissolved)

The parked doc parked itself on multi-module change sets: a change in module `A` that also affects
module `B`'s *unchanged* files leaves those files compiled against the old `A`, a silently partial
result, and making it whole "is exactly what Maven does."

For **analysis correctness that reasoning does not hold**, and that is what makes this feasible now:

- Each `.class` in the mirror resolves by its **own** signature. A closed caller `B` that depends on
  `A`'s new API is **self-healing**: the moment it is opened, Lathe compiles it live against `A`'s fresh
  `.class` in the mirror and produces correct diagnostics. Its stale mirror `.class` is never consulted
  for `B`'s own diagnostics, and it exposes `B`'s unchanged signature to anything that depends on `B`.
- Therefore the reaction only has to compile the **changed set** (in dependency order) and re-run the
  **open** docs. Closed indirect dependents need nothing eager.

The residual — a stale closed-caller `.class` used at **execution** time (a test run linking the
mirror) — is an execution-freshness concern, not an analysis one, and is covered by the escape hatch
(*Where in-process cannot win*).

## Design

All of this runs inside the existing idle reconcile (`reconcileIfIdle`), gated by
`reactorBuildInProgress()` (the `LatheLock`) and executed on the compile worker, so it never races a
live Maven build or an editor save.

### 1. Detect the change set (no new I/O)

The stamp map is already loaded every tick by the stale scan; reuse it.
Per `ModuleSourceConfig`, load `CompiledStamps.load(moduleDir, sourceTree)` and classify:

- **updated** — file exists and `mtime > stamp`, or the file has **no** stamp (a new file).
- **deleted** — a stamp key whose file exists under **none** of the tree's source roots
  (the multi-root check avoids false positives for generated trees).

Group both sets by module.
`mtime` is sound for the same reason the POM fingerprint is: `lathe:sync` writes the mirror fresh, so an
unchanged source is older-or-equal to its stamp, and any external edit (git stamps touched files "now")
is strictly newer.

### 2. Skip open files

Drop any *updated* file that is an open document.
The editor buffer is authoritative and its save path already keeps the mirror fresh; compiling a disk
copy over unsaved edits would clobber live state.

### 3. Order affected modules topologically

Compile modules **upstream-first** so a downstream module sees its dependency's fresh
`.lathe/<up>/classes` (each module's `remappedClasspath()` already points into the mirror — only the
ordering is new).
The order is derivable from `WorkspaceModuleGraph` (it already builds the dependency edges); this adds a
topo-sort accessor.
Topo order is not strictly required for eventual analysis correctness (a wrong order simply fails and
retries next tick, converging), but it converges in a single pass and is free, so we do it.

### 4. React per module, in order

- **updated** → FULL compile from disk into the mirror (writes `.class` + generated sources), then the
  `afterModuleSave` follow-ups (record stamp, refresh shard, reschedule open dependents).
- **deleted** → `deleteClassOutputs(config, root.resolve(relKey))` (today dead code; it derives the
  class location from the path/name and works on a non-existent path), then prune the stamp key and
  `refreshReactorShard`.
- Resources → the existing `reconcileResources()` / `refreshResource` path (unchanged).

### 5. Republish open docs

After the mirror is current, re-run and republish the **open** documents in the affected and downstream
modules, reusing `scheduleDownstreamOpenFiles`, so their diagnostics reflect the fresh siblings.

## The one genuinely new compiler primitive

Everything above composes from methods that exist **except** a FULL compile that reads from disk.
Today `ModuleSourceCompiler.compile(uri, content, FULL)` takes an in-memory buffer.

- **MVP — per-file loop.** Read the file's bytes and call the existing `compile(uri, content, FULL)`,
  one file at a time. Zero new compiler code; annotation-processor behavior is per-file — **identical to
  what save does today**, so it introduces no new risk, and the escape hatch covers the AP-aggregation
  edge.
- **Optimization — batch FULL per module.** A new compile-core entry point: a module plus a list of
  source *paths* → one javac task that writes `.class`/generated sources and returns the union of
  `writtenBinaryNames` (the analysis-only `analyzeBatch` is the multi-file-task precedent). This closes
  same-module AP aggregation and amortizes javac's per-invocation cost.

We ship the per-file MVP first and add batching as a measured follow-up.
(This reverses the parked doc's "file-by-file rejected" call, on the grounds that the always-available
full build makes the AP-aggregation edge a fallback case rather than a correctness requirement — see
*Decisions for review*.)

## Where in-process cannot win → the escape hatch covers it

- **POM / dependency / module-structure** → the Maven sync prompt (by design).
- **Non-javac generated sources** (protobuf, OpenAPI, and other generate-sources plugins): Lathe runs
  javac + annotation processors only. A change to such a generator's input is not a `.java` file, so it
  never triggers the reaction, and the mirror's generated `.java` goes stale silently. Pre-existing gap,
  unchanged; a manual Maven build fixes it.
- **Cross-module AP aggregation**: a processor aggregating across modules in one pass stays
  Maven-bounded (per-module batching only closes the same-module case).
- **Execution freshness** (run/test): a closed caller's mirror `.class` may lag a changed upstream; if
  the run path links the mirror, a run could execute stale code. Whether run/test uses the mirror or
  Maven `target/` needs a confirming check; regardless, "run a Maven build before running" is the robust
  fallback. Treated as a separate slice.
- **Repeated in-process failure**: a file that cannot compile in-process (e.g. it needs a
  not-yet-generated dependency) must not retry forever. After a bounded number of attempts, surface the
  Maven sync suggestion — honest degradation to the escape hatch.

## Robustness

- **Inert on failure** — keep the old mirror, do not record the stamp, retry next tick (bounded by the
  failure counter above). A half-written agent file simply fails and is retried when it stabilizes.
- **Mid-write safety** — act only on files whose `mtime` is stable across two consecutive ticks, so a
  file being written is not compiled mid-flight.
- **Bulk cutoff** — a branch switch or large `git pull` can touch hundreds of files. Above a tunable
  threshold (or when the change set includes a `pom.xml`), the light regime **stops** and defers to the
  Maven sync prompt — a large or structural change is exactly the full build's job. Logged when it trips
  (no silent cap). Below the threshold, react in-process, capped per tick and drained across ticks.
- **Concurrency** — gated by `reactorBuildInProgress()` and run on the single compile worker, so it
  serializes with save-compiles and feature requests and never reads a half-written mirror.

## What gets removed / simplified

- The **source-staleness → sync-prompt** path (`checkSourceStaleness`, its prompt, and the
  `acknowledgedSourceMtime` high-water mark **for sources**) is replaced by the reaction.
- The **POM prompt** and its acknowledgement state (`WorkspaceWatcher` POM fingerprints,
  `onSyncPromptResponse`, `acknowledgePoms`) stay unchanged.
- Net: less prompt/ack state and a single-meaning prompt.

## Decisions for review

Baked into this plan as defaults; flag any to change before implementation:

1. **Bulk change set (branch switch)** → defer to the Maven prompt above a threshold, rather than
   grinding through it in-process. *(Chosen.)*
2. **Tier-1 compile** → per-file loop as the MVP (reuses the save path, no new compile primitive);
   batch-FULL-per-module as a later optimization. *(Chosen; note this reverses the parked doc.)*
3. **Mid-write safety** → require `mtime` stable across two ticks before compiling. *(Chosen.)*
4. **Run/test execution freshness** → out of scope for the first cut; rely on the manual-build escape
   hatch. *(Chosen.)*

## Testing and verification

- **Deletion** — a closed source deleted on disk: its primary and nested `.class` leave the mirror, its
  stamp is pruned, the type disappears from `workspace/symbol`, and a closed caller now fails to resolve
  it (the correct outcome). Positive and negative (non-Java file: nothing removed).
- **Update (single module)** — an external `Changed` on a closed file updates the mirror and the type
  index (e.g. an added type appears in `workspace/symbol`); an open file in the set is skipped; several
  edits in one module coalesce into one reaction pass, not N prompts.
- **Update (cross-module, topo order)** — an upstream API change plus a downstream *changed* file: after
  one reconcile pass, opening the downstream file yields correct diagnostics against the new API; a
  closed downstream caller self-heals on open.
- **New file** — a source with no stamp compiles and gains a stamp; no prompt.
- **Bulk cutoff** — a change set over the threshold defers to the Maven sync prompt instead of
  recompiling; logged.
- **Resource** — an external resource change lands in `.lathe/` (existing path, regression-guarded).
- **Concurrency** — reconciliation is suppressed while the reactor build lock is held.
- **End-to-end probe** against the `multi-module` invoker workspace (the method used for FR-015 and the
  save→`\ws` path): edit a closed `.java` on disk, then `sym`/`refs` to confirm the reaction reflected
  it; delete a closed `.java` and confirm it disappears; repeat for a resource.

Prefer the existing `WorkspaceSession` staleness unit tests for the scan/classify logic; reduce a real
fixture only where a full module build is essential.

## Related gaps and designs

- [In-Process External-Change Recompilation](../potential/lathe-external-change-recompilation.md) — the
  parked predecessor this supersedes; retains the R1 batch-compile, D2 startup-reconciliation, and
  live-watch analysis as archive.
- [External-Change Detection → Sync Prompt](../done/lathe-external-change-detection.md) — the shipped
  detect→prompt whose **source** half this revises (POM half retained).
- [Staleness via Compile Stamps](../done/lathe-staleness-compile-stamps.md) — the per-source stamp map
  this reaction reads (updates) and prunes (deletions); the enabler that makes deletion detection
  possible across restarts.
- [Sibling Recompilation](../done/lathe-sibling-recompilation.md) — the in-module dependent refresh;
  its open-file rescheduling is reused here.
- [Reactor Type Index](../planned/lathe-reactor-type-index.md) — the index the shard refresh keeps
  current.
- [Lightweight Watcher](../planned/lathe-lightweight-watcher.md) — the `.lathe/`-poll redesign the
  detection tick should adopt alongside this.
- [WS gaps](../gaps/gaps.md) — the freshness/lifecycle umbrella; the WS source-staleness and
  won't-do-reconciliation entries need updating to reflect this reversal at implementation time.
