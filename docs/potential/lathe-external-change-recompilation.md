# Lathe — In-Process External-Change Recompilation (parked)

This document preserves the design for **recompiling externally changed sources in-process** (without
a Maven round trip) so the `.lathe/` mirror and in-memory indices stay fresh after an on-disk change —
a branch switch, a `git pull`, or an AI agent editing files directly.

**Status: potential — not on the active roadmap.** The in-process compilation reaction is **not
pursued for now.** It only produces a correct, complete result for a change set confined to a *single*
module — the moment an external change spans multiple modules (common on a large reactor with agent
edits), correctness needs ordered, cross-module reactor compilation, which is Maven's job and which
this would effectively reimplement (see the multi-module note in *Correctness and boundaries*). The
decided direction instead **detects** the staleness and **nudges the user to run Maven**, reusing the
shipped sync prompt — see
[External-Change Detection → Sync Prompt](../planned/lathe-external-change-detection.md).

Kept because the ideas remain useful: an in-process *single-module fast path* could be revived later
as a latency optimization once detection→prompt is in place. Read this as an archive of the reaction
(R1 batch compile), the startup-reconciliation trigger (D2), and the live-watch detection /
gitignore / Neovim analysis — not as an approved plan.

It relates to the [WS-1](../gaps/gaps.md) freshness umbrella and builds on the existing save-time
compile pipeline ([Reactor Type Index](../planned/lathe-reactor-type-index.md)).

The sections below retain the original planned framing (D1 live watch, R1 reaction, D2
reconciliation); treat all "planned"/"ships" wording as the parked proposal, not current state.

---

## Problem

Lathe compiles the currently open file live and otherwise trusts the `.lathe/` mirror that Maven
produced at the last `mvn process-test-classes`. Any change that lands on disk without going through
the editor's save path is invisible until the next Maven build:

- A file an agent (or a `git` operation) creates or edits is **not** reflected in navigation,
  completion, `workspace/symbol`, or dependents' diagnostics.
- The dominant agent workflow is *edit-then-open*: a tool changes files while Lathe is **down**, then
  Lathe starts against an already-stale mirror. Nothing observed those edits, so nothing recompiled.
- Resource edits are only carried into `.lathe/` when the editor saves them (a `BufWritePost` autocmd
  forwards non-Java saves to the `lathe.resource.refresh` command); an external resource change is
  missed.

The expectation, stated plainly: if a tool edits a `.java` file or a resource in the workspace, Lathe
should notice and recompile/copy just that file, no Maven, no prompt.

## Goal

React to changed sources/resources by reusing the machinery Lathe already has, and reconcile the
cold-start delta at startup:

- **R1 — Reaction (per-module batch).** Treat a set of externally changed `.java` files in a module
  like a save: **one** `FULL` compile over those files from disk into the mirror (annotation
  processors run), then a reactor-shard refresh. Batch per module, not file-by-file.
- **Resources.** Route a changed resource through the existing `refreshResource` copy.
- **D2 — Startup reconciliation.** After the workspace loads, a one-time mtime scan compares each
  tracked source/resource against its `.lathe/` artifact and feeds the stale ones into R1.

The reaction has two triggers: **D2 (this doc)** for the cold-start delta, and the
[live watch](../planned/lathe-external-change-detection.md) for in-session edits. Both group changes by module and hand
them to the same R1 entry point.

The light regime (per-file source/resource changes) is handled without Maven; the heavy regime (POM,
dependency, or module-structure changes) continues to route to the Maven sync prompt.

## Non-Goals

- Live **cross-module** recompilation. A change in module A that breaks module B is not live-compiled
  here; that stays bounded by Maven, consistent with
  [lathe-sibling-recompilation.md](../planned/lathe-sibling-recompilation.md).
- Blindly recompiling a whole module or the whole reactor on every change.
- Replacing Maven as the source of truth for classpaths, JPMS, module structure, or dependency
  resolution — POM/structural changes remain the heavy path (see WS-3/WS-5 prompt).
- Cleaning up orphan `.class` files for sources deleted while Lathe was down — mtime cannot detect a
  vanished source; the next real `mvn` cleans it (see §3, *Out of scope*).

## Design

### 1. Reaction (R1) — per-module batch

