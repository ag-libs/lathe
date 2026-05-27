# Lathe Server Data Flow Recipe

This recipe documents the settled server refactor.
The server uses one workspace worker for LSP/session state and one worker per compiler context for javac-backed work.

## Thread Ownership

| Thread | Owns | Does not own |
|---|---|---|
| LSP4J receive thread | immutable request data extracted from LSP params | workspace state, javac state |
| `lathe-worker` | `WorkspaceSession`, `WorkspaceModules`, `WorkspaceManifest`, `WorkspaceWatcher`, `openDocuments`, routing, stale checks, client publishing | javac-backed `ModuleAnalysisSession` |
| `lathe-module-<name>` | one module `ModuleAnalysisSession` and `JavaSourceCompiler` | workspace routing state, `openDocuments`, client publishing |
| `lathe-external` | one external-source `ModuleAnalysisSession` and `JavaSourceCompiler` | workspace routing state, `openDocuments`, client publishing |

`openDocuments` is a plain `HashMap`.
It is server-worker-confined.

Module workers never read `openDocuments`.
Compilation results always hop back to `lathe-worker` before stale checks or publishing.

## Main Types

`LatheTextDocumentService` is the LSP adapter.
It extracts request data and submits work to `ServerWorker`.
It owns the `WorkspaceSession` reference, but does not mutate session state directly after construction.

`ServerWorker` is the server event loop.
It serializes workspace state changes, keyed debounced tasks, watcher polling, and LSP request routing.

`WorkspaceSession` owns workspace-level state:

```java
private WorkspaceManifest manifest;
private WorkspaceModules workspace;
private WorkspaceWatcher watcher;
private final Map<String, OpenDocument> openDocuments = new HashMap<>();
```

`OpenDocument` is the latest open-document snapshot:

```java
record OpenDocument(String uri, String content, long generation) {}
```

The single `generation` value is incremented for every open-document snapshot.
Reload refreshes all open-document generations, so old compile results are stale even when text is unchanged.

`WorkspaceModules` owns discovered module configs and lazy workers.
It also owns the external-source worker for the current manifest.

`ModuleSourceWorker` owns a single-thread executor and a lazy `ModuleAnalysisSession`.
It exposes domain-specific methods: `compile`, `hover`, `definition`, `complete`, `semanticTokens`, and `dropFromCache`.
Its `close()` waits for context cleanup to finish.

`ModuleAnalysisSession` owns analysis cache and feature helpers.
Its cache stores source text separately from javac analysis:

```java
record CachedFileAnalysis(String content, AttributedFileAnalysis analysis) {}
```

`AttributedFileAnalysis` contains only javac analysis state.
It does not contain source text.

`ModuleSourceCompiler` and `ExternalCompiler` implement `JavaSourceCompiler`.
Both delegate javac task execution to `JavacRunner`.

## Compile Identity

Module workers receive explicit requests and return pure results:

```java
record CompileRequest(String uri, String content, long generation, CompileMode mode) {}

record CompileResponse(String uri, long generation, List<Diagnostic> diagnostics) {}
```

The worker does not publish diagnostics and does not inspect workspace state.
The result carries the generation from the request.

`WorkspaceSession` publishes only when the result still matches the current open-file generation:

```java
private boolean isStale(final OpenDocument snapshot, final long generation) {
  final var current = openDocuments.get(snapshot.uri());
  return current == null || current.generation() != generation;
}
```

This drops results for closed files, later edits, saves with newer content, and workspace reloads.

## Routing

`CompilerRoute` is a private `WorkspaceSession` value:

```java
sealed interface CompilerRoute {
  record Module(ModuleSourceWorker worker, ModuleSourceConfig config) implements CompilerRoute {}

  record External(ModuleSourceWorker worker) implements CompilerRoute {}

  record Missing(String uri, String message) implements CompilerRoute {}
}
```

Routing is centralized in `WorkspaceSession`.
A file under a known module source root uses that module worker.
A file in the manifest but outside a reactor module uses the external worker.
Missing files produce a warning diagnostic for compile requests and empty/null results for feature requests.

