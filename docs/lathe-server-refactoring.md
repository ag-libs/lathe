# Lathe Server — Refactoring Design

## Goals

1. Introduce cleaner ownership: `CompilationContext` owns the compiler, not the other way around.
2. Fix a resource leak (`parsingFm`).
3. Remove mutable shared-state patterns (`setManifest`, `patchedModules` accumulation).
4. Extend the threading model to support per-module parallelism,
   so an agentic LSP client that opens files in multiple modules simultaneously benefits from parallel processing.

This document records the current design findings and the target design for refactoring.

---

## Current Architecture

### Threads

Three threads are active during a session:

| Thread | Name | Role |
|---|---|---|
| LSP4J receive thread(s) | internal to lsp4j | Accept LSP messages, extract immutable data (URI + content strings), dispatch to worker |
| `lathe-worker` | single-thread executor in `ServerWorker` | Owns ALL mutable state; does all compilation, caching, diagnostic publishing |
| `lathe-watcher` | single-thread executor in `WorkspaceWatcher` | Polls `.lathe/` every 2 s; fires `worker.execute(session::reload)` on change |

The worker serializes everything.
LSP receive threads only enqueue `Runnable`/`Callable` carrying immutable data.
`CompletableFuture`s returned from `worker.submit()` complete on the worker thread
and are read by lsp4j's response machinery.

### State (all worker-thread-owned)

```
DocumentSession
  openFiles: Map<String, String>                ← URI → latest text
  registry: ModuleRegistry                      ← replaced entirely on reload
  manifest: WorkspaceManifest                   ← immutable snapshot, replaced on reload
  externalCompiler: ExternalFileCompiler        ← persists across reloads; manifest MUTATED on reload
  watcher: WorkspaceWatcher
  client: LanguageClient

ModuleRegistry
  modules: List<ModuleConfig>                   ← immutable list built at scan
  compilers: Map<ModuleConfig, ModuleCompiler>  ← lazily populated on first file open

ModuleCompiler  (one per module, created on first file access)
  fm: StandardJavaFileManager                   ← initialized with classpath/modulepath/output locations
  td: Path                                      ← temp dir for content writes
  compilerArgs: List<String>
  analysis: AnalysisEngine                      ← created by the compiler; holds a back-reference to it

AnalysisEngine  (one per ModuleCompiler + one inside ExternalFileCompiler)
  compiler: SourceCompiler                      ← back-reference to its creator
  cache: Map<String, FileAnalysis>              ← per-file attributed tree + tokens + content
  completionProvider: CompletionProvider
  definitionLocator: DefinitionLocator
  javadocLocator: JavadocLocator
  (unnamed) parsingFm: StandardJavaFileManager  ← NEVER CLOSED — resource leak
```

### Data Flow: `didChange` → diagnostics

```
LSP recv: extract uri, content (immutable)
  → worker.execute(onChange)

Worker – onChange:
  openFiles.put(uri, content)
  client.publishDiagnostics([])                  ← clear immediately
  worker.schedule(uri, 500 ms, debounce)

Worker – debounce fires:
  openFiles.get(uri)                             ← latest content
  compileWith(uri, content, FAST)
    registry.moduleFor(path)                     ← scan source roots
    registry.engineFor(module)                   ← lazy: computeIfAbsent(config, ModuleCompiler::new).analysis()
    AnalysisEngine.compile(uri, content, FAST)
      ModuleCompiler.compile(...)
        write content to td/relative-path
        build JavacTask(options, sourceFile)
        parse → analyze (no codegen for FAST)
        TokenScanner.scan(trees, cu)
        return CompilationResult(diags, FileAnalysis)
      cache.put(uri, fileAnalysis)
      return filtered LSP Diagnostic list
  client.publishDiagnostics(diags)
  client.refreshSemanticTokens()
```

### Data Flow: `hover` → response

