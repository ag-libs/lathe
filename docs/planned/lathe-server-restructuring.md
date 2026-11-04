# Lathe — Server Restructuring

**Status: Phase 0 and Phase 1 done (2026-11-04, branch `refactor/server-threading`); Phase 2 next.**
Starts on `main` after the Gradle merge (#44, 2026-11-04), which did not touch `lathe-server` or `lathe-mcp-server`.

## 1. Why

The server's model is sound, but its shape has made it hard to read, hard to change safely, and hard to test.

- `WorkspaceSession` is 5,863 lines: about 15 responsibilities over about 50 fields.
  The M1 refactoring split it at 1,365 lines; it grew back 4×, because every new behaviour had nowhere else to go.
- The threading contract ("only the event loop touches session state") lives in a comment.
  Whether a `thenCompose` body runs on the loop depends on which thread completed the previous future,
  and nothing in the code shows it.
  It is already broken in several places (§5, Phase 0).
- MCP reaches the core through the LSP adapter: `LatheEngine` builds lsp4j params for `LatheTextDocumentService`,
  which grew about 25 MCP-only methods and about 60 one-line pass-throughs.
  One MCP tool call makes 6–8 sequential blocking round trips, each with a 30 s timeout that does not cancel the work.
  "Build files pending" survives in MCP only because `NoopLanguageClient.showMessageRequest` never completes.
- Freshness ("is this answer current?") is owned by no one: the write pass and the verify support,
  about 1,400 lines and 18 fields, with recursive future chains.
  About 15 archived gaps and the open WS-21/23/26 and MC-72/73/80–82 sit there.
  The `dev/` probes find them; nothing smaller than the whole session can unit-test them.
- Tests: 119 server test classes sum to 137 s and run in 47 s on CI.
  Classes run in parallel, tests within a class in order, so the largest classes
  (`CodeActionTest` 18 s, `LatheEngineTest` 15 s) set the floor.
  Every gap fix adds tests at the most expensive level, the full session or engine.

## 2. What stays (the model)

- One event loop (`ServerEventLoop`) owns all mutable workspace state.
- One thread per module source tree (`CompilationWorker`) owns javac state; `SourceAnalysisSession` stays thread-confined.
- Only immutable data crosses between them.
- No new concurrency model, no locks, no concurrent collections (see the `session-state-on-event-loop` rule).
- Behaviour is unchanged by every step except Phase 0's fixes; the existing tests are the safety net and stay green on each commit.

## 3. Principles, in priority order

1. **DRY first.** One home for each piece of knowledge:
   one URI canonicalisation, one position conversion, one way to rejoin the loop,
   one enclosing-path walk, one edit applier, one rename commit path.
   A move that leaves two copies behind is not done.
2. **KISS second.** Prefer moving code into a plain class over adding a layer;
   no frameworks, no factories, no event buses.
3. **A new abstraction when it earns its place**: it removes duplication, or it states a boundary
   the code otherwise breaks (the threading contract, the analysis module's view of the manifest).
   Each one is named in its step's design summary and approved there,
   including any exception to the `CLAUDE.md` single-implementation interface rule.

## 4. The threading contract, made explicit

These rules are in `AGENTS.md` (`CLAUDE.md` links to it), so every agent sees them.

1. A loop component's methods either return a plain value (called on the loop) or a future that **completes on the loop**.
2. A `thenApply`/`thenCompose` runs on whichever thread completed the future.
   Module workers deliver every result on the loop, so session code chains on them directly;
   any other future (the common pool, a process) rejoins the loop with `eventLoop.rejoin(future)` before touching loop state.
   A value in a session field counts as loop state even when the value is immutable.
3. Loop components check `eventLoop.checkOnLoop()` at their entry points: fail-fast (`IllegalStateException`), not `assert`.
   Tests then trip over a violation instead of a production race.
4. Nothing waits on the loop: no `get`/`join`, no waiting on a process or another thread.
   Known exception, kept for now (KISS): a workspace reload closes the old registry on the loop, waiting for its
   workers to drain. Reloads are rare; make the close asynchronous (with one admission shared across registries)
   only if a measurement shows a noticeable stall.
5. Field names say the thread: `eventLoop` for `ServerEventLoop`, `moduleWorker` for a `CompilationWorker`
   (today both are called `worker`).

## 5. Phases

One PR per phase, one commit per step, review between steps.
Each step moves code without changing behaviour unless it says otherwise.

### Phase 0 — threading guardrails and the violations they expose (behaviour fixes)

- Add `ServerEventLoop.checkOnLoop()` and `ServerEventLoop.rejoin(CompletableFuture)`;
  replace the `eventLoop.submit(() -> future).thenCompose(f -> f)` idiom with one helper (`submitCompose`).
- Module workers deliver their results on the loop (`CompilationWorker` completes through `eventLoop::deliver`),
  so no feature code has to remember to rejoin after a worker call.
  Measured A/B on Dropwizard and Helidon (3 alternating rounds each, hover and completion while idle and while typing):
  no difference in p50 or p90, and p99 differences in both directions within noise.
- Rename `worker` → `eventLoop` where it is the `ServerEventLoop`.
- Fix the verified violations:
  - references search touches the analysis LRU and the registry caches from the common pool
    (`searchReferencesForTarget` → `runSearch` → `searchOpenDocument` → `touchAnalysisCache`);
  - the type-hierarchy explorer reads `typeIndex` and routes a compile on a module-worker thread;
  - code actions read `reactorShards` on a module-worker thread;
  - `LatheTextDocumentService.definition/declaration/implementation/typeDefinition` pass `session::x` method references;
  - `debugTest`/`debugMain`/`debugNamed` block the loop up to 15 s on `jdwpReady`;
  - incoming calls read candidate files on the loop.
- Add the §4 rules to `AGENTS.md`.

### Phase 1 — split `WorkspaceSession` into loop components (done)

As built, in this order (the foundations first, so no component took its inputs as lambdas only to be rewired):

1. `SourceLayout` (module, package, context, dir-run and test-source queries over `(configs, root)`),
   `NewTypeTemplates`, `ResourceFinder`.
2. `WorkspaceState`: root, manifest, module registry, module graph and candidate index, replaced together by `load`;
   every accessor loop-checked, so reading the workspace off the loop fails in tests even when the value is immutable.
3. `ReactorTypeIndex`: the type index and its reactor shards; `TreeState` became its own record and the source-root
   lookup `ModuleSourceConfig.sourceRootOf`.
4. `RunCoordinator`: run, debug, test discovery, run configs.
5. `FeatureRouter` (routing a file to its worker and the analysis LRU, the seam every component needs), then
   `ReferenceSearch`: references, instantiations, highlights, implementations, call hierarchy.
6. `WritePass` (the stale scan, writes into `.lathe`, stamps, failed writes, resources, lombok.config), reaching the
   session only through `WritePass.Hooks`, a record of five functions; then `ChangeVerifier`, the read-only view
   verify_change and the MCP notes are built from.
7. `RenameService`: the rename checks and the edit.

Kept in `WorkspaceSession` on purpose: the open documents and their lifecycle, compile scheduling and diagnostics
publishing, the rename transaction (it hooks into open, change, save, close and format), the tick and reload,
`reconcileForVerify` (the freshen step before every MCP query, which drives the tick, the build-file flag and the
pass), the per-document features, and one-line delegates for the components' entry points.
It went from 5,863 lines to 1,779; the delegates go when Phase 2 lets the front ends call the components.

### Phase 2 — straighten the front ends

- `LatheTextDocumentService` becomes the LSP adapter only.
- `LatheEngine` calls the session's API directly, without lsp4j params;
  each MCP tool is one composed call with one timeout that cancels the work.
- "Build files pending" becomes session state; `NoopLanguageClient` no longer carries it.
- One place each for URI canonicalisation, 0/1-based positions, and applying edits to files.

### Phase 3 — Maven modules and `analysis` tidy-ups

- New module `lathe-analysis`: today's `analysis` and `analysis/completion` packages.
  First cut its four wrong-way edges:
  `SourceAnalysisSession` moves next to `CompilationWorker`;
  `SourceFeatureRequest` takes a small interface instead of `WorkspaceManifest`;
  `RunTarget`/`RunnableKind` move down;
  `WorkspaceManifest` stops calling `DefinitionLocator.topLevelClass`.
  The compiler then enforces the layering agents keep crossing.
- Optional, decided then: `lathe-server-debug`, isolating JDI, java-debug and rxjava.
- Split `SourceLocator` (six concerns) and `TypeResolver` (three); one helper for the enclosing-path walks
  that about ten classes repeat.

Each new module is listed with its pom changes for approval before it is added.

## 6. Tests

- Split `CodeActionTest` by provider family and `LatheEngineTest` by tool; files get clearer and the slowest classes shorter
  (estimated 47 s → about 35 s on CI's 4 cores; the 137 s total is the real limit).
- Once `WritePass` and `ReferenceSearch` exist, new gap fixes are tested against them, not through the full engine.
- `WorkspaceSessionTest`'s direct calls into about 25 package-private statics move with the code they test.

## 7. Keeping it from growing back

- `CLAUDE.md` names the components and says new behaviour goes into the owning component;
  `WorkspaceSession` takes wiring only.
- Module boundaries (Phase 3) are enforced by the compiler, not by convention.

## 8. Process

- Branch per phase from `main` after the Gradle merge, in a worktree.
- Build with `mvnd verify -pl lathe-server,lathe-mcp-server -am`; no `install` without asking
  (other worktrees share the `0.1.0-SNAPSHOT` in `~/.m2`).
- `mvnd spotless:apply` after every Java change; invoker tests (`mvnd verify` on the reactor) before each PR.
- Run `dev/mcp_staleness.py` and `dev/mcp_battery.py` before and after Phases 1.6 and 2 to show no behaviour change.
