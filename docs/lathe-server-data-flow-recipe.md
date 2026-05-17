# Lathe Server Data Flow Recipe

This recipe refines the current server refactor.
It keeps the high-level design from `lathe-server-new-design.md`:
a single server worker owns workspace state, and per-module workers own javac-backed compilation contexts.

The goal is to make data flow explicit and reduce nested delegation lambdas.
Module workers should return pure results.
The server worker should remain the only owner of workspace/session state, including open-file snapshots and stale-result checks.

## Settled Implementation Scope

Use the lean version of this recipe.

Implement these pieces:

- `OpenFile`, `CompileRequest`, and `CompileResult`.
- `CompilerRoute`, centralized in `WorkspaceSession`.
- Domain-specific `ModuleWorker` methods for compile and feature operations.
- A private, minimal compile-result policy in `WorkspaceSession`.
- Compile-result callbacks that always hop back to `lathe-worker` before reading `openFiles` or publishing diagnostics.

Do not introduce a separate dispatcher layer.
Do not make `AfterCompile` a top-level abstraction.
Do not add feature request records unless they make the code clearer than direct method parameters.

`workspaceGeneration` is optional but recommended.
If included, it makes reload stale-result handling explicit even when file content does not change.

## Thread Ownership

| Thread | Owns | Does not own |
|---|---|---|
| LSP4J receive thread | immutable request data extracted from LSP params | workspace state, javac state |
| `lathe-worker` | `WorkspaceSession`, `ModuleWorkspace`, `WorkspaceManifest`, `WorkspaceWatcher`, `openFiles`, routing, stale checks, client publishing | javac-backed `CompilationContext` |
| `lathe-module-<rel>` | one `CompilationContext` and its `SourceCompiler` | workspace routing state, `openFiles` |
| `lathe-external` | external-file `CompilationContext` | workspace routing state, `openFiles` |

`openFiles` should remain a plain `HashMap`.
It is read and written only on `lathe-worker`.

Module workers must not read `openFiles`.
Compilation results hop back to `lathe-worker` before publication.

## Core Values

### `OpenFile`

`OpenFile` is a server-worker-owned snapshot of the latest open-file content.
Use a monotonically increasing version to make stale-result checks clear.
Prefer including `workspaceGeneration` in the implementation so reloads also invalidate old results.

```java
record OpenFile(String uri, String content, long version, long workspaceGeneration) {}
```

`WorkspaceSession` stores:

```java
private final Map<String, OpenFile> openFiles = new HashMap<>();
```

Every `didOpen` and `didChange` creates a new `OpenFile` version.
`didSave` uses either the saved content, the open-file content, or disk content.
If disk content is used for a file that is not open, the snapshot still carries a version for result matching.

### Compile Requests And Results

Module workers should receive explicit request values and return explicit result values.

```java
record CompileRequest(
    String uri, String content, long version, long workspaceGeneration, CompileMode mode) {}

record CompileResult(
    String uri, long version, long workspaceGeneration, List<Diagnostic> diagnostics) {}
```

The result contains the version from the request.
It does not publish diagnostics.
It does not inspect workspace state.

Feature requests do not need records by default.
Prefer direct `ModuleWorker.hover(...)`, `complete(...)`, `definition(...)`, and `semanticTokens(...)` parameters unless a record clearly improves readability.
If records become useful, they can follow this shape:

```java
record HoverRequest(String uri, Position position, List<Path> sourceRoots, WorkspaceManifest manifest) {}

record DefinitionRequest(String uri, Position position, List<Path> sourceRoots, WorkspaceManifest manifest) {}

record CompletionRequest(String uri, String content, long version, Position position) {}
```

Completion carries content because it must work from the open-file snapshot.
Hover, definition, and semantic tokens read from the existing compilation cache.

## `CompilerRoute`

`CompilerRoute` is a small value created on `lathe-worker`.
It answers: where should this file be compiled or queried?

Suggested shape:

```java
sealed interface CompilerRoute {
  record Module(ModuleWorker worker, ModuleConfig module) implements CompilerRoute {}

  record External(ModuleWorker worker) implements CompilerRoute {}

  record Missing(String uri, String message) implements CompilerRoute {}
}
```

