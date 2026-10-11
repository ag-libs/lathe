# Lathe — One Write Pass: Simplifying Recompilation

## Status

**Implemented** on 2026-10-11, in the migration's steps: `ad0952ad` (the pass, level-by-level writes, no bulk threshold), `b8cfcdd3` (rename commit), `dbf516ad` (saves, one refresh per pass, write-only diagnostics), `067ee923` (live analysis with unsaved siblings), `80150f28` (MCP notes), `b25db634` (test reorganization).
Where the implementation settled a detail differently from the plan below, the plan was updated: writes go a dependency level at a time (rule 12), the reactor type index is rebuilt off the session thread, and a file in no synced module suggests the full sync.

Every way the server writes compiled classes collapses into one pass that every trigger requests; the bulk threshold and most bookkeeping disappear with the paths they served.
The MCP server then either fixes staleness itself or tells the agent exactly why and what to run.

## Why

Each feature got its own write path, and most of the state exists so those paths do not step on each other:

| Write path | Its own state |
|---|---|
| Save | a per-file FULL compile, the `saving` map, a save queue, a per-tree in-flight count |
| Reconcile (external changes) | the `reacting` set, two-tick stability, an open/closed split, the bulk threshold and its notice |
| Rename commit | its own deletion and batch entry point (`commitChangeSet`) |
| MCP freshen | its own copy of scan, filter and write (`reconcileForVerify`), with deferral reasons |
| Dependents refresh | an in-flight counter and request merging, because writes arrive from four directions at once |

The 50-file bulk threshold is the clearest symptom: added when reconcile compiled file by file, then worked around three times (an open/closed split, a rename exemption, an MCP deferral) once batching made it pointless.

## The model

Two things carry all the state that matters:

- **The mirror** `.lathe/<module>/classes` and `test-classes`: compiled classes every analysis resolves against.
  Maven fills it during a sync; the server rewrites parts of it.
- **Compile stamps** `.lathe/<module>/lsp-stamps-<tree>.json`: per source, the mtime it had when its class was last written.
  A source whose mtime differs from its stamp is **stale**.

### One pass

```
 save ─────────────┐
 external tick ────┤
 rename commit ────┼──► request a pass ──► pass:
 MCP query ────────┤                        1. drop the classes of deleted sources
 startup ──────────┘                        2. stale = sources newer than their stamp that are ready,
                                               minus held and failed-unchanged ones
                                            3. write them: one javac task per source tree,
                                               level by level, upstream trees first
                                            4. re-analyze open files in and downstream of the
                                               written trees: one analysis task per tree, publish
```

- **One pass at a time.** A request during a pass marks it dirty; another pass runs right after it.
  This replaces the save queue and coalesces every kind of burst the same way.
- **Ready** means the editor reported the file saved since the last pass, or its mtime held across one tick (an external writer may still be writing).
  This is the only readiness rule.
- **Excluded:** files held by an applied rename transaction, and files whose last write failed with no change since.
- **The reactor lock** `.lathe/lathe.lock` is checked before each tree's write (rules 1 and 12).
- **Step 4 publishes** each re-analyzed open file once and refreshes semantic tokens once per tree.
  For files the pass wrote, diagnostics only the write can produce — errors reported by annotation processors, which analysis runs without — are merged in, as a save's FULL compile publishes them today.

### Threads and state

One rule keeps this free of shared synchronized state: **every piece of pass state has one owner, the session thread** — the pass's running and dirty flags, `reported`, `failedCompiles`, the one-tick readiness map, the rename transaction.
Other threads never read or write it; they exchange messages:

- the session thread hands a tree's worker an immutable request (the sources to write or analyze);
- the worker runs javac on its own thread, with its own tree-local cache, and posts an immutable result back to the session thread;
- timers (the reconcile tick, the rename idle commit and unapplied drop) post to the session thread;
- the MCP engine requests a pass and waits on the returned future from its own thread;
- the reactor type index is rebuilt once after the passes, on a pooled thread from an immutable snapshot, and applied on the session thread only if the index is still the one it was built from — a rebuild takes 100–200 ms on a large reactor and would otherwise hold back the publishes queued behind it.

