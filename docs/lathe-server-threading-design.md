# Lathe Server Threading Design

Future design note.
This documents the target direction discussed while preparing member-access completion.
It is not fully implemented yet.

---

## Current Problem

Lathe currently has several threads that can touch server state:

- LSP4J message-processing threads run request and notification handlers.
- `lathe-debouncer` runs delayed and submitted document compilation.
- `lathe-watcher` polls `.lathe/` and runs workspace reload.

Most reactor compilation already happens on `lathe-debouncer`, but not all mutable state is thread-confined.
`didOpen` currently compiles directly on an LSP thread.
`WorkspaceWatcher` runs `reload()` on `lathe-watcher`.
External source compilation may be triggered from request paths through `resolveAnalysis()`.

The result is a mixed model:

- Reactor compiles are mostly serialized by the debouncer.
- External compiles are protected by `ExternalFileCompiler.compile()` being synchronized.
- Registry reload can swap and close compilers while other threads may still be using them.
- `ExternalFileCompiler.setManifest()` can race with `compile()` unless it is serialized too.

These races are unlikely to corrupt user files, but they can produce transient bad diagnostics,
failed requests, or noisy javac/file-manager exceptions.

---

## Near-Term External Compiler Fix

Before a broader threading refactor, `ExternalFileCompiler` should protect all manifest-related mutable state.

`compile()` is already synchronized.
`setManifest()` should also be synchronized, and `compile()` should snapshot the manifest at the start of the method:

```java
public synchronized void setManifest(final WorkspaceManifest manifest) {
  this.manifest = manifest;
  analysis.clearCache();
}

public synchronized CompilationResult compile(
    final String uri, final String content, final CompileMode mode) throws IOException {
  final WorkspaceManifest currentManifest = manifest;
  ...
}
```

This prevents one compile from reading the source root from one manifest and the classpath or JDK module from another.

---

## Target Model

The clean target is a single server worker thread.

The LSP and watcher threads should decode incoming events, enqueue work, and return futures.
The worker thread should own Lathe's mutable state and all javac-backed objects.

Core invariant:

```text
All Lathe mutable state is read or written on the server worker thread.
```

This includes:

- `ModuleRegistry`
- `WorkspaceManifest`
- `ExternalFileCompiler`
- `ModuleCompiler` instances
- `StandardJavaFileManager` instances
- temporary compile directories
- `openFiles`
- `AnalysisEngine` caches
- pending debounce tasks
- workspace reload

Under this model, there is no need for broad `synchronized` methods or registry lifecycle locks.
No two Lathe operations run at the same time.

---

## Thread Boundary

Only immutable request data should cross into the worker.
Prefer extracting simple values before enqueueing:

```java
final String uri = params.getTextDocument().getUri();
final Position pos = params.getPosition();
return worker.submit(() -> doHover(uri, pos));
```

For document changes, copy the full document snapshot before enqueueing:

```java
final String uri = params.getTextDocument().getUri();
final String content = params.getContentChanges().getFirst().getText();
worker.execute(() -> doDidChange(uri, content));
```

Values that may cross into the worker:

- `String uri`
- `String content`
- `Position`
- `Path`
- other immutable request parameters

Values that should stay inside the worker:

- `Trees`
- `CompilationUnitTree`
- `TreePath`
- `Element`
- `TypeMirror`
- `FileAnalysis`
- `AnalysisEngine`
- `ModuleCompiler`
- `ExternalFileCompiler`
- `StandardJavaFileManager`

Values that cross out of the worker should be final LSP data transfer objects:

- `Hover`
- `Location`
- `SemanticTokens`
- `TextEdit`
- `Diagnostic`
- completion items in the future

Do not expose javac task-derived objects outside the worker.
Compute the LSP result on the worker and complete the returned `CompletableFuture` with that result.

---

## Worker API Shape

The worker is intentionally not just a generic executor.
It owns Lathe-specific policy:

- immediate notification work
- request work that returns a `CompletableFuture`
- debounce/cancel by URI for diagnostics
- ordered shutdown of worker-owned state

Possible shape:

```java
final class ServerWorker implements AutoCloseable {
  void execute(Runnable task);

  <T> CompletableFuture<T> submit(Callable<T> task);

  void schedule(String key, long delayMs, Runnable task);

  void cancel(String key);

  @Override
  public void close();
}
```

This is an evolution of the current `Debouncer`.
The important difference is that request handlers also use it,
so all server-owned mutable state is thread-confined.

---

## Request Handler Pattern

