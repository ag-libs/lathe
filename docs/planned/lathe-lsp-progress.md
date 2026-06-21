# Lathe — LSP Work-Done Progress

## Goal

Surface long-running server operations (workspace initialization, workspace reload) as LSP progress
notifications so that editors render a visible status bar entry via `vim.lsp.status()` or equivalent.

---

## LSP Protocol Recap

The lifecycle requires three steps:

1. **Capability negotiation** — check `clientCapabilities.window.workDoneProgress` during `initialize`.
2. **Token creation** — send `window/workDoneProgress/create` with a unique token.
3. **Progress notifications** — send `$/progress` notifications with `kind: begin`, `kind: report`
   (zero or more), and `kind: end`.

LSP4J 0.21 ships all required types: `WorkDoneProgressCreateParams`, `WorkDoneProgressBegin`,
`WorkDoneProgressReport`, `WorkDoneProgressEnd`, `ProgressParams`, and the `LanguageClient` methods
`createProgress()` and `notifyProgress()`.

### Note on `createProgress` acknowledgement

The LSP spec says the server should wait for the client to acknowledge `window/workDoneProgress/create`
before sending `begin`.
In practice, jdtls fires `createProgress` and sends `begin` immediately without waiting.
Neovim handles this correctly.
Lathe will follow the same fire-and-forget approach to avoid blocking the `ServerEventLoop` thread.

---

## Scope

Only two operations are long enough to warrant a progress bar:

- **`WorkspaceSession.initialize()`** — loads manifest, scans modules, scans reactor shards, builds
  the full type index.
  Proposed checkpoints:
  ```
  begin  → "Indexing workspace", "Loading manifest…"
  report → "Scanning reactor classes…", 30%
  report → "Building type index…", 70%
  end    → "Done"
  ```

- **`WorkspaceSession.reload()`** — same steps triggered by a workspace file change.
  Proposed checkpoints:
  ```
  begin  → "Reloading workspace"
  report → "Scanning reactor classes…", 50%
  end    → "Done"
  ```

Per-file save and change compiles are fast enough to not need progress reporting.

---

## Lessons from jdtls

Eclipse JDT LS (`ProgressReporterManager`) was studied as a reference implementation.
Key findings:

- **Fire-and-forget `createProgress`** — jdtls does not `.join()` on the `createProgress` future.
  `begin` is sent immediately after without waiting for acknowledgement.
- **200ms throttle** — `report` calls are suppressed if fewer than 200ms have passed since the last
  one, to avoid flooding the client with rapid updates.
- **`sentBegin` / `sentEnd` guards** — prevent sending `end` without a prior `begin` (Neovim logs
  an error if this happens), and prevent duplicate `end` notifications.
- **Eclipse job framework** — jdtls wires progress into Eclipse's `IProgressMonitor` / job
  scheduler, so `begin`/`report`/`end` are called automatically by the framework.
  Lathe does not use Eclipse's job system, so progress calls must be explicit at the call site.

---

## Chosen Design — Lambda wrapper (`run`)

After exploring several shapes, the lambda wrapper is the preferred approach.
`ProgressReporter` exposes a single `run()` method.
`begin` and `end` are fully internal — the caller only sees `task.report()`.

```java
@FunctionalInterface
interface ProgressWork {
    void execute(ProgressTask task);
}

@FunctionalInterface
interface ProgressTask {
    void report(String message, int percentage);
}

final class ProgressReporter {
    private final LanguageClient client;
    private final boolean supported;

    void run(String title, String message, ProgressWork work) {
        begin(title, message);
        try {
            work.execute(this::report);
        } finally {
            end();
        }
    }
}
```

Call site in `initialize()`:
```java
progress.run("Indexing workspace", "Loading manifest…", task -> {
    manifest = WorkspaceManifest.load(root);
    workspace = WorkspaceModuleRegistry.scan(root, manifest);
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    task.report("Scanning reactor classes…", 30);
    scanReactorShards();
    task.report("Building type index…", 70);
    typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
});
watcher = new WorkspaceWatcher(root);
watcher.updatePomPaths(manifest.pomPaths());
worker.scheduleAtFixedRate(2_000L, this::checkForChanges);
client.showMessage(...);
```

Call site in `reload()`:
```java
final var old = workspace;
progress.run("Reloading workspace", null, task -> {
    final var newManifest = WorkspaceManifest.load(workspaceRoot);
    workspace = WorkspaceModuleRegistry.scan(workspaceRoot, newManifest);
    manifest = newManifest;
    moduleGraph = WorkspaceModuleGraph.build(workspace.allConfigs());
    candidateIndex = ReferenceCandidateIndex.build(workspace.allConfigs());
    reactorShards.clear();
    task.report("Scanning reactor classes…", 50);
    scanReactorShards();
    typeIndex = WorkspaceTypeIndex.build(newManifest.typeIndexShardPaths(), reactorShards.values());
});
old.close();
scheduleAllOpenFiles();
client.showMessage(...);
```

**Why lambda over alternatives:**
- `begin` and `end` are invisible to the caller — only `task.report()` is exposed.
- Exception safety is guaranteed by the `try/finally` inside `run()`.
- No try-with-resources syntax, no extra `ProgressTask` class.
- The lambda body assigns to instance fields of `WorkspaceSession` directly (Java allows this).
- `old` in `reload()` is captured before the lambda and is effectively final — works cleanly.

**Implementation notes for `ProgressReporter`:**
- `sentBegin` boolean guards `report()` and `end()` — both are no-ops until `begin` fires.
- `sentEnd` boolean prevents duplicate `end` notifications.
- `createProgress` is fire-and-forget (no `.join()`).
- A 200ms throttle on `report()` suppresses rapid successive calls.
- When `!supported`, `run()` calls `work.execute(noOpTask)` directly with no LSP calls.

---

## Previously Considered Alternatives

### Alternative A — explicit `begin` / `report` / `end` calls

Simple but caller must remember to call `end()`, even on exception (requires `try/finally` at every
call site).

### Alternative B — `begin()` returns `AutoCloseable` `ProgressTask`

Exception-safe via try-with-resources, but `begin` and `end` remain visible to the caller,
and the end message is either fixed or requires an awkward setter before closing.

---

## Wiring

- `LatheLanguageServer.initialize(InitializeParams)` reads `workDoneProgress` from
  `capabilities.window` (null-safe).
- A `ProgressReporter` is constructed with the `LanguageClient` and the `supported` flag,
  and stored as a field on `WorkspaceSession`.
- `WorkspaceSession.initialize()` and `reload()` call `progress.run(...)` — no boolean is threaded
  through any method signature.

The `supported` flag never appears outside `ProgressReporter` itself.

---

## Classes Changed

| Class | Change |
|---|---|
| `ProgressReporter` | New class |
| `LatheLanguageServer` | Read `workDoneProgress` flag from `InitializeParams`; pass to session |
| `WorkspaceSession` | Hold `ProgressReporter` field; wrap `initialize()` and `reload()` bodies |
| `LatheTextDocumentService` | Thread `ProgressReporter` or construction inputs into session |