```
LSP recv → worker.submit(hover) → CompletableFuture

Worker:
  resolveAnalysis(uri)                       ← registry.moduleFor → registry.engineFor (or externalCompiler)
  AnalysisEngine.hover(uri, pos, allSourceRoots, manifest)
    cache.get(uri)                           ← FileAnalysis (trees, cu, elements, types)
    SourceLocator.pathAt(trees, cu, offset)
    SourceLocator.elementAt(trees, path)
    manifest.originLabel(element, fm)        ← fm comes from ModuleCompiler, surfaced via AnalysisEngine.compiler
    HoverFormatter.format(...)
```

### Data Flow: workspace reload

```
Watcher thread: params or manifest mtime changed
  → worker.execute(session::reload)

Worker – reload():
  setRegistry(ModuleRegistry.scan(workspaceRoot))  ← old registry.close() → all ModuleCompilers closed
  manifest = WorkspaceManifest.load(workspaceRoot)
  externalCompiler.setManifest(manifest)           ← MUTATION: clears cache, sets new manifest field
  scheduleAllOpenFiles()                           ← re-compile every open file at delay=0
```

---

## Problems

### P1 — Inverted / circular ownership

`ModuleCompiler` creates `AnalysisEngine(this)`.
`AnalysisEngine` holds a `SourceCompiler` back-reference to `ModuleCompiler`.
`ModuleRegistry.engineFor()` creates the compiler and returns `compiler.analysis()`.

Callers (`DocumentSession`) never hold a `ModuleCompiler` reference — they only see `AnalysisEngine`.
Yet `AnalysisEngine` was created by `ModuleCompiler`.
The wrapper wraps its creator.

Conceptually the cache+feature-dispatch layer should *own* the compilation-machinery layer,
not the other way around.

### P2 — `AnalysisEngine` is a misleading name

It is a per-file analysis *cache* that also dispatches feature requests (hover, definition, completion).
"Engine" suggests active processing.
It is closer to a `CompilationContext`:
the stateful result of a compiler configuration, holding its cache
and the feature logic that serves reads from it.

### P3 — Resource leak: `parsingFm` never closed

In `AnalysisEngine`'s constructor:

```java
final var parsingFm = compiler.compiler().getStandardFileManager(null, null, null);
final var parser = new SourceParser(compiler.compiler(), parsingFm);
```

`parsingFm` is stored only in `SourceParser` (inside `DefinitionLocator`/`JavadocLocator`).
`clearCache()` only clears the map.
`ModuleCompiler.close()` calls `analysis.clearCache()` — but `parsingFm` is never closed.
Each module leaks a `StandardJavaFileManager` until GC.

### P4 — `ExternalFileCompiler.manifest` mutable field