The reaction unit is **a module plus a set of changed sources**, not a single file:
`WorkspaceSession.recompileModule(config, changedSources)`. A single change is simply a batch of one;
both triggers (D2 startup stale set, the live watch's debounced bursts) group changes by module and
submit **one batch per module**.

1. **Skip open files.** Drop any changed source that is an open document — the editor buffer is
   authoritative and its save path already keeps the mirror fresh; this avoids compiling a
   half-written external copy over unsaved edits. (An empty remaining set is a no-op.)
2. **Route.** Resolve each source's module via `routeCompiler`; only `Module` routes recompile,
   `External`/`Missing` are ignored. Group the surviving sources by module.
3. **Batch FULL compile from disk.** Per module, run **one** `FULL` compile over the changed source
   *paths* (read from disk, not in-memory buffers). `CompileMode.FULL` runs annotation processors (no
   `-proc:none`) and writes `.class` into `.lathe/<module>/classes` plus generated sources into
   `.lathe/<module>/generated-sources`. The `afterModuleSave` follow-up (`refreshReactorShard`,
   dependent open-file rescheduling) runs once per module afterward.
4. **Failure is inert.** A broken mid-edit file fails the batch (or is skipped); the previous mirror
   is kept and no diagnostics are published for non-open files.

**Core addition.** The live path compiles a single in-memory buffer (`compile(uri, content, mode)`).
A multi-file javac *task* already exists but only for analysis
([`analyzeBatch`](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/JavaSourceCompiler.java),
writes no `.class`). R1 needs a new **batch FULL compile that writes classes**: given a module and a
list of source paths, one javac task that emits `.class`/generated sources and returns the union of
`writtenBinaryNames`. This is an internal compile-core entry point on `CompilationWorker` /
`ModuleSourceCompiler`, paired with a source-enumeration helper over `sourceRoots()` (minus
`originalGenSourcesDir()`) used both to *find* stale files (D2) and to *feed* the batch. Batching per
module — rather than file-by-file — is what makes multi-file annotation-processor aggregation and
intra-batch cross-references compile correctly (see *Correctness and boundaries*), on top of
amortizing javac's per-invocation cost.

### 2. Resources

`refreshResource(uri)` already maps a file to its `.lathe/` destination through `ResourceRootIndex` /
`manifest.resourceDestination` and copies it (no-op if the file is under no resource root). Both
triggers drive it: D2 for a stale resource at startup, the live watch on `Created`/`Changed`. Once the
watch drives it, the editor `BufWritePost` autocmd — whose own comment notes
"`workspace/didChangeWatchedFiles` can drive the same server command later" — becomes redundant for
watched paths and is removed to avoid a double fire; the `lathe.resource.refresh` command stays for
manual/other-client use.

### 3. Startup reconciliation (D2, cold-start delta)

A trigger only observes changes made while it is live. The common agent workflow is the opposite
order — a tool edits files on disk, *then* Lathe starts — so those edits predate any watcher and would
stay invisible until the next `mvn`. D2 closes that: a **one-time reconciliation scan** runs after
`loadWorkspace`, on the worker, and feeds stale files into the *same* R1 / `refreshResource` reaction —
a new trigger, not a new reaction.

**What it compares (mtime, per tracked root):**

- **Java** — for each `.java` under a tracked source root (excluding `originalGenSourcesDir()`),
  compare its mtime to the *primary* class file
  `<latheClassesDir>/<packagePath>/<FileBaseName>.class`. Missing, or older than the source → stale.
  Only the primary class is checked; the FULL compile regenerates inner/anonymous classes and AP
  output. Stale sources are **grouped by module** and each module's set handed to `recompileModule`
  (§1) as one batch.
- **Resources** — for each file under a tracked resource root, compare its mtime to its `.lathe/`
  destination (`ResourceRootIndex` / `manifest.resourceDestination`). Newer → `refreshResource(uri)`.

**Why mtime is sound here.** `lathe:sync` writes the `.lathe/` mirror fresh, so an unchanged source is
older-or-equal to its artifact (no false positive); an edit landed after the last sync is strictly
newer (detected). This is exactly the git-checkout/agent-edit case — git stamps touched files "now" —
and it mirrors the mtime+size fingerprint the POM watcher already trusts.

**Cost.** One `O(sources)` metadata walk at startup, run async on the worker and logged
(`[reconcile] modules=%d stale=%d %dms`). Unlike a *continuous* source-mtime poll — rejected for large
reactors in Alternatives — a single startup pass is bounded and one-off.

**Out of scope.** A source deleted while Lathe was down leaves an orphan `.class` that mtime cannot
detect (no source to compare); the next real `mvn` cleans it. Not handled here.

### 4. Coalescing and the bulk cutoff

Both triggers converge on **per-module batches**, and both honour a shared cutoff:

- **Coalescing.** D2 already delivers the full stale set at once; the live watch delivers a burst over
  a debounce window (that window lives in the [watch doc](../planned/lathe-external-change-detection.md)). Either way,
  changes for one module collapse into a single `recompileModule` batch — no per-file storm.
- **Bulk cutoff → defer to the heavy path.** A `git pull` or branch switch can change hundreds of
  files at once; recompiling in-process would storm the compiler and thrash the mirror. So above a
  threshold of changed files — or when the change set includes a `pom.xml` — the light regime **stops**
  and defers to the heavy-path Maven sync prompt (WS-3) instead of recompiling. The light regime owns
  *small* change sets (the agent-edit and few-file-pull cases); large or structural sets are the
  prompt's job, with WS-4 picking up the result. The threshold is tunable and logged when it trips (no
  silent cap).