Routing should be centralized in `WorkspaceSession`:

```java
private CompilerRoute routeCompiler(final String uri) {
  final Path path = toPath(uri);

  return workspace
      .moduleFor(path)
      .<CompilerRoute>map(module -> new CompilerRoute.Module(workspace.workerFor(module), module))
      .orElseGet(
          () -> {
            if (manifest.containsFile(path)) {
              return new CompilerRoute.External(workspace.externalWorker());
            }

            return new CompilerRoute.Missing(
                uri, "Run `mvn process-test-classes` to initialize Lathe for this module");
          });
}
```

`ModuleWorkspace.workerFor(ModuleConfig)` and `ModuleWorkspace.externalWorker()` need to be accessible to `WorkspaceSession`.
They still must only be called on `lathe-worker`.

## `AfterCompile`

`AfterCompile` is a private callback policy in `WorkspaceSession`.
It runs on `lathe-worker` after a module worker returns a `CompileResult`.
Keep it minimal.
If one helper method is clearer during implementation, use the helper instead of this interface.

Suggested shape:

```java
@FunctionalInterface
private interface AfterCompile {
  void accept(OpenFile snapshot, CompileResult result);
}
```

Typical policies:

```java
private final AfterCompile publishIfCurrent =
    (snapshot, result) -> publishIfCurrent(snapshot, result);

private AfterCompile publishIfCurrentThen(final Runnable followUp) {
  return (snapshot, result) -> {
    if (publishIfCurrent(snapshot, result)) {
      followUp.run();
    }
  };
}
```

`publishIfCurrent` should return whether publication happened:

```java
private boolean publishIfCurrent(final OpenFile snapshot, final CompileResult result) {
  final OpenFile current = openFiles.get(snapshot.uri());
  if (current == null) {
    return false;
  }

  if (current.version() != result.version()) {
    return false;
  }

  if (current.workspaceGeneration() != result.workspaceGeneration()) {
    return false;
  }

  client.publishDiagnostics(new PublishDiagnosticsParams(result.uri(), result.diagnostics()));
  client.refreshSemanticTokens();
  return true;
}
```

This drops results for closed files, superseded edits, and old workspaces after reload.

## Compile Submission

Compile submission should have one obvious shape:

```java
private void submitCompile(
    final OpenFile snapshot,
    final CompileMode mode,
    final AfterCompile afterCompile) {

  switch (routeCompiler(snapshot.uri())) {
    case CompilerRoute.Module route ->
        submitTo(route.worker(), snapshot, mode, afterCompile);

    case CompilerRoute.External route ->
        submitTo(route.worker(), snapshot, mode, afterCompile);

    case CompilerRoute.Missing route ->
        publishMissingDiagnostic(route.uri(), route.message());
  }
}
```

The module-worker future must hop back to `lathe-worker` before reading `openFiles` or publishing:

```java
private void submitTo(
    final ModuleWorker worker,
    final OpenFile snapshot,
    final CompileMode mode,
    final AfterCompile afterCompile) {

  final var request =
      new CompileRequest(
          snapshot.uri(),
          snapshot.content(),
          snapshot.version(),
          snapshot.workspaceGeneration(),
          mode);

  worker
      .compile(request)
      .thenAccept(result -> serverWorker.execute(() -> afterCompile.accept(snapshot, result)))
      .exceptionally(ex -> {
        serverWorker.execute(() -> publishCompileError(snapshot, mode, ex));
        return null;
      });
}
```

No `thenAccept` callback should directly read `openFiles`.
No `thenAccept` callback should directly publish diagnostics unless it is already running on `lathe-worker`.

## Module Worker API

The public `ModuleWorker` API should be domain-specific.
Keep generic context access private.
Records for hover/definition/completion are optional.
Direct parameters are acceptable when they are simpler.

Suggested shape:

```java
final class ModuleWorker {
  CompletableFuture<CompileResult> compile(CompileRequest request) {
    return submit(
        ctx ->
            new CompileResult(
                request.uri(),
                request.version(),
                request.workspaceGeneration(),
                ctx.compile(request.uri(), request.content(), request.mode())));
  }

  CompletableFuture<Hover> hover(
      String uri, Position position, List<Path> sourceRoots, WorkspaceManifest manifest) {
    return submit(ctx -> ctx.hover(uri, position, sourceRoots, manifest));
  }

  CompletableFuture<List<CompletionItem>> complete(
      String uri, String content, Position position) {
    return submit(ctx -> ctx.complete(uri, content, position));
  }

  CompletableFuture<Optional<Location>> definition(
      String uri, Position position, List<Path> sourceRoots, WorkspaceManifest manifest) {
    return submit(ctx -> ctx.definition(uri, position, sourceRoots, manifest));
  }

  CompletableFuture<SemanticTokens> semanticTokens(String uri) {
    return submit(ctx -> encodeTokens(ctx.semanticTokens(uri)));
  }

  private <T> CompletableFuture<T> submit(Function<CompilationContext, T> operation) {
    // lazy context creation and executor handling stay here
  }
}
```

Exact return types may be adjusted to fit existing LSP4J DTO handling.
The important point is that `WorkspaceSession` no longer passes arbitrary lambdas into the workspace layer.

## Data Flows

### `didOpen`

```text
LSP didOpen(uri, content)
  -> LatheTextDocumentService captures uri/content
  -> serverWorker.execute(session.onOpen(uri, content))

lathe-worker:
  snapshot = putOpenFile(uri, content)
  submitCompile(snapshot, OPEN, publishIfCurrent)

lathe-module-X:
  context.compile(uri, content, OPEN)
  return CompileResult(uri, version, diagnostics)

lathe-worker:
  publish only if openFiles[uri].version == result.version
```

### `didChange`

```text
LSP didChange(uri, content)
  -> serverWorker.execute(session.onChange(uri, content))

lathe-worker:
  snapshot = updateOpenFile(uri, content)
  publish empty diagnostics
  serverWorker.cancel(uri)
  serverWorker.schedule(uri, debounceMs, debounce task)

debounce task on lathe-worker:
  latest = openFiles.get(uri)
  if latest != null:
    submitCompile(latest, FAST, publishIfCurrent)

lathe-module-X:
  compile latest content
  return CompileResult

lathe-worker:
  publish only if result version is still current
```

### `didSave`

```text
LSP didSave(uri, optionalContent)
  -> serverWorker.execute(session.onSave(uri, optionalContent))

lathe-worker:
  snapshot = snapshotForSave(uri, optionalContent)
  route = routeCompiler(uri)
  afterCompile = publishIfCurrentThen(() -> scheduleOpenFilesInSameModule(route.module))
  submitCompile(snapshot, FULL, afterCompile)

lathe-module-X:
  context.compile(uri, snapshot.content, FULL)
  return CompileResult

lathe-worker:
  if result is current:
    publish diagnostics
    refresh semantic tokens
    schedule sibling open files in the saved module
```

For external or missing routes, there are no module siblings to schedule.

### `didClose`

```text
LSP didClose(uri)
  -> serverWorker.execute(session.onClose(uri))

lathe-worker:
  openFiles.remove(uri)
  serverWorker.cancel(uri)
  routeCompiler(uri).dropFromCache(uri), or workspace.dropFromAllCaches(uri)
  publish empty diagnostics

module worker:
  context.dropFromCache(uri)
```

### Hover

```text
LSP hover(uri, position)
  -> serverWorker.submit(() -> session.hoverFuture(uri, position))
  -> thenCompose(identity)

lathe-worker:
  route = routeCompiler(uri)
  roots = workspace.allSourceRoots()
  manifestSnapshot = manifest
  if route missing:
    return completedFuture(null)
  return route.worker.hover(uri, position, roots, manifestSnapshot)

module worker:
  context.hover(...)
  return Hover
```

### Completion