The session thread never blocks: a pass is a chain of these messages, each tree's result posting the next step.
No lock, `synchronized`, atomic, or concurrent collection is introduced; the one volatile field in this area (`cachedStaleModules`, read for MCP's stale note) goes in the MCP step.
What remains between threads is infrastructure, not state: lock files between processes, which are only read, and the compile-admission semaphore, which caps how many javac tasks run at once across tree workers.

The pass is the only write lock: being single-owned and single-flight, it writes at most one dependency level at a time (rule 12), so writing javac tasks never overlap a tree they depend on.
The trees of one level are written together; the compile-admission semaphore still caps how many javac tasks run at once, so memory stays bounded as with parallel saves today.
The cost is that a wide level can take every permit for a while, so typing in other trees waits behind it during a long catch-up.

### Triggers

| Trigger | Request |
|---|---|
| `didSave` of a reactor file | marks it ready, requests a pass now |
| reconcile tick (2 s, slower when idle) | requests a pass if anything is stale |
| startup | one pass with every stale file treated as ready |
| rename commit | releases the transaction's hold, requests a pass |
| MCP query | requests a pass and waits for it before answering |

Typing and opening keep their in-memory analysis; they never write.
A save of a file outside the reactor (a JDK or dependency source) keeps its own small path.

### Rules the pass must keep

Found by checking the design against the code; each is a test in the migration step that introduces it.

1. **Reported files skip the lock.** Today only reconcile and the MCP freshen respect `.lathe/lathe.lock`; saves compile during a running `mvn test`.
   The pass keeps that: while a reactor build holds the lock, it writes only *reported* files (saved by the editor, or written by the MCP engine) and leaves external catch-up for after the build.
2. **A stamp records the mtime the content was read at**, not the mtime after the write.
   Today the stamp is read after the compile, so a file saved again during its own write is stamped with the new mtime for the old content and never rewritten — a latent race on both paths that faster saves would expose.
3. **A reported file stays ready until it is written**, not just until the next pass.
   An applied rename's files are saved (reported) while held; when the commit releases them they must still be ready, or they would wait for an extra tick.
4. **A reported file is written even if its mtime equals its stamp** (a coarse-mtime file system, or two saves within one tick): the editor said it changed.
5. **Progress shows only for external catch-up.** A pass that writes only reported files shows no progress task; otherwise every save would flash `recompiling 1 file(s)`.
6. **The pass's refresh (its step 4) includes trees that lost classes in its step 1**, so a deletion still refreshes its open dependents.
7. **Changed resources are copied in the pass**, as reconcile does today.
8. **The pass reports what it wrote**: `verify_change` uses that list when the agent names no files.
9. **A workspace reload abandons a running pass** (its results are dropped) and requests a new one, as the configs and workers it used are replaced.
10. **Write diagnostics per file**: the batch write must return each source's diagnostics (as the analysis batch does), so annotation-processor errors can be merged into that file's publish.
11. **A failed file is retried whenever its tree writes again.** If a pass writes any ready file of a tree, the tree's failed-unchanged files join that batch.
    Two files that need each other's new code but became ready in different passes (a slow `:wa`) would otherwise each fail alone and stay stuck until edited; together in one javac task they compile.
    This replaces today's "clear every failure after any successful write", which retries unrelated trees and does nothing when the only new write fails too.
12. **One level at a time, the reactor lock checked per level.** The trees to write are grouped into dependency levels (`WorkspaceModuleGraph.levels`): a tree lands one level after the latest tree on its class or module path.
    The trees of a level are written together, and the next level starts once all of them finish, so every write resolves against freshly written upstream classes.
    The futures are joined on the session thread, so no state is shared with the workers.
    Writing one tree at a time doubled the tail of a 108-file rename (14.6 s against 6.8 s), because a tree batch costs about a second even for one file.
    Before each level, the pass checks the reactor lock (`LatheLayout.reactorLockFile`, `.lathe/lathe.lock`; module locks are the build's own, per module): held by another process (a Maven build started meanwhile), it stops writing external catch-up and continues it after the build, while reported files are still written (rule 1); held by this process (the MCP engine writing a rename), it proceeds.
13. **A tree batch with errors is retried once without the files that had errors.** javac writes no class at all when any file of the task has an error (verified on JDK 26 with every compile policy), so one broken file would otherwise leave its whole tree unwritten and every downstream file compiling against stale classes.
    The retry leaves out the files javac reported errors in; what compiles without them is written and stamped, and what still fails joins the failed set, retried by rule 11.
    It uses which files failed, not what the errors say, and costs at most one extra javac task when a batch fails; the result — good files written, broken ones keeping their last classes — is what per-file saves produce today.

14. **A pass that is dirty when its writes finish hands its refresh to the next pass**, which refreshes the union of both scopes once.
    A fast `:wa` gives one pass for the first saved file and another for the rest; refreshing after each would analyze every open dependent twice.
    Today the dependents drain prevents that, and `didSave_singleSaveThenBurstAcrossFiles_recompilesEachOpenDependentOnce` pins it.
15. **Each config keeps its write target.** A Maven tree writes into its `.lathe` mirror; an OpenJDK tree (`MODULE_SYSTEM`, external output) writes throwaway classes, its real output being the JDK build's, rebuilt through the build-file watch (batch markers).
    The pass changes when writes happen, not where.

One trade-off to accept and measure: a save made during a long external catch-up (a branch switch) waits for that pass to end before it is written.
The catch-up is rare and visible through its progress task; if measurement shows the wait matters, a reported file could start its own pass for trees the running one is not writing — not planned now.

### The rename transaction, reduced

It keeps its job — hold the files of a rename the editor is applying until they are all saved — but loses its own commit path.
While applied and unsaved, its files are held (step 2 skips them, step 4 does not re-analyze them); its commit is "release and request a pass".
Its triggers stay as they are: the last save, 10 s idle after the first save, abandon on full undo, forget when never applied.

### Live analysis sees unsaved siblings

The pass is about writes; this is the matching simplification for analysis, done as its own step.

**Today** an open file is analyzed alone, resolving its siblings from their written classes.
When that produces a `cant.resolve…` or `cant.inherit.from.sealed` error, a second analysis runs with the tree's open buffers and not-yet-written files from disk, and replaces the first result (`maybeWidenSealed`).
The error-code trigger misses every other error a sibling's unsaved change can cause — a changed parameter list (`cant.apply.symbol`), a changed return type (`incompatible types`), a new interface method implemented ahead of its declaration — which stay as false errors until the sibling is saved.

**After**, analyzing an open file (on open and while typing) always includes the **unsaved open buffers of its source tree** as extra sources, through the same analysis batch call the refresh uses; only the analyzed file publishes.

- *Unsaved* means the buffer differs from the file on disk: checked once at open (an editor may restore a buffer with unsaved changes), set by a change, cleared by a save; a session-owned set tracks it.
- No unsaved sibling — the common case — means the file is analyzed alone, as today.
- A sibling joins only if its own last analysis parsed cleanly, so a half-typed sibling (an unbalanced brace) cannot cause false errors elsewhere; otherwise its written classes are used, as today.
  *Parsed cleanly* means javac's parse phase, which runs before attribution, reported no error for that content; the tree's worker records it with each analysis and checks it when the sibling is offered, so no error code is inspected.
- The most recently edited siblings that parse cleanly join, so that the file and its siblings fill at most one `ANALYSIS_BATCH_MAX` (50) task; older unsaved buffers beyond that count with their written classes, as before.
- Siblings in other trees are not included, as today: an upstream module's unsaved change still shows downstream after it is saved.
- Files changed on disk but not yet written need no special case: the pass writes them promptly.

No error is inspected and nothing is analyzed twice; the input follows from which buffers are unsaved, and every kind of cross-file error is covered.

## Inventory

Mutable state in `WorkspaceSession` that decides what compiles:

| State | Today's job | After |
|---|---|---|
| `saving` | a save write is running or queued for a file | **gone** — the pass owns every write |
| `reacting` | a reconcile batch is writing a file | **gone** |
| `queuedSaves`, `treeSavesInFlight` | the save queue | **gone** — the dirty flag coalesces |
| `sourceCompilesInFlight` | when writes from all paths have drained | **gone** — the pass knows its own writes |
| `pendingRefresh` (`DependentRefresh`) | merged refresh requests from all paths | **gone** — one refresh at the end of the pass |
| `bulkNoticeShown` | show the bulk notice once | **gone** |
| `pendingStale`, `awaitingSettle` | one-tick readiness of external changes; tick back-off | `pendingStale` stays; the idle back-off and `awaitingSettle` were removed later (a pass every 2 s; a scan costs ~25 ms on a 377-tree reactor after the one-walk scan) |
| `failedCompiles` | do not retry a failed, unchanged file | stays |
| — | | **new** `reported`: files saved or engine-written, until written |
| — | | **new** pass `running` / `dirty` flags |
| — | | **new** per-document *unsaved* flag (live analysis) |

Ten pieces of state become six, and four of the remaining ones mean one thing each.

Methods and types removed: `afterModuleSave`, `saveCompileDone`, `releaseSaving`, `treeSaveDone`, `sourceCompileDone`, `refreshOpenDependents` and `DependentRefresh` (the pass's step 4 replaces them), `commitChangeSet` (session and service), `reconcileForVerify`'s own scan and write, `noticeBulkChange`, `BULK_CHANGE_THRESHOLD`, `DeferReason.BULK_CHANGE`, the save branch of `publishThen`; `claimStable` keeps only its readiness and exclusion checks.
The parallel chaining — `compileAfter`, `upstreamBatches`, `WorkspaceModuleGraph.compilesBefore`, and deferred batches counted as in flight — becomes a loop over dependency levels (rule 12).
In the MCP step, `cachedStaleModules` (volatile) goes: the notes come from the pass's result.
With the live-analysis step, the sealed widening goes entirely — `maybeWidenSealed`, `widenSources`, `hasCrossFileResolutionError`, `isCrossFileResolutionCode`, the two error-code constants — and with it `diagnoseInBatch` in the compiler, the analysis session, and the worker, leaving one analysis entry point (`compileOpenBatch`) for the refresh and for live analysis alike.
`failedCompiles` keeps its job but loses the clear-on-any-success rule (rule 11).

Kept: the stamps, the per-tree batch write (`compileChangedBatch`, now with the retry of rule 13), the dependency order (`compileOrder`), the batched analysis (`compileOpenBatch`), the reactor index refresh, the rename transaction's membership rules, the compile-admission semaphore, the watcher, and the paths for typing, opening, and saving files outside the reactor.

Tests rewritten with the code: the `claimStable` and `DependentRefresh` unit tests, the save-coalescing and burst service tests (now pass coalescing), the two bulk-threshold tests, and the MCP freshen tests.

## Measurements behind it

From today's equalsverifier runs:

| Work | Time |
|---|---|
| One saved file, written (today's save compile) | median 454 ms, p90 632 ms |
| One saved file through the pass, save to publish (step 3, measured) | median 571 ms, p90 654 ms, against 497 / 569 ms for the save compile on the same run |
| A batch write of one file | the same call (`generate` with one source); slower only when cold and contended |
| 108 renamed files written as 8 tree batches in parallel | ~4 s, the largest tree (81 files) 3.9 s |
| The same written one tree at a time | 12.8 s of writes, commit to refreshed 14.6 s (step 1, measured, rejected) |
| The same written level by level (rule 12) | 2 levels; commit to refreshed 6.8 s, the 81-file tree 4.9 s (step 1, measured) |
| Re-analysis of one open file | 34–64 ms |
| Re-analysis of 50 open files in one task | 212 ms |
| Analysis of 2–7 files in one task | 38–95 ms — the live-analysis cost with a few unsaved siblings |

So a save in the pass costs today's write plus one ~50 ms analysis, and a branch switch of hundreds of files costs a few seconds per tree — which is why the threshold can go.

## What the user sees

| Situation | Today | After |
|---|---|---|
| Save one file | its write publishes its diagnostics | same write, then its analysis publishes (~50 ms later) |
| Branch switch, 51+ Java files | warning "N files changed at once — run a Lathe sync"; mirror stays stale | `Lathe: recompiling N file(s)` progress; compiled in-process |
| A build file changed (`pom.xml`, or an OpenJDK batch marker) | sync prompt | unchanged |
| Rename, `:wa`, Save All | as now | as now (same batches, through the pass) |
| Typing in a file that uses a sibling's unsaved change | correct only for "cannot find symbol" and sealed errors; others are false errors until the sibling is saved | every kind of cross-file error resolved from the unsaved sibling |

## Tests as the safety net

The refactor must not change behavior (except the branch-switch row above), so the tests that pin behavior stay as they are through every step; only tests of internals the refactor deletes go with them.

| Test | During the refactor |
|---|---|
| Service tests of saves, reconcile, rename, refresh, MCP (`LatheTextDocumentServiceTest`, `LatheEngineTest`), the invoker ITs, the Neovim specs | **unchanged** — every step keeps them green |
| Four service tests that assert specific compile log tags (`[compile:full]` once, `[compile:batch] 3 files`) | reworded **in step 0** to count kinds of work — write tasks, analysis tasks, files analyzed — so they pin the behavior (one write task for the coalesced saves, one analysis task per tree) and survive saves moving onto the pass |
| Unit tests of kept logic: `wroteClassOf`, `withoutFailedCompiles`, `stableSources`, `reconcileDue` | unchanged |
| Unit tests of removed internals: `claimStable`'s saving/reacting cases, `DependentRefresh` merging | deleted with the code; the pass gets its own unit tests |
| The two bulk-threshold tests | replaced in step 1, the one intended behavior change |

The live-analysis step adds service tests: a typed file calling a sibling's unsaved new signature shows no false error; with no unsaved sibling it is analyzed alone; a half-typed sibling does not leak errors; the sibling cap holds.
The existing sealed-widening tests (an open sealed root and a new permitted subtype) stay as the behavior pin.

The burst harness (`dev/burst.py`) is the end-to-end layer: each step runs its scenarios on the three projects and compares compile counts, publishes, token refreshes, time to quiet, and the end-of-run checks against the previous step.

After the refactor, a separate step reorganizes the tests: `LatheTextDocumentServiceTest` (39 tests, about 1,270 lines) mixes five concerns — saving, external changes, rename, refresh, MCP — and splits into one class per concern sharing one fixture class (`depWithUsers`, `writeMirroredModule`, the compile-log collector, open/edit/save helpers).

## Migration

Each step is one commit, with the full reactor build (invoker ITs included) and burst-harness runs on equalsverifier, dropwizard and helidon (Neovim pacing, Save All, partial save, never applied, undo, MCP beside an editor).

0. **Make the safety net behavioral** (tests only): reword the four log-tag assertions as above; no production change.
1. **Introduce the pass** (single-owner, level by level, rules 12 and 13) for the reconcile tick, startup, and the MCP freshen; delete the bulk threshold, its notice, the open/closed split, and `reconcileForVerify`'s copy.
2. **Rename commit through the pass**; delete `commitChangeSet` (the MCP engine's own pass runs under the lock it holds).
3. **Saves through the pass**, with the write-diagnostics merge; delete the per-file save write, `saving`, the save queue, and the in-flight counter and refresh merging.
   A before/after timing of a single save on a warm worker confirms the cost above.
4. **Live analysis sees unsaved siblings**, replacing the sealed widening and `diagnoseInBatch`.
   Measured: keystroke-to-publish latency on equalsverifier with 0, 3, and 10 unsaved siblings in the tree, before and after.
5. **MCP notes** (part below).

Steps 1–3 change no user-visible behavior except the branch-switch row above; step 3 is last because saves are the most frequent trigger. A last step after them reorganizes the tests (above). Step 4 changes typing behavior on purpose (more cross-file errors resolved) and is measured on its own.

## Risks

- **A very large catch-up** (thousands of files after a branch switch) compiles every affected tree in the server JVM, level by level (rule 12): the admission semaphore bounds memory, but a wide level can hold every permit, so typing in other trees may wait.
  Measured in step 1 on helidon: time to quiet, heap high-water mark, and keystroke latency during the catch-up.
  If sequential writes prove too slow, the one knob is how many trees may write at once; the model is one.
- **One broken file in a batch**: handled by rule 13 (retry without it); measured with the helidon `MediaType.text()` case, where one record broke `common/media-type` and 31 downstream files.
- **Annotation-processor diagnostics**: step 3 must keep publishing them for saved files; a test pins it.

## MCP: fix it, or say exactly what is needed

Before answering, every MCP tool requests a pass and waits for it (above), so the agent's own edits are compiled first, however many.
Whatever is still not current is reported as one of two notes, replacing today's single `Stale: … run a sync`, which suggested a sync even when only the agent's code was broken:

- **Not compiled** — files whose write failed and that have not changed since, each with its first error (kept with the failure): `Not compiled: app/src/main/java/Foo.java:12: cannot find symbol — fix the code; cross-module answers use their last good classes.`
  No sync is suggested; the next edit is retried automatically.
- **Sync required** — only what the server cannot do itself, with the reason and the exact command:
  a changed build file awaiting a sync (`BUILD_FILES_PENDING`: `build files changed — run .lathe/lathe-sync.sh`), a queried file in no synced module (`<file> is in no synced module — run .lathe/lathe-sync.sh`; the full sync, as the server cannot name a module it has never synced without the build tool), or a `.lathe/` written by another Lathe version (`WorkspaceManifest.schemaMismatch`: `.lathe was written by Lathe X — run .lathe/lathe-sync.sh`).

A running reactor build gets neither: the note says the answer reflects the mirror before the build and to re-query when it ends.
The server does not run a sync itself: it is a Maven build the agent should see and own, like every other build it runs.

Tests: an agent edit that does not compile yields `Not compiled` with the file and error and no sync suggestion, and once fixed the next query has no note; a pending build-file change and a schema mismatch each yield `Sync required` with the command; the routing instructions served at `initialize` describe the two notes.

## Docs updated with the code

`docs/guide/ai-agents.md` (deferrals and notes), `docs/planned/lathe-change-impact-and-verification.md` (the `BULK_CHANGE` gate), `docs/potential/lathe-rename-transactions.md` and `docs/potential/lathe-batching.md` (now one pass), and pointers here from `docs/done/lathe-in-process-workspace-sync.md` and `docs/done/lathe-external-change-detection.md`.
