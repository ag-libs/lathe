# Lathe Server — New Design

## Threads

```
lathe-worker       ServerWorker      ScheduledExecutorService(1)   session state, routing, debounce, watcher poll
lathe-module-<rel> ModuleWorker      ExecutorService(1)            compile + features, one per module (lazy)
lathe-external     ModuleWorker      ExecutorService(1)            external file compile + features
lsp4j-recv         (internal)        lsp4j                         captures URI/content, enqueues to server worker
```

---

## Classes and state

```
LatheTextDocumentService
  serverWorker: ServerWorker        final
  session:      WorkspaceSession    set on connect()

ServerWorker
  executor: ScheduledExecutorService(1)
  pending:  HashMap<String, ScheduledFuture<?>>    debounce per URI

WorkspaceSession                              server worker thread only
  client:        LanguageClient               final
  worker:        ServerWorker                 final
  debounceMs:    long                         final
  workspaceRoot: Path
  manifest:      WorkspaceManifest
  workspace:     ModuleWorkspace
  watcher:       WorkspaceWatcher
  openFiles:     HashMap<String, String>

ModuleWorkspace                               server worker thread only
  modules:        List<ModuleConfig>          immutable
  workers:        HashMap<String, ModuleWorker>    lazy, keyed by module relative path
  externalWorker: ModuleWorker                eager (context inside is still lazy)

  moduleFor(Path)                                                   → Optional<ModuleConfig>
  allSourceRoots()                                                  → List<Path>
  submitModule(ModuleConfig, Function<CompilationContext, T>)       → CompletableFuture<T>
  submitExternal(Function<CompilationContext, T>)                   → CompletableFuture<T>
  close()

ModuleWorker                                  package-private
  executor:       ExecutorService(1)
  contextFactory: Supplier<CompilationContext>
  context:        CompilationContext           null until first submit; created on executor thread

  submit(Callable<T>)              → CompletableFuture<T>
  schedule(key, delayMs, Runnable)
  cancel(key)
  close()   → executor.shutdownNow(); context?.close()

CompilationContext                            module worker thread only
  compiler:           SourceCompiler          owns it (ModuleCompiler or ExternalCompiler)
  parsingFm:          StandardJavaFileManager owns it; closed in close()
  cache:              HashMap<String, FileAnalysis>
  completionProvider: CompletionProvider
  definitionLocator:  DefinitionLocator
  javadocLocator:     JavadocLocator

  compile(uri, content, mode)           → List<Diagnostic>
  complete(uri, content, pos)           → List<CompletionItem>
  hover(uri, pos, roots, manifest)      → Hover
  definition(uri, pos, roots, manifest) → Optional<Location>
  semanticTokens(uri)                   → List<SemanticToken>
  dropFromCache(uri)
  close()   → parsingFm.close(); compiler.close()

ModuleCompiler   implements SourceCompiler
  config:       ModuleConfig
  compiler:     JavaCompiler
  fm:           StandardJavaFileManager
  td:           Path
  compilerArgs: List<String>
  close()   → fm.close(); delete td

ExternalCompiler   implements SourceCompiler
  manifest: WorkspaceManifest    final
  javac:    JavaCompiler
  fm:       StandardJavaFileManager
  td:       Path
  close()   → fm.close(); delete td

WorkspaceWatcher                              server worker thread only; no executor, no callback
  latheDir:              Path
  manifestPath:          Path
  lastManifestMtime:     long
  lastParamsFingerprint: Fingerprint
  poll() → boolean
```

Unchanged: `WorkspaceManifest`, `ModuleConfig`, `FileAnalysis`, `CompilationResult`, `SemanticToken`,
`CompileMode`, `SourceCompiler`, `CompletionProvider`, `DefinitionLocator`, `HoverFormatter`,
`JavadocLocator`, `SourceLocator`, `TokenScanner`, `JavaFormatter`,
`LatheTextDocumentService`, `LatheLanguageServer`.

---

## Data flows

### Initialization