```text
LSP completion(uri, position)
  -> serverWorker.submit(() -> session.completionFuture(uri, position))
  -> thenCompose(identity)

lathe-worker:
  open = openFiles.get(uri)
  if open == null:
    return completedFuture(List.of())
  route = routeCompiler(uri)
  if route missing:
    return completedFuture(List.of())
  return route.worker.complete(uri, open.content, position)

module worker:
  context.complete(...)
  return List<CompletionItem>
```

### Definition

```text
LSP definition(uri, position)
  -> serverWorker.submit(() -> session.definitionFuture(uri, position))
  -> thenCompose(identity)

lathe-worker:
  route = routeCompiler(uri)
  roots = workspace.allSourceRoots()
  manifestSnapshot = manifest
  if route missing:
    return completedFuture(empty locations)
  return route.worker.definition(uri, position, roots, manifestSnapshot)

module worker:
  context.definition(...)
  return Optional<Location>
```

### Semantic Tokens

```text
LSP semanticTokens/full(uri)
  -> serverWorker.submit(() -> session.semanticTokensFuture(uri))
  -> thenCompose(identity)

lathe-worker:
  route = routeCompiler(uri)
  if route missing:
    return completedFuture(null)
  return route.worker.semanticTokens(uri)

module worker:
  context.semanticTokens(uri)
  return encoded SemanticTokens
```

### Reload

```text
lathe-worker scheduled watcher poll:
  if watcher.poll():
    reload()

lathe-worker reload:
  newManifest = WorkspaceManifest.load(root)
  newWorkspace = ModuleWorkspace.scan(root, newManifest)
  oldWorkspace = workspace
  workspace = newWorkspace
  manifest = newManifest
  oldWorkspace.close()
  scheduleAllOpenFiles()

old module-worker futures:
  may complete
  hop back to lathe-worker
  stale version/generation check drops them if open content changed, file closed, or workspace reloaded
```

If a reload happens without an edit, an old result can have the same open-file version as the current file.
If that is not acceptable, keep the `workspaceGeneration` field in `OpenFile`, `CompileRequest`, and `CompileResult`.
Increment it on reload and include it in `publishIfCurrent`.

Recommended implementation:
include `workspaceGeneration`, because it makes reload stale checks explicit.

```java
record OpenFile(String uri, String content, long version, long workspaceGeneration) {}
record CompileRequest(String uri, String content, long version, long workspaceGeneration, CompileMode mode) {}
record CompileResult(String uri, long version, long workspaceGeneration, List<Diagnostic> diagnostics) {}
```

## Implementation Notes

- Keep `CompilerRoute`, `AfterCompile`, `OpenFile`, `CompileRequest`, and `CompileResult` close to their use.
  Private nested records/interfaces inside `WorkspaceSession` are acceptable for the first pass.
- If `ModuleWorker` cannot reference LSP4J DTOs cleanly due package boundaries, request/response records can live in the `module` package.
- Prefer making `ModuleWorker.submit(Function<CompilationContext, T>)` private.
  Public methods should be named operations: `compile`, `hover`, `complete`, `definition`, `semanticTokens`, `dropFromCache`.
- Avoid direct `LanguageClient` calls from module workers for diagnostics.
  Diagnostics need stale-result checks against server-worker-owned state.
- `LanguageClient` can remain a final field in `WorkspaceSession`.
  There is no need to pass it to `ModuleWorker` for this recipe.
- `WorkspaceSession.close()` should close the active `ModuleWorkspace`.
  It does not need to publish diagnostics.
- `ServerWorker.scheduleAtFixedRate` should either be keyed/cancellable or only be used once per session.
  If `initialize` can be called more than once, avoid accumulating watcher polls.

## Tests To Add Or Adjust

- Stale diagnostic result after a later edit is not published.
- Diagnostic result after `didClose` is not published.
- Reload increments workspace generation and drops old compile results.
- `didSave` publishes only if the saved snapshot is still current.
- `didSave` schedules sibling open files only after a current result is published.
- `openFiles` remains server-worker-confined; tests should not require `ConcurrentHashMap`.

Existing tests may continue to cover compiler behavior.
Rename tests such as `AnalysisEngineTest` and `ExternalFileCompilerTest` separately if desired.