`ExternalFileCompiler` is created once with an empty manifest, then mutated via `setManifest()` on every reload.
The mutable manifest field, combined with `patchedModules: Set<String>` (which accumulates entries across
the object's lifetime without being cleared when the manifest changes), makes lifetime reasoning hard.
Replacing the whole object on reload is simpler and removes the mutation.

### P5 — Parallel but inconsistent `ModuleCompiler` / `ExternalFileCompiler`

Both implement `SourceCompiler` and both create `AnalysisEngine(this)`, but the pattern is inconsistent:
`ModuleCompiler.compile()` is a clean delegate called through `AnalysisEngine.compile() → compiler.compile()`,
while `ExternalFileCompiler.compile()` has its task-construction and result-building logic inline,
duplicating the `JavacTask` setup, token scanning, and `FileAnalysis` construction
that `ModuleCompiler.runTask()` already encapsulates.

---

## New Design

### Core idea: flip ownership, add per-module workers

`CompilationContext` (renamed from `AnalysisEngine`) owns its `SourceCompiler` instead of being created
by it. A new `ModuleWorker` gives each module its own single-thread executor, so different modules
compile in parallel while operations on the same module remain serialized.
`WorkspaceSession` (renamed from `DocumentSession`) owns all workspace-level state as plain fields
on the server worker thread, then dispatches per-file work to module workers and composes the
resulting futures to publish diagnostics.

### Classes

#### `CompilationContext` (renamed from `AnalysisEngine`)

```
CompilationContext
  compiler: SourceCompiler              ← owns; either ModuleCompiler or ExternalCompiler
  parsingFm: StandardJavaFileManager    ← owns; closed in close()
  cache: Map<String, FileAnalysis>      ← not thread-safe; only accessed from owning ModuleWorker thread
  completionProvider: CompletionProvider
  definitionLocator: DefinitionLocator
  javadocLocator: JavadocLocator

  close()  → parsingFm.close() + compiler.close()
```

All methods unchanged: `compile`, `complete`, `hover`, `definition`, `semanticTokens`,
`isCached`, `dropFromCache`, `clearCache`.
No LSP publishing happens here — the context returns data (diagnostics, hover, location);
`WorkspaceSession` handles publishing.

#### `ModuleCompiler` (cleaned up)

Remove `analysis` field and `analysis()` method.
`close()` no longer calls `analysis.clearCache()`.
Pure `SourceCompiler` implementation; no back-reference.

#### `ExternalCompiler` (renamed from `ExternalFileCompiler`)

```
ExternalCompiler
  manifest: WorkspaceManifest  ← final; set at construction, never mutated
  javac: JavaCompiler
  fm: StandardJavaFileManager
  td: Path
  patchedModules: Set<String>  ← starts empty; reset on each object replacement
```

No `analysis` field. No `setManifest()`. No `ensureCompiled`.
`close()` closes `fm` and deletes `td`.

#### `ModuleWorker` (new, package-private in `module` package)

```
ModuleWorker
  executor: ScheduledExecutorService(1)         ← "lathe-module-<name>" or "lathe-ext"
  pending: Map<String, ScheduledFuture<?>>      ← debounce per URI; accessed from executor thread only
  context: CompilationContext                   ← lazily created from contextFactory on first use
  contextFactory: Supplier<CompilationContext>  ← e.g. () → new CompilationContext(new ModuleCompiler(config))

  submit(Callable<T>)  → CompletableFuture<T>
  schedule(key, delayMs, Runnable)
  cancel(key)
  close()  → executor.shutdownNow(); if (context != null) context.close()
```

`submit` enqueues the callable on the executor. If `context` is null, `contextFactory.get()` is called
first (lazy init on the module's own thread). `close()` shuts down the executor (cancelling pending work)
then closes the context if it was initialized.

#### `ModuleWorkspace` (renamed from `ModuleRegistry`)

```
ModuleWorkspace
  modules: List<ModuleConfig>                   ← immutable; built at scan
  workers: Map<ModuleConfig, ModuleWorker>      ← lazily populated; HashMap (worker-thread-owned)
  externalWorker: ModuleWorker                  ← eager; wraps ExternalCompiler(manifest)

  static scan(workspaceRoot, manifest) → ModuleWorkspace
  static empty()                        → ModuleWorkspace

  moduleFor(Path)         → Optional<ModuleConfig>
  allSourceRoots()        → List<Path>
  workerFor(ModuleConfig) → ModuleWorker          ← lazy computeIfAbsent
  close()                 → closes all workers (including externalWorker)
```

`workerFor` is the only module-worker accessor; it is only called from the server worker thread
(lazy creation is not concurrent).
`externalWorker` is created eagerly in the constructor with a factory
`() → new CompilationContext(new ExternalCompiler(manifest))`.

#### `WorkspaceSession` (renamed from `DocumentSession`)

```
WorkspaceSession
  client: LanguageClient                ← final; passed at construction (WorkspaceSession created inside connect())
  worker: ServerWorker                  ← final
  debounceMs: long                      ← final
  workspaceRoot: Path
  manifest: WorkspaceManifest           ← plain field; server-worker-owned
  workspace: ModuleWorkspace            ← plain field; server-worker-owned; replaced on reload
  watcher: WorkspaceWatcher             ← plain field; initialized in initialize()
  openFiles: ConcurrentHashMap<String, String>  ← URI → latest content
```

`openFiles` is `ConcurrentHashMap` because it is written on the server worker thread and
read in `thenAccept` callbacks (which run on module worker threads for the stale check).

All other fields are plain (non-atomic): they are only read and written on the server worker thread,
and the single-thread executor guarantees serializability without any synchronization primitives.

#### `WorkspaceWatcher` (simplified)

Remove `ScheduledExecutorService`, `onReload` callback, `AtomicLong`, `AtomicReference`.
The watcher becomes stateful poll-only logic:

```
WorkspaceWatcher
  latheDir: Path
  manifestPath: Path
  lastManifestMtime: long     ← plain field; accessed only from server worker thread
  lastParams: Fingerprint     ← plain field; accessed only from server worker thread

  poll() → boolean            ← returns true if anything changed; no side effects
```

`WorkspaceSession.initialize()` schedules `checkForChanges()` on the server worker at a fixed rate:

```java
worker.scheduleAtFixedRate("watcher", 2_000L, () -> {
    if (watcher.poll()) {
        reload();
    }
});
```

`ServerWorker` gains a `scheduleAtFixedRate(key, intervalMs, Runnable)` method.

---

### Threading Model

```
Thread                         Role
──────────────────────────────────────────────────────────────────────────────
LSP recv (lsp4j)               Accept LSP messages; extract immutable URI/content;
                               call worker.execute() or worker.submit()
lathe-worker (ServerWorker)    Owns WorkspaceSession state as plain fields;
                               routes per-file work to module workers;
                               schedules debounce and watcher poll
lathe-module-<name>            One per module (ModuleWorker); owns CompilationContext;
                               runs JavacTask compilation
lathe-ext                      ModuleWorker for external (dep/JDK) files
```

`CompilationContext` and its enclosed `SourceCompiler` are **not thread-safe**;
each is always accessed from exactly one `ModuleWorker` thread.

The server worker thread owns `workspace`, `manifest`, `watcher`, and `workspaceRoot` as plain fields.
No `AtomicReference`, no `ConcurrentHashMap` (except `openFiles`), no `synchronized` needed.

---

### Data Flows

#### Two-hop dispatch

For LSP request/response methods (hover, definition, completion, semanticTokens),
the server worker executes a first hop to read workspace-level state (module routing, manifest snapshot,
open-file content) and dispatch to the correct module worker.
The module worker runs the second hop (actual compilation/cache lookup).
`thenCompose` flattens the nested future:

```
LSP recv
  → worker.submit(() -> {
      // first hop: on server worker thread
      final var path = toPath(uri);
      final var snap = manifest;                       ← captured snapshot
      final var roots = workspace.allSourceRoots();    ← captured snapshot
      final var w = workspace.workerFor(workspace.moduleFor(path).orElseThrow());
      return w.submit(() -> ctx.hover(uri, pos, roots, snap));
                                    // ↑ second hop: on module worker thread
  })                                // returns CompletableFuture<CompletableFuture<Hover>>
  .thenCompose(f -> f)             // → CompletableFuture<Hover>
```

#### `didOpen`

```
LSP recv → worker.execute(() -> session.onOpen(uri, content))

Server worker – onOpen:
  openFiles.put(uri, content)
  compileAndPublish(uri, content, CompileMode.OPEN)

compileAndPublish(uri, content, mode):
  final var path = toPath(uri)
  final var module = workspace.moduleFor(path)
  if module.isPresent():
    workspace.workerFor(module.get())
      .submit(() -> context.compile(uri, content, mode))
      .thenAccept(diags -> publishIfCurrent(uri, content, diags))
  else if manifest.containsFile(path):
    workspace.externalWorker
      .submit(() -> context.compile(uri, content, mode))
      .thenAccept(diags -> publishIfCurrent(uri, content, diags))
  else:
    client.publishDiagnostics(singleDiag(uri, "Run `mvn process-test-classes`…", Warning))
```

`publishIfCurrent` runs in the `thenAccept` callback (on the module worker thread):

```java
void publishIfCurrent(String uri, String content, List<Diagnostic> diags) {
    if (content.equals(openFiles.get(uri))) {          ← ConcurrentHashMap read; safe from any thread
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diags));
        client.refreshSemanticTokens();
    }
}
```

#### `didChange` (debounced)

```
LSP recv → worker.execute(() -> session.onChange(uri, content))

Server worker – onChange:
  openFiles.put(uri, content)
  client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()))  ← clear immediately
  worker.schedule(uri, debounceMs, () -> {
      final var latest = openFiles.get(uri)
      if (latest != null):
          compileAndPublish(uri, latest, CompileMode.FAST)
  })
```

#### `didClose`

```
LSP recv → worker.execute(() -> session.onClose(uri))

Server worker – onClose:
  openFiles.remove(uri)
  worker.cancel(uri)
  workspace.dropFromAllCaches(uri)           ← dispatched to each module worker
  client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()))
```

`dropFromAllCaches` dispatches `context.dropFromCache(uri)` to each module worker
(and the external worker) so the cache drop is serialized on the thread that owns the context.

#### `didSave`

```
LSP recv → worker.execute(() -> session.onSave(uri, savedContent))

Server worker – onSave:
  worker.cancel(uri)
  final var content = savedContent ?? openFiles.get(uri) ?? Files.readString(path)
  final var module = workspace.moduleFor(toPath(uri))
  if module.isPresent():
    workspace.workerFor(module.get())
      .submit(() -> context.compile(uri, content, CompileMode.FULL))
      .thenAccept(diags -> {
          publishIfCurrent(uri, content, diags)
          worker.execute(() -> scheduleOpenFilesInModule(uri, module.get()))  ← back to server worker
      })
  else:
    compileAndPublish(uri, content, CompileMode.FULL)
```

After a FULL compile the server worker re-schedules sibling open files (same module) for recompilation.
This uses `worker.execute` to hop back onto the server worker thread, where `openFiles` and `workspace`
can be read safely.

#### `hover` / `definition`

```
LSP recv → worker.submit(() -> session.hoverFuture(uri, pos)) → CompletableFuture<Hover>

Server worker – hoverFuture:
  final var w = routeWorker(uri)
  if w is empty: return CompletableFuture.completedFuture(null)
  final var roots = workspace.allSourceRoots()
  final var snap = manifest
  return w.submit(() -> context.hover(uri, pos, roots, snap))
```

`LatheTextDocumentService.hover()` calls:
```java
worker.submit(() -> session.hoverFuture(uri, pos)).thenCompose(f -> f)
```

#### `completion`

```
LSP recv → worker.submit(() -> session.completionFuture(uri, pos)) → CompletableFuture<List<CompletionItem>>

Server worker – completionFuture:
  final var content = openFiles.get(uri)
  if content == null: return CompletableFuture.completedFuture(List.of())
  final var w = routeWorker(uri)
  if w is empty: return CompletableFuture.completedFuture(List.of())
  return w.submit(() -> context.complete(uri, content, pos))
```

#### `semanticTokens`

```
LSP recv → worker.submit(() -> session.semanticTokensFuture(uri))

Server worker – semanticTokensFuture:
  final var w = routeWorker(uri)
  if w is empty: return CompletableFuture.completedFuture(null)
  return w.submit(() -> context.semanticTokens(uri))
```

#### Workspace reload

```
Server worker – checkForChanges (scheduled every 2 s):
  if watcher.poll():
      reload()

Server worker – reload:
  final var newManifest = WorkspaceManifest.load(workspaceRoot)
  final var newWorkspace = ModuleWorkspace.scan(workspaceRoot, newManifest)
  final var oldWorkspace = workspace
  workspace = newWorkspace
  manifest = newManifest
  oldWorkspace.close()       ← shuts down all module worker executors; closes all CompilationContexts
  scheduleAllOpenFiles()     ← worker.schedule(uri, 0, ...) for each open URI
```

`oldWorkspace.close()` shuts down each `ModuleWorker`'s executor (`shutdownNow`) and then closes its
`CompilationContext`. In-flight tasks on those executors are interrupted; in-progress compilations
produce a result that will be discarded by the stale check in `publishIfCurrent` (the old content
snapshot no longer matches `openFiles.get(uri)` once the new compile fires).

---

### Initialization sequence

```
1. LatheLanguageServer.connect(client)
   → WorkspaceSession created with final client field
   → ServerWorker.scheduleAtFixedRate("watcher", 2000, checkForChanges)

2. LatheLanguageServer.initialize(params) — on LSP recv thread
   → worker.execute(() -> session.initialize(workspaceRoot))

3. Server worker – initialize:
   → manifest = WorkspaceManifest.load(workspaceRoot)
   → workspace = ModuleWorkspace.scan(workspaceRoot, manifest)
   → watcher = new WorkspaceWatcher(workspaceRoot)
   → scheduleAllOpenFiles() (none at startup)

4. LSP client opens first file (didOpen)
   → server worker routes to workspace.workerFor(module)
   → ModuleWorker created lazily (first use of that module)
   → on module worker: contextFactory.get() → new CompilationContext(new ModuleCompiler(config))
   → compilation runs, future completes, diagnostics published
```

---

### What becomes parallel vs what stays serialized

| Operation | Behaviour |
|---|---|
| Compilation in module A while compilation in module B | parallel (separate module workers) |
| Two compilations in the same module | serialized (same module worker) |
| hover in module A while compiling module B | parallel |
| hover and compile in the same module | serialized (same module worker) |
| Workspace reload vs ongoing module compilation | parallel; stale check discards superseded results |
| External file operations | serialized (single `externalWorker`) |
| Watcher poll and diagnostics publish | server worker / module worker — parallel |

---

## Change Table

| What | Before | After |
|---|---|---|
| `AnalysisEngine` | owned by compiler, holds back-ref | renamed `CompilationContext`; owns compiler; `close()` closes `parsingFm` + compiler |
| `ModuleCompiler` | creates `AnalysisEngine(this)`, exposes `.analysis()` | no analysis field; pure compilation machinery |
| `ExternalFileCompiler` | mutable manifest; `AnalysisEngine` field | renamed `ExternalCompiler`; `final manifest`; no analysis field |
| `ModuleRegistry` | creates `ModuleCompiler`, returns `.analysis()` | renamed `ModuleWorkspace`; creates `ModuleWorker` per module (lazy); `workerFor()` API |
| `ModuleWorker` | did not exist | new package-private class; single-thread executor + lazy `CompilationContext` |
| `DocumentSession` | client set via `connect()`; all work inline | renamed `WorkspaceSession`; `final client` (ctor); routes to module workers; composes futures |
| `DocumentSession.externalCompiler` | persists across reloads; manifest mutated | absorbed into `ModuleWorkspace.externalWorker`; replaced on reload |
| `parsingFm` resource | no close path | `CompilationContext.close()` closes it |
| `WorkspaceWatcher` | own executor; `onReload` callback | pure `poll() → boolean`; server worker schedules polling |
| `ServerWorker` | no fixed-rate scheduling | gains `scheduleAtFixedRate` for watcher polling |
| Per-module parallelism | none (single worker serializes all) | separate `ModuleWorker` per module |
| `openFiles` | `HashMap`, server-worker only | `ConcurrentHashMap` (written on server worker; read in `thenAccept` stale check) |

## What Does NOT Change

- `SourceCompiler` interface
- `WorkspaceManifest` (immutable snapshot, already well-structured)
- All feature implementations: `CompletionProvider`, `DefinitionLocator`, `HoverFormatter`, `JavadocLocator`, `SourceLocator`, `TokenScanner`
- `ModuleConfig` and scan logic (moved into `ModuleWorkspace`)
- `FileAnalysis`, `CompilationResult`, `SemanticToken`, `CompileMode`
- LSP protocol layer: `LatheTextDocumentService`, `LatheLanguageServer`
- `JavaFormatter`
