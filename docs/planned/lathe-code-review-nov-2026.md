# Lathe — Code Review Findings (November 2026)

**Status: planned (2026-11-06).**
A structural review of the whole codebase after the 0.3.1 release,
which shipped the [Server Restructuring](lathe-server-restructuring.md) (phases 0–3).
Five read-only reviewers covered the server core, `lathe-analysis`, the front ends (LSP, `LatheEngine`, MCP),
the build side (core, install, the Maven, OpenJDK and Gradle front-ends) and the clients, tests and docs.
Each finding below was checked against the code at `dfba186d`.

The restructuring held: the threading contract is kept at every boundary the review looked at,
the front ends call the session directly, `lathe-analysis` uses no javac internals and never reaches into the server,
and the `.lathe/` formats have one schema shared by writer and reader.
What is left is the same pressure the restructuring named:
`WorkspaceSession` and `SourceAnalysisSession` are still the default home for new features,
some work was copied rather than shared (run vs. debug launch, the three sync pipelines, call-hierarchy items),
and the docs drifted from the code.

Principles are the restructuring's (§3 there): DRY first, then KISS, a new abstraction only when it removes
duplication or states a boundary the code otherwise breaks. Every multi-class step gets a design summary and an
"Approved" before code.

---

## Group A — cheap fixes for real defects and drift (one PR)

**Done on branch `fix/code-review-group-a`**, one commit per item, except the one noted below.

### A1. Debug launches run on the event loop, and copy the run path

`RunCoordinator.startDebugTest` / `startDebugMain` repeat `launchTest` / `launchMain`, but on the loop:
reading `test-launch.json` / `main-launch.json`, `CompletenessGate.verify` (a stat per classpath entry),
`Files.createTempFile`, the argfile write and the process spawn all run inside a `checkOnLoop` entry point.
The run path does the same work on a launch thread of its own (`launchOnItsThread`).
The copies already drifted: a debug run drops the completeness gate's notes (the run path streams them and adds
them to the outcome).