## Correctness and boundaries

- **Annotation processing is covered** because the reaction runs a `FULL` compile with processors on,
  writing generated sources to the module's `generated-sources` output. **Batching per module (R1)
  closes the multi-file AP-aggregation case** for a same-module change set — a processor that reads
  several sources at once sees them in one task, matching Maven. The residual divergence shrinks to a
  processor aggregating across *different modules* in one pass, which stays Maven-bounded and converges
  at the next `mvn`.
- **Cross-module staleness** remains: a changed public API in module A does not live-recompile module
  B. In-module dependents can later be wired via [Sibling Recompilation](../planned/lathe-sibling-recompilation.md);
  cross-module stays Maven-bounded.
- **Multi-module change sets — why this design is parked.** When one external change set touches files
  in *several* modules at once, the batches cannot run in any order: a downstream module must compile
  *after* its upstream dependency so it sees the upstream's fresh `.lathe/<up>/classes` (the wiring is
  already there — a module's `remappedClasspath()` points into `.lathe/`, so only **ordering** is
  missing, derivable from `WorkspaceModuleGraph.downstreamOf`). But even a correctly topologically
  ordered pass only recompiles the *changed* files; a downstream module's **unchanged** files that also
  depend on the new upstream API are left compiled against the old API — a silently *partial* result.
  Making it whole means recompiling indirect dependents too, i.e. an ordered reactor build — which is
  exactly what Maven already does. Reimplementing that in-process is the "don't replace Maven"
  Non-Goal, so multi-module sets are the decisive reason this reaction is parked in favour of
  detect→prompt. A single-module change set has none of this and is the only case a fast path would
  ever cover.
- **Write contention.** The server and Maven both write `.lathe/<module>/classes`. Writes are atomic
  and a subsequent `mvn` overwrites; a server compile racing a live Maven build is a known, tolerated
  edge (documented, not locked in this slice).
- **Heavy path unchanged.** POM/dependency/module-structure changes route to the Maven sync prompt
  (WS-3/WS-5), never to `recompileModule`.

## Heavy-path companion (WS-3)

Structural changes — `pom.xml`, dependencies, module add/remove — are **not** handled here; they route
to the Maven sync prompt, which is a separate mechanism by design:

- **Detection stays on the server poll.** `pom.xml` and `workspace.json` are a small, bounded,
  server-authoritative set, kept in `WorkspaceWatcher` (the [lightweight-watcher](../planned/lathe-lightweight-watcher.md)
  target of `O(modules)`). They are deliberately **not** folded into the live watch, which exists for
  the huge source/resource set. Two mechanisms, clean split.
- **Actionable prompt, client runs Maven.** On a change, the server shows the prompt and, on a chosen
  action, sends a custom `lathe/sync` notification; the **client** runs Maven as a job (the server
  never runs Maven), and the refreshed `.lathe/` is picked up via WS-4. The prompt shows once per
  change (WS-3 loop fix).
- **Two actions.** "Sync" → `mvn process-test-classes` (types/mirror/main-launch + manifest); "Sync +
  capture tests" → `mvn test` (also re-captures `test-launch.json`, which is *captured from a test
  fork*, not derived by `lathe:sync`, so a new/changed test module needs a real test run). Both offered
  on every change for now; auto-recommending capture on new-module detection is deferred (Slice 2).

Full detail lives on WS-3 in the [gap registry](../gaps/gaps.md).

## Alternatives considered

