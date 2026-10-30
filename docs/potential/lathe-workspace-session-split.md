# Lathe — Split `WorkspaceSession` by Responsibility

## Problem / motivation

`WorkspaceSession` has 5,324 lines, 306 methods and 96 fields (measured 2026-10-30):
about 11% of all production code in one class, and four times its size when the
[M1 refactoring](../done/lathe-m1-refactoring.md) (§6) first listed it as a god class at 1,365 lines.
The next-largest production class (`LatheMcpTools`) has 1,488.

The class is hard to read and review, its `outline` alone overflows an agent's result limit,
and its test class reaches into it through 25 package-private static members spanning five
unrelated concerns: layout queries, test discovery, class-output deletion, stale scanning and write batching.
The tests already treat those as separate units; the production code does not.

The responsibilities share little state.
Grouping the fields by the methods that touch them gives clusters with clean seams:

| Lines (approx.) | Responsibility | Size | Own state |
|---|---|---|---|
| 370–1700 | Run, test and debug: launch, test discovery, debug hosts, run configurations | ~1,000 | `activeRuns`, `activeDebugHosts`, `discoveries`, `lastDiscovery` |
| 790–1305 | Layout queries (`modules`, `packages`, `resolveContext`, `dirRun`, `testSources`, `resources`) and new-type templates (`renderNewType` …) | ~500 | none: already `static`, over `(configs, workspaceRoot)` |
| 1706–1840 | Document lifecycle (open, change, close, save) | ~130 | `docs`, `unsaved`, `documentsFollowDisk` |
| 1840–2585 | References, rename, document highlight, search-scope planning | ~750 | `candidateIndex` (read) |
| 2586–3370 | Navigation routing: definition, implementation, call and type hierarchy, completion, code actions, format | ~780 | `formatFailureReported` |
| 3371–3500 | Reactor type index | ~130 | `reactorShards`, `reactorBaseShards`, `reactorLambdaShards`, `reactorUsageCounts`, `reactorLambdaImplementors`, `typeIndex`, `reactorIndexStale`/`Builds`/`Rebuilt` |
| 3500–4575 | Write passes, rename transaction, `verify_change` support, write diagnostics | ~1,075 | `runningPass`, `nextPass`, `writeLock`, `stampsSeen`, `failedCompiles`, `reported`, `writtenSinceVerify`, `refreshScope`, `scannedResources`, `renameTransaction`, `writeDiagnostics` |
| 4575–4940 | Staleness scan, sync prompt, reload | ~365 | `pendingStale`, `pendingSyncModules`, `buildFilesNotificationPending` |
| 4945–5290 | Compile routing and diagnostic publishing | ~345 | `analysisLru`, `publisher` |

`analyze_change` on the class: 16 production references, all in `lathe-server`, and 59 test references,
all in `WorkspaceSessionTest`. The class is package-private, so a split stays inside one module and one package.

## Sketch

Extract concrete, package-private classes (no interfaces: each has one implementation), in this order,
cheapest and least coupled first. Each step is its own commit and leaves the server behaving the same.

1. **Layout queries and new-type templates** → e.g. `WorkspaceLayout` (static functions over the module configs)
   and `NewTypeTemplates`. No state moves; `WorkspaceSession` keeps thin instance wrappers or callers
   pass `workspace.allConfigs()` themselves. The related tests move out of `WorkspaceSessionTest`.
   Fold in M1 §6.5 here: move `deleteClassOutputs` / `deleteOrphanedClassOutputs` out.
   Reuse `FileUtil`'s package-name conversion instead of `WorkspaceSession.packageOf`.
2. **Reactor type index** → e.g. `ReactorTypeIndex`, owning the shards, the merged index,
   the lambda implementors, the usage counts and the rebuild future.
   Its inputs are module configs and source deltas; its output is the `WorkspaceTypeIndex`.
3. **Run, test and debug** → e.g. `RunCoordinator`, next to the existing `run/` and `debug/` packages.
   It needs the client (to stream transcripts), the event loop, the module configs and the launch reader.
4. **Write passes** → e.g. `WritePass`, owning the pass queue, the write lock, stamps, failures,
   the reported-file set and the rename transaction.
   The most coupled cluster (it triggers recompiles, publishes and the index rebuild); do it last,
   after steps 1–3 have shrunk the class enough to see its real dependencies.

What stays in `WorkspaceSession`: the workspace snapshot fields (`manifest`, `workspace`, `moduleGraph`,
`candidateIndex`), the document lifecycle, and feature routing, which is the LSP entry surface.

Constraints:

- **Every extracted class stays confined to the `ServerEventLoop` thread**, like `WorkspaceSession` today:
  no `synchronized`, no concurrent collections. Cross-thread callbacks keep marshalling through
  `worker.execute`.
- **Not M1 §6.2.** That immutable-state-holder idea was dropped (2026-06-27) and is not revived here:
  this is a split by responsibility, each new class owning its own mutable fields, not a snapshot record.
- No behaviour change, and no new module or dependency.

## Open questions

- How the extracted classes reach shared workspace state (`workspace.allConfigs()`, `manifest`) after a reload:
  passed per call, or each given the session's current registry on reload.
  Per-call keeps them stateless about the workspace and is the simpler default.
- Whether the write-pass cluster splits once more (the verify-change bookkeeping from the pass queue),
  or stays one class; decide after measuring it out of `WorkspaceSession`.
- Whether to start with step 1 alone and re-measure, as M1 did with its slices.
- `WorkspaceSessionTest` (866 lines) follows the code: each step moves its tests to the new class's test.

## Milestone candidate

Untriaged. Step 1 is a low-risk, standalone first slice; the rest each need their own design summary and approval.