The design is in [§ Debug launch thread model](#debug-launch-thread-model) below.

### A2. `DocumentRegistry` is unchecked

`DocumentRegistry` is a plain `HashMap` handed to five loop components and checks nothing.
`WorkspaceSession`, `ReferenceSearch`, `ChangeVerifier` and `RenameService` have no `checkOnLoop` at all;
they are protected only because they read `state`, which checks.
Fix: give `DocumentRegistry` the event loop and check in every method (contract §4.3).
A test that trips the new check is a real violation, fixed in the same commit.

### A3. Dead code

- `WorkspaceSession.pendingSyncModules`: the only caller of `promptForSync` passes `List.of()`,
  so the module-scoped sync it feeds can never happen. Remove it (the scope parameter too).
- `LatheTextDocumentService.onTypeFormatting`: a `TODO` stub that returns no edits, is never advertised,
  and would log at `INFO` per keystroke. Remove it; the design stays in [Google Indentation](lathe-google-indent.md).
- `LatheLayout.LEGACY_CURRENT_LINK` and its cleanup in `ServerInstaller`: migration code, against the
  no-backward-compatibility rule.
- `core.typeindex`: `ReactorTypeIndexOrigin`, `TypeIndexOriginKind.REACTOR` and the `SourceTree` enum have no
  producer or consumer in main code (only a test). Confirm with a compile after removal.

### A4. Small leftovers

- The client field is typed `LanguageClient` and cast to `LatheLanguageClient` four times
  (`WorkspaceSession`, `RunCoordinator` ×3). Type the field `LatheLanguageClient`.
- `WorkspaceSession.saveRunConfig` is a forwarder; `LatheWorkspaceService` calls `session.runs()` directly.
- The debug providers build URIs with `file.toUri().toString()` (`LatheEvaluationProvider`,
  `LatheCompletionsProvider`, `LatheSourceLookUpProvider`), bypassing `LatheUri` (a Windows risk).
- Orphan comments in `LatheEngine` describing code Phase 2 removed ("What verify_change checks…",
  "An agent edits files… / What run_test reads").
- Twelve comments carry gap IDs (`MemberAccessCompleter`, `CandidateFactory`, `DefinitionLocator`,
  `UnusedDeclarationScanner`, …).
- `ZipFixture` exists in both `lathe-core` and `lathe-install` tests: one copy in `lathe-core`'s `core.testing`,
  shared through a `lathe-core` test-jar (approved), as `lathe-analysis` shares its test helpers.

### A5. Docs that contradict the code

- `docs/status.md` says type definition is not implemented; it is advertised and shipped.
- Shipped designs still under `planned/`: type definition, MCP trust (PR #73),
  and the restructuring doc's "on branch" wording (merged as PR #93).
  Re-run failed tests stays planned: its option 2 is still open, and its status already says option 1 shipped.
- [Lightweight Watcher](lathe-lightweight-watcher.md) describes a `Files.walk` the current `WorkspaceWatcher`
  no longer does: mark it obsolete or move it to `potential/`.
- `done/lathe-gradle-support.md` and `done/lathe-new-type-creation.md` still open with "Proposed".
- Two docs link to `file:///home/...` paths; make them repo-relative.
- Restructuring §7 ("`AGENTS.md` names the components; `WorkspaceSession` takes wiring only") never landed.
  Added with B1 (Group B).

---

## Debug launch thread model

### Today

| Step | Run (test / main) | Debug (test / main) |
|---|---|---|
| Run-config overlay read | loop | loop |
| Launch JSON read, completeness gate, test filter | launch thread | **loop** |
| Temp results sink, argfile | launch thread | **loop** |
| Process spawn | launch thread | **loop** |
| Register under the token | loop, via `eventLoop.execute` from the launch thread | loop |
| Gate notes | streamed and added to the outcome | **dropped** |
| Wait for the JDWP banner | — | rejoined, non-blocking, 15 s timeout |
| Open the DAP host (reads registry, manifest, reactor index) | — | loop |
| Result future completes on | the process reaper thread | the loop |

Two races exist in both paths today:

- **Cancel before spawn is lost.** A `lathe.run.cancel` that arrives while the launch is still being prepared finds
  no session under the token, logs "no active run", and the JVM starts anyway.
  For a debug launch this is worse: the debuggee starts with `suspend=y`, the editor never attaches,
  and it stays parked until it is killed by hand.
- **A debuggee that exits before its banner** (bad JVM option, missing class) is reported only after the
  15 s JDWP timeout, because the readiness wait does not watch the process.

Shutdown is not a gap: `ChildProcesses.destroyOnExit` kills every child of the server JVM when it exits,
and a session is only closed on the way out.

### Decision

**One launch path, three phases, each on a fixed thread.**

1. **Snapshot — on the loop.**
   Everything the launch needs from session state is read here and handed on as immutable values:
   workspace root, `ReactorRewrite`, runner classpath, the resolved `RunItem` overlay,
   and for a debug the JDWP port (`PortUtil.free` binds and releases an ephemeral port: no wait).
   The run-config file stays read on the loop: it is a small JSON read like the other config reads,
   and an unknown config name keeps failing the request synchronously (`InvalidParams`).
   The token is registered as **pending** in the runs map before anything leaves the loop.
2. **Prepare and spawn — on the launch's own thread** (`lathe-launch-<module>`, as runs use today).
   One static `prepareTest` / `prepareMain` builds a `Replay` value — the resolved launch, the results sink,
   the gate notes, or the blocked reasons — taking `JdwpOptions.NONE` for a run and the debug's `JdwpOptions`.
   The thread then spawns the process (with the JDWP-banner consumer for a debug) and its work ends.
   Nothing on this thread touches session state; it reads files and starts a process, nothing else.
   A thread per launch, not a shared executor: launches are rare, user-started, and each one should start at once,
   not queue behind a slow gate on another module.
3. **Register and finish — back on the loop** (`eventLoop.rejoin` of the spawn future).
   - If the token was cancelled while pending, the fresh session is cancelled at once:
     a run completes with the killed JVM's outcome, a debug fails the request.
     This closes the lost-cancel race for both.
   - Otherwise the session replaces the pending entry.
   - **Run**: chain on `rejoin(session.onExit())`, so the run's result future completes on the loop
     (contract §4.1; today it completes on the reaper thread) and the entry is removed there.
   - **Debug**: wait for `jdwpReady` **or** the process exit, whichever comes first, with the 15 s timeout;
     an exit first fails the request at once instead of after 15 s.
     Then open the DAP host on the loop: it reads the registry, manifest and reactor index (loop state),
     and `DapHost.start` only binds a loopback socket and starts its own thread.

Blocked launches keep their current reporting: a run completes with `LaunchOutcome.blocked(reasons)`,
a debug fails the request with the reasons. Gate notes are streamed and attached to the outcome for both,
so a debug session's `testFinished` carries them too.

### Shape

- The runs map becomes `Map<String, RunSlot>`, `RunSlot` a small record (`LaunchSession session` or none,
  `boolean cancelled`) replaced on each change; loop-confined like today.
- `MainLaunchPlan` generalises into the `Replay` record shared by test and main, run and debug;
  `LaunchInputs` carries the phase-1 snapshot.
- `launchTest` / `launchMain` / `startDebugTest` / `startDebugMain` collapse into one `spawn`
  (phases 1–2) and two tails, `run` and `debug` (`attachDebugHost`).
  The public entry points and the named-config dispatch stay as they are.
- `RunCoordinator` stays about the same size: the copies go, the records and the readiness race come in.

### Tests

The debug paths have no tests today because they need a debuggee.
`RunCoordinatorTest` gains:
a run and a debug cancelled in the same loop task that started them (so still pending) are killed as they start;
a debug launch attaches, and one whose JVM exits before the banner (a bad JVM option) fails at once.
The JVM is the test JDK's own `java` (`TestProcess`), as `LauncherTest` already does.

---

## Group B — stop `WorkspaceSession` from regrowing

**B1 and B4 done on branch `refactor/session-components`** (one commit per step): `DocumentFeatures`, `FormatService`,
`WorkspaceQueries`, `ReactorTypeIndex.withOpenEntries`, `run/TestDiscoveryStore`, `ClassOutputs`, `ResourceMirror`,
the `run/` and `format/` packages, and the component rule in `AGENTS.md`. `WorkspaceSession` is down from 1,601 to about 1,050 lines.

### B1. Split `WorkspaceSession` further (1,621 lines, about six concerns)

- `DocumentFeatures`: hover through workspace symbol, type hierarchy, completion, code actions, missing imports
  (about 440 lines; they touch only `router`, `reactorIndex`, `state` and `docs`).
- `FormatService`: the format thread, in-flight set and failure flag (fields and about 120 lines),
  with the rename transaction's `onFormat` passed in.
- Then restructuring §7 goes into `AGENTS.md` (A5).

### B2. A session host apart from `LatheTextDocumentService`

**Done on branch `refactor/session-components`.**

`LatheTextDocumentService` is both the LSP adapter and the way onto the loop for everything else
(`onLoop`, `onLoopFuture`, `onLoopCompose`; `LatheEngine` builds a `TextDocumentService` to reach the session).
That keeps forwarders alive (`formatFuture`, `instantiationsFuture`, the reconcile test seams)
and makes MCP `format` three loop requests.
A package-private host (event loop, session, progress, formatter, the `onLoop*` methods, `close`)
taken by both LSP services and `LatheEngine` leaves the document service a pure params-to-host adapter.

### B3. `CompilationWorker` pass-through layer

**Done** (`refactor/session-components`): one public `submit`; the 41 wrappers are gone.

45 one-line `submit(ctx -> ctx.x(...))` wrappers, so a new feature touches four classes.
Expose `<T> submit(Function<SourceAnalysisSession, T>)` (and a cancel-aware overload);
keep only the wrappers with logic (`compile`, `compileOpenBatch`).
A design choice: the typed wrappers document the worker's surface; the generic one removes a layer.

### B4. Smaller server moves

- `WorkspaceSession.enrichedIndex` repeats `ReactorTypeIndex.rebuild`; `ReactorTypeIndex.shards()` exists only for it.
  Add `ReactorTypeIndex.withOpenEntries(...)`, drop `shards()`.
- Test discovery's cache and persistence (`RunCoordinator`, about 140 lines) → `run/TestDiscoveryStore`.
- `WritePass`: extract `ClassOutputs` (class-output deletion, mostly static already) and `ResourceMirror`,
  leaving about 700 lines of pass logic.
- Root package (64 files): the formatter subsystem (10 files, 1,005 lines, no loop or session reference) → `server.format`;
  `TestDiscovery`, `DiscoveredTest`, `TestSource`, `TestCounterparts` → `run/`.

---

## Group C — `lathe-analysis` (restructuring Phase 3 leftovers)

### C1. DRY helpers (small, safe)

**Done**: `TreePaths`, `nameRange`, the call-hierarchy item factories; `CodeActionProvider` removed.

- `TreePaths.enclosing(path, Class<T>[, stopAt])`: the parent walk is hand-written about twelve times
  (`CodeActionSupport` ×4, `ExtractVariableProvider` ×5, `SignatureHelpResolver`, `TypeResolver`,
  `ExtractMethodProvider`, `CallHierarchyIncomingLocator`).
- `nameRange(cu, offset, name)`: written about seven times.
- Call-hierarchy items are built in three places (`SourceAnalysisSession`, `CallHierarchyIncomingLocator`,
  `CallHierarchyOutgoingLocator`): two factories on `CallHierarchyItemDataCodec`.
- `CodeActionProvider` is implemented five times and never used as a type; the eight range-based providers have
  another signature; all fifteen are stateless and built per request. Remove the interface.

### C2. Split `SourceAnalysisSession` (1,385 lines, about 45 public methods)

**Done** (`refactor/session-components`): the session keeps compile, cache and completion (371 lines) and exposes
`Cursor`, `analysis`, `analysisAtVersion`, `cachedAnalysis` and the transient analyses; `NavigationFeatures`,
`ReferenceFeatures`, `HierarchyFeatures` and `FileFeatures` hold the features; the cached/transient search and
query twins are gone. Not merged, on purpose: `complete`/`completeTransient` (one reads and writes the open file's cache,
the other completes spliced debug-console text against an uncached baseline), and the debugger's expression attribution,
which compiles uncached without `transientAnalysis`'s per-call `[compile:fast]` INFO line.

The session keeps compile, cache and cursor resolution (`CursorContext`);
navigation, call hierarchy, rename targets and code-action dispatch move to stateless feature classes.
The cached/transient twins (`complete`/`completeTransient`, `searchReferences…`, `queryFile…`) become one
private transient-analysis step (no-twin-wrappers rule). Pairs naturally with B3.

### C3. Split `SourceLocator` (694 lines, six concerns)

**Done**: `Positions`, `CursorElements`, `DeclarationNames`, `GeneratedCode`; `declarationPath` in `SourceParser`, matching
enclosing types and parameter type trees (element comparison is impossible on a parse-only tree).

`Positions` (with `applyEdits`), `CursorElements`, `DeclarationNames`, `GeneratedCode`;
`declarationPath` moves to `SourceParser`.
`declarationPath` matches overloads with a regex over type `toString()` and classes by simple name, last match wins:
ad hoc text matching under `AGENTS.md`, wrong for same-named nested classes. Replace with element comparison.

### C4. `TypeResolver`'s text fallback (riskiest)

**Done** (`refactor/code-review-nov-2026`): split into `ExpectedValueResolver`, `ReceiverResolver` and `NameLookup`.
The text fallback stays, as the narrowly scoped exception `AGENTS.md` allows: it serves a snapshot attributed
before the dot, whose tree has no receiver at the completion's position, so javac's scope at the cursor cannot
replace it. Its name lookups already go through javac; `ReceiverResolverTest` pins every receiver kind it reads
and what it gives up on, and the code says why it exists.

When the position-based lookup fails, receivers are resolved from `receiverText`, which `SentinelInjector`
builds by scanning backwards with bracket counting, then decided by `indexOf('(')`, quotes and capitalisation.
That duplicates `Trees.getScope`, which `resolveScope` already uses, and is the pattern the parsing rule bans.
Split `TypeResolver` into `ExpectedValueResolver` and `ReceiverResolver`; pin the cases the fallback still
catches with tests before replacing it.

### C5. Packages and exports

**Done in part**: `DeclarationLocator`, `HoverFormatter` and `RenameConflicts` are package-private, and `completion` is no
longer exported (`CompletionOutcome`, the one type the server used, moved to the API package). The `refactor/` and
`navigation/` subpackages are not done: those classes use package-private helpers of the root package, so moving them
would make those helpers public in the exported package and widen, not narrow, the API. Worth doing only with an
unexported `internal` package for the shared helpers.

The root package holds 91 files, all exported. Five public types are used by no other package or module
(`DeclarationLocator`, `HoverFormatter`, `JavadocLocator`, `RenameConflicts`, `TypeHierarchyItemData`):
make them package-private now. Later: `refactor/` (with `CodeActionSupport` and `ExtractionSupport` split by concern)
and `navigation/` subpackages, and stop exporting `completion` once `CompletionOutcome` moves out.

---

## Group D — front ends

### D1. `LatheMcpTools` (1,408 lines)

**Done**: `tool(...)`, `McpText`, `McpTextTest`. Notes stay a separate request: folding them in would change all 15
engine methods' return types for one loop hop.

- One `tool(name, schema, annotations, description, handler)` helper replaces fifteen copies of the same
  definition shape (and the name literal written twice per tool).
- Text rendering (`*Result`, `*Line`, `noteLines`, `toWire`) → package-private `McpText`.
- Notes come back with the result of the same composed call, so no tool makes a second `engine.notes()` request;
  symbol candidates are checked inside one request.

### D2. `LatheEngine` API types

**Done**, except extracting the disk-write transaction.

`diagnostics()` returns lsp4j `Diagnostic`; `LatheDiagnostic` and `LatheLocation` wrap lsp4j types;
`LatheVerifyChange.deferral` is a `String` that MCP switches on by literal;
the wire-name parsing of `TestScope`, `CallDirection`, `SymbolScope` lives in the engine.
Make the engine's results plain values, type the deferral as its enum, move wire parsing to MCP (one `enumArg`).
Extract the disk-write transaction (`writeAndCommit` / `applyFileEdits`) shared by rename, format and fix imports.

### D3. `LatheWorkspaceService` commands

**Done.**

One `command(parse, work)` helper: widens the result (24 identity `thenApply(x -> x)` today)
and maps bad arguments to `InvalidParams` for every command (four of 26 do today).

---

## Group E — build side

### E1. OpenJDK sync bypasses the shared writers and the lock contract

**Done.**

`OpenJdkSync` writes `workspace.json`, `java-home` and the params files itself (`Json.write`),
so it skips write-if-changed and the unchanged-touch signal, ends `java-home` with a line separator the other
front-ends do not write, and holds a bare `LatheLock` with no heartbeat (a sync longer than two minutes looks
crashed). `SyncMojo` installs the server after releasing the lock.
Fix: `WorkspaceManifestWriter` and `CompileCapture.writeParams` for every front-end; one heartbeat lock over the
whole sync.

### E2. Maven and Gradle repeat the sync pipeline, in different orders

**Done**: `WorkspaceSync`. The capture-under-lock sequences stay apart (Maven locks around javac, Gradle around the copy);
the OpenJDK `ModuleConfigData` factory is not done.

`SyncCoordinator` and `LatheWorkspaceTask` call the same eight steps (dependency sources, JDK sources,
JDK type index, server install, manifest, JVM args, sync script, capture prune).
One `WorkspaceSync.run(WorkspaceInputs)` in `lathe-install`; each front-end keeps only the gathering.
The capture-under-lock sequence (`LatheCompiler`, `LatheCaptureTask`, OpenJDK) becomes one
`CompileCapture.capture(...)`.
`ModuleConfigData` (15 components) gets a short factory for OpenJDK's two literal-heavy constructions.

### E3. `lathe-core` carries server-only code (policy decision)

**Done** (decided: relax the rules): `LaunchPlan`, `LaunchOverlay`, `JdwpOptions`, `RunKind` and `PortUtil` moved to
`server.run`, `WorkspaceStyle` to `server.format`, `SourceTypeScanner` to the server, `ZipCache` to `lathe-install`, each
with its tests. `ClassSourceFile` stays: it shares a package-private class-file reader with `lathe-install`'s scanner.
`AGENTS.md` now says a constant or file helper one module owns lives in that module. The server-only `FileUtil`
methods and single-module `LatheLayout` constants move when next touched, under that rule.

`LaunchPlan`, `LaunchOverlay`, `JdwpOptions`, `RunKind`, `PortUtil`, `WorkspaceStyle`, `SourceTypeScanner`,
`ClassSourceFile` and about thirteen `FileUtil` methods are used only by the server, yet `lathe-core` is shaded
into `lathe-junit` (the user's test fork) and sits on the extension and Gradle plugin classpaths.
About 45 `LatheLayout.Maven` / `.Gradle` constants are used by one module each.
Two `AGENTS.md` rules push code there ("use or extend `FileUtil`", "constants exclusively in `LatheLayout`");
changing this means relaxing them to "the lowest module that uses it". Needs a decision.
(`AGENTS.md` also says `lathe-core` has no external dependencies; it requires gson and validcheck.)

---

## Group F — clients and tests

### F1. Neovim holds logic VS Code will need (editor-neutral first)

- New type: destination ranking and package derivation (`new.lua`), and a save-on-attach workaround for the
  server's staleness check on a type it just created.
- Stack traces: Java frames parsed with Lua patterns, then resolved by a `workspace/symbol` guess on the simple name.

Move into the server (ranked destinations from `lathe.packages`; a frame-resolving command) before the planned
VS Code features copy them. Needs a design of its own.

### F2. Command names and calls

The 26 commands mix `lathe.run.test`, `lathe.resourceOpen` and `runconfig`/`runconfigs`;
about eighteen Neovim call sites bypass `util.exec`. Pick one scheme (no external adopters, so a clean rename),
one Lua table of names. `lathe.tests.modules` has no client yet (built ahead for VS Code): note it in `status.md`.

### F3. Tests

- No Gradle LSP smoke test: the session helpers live in `lathe-maven-plugin/src/it/setup-support`.
  Sharing them needs a new test artifact (a new module or dependency: ask first).
- `ExtractMethodSweep` is a tool living in `lathe-server/src/test`; document it as one.
- `LatheMcpToolsTest` splits with D1.

---

## Order

1. **Group A** (one PR; A1 is its main commit).
2. **B1 + B4**, then **B2**, **B3 + C2** together.
3. **C1, C3, C5** (one PR), then **C4** behind tests.
4. **D1–D3**.
5. **E1 + E2**.
6. Decisions first: **E3** (rule change), **F1** (design), **F2** (rename scheme), **F3** (new test artifact).