- **Poll source mtimes continuously** (detection): simple but `O(N)` walks that do not scale to large
  reactors when run every tick. Rejected as a live mechanism; used only as the *one-shot* startup scan
  (D2), where a single bounded pass is acceptable. Live in-session detection is the
  [watch](../planned/lathe-external-change-detection.md)'s job.
- **Content hashing / git-diff for reconciliation** (D2 detection): more precise than mtime but far
  heavier — hashing reads every file; git-diff needs a recorded last-sync commit and misses uncommitted
  agent edits. mtime is the pragmatic match to the existing POM fingerprint, and git-checkout/agent
  edits already bump mtime. Rejected as primary; a hash tiebreak could be added later if mtime proves
  flaky.
- **Invalidate + lazy recompile** (reaction): recompile on first feature that touches the file.
  Deferred — R1's eager batch compile is simpler and reuses the save follow-up; laziness can be a later
  optimization if agent write-storms prove costly despite coalescing.
- **File-by-file compile** (reaction): one javac invocation per changed file. Rejected — loses
  multi-file AP aggregation and intra-batch cross-references, and pays javac's per-invocation cost N
  times. Batch per module instead (§1).

## Implementation sketch

- Compile core — new **batch FULL compile** on `CompilationWorker` / `ModuleSourceCompiler`: a module
  plus a list of source *paths* → one javac task that writes `.class`/generated sources and returns
  the union of `writtenBinaryNames` (the analysis-only `analyzeBatch` is the existing multi-file-task
  precedent). Plus a source-enumeration helper over `sourceRoots()` minus `originalGenSourcesDir()`.
- `WorkspaceSession` — `recompileModule(config, changedSources)` (R1, §1): open-file skip, batch FULL
  compile, then `afterModuleSave` once per module. Resource events call the existing `refreshResource`.
- `WorkspaceSession` — `reconcileOnStartup()` (D2, §3): after `loadWorkspace`, scan tracked
  source/resource roots, compare mtimes against the `.lathe/` artifacts, group stale sources by module
  and hand each module's set to `recompileModule` (resources to `refreshResource`), under the shared
  bulk cutoff. Async on the worker; logged.

The reaction reuses `afterModuleSave` / `refreshReactorShard` / `refreshResource`, but the multi-file
disk **batch FULL compile** is a new internal compile-core entry point (no *public* API change; the
new method is package-private within the module core).

## Testing and verification

- Batch compile (R1/§1) — the compile-core batch FULL compile over several source paths in a module
  writes all their `.class` (union of `writtenBinaryNames`) in one task, including a class that
  depends on a sibling in the same batch; a multi-file annotation processor sees the whole batch.
- `WorkspaceSessionTest` — an external `Changed` on a closed file updates the mirror and type index
  (an added `@Builder` type appears in `workspace/symbol`); an open file is skipped; several edits in
  one module coalesce into **one batch compile** (not N); an external resource change lands in
  `.lathe/`.
- Startup reconciliation (D2/§3) — with several sources in a module touched newer than their `.lathe/`
  classes (and a resource newer than its destination) *before* the session loads, `reconcileOnStartup`
  issues **one batch compile for that module** over exactly the stale set and re-copies the stale
  resource, leaving up-to-date files alone; a stale count over the bulk-cutoff threshold defers to the
  Maven sync prompt instead of recompiling.
- End-to-end probe against the `multi-module` invoker workspace (the method used to verify FR-015 and
  the save→`\ws` path): touch a **closed** `.java` on disk, start the session, then `sym`/`refs` to
  confirm reconciliation reflected it; repeat for a resource.

## Related gaps and designs

- [External-Change Detection → Sync Prompt](../planned/lathe-external-change-detection.md) — the
  **active** direction that supersedes this doc's reaction: detect staleness, prompt for Maven.
- [WS-1](../gaps/gaps.md) — the freshness/invalidation umbrella.
- WS-5 — the gap this parked design was written for (its in-process recompile is superseded by
  detection→prompt).
- WS-3 — the shipped Maven sync **prompt** (loop/actionability fix) the active direction reuses.
- WS-4 — the shipped post-Maven silent reactor-index refresh.
- WS-6 — the in-session open-file dependent-module refresh (the open-file cousin of this reaction).
- [Sibling Recompilation](../planned/lathe-sibling-recompilation.md) — in-module dependents after an
  API change.
- [Reactor Type Index](../planned/lathe-reactor-type-index.md) — the index this refresh keeps current.
- [Lightweight Watcher](../planned/lathe-lightweight-watcher.md) — the `.lathe/`-poll redesign.