## Compile Flow

### Open

```text
LSP didOpen(uri, content)
  -> LatheTextDocumentService captures uri/content
  -> ServerWorker.execute(session.onOpen(uri, content))

lathe-worker:
  snapshot = putOpenFile(uri, content)
  submitCompile(snapshot, OPEN, publishIfCurrent)

module worker:
  context.compile(uri, content, OPEN)
  return CompileResponse(uri, generation, diagnostics)

lathe-worker:
  publish only if current generation still matches
```

### Change

```text
LSP didChange(uri, content)
  -> ServerWorker.execute(session.onChange(uri, content))

lathe-worker:
  putOpenFile(uri, content)
  publish empty diagnostics
  cancel previous debounce for uri
  schedule debounce task

debounce task on lathe-worker:
  latest = openDocuments.get(uri)
  if latest != null:
    submitCompile(latest, FAST, publishIfCurrent)
```

### Save

```text
LSP didSave(uri, optionalContent)
  -> ServerWorker.execute(session.onSave(uri, optionalContent))

lathe-worker:
  snapshot = current open file, or saved content if supplied
  if file is not open:
    return
  route = routeCompiler(uri)
  submitCompile(route, snapshot, FULL, afterCompile)

module worker:
  context.compile(uri, snapshot.content, FULL)
  return CompileResponse

lathe-worker:
  publish only if current
  if saved file is a module file:
    schedule sibling open files in that module
```

Save does not read from disk and does not create pseudo-open files.

### Close

```text
LSP didClose(uri)
  -> ServerWorker.execute(session.onClose(uri))

lathe-worker:
  openDocuments.remove(uri)
  cancel pending debounce
  workspace.dropFromAllCaches(uri)
  publish empty diagnostics

module workers:
  context.dropFromCache(uri), if initialized
```

### Reload

```text
lathe-worker watcher poll:
  if changed:
    newManifest = WorkspaceManifest.load(root)
    newWorkspace = WorkspaceModules.scan(root, newManifest)
    oldWorkspace = workspace
    workspace = newWorkspace
    manifest = newManifest
    refreshOpenFileGenerations()
    oldWorkspace.close()
    scheduleAllOpenFiles()
```

`oldWorkspace.close()` waits for its module workers to close their contexts.
Any old compile result that returns after reload is dropped by the refreshed open-file generation.

## Feature Flows

Hover, definition, completion, semantic tokens, and formatting all enter through `LatheTextDocumentService`.
The service submits a small routing operation to `ServerWorker`.

Hover and definition snapshot source roots and manifest on `lathe-worker`, then query the routed module worker.
Completion uses the current open-file content from `openDocuments`.
Semantic tokens read cached tokens from the routed context and return `null` at the LSP boundary when no tokens are cached.
Formatting uses the current open-file content and does not use module workers.

Feature requests do not publish diagnostics and do not mutate workspace state.

## Close Flow

Server shutdown closes through the server worker:

```text
LatheTextDocumentService.close()
  -> ServerWorker.submit(session.close()).join()
  -> ServerWorker.close()

WorkspaceSession.close()
  -> WorkspaceModules.close()

WorkspaceModules.close()
  -> close all module workers and external worker
  -> wait for all close futures

ModuleSourceWorker.close()
  -> close ModuleAnalysisSession on the module worker thread
  -> complete close future
  -> shutdown executor
```

## Cleanup Notes

Keep `WorkspaceSession` as the orchestration point unless it becomes clearly too large.
Do not introduce a generic worker abstraction just to share executor boilerplate between `ServerWorker` and `ModuleSourceWorker`.
The two classes have different domain roles.

Keep `CompilerRoute`, `AfterCompile`, and `OpenDocument` private to `WorkspaceSession`.
Keep `CompileRequest` and `CompileResponse` in the `module` package because they are the public value boundary for `ModuleSourceWorker.compile`.