```
LatheServer.main() → new LatheLanguageServer()
  → new LatheTextDocumentService()
      → new ServerWorker()                      (lathe-worker starts)

LSP connect(client):
  serverWorker.execute(() →
    session = new WorkspaceSession(client, serverWorker, debounceMs))

LSP initialize(params):
  serverWorker.execute(() → session.initialize(workspaceRoot))

  initialize — server worker:
    manifest  = WorkspaceManifest.load(workspaceRoot)
    workspace = ModuleWorkspace.scan(workspaceRoot, manifest)
                └─ creates externalWorker immediately   (lathe-external starts)
                   module workers created lazily on first file open per module
    watcher   = new WorkspaceWatcher(workspaceRoot)
    worker.scheduleAtFixedRate(this::checkForChanges, 2s)
```

### didOpen

```
LSP recv → serverWorker.execute(() → session.onOpen(uri, content))

server worker:
  openFiles.put(uri, content)
  compileAndPublish(uri, content, OPEN)

compileAndPublish(uri, content, mode):
  module = workspace.moduleFor(path)
  if module.present:
    workspace.submitModule(module, ctx → ctx.compile(uri, content, mode))
      .thenAccept(diags → publish(uri, diags))
  elif manifest.containsFile(path):
    workspace.submitExternal(ctx → ctx.compile(uri, content, mode))
      .thenAccept(diags → publish(uri, diags))
  else:
    client.publishDiagnostics(singleDiag(uri, "Run `mvn process-test-classes`…", Warning))
```

### didChange

```
LSP recv → serverWorker.execute(() → session.onChange(uri, content))

server worker:
  openFiles.put(uri, content)
  client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()))
  worker.cancel(uri)
  worker.schedule(uri, debounceMs, () → {
    latest = openFiles.get(uri)
    compileAndPublish(uri, latest, FAST)
  })
```

### didSave

```
LSP recv → serverWorker.execute(() → session.onSave(uri, savedContent))

server worker:
  worker.cancel(uri)
  content = savedContent ?? openFiles.get(uri)
  route compile(uri, content, FULL)
    .thenAccept(diags → {
      publish(uri, diags)
      worker.execute(() → scheduleOpenFilesInModule(uri))
    })
```

### didClose

```
LSP recv → serverWorker.execute(() → session.onClose(uri))

server worker:
  openFiles.remove(uri)
  worker.cancel(uri)
  workspace.dropFromAllCaches(uri)
  client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()))
```

### hover / definition / completion / semanticTokens (two-hop pattern)

```
LSP recv → worker.submit(() → {
    // first hop — server worker: read state, route
    roots = workspace.allSourceRoots()
    snap  = manifest
    return workspace.submitModule/External(ctx → ctx.hover(uri, pos, roots, snap))
})                                    // → CompletableFuture<CompletableFuture<Hover>>
.thenCompose(f → f)                   // → CompletableFuture<Hover>   returned to lsp4j
```

`completion` additionally reads `openFiles.get(uri)` on the first hop before routing:

```
worker.submit(() → {
    content = openFiles.get(uri)
    if content == null: return completedFuture(List.of())
    return workspace.submitModule/External(ctx → ctx.complete(uri, content, pos))
}).thenCompose(f → f)
```

### Reload

```
checkForChanges — server worker:
  if watcher.poll():
    newManifest  = WorkspaceManifest.load(workspaceRoot)
    newWorkspace = ModuleWorkspace.scan(workspaceRoot, newManifest)
    old          = workspace
    workspace    = newWorkspace
    manifest     = newManifest
    old.close()        ← shuts down all module workers + externalWorker; closes all contexts
    scheduleAllOpenFiles()   ← worker.schedule(uri, 0, recompile) for every open URI
```

### publish helper

```
publish(uri, diags):                  // runs on module worker thread (thenAccept)
  client.publishDiagnostics(new PublishDiagnosticsParams(uri, diags))
  client.refreshSemanticTokens()
```

---

## Rename map

| Old | New |
|---|---|
| `AnalysisEngine` | `CompilationContext` |
| `DocumentSession` | `WorkspaceSession` |
| `ModuleRegistry` | `ModuleWorkspace` |
| `ExternalFileCompiler` | `ExternalCompiler` |
| `ModuleWorker` | new class |