Public LSP methods become thin adapters.

Example request:

```java
@Override
public CompletableFuture<Hover> hover(final HoverParams params) {
  final String uri = params.getTextDocument().getUri();
  final Position pos = params.getPosition();
  return worker.submit(() -> doHover(uri, pos));
}
```

Worker-owned implementation:

```java
private Hover doHover(final String uri, final Position pos) {
  final AnalysisEngine analysis = resolveAnalysis(uri);
  return analysis != null ? analysis.hover(uri, pos, registry.allSourceRoots(), manifest) : null;
}
```

Example notification:

```java
@Override
public void didOpen(final DidOpenTextDocumentParams params) {
  final String uri = params.getTextDocument().getUri();
  final String content = params.getTextDocument().getText();
  worker.execute(() -> doDidOpen(uri, content));
}
```

Worker-owned implementation:

```java
private void doDidOpen(final String uri, final String content) {
  openFiles.put(uri, content);
  compileWith(uri, content, CompileMode.OPEN);
}
```

---

## Debounced Compilation

`didChange` still clears diagnostics immediately from the worker and schedules one delayed compile per URI:

```java
private void doDidChange(final String uri, final String content) {
  openFiles.put(uri, content);
  client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
  worker.schedule(uri, debounceMs, () -> compileWith(uri, content, CompileMode.FAST));
}
```

`didSave` cancels any pending compile for the URI and submits an immediate full compile:

```java
private void doDidSave(final String uri) {
  worker.cancel(uri);
  compileWith(uri, Files.readString(toPath(uri)), CompileMode.FULL);
  registry.moduleFor(toPath(uri)).ifPresent(module -> scheduleOpenFilesInModule(uri, module));
}
```

All calls to `SourceCompiler.compile()` happen on the worker.

---

## Workspace Reload

`WorkspaceWatcher` should only detect changes.
It should not mutate server state directly.

Current production path:

```text
lathe-watcher
  -> WorkspaceWatcher.poll()
  -> onReload.run()
  -> LatheTextDocumentService.reload()
```

Target path:

```text
lathe-watcher
  -> WorkspaceWatcher.poll()
  -> onReload.run()
  -> worker.execute(doReload)
```

Worker-owned reload:

```java
private void doReload() {
  setRegistry(ModuleRegistry.scan(workspaceRoot));
  setManifest(WorkspaceManifest.load(workspaceRoot));
  scheduleAllOpenFiles();
}
```

Because reload runs on the worker, it cannot close a registry while a compile is using it.

---

## Shutdown

Shutdown should close worker-owned state on the worker.

Shape:

```java
void close() {
  if (watcher != null) {
    watcher.close();
  }

  worker.submit(
      () -> {
        registry.close();
        externalCompiler.close();
        return null;
      })
      .join();

  worker.close();
}
```

This avoids closing file managers and temp directories from an arbitrary LSP shutdown thread.

---

## Concurrent Maps

After the single-worker invariant is implemented,
the current concurrent maps are no longer required for correctness:

- `LatheTextDocumentService.openFiles`
- `AnalysisEngine.cache`
- `ModuleRegistry.compilers`
- pending debounce tasks inside the worker

Do not convert them immediately during the threading refactor.
Keep the concurrent maps first to reduce blast radius.
Once all accesses are clearly worker-owned,
they can be replaced with ordinary `HashMap` instances as a cleanup.

The design should still treat these maps as worker-owned even before the data structure is simplified.

---

## Completion Implications

Member-access completion should use the same worker.

The LSP method extracts immutable request data and enqueues the request:

```java
public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
    final CompletionParams params) {
  final String uri = params.getTextDocument().getUri();
  final Position pos = params.getPosition();
  return worker.submit(() -> doCompletion(uri, pos));
}
```

The worker reads the current open-file snapshot and runs cached or sentinel completion.
If sentinel completion needs one or more fresh javac tasks,
those tasks are naturally serialized with diagnostics, reload, and other compiler work.

This avoids adding `synchronized` to `ModuleCompiler.compile()`.
It also leaves room for a future design with one worker per module or a bounded pool of compiler contexts.

---

## Future Scaling

The single-worker model is the simplest correct model.
If it becomes a latency bottleneck, it can evolve without changing the public LSP method shape.

Possible future shapes:

- one worker per module
- one worker per compiler/file-manager context
- bounded pool of compiler contexts
- separate cheap-read lane for cache-only requests

Do not introduce those until profiling shows the single-worker model is too slow.
