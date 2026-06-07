# Lathe — Lightweight Module-Targeted Workspace Watcher

## Problem

The current [WorkspaceWatcher.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/WorkspaceWatcher.java) polls the workspace every `2,000ms` using `Files.walk(latheDir)`.
It filters every file in the `.lathe/` directory to calculate a fingerprint of all `lsp-params-*.json` files.

Because `.lathe/` holds the copied `.class` files and generated sources for all reactor modules, this directory tree can grow to contain tens of thousands of files in large codebases (such as Dropwizard).
Walking this entire directory tree recursively every two seconds consumes CPU cycles, triggers significant disk I/O, and degrades editor responsiveness.

## Goal

Replace the recursive file-system walk with a **lightweight, targeted modification check**:
* Query only the specific manifest and module configuration paths that Lathe already tracks.
* Reduce the poll time complexity from $O(N)$ (where $N$ is the total number of files in `.lathe/`) to $O(M)$ (where $M$ is the number of modules in the reactor).
* Maintain the simplicity of a polling watcher (KISS) without the configuration and event-loss complexities of `WatchService`.

## Non-Goals

* Implementing a fully event-driven recursive `WatchService` wrapper.
* Watching directories for non-Lathe files (e.g., watching Java source files, which is already handled via LSP `workspace/didChangeWatchedFiles`).

## Proposed Design

The server already registers and maintains the workspace configuration inside [WorkspaceSession.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/WorkspaceSession.java).
Instead of walking the file system to find params files, the watcher should query the status of the files Lathe already cares about.

### 1. Watcher Initialization

The watcher should be initialized with the known set of configuration file paths rather than just the workspace root.
Modify [WorkspaceWatcher.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/WorkspaceWatcher.java) to accept the list of active module configuration paths:

```java
final class WorkspaceWatcher {
  private final Path manifestPath;
  private final Set<Path> watchedParamsPaths;
  private final Map<Path, Long> lastModifiedTimes;

  WorkspaceWatcher(final Path workspaceRoot, final Set<Path> initialParamsPaths) {
    this.manifestPath = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    this.watchedParamsPaths = new HashSet<>(initialParamsPaths);
    this.lastModifiedTimes = new HashMap<>();

    // Initialize timestamps
    recordTimestamp(manifestPath);
    watchedParamsPaths.forEach(this::recordTimestamp);
  }
}
```

### 2. Polling Logic ($O(M)$)

During a poll check:
1. Verify if `workspace.json` has changed.
2. Check each path in the `watchedParamsPaths` set.
3. Walk *only* the immediate top-level directories of `.lathe/` (depth of 2) or use a targeted prefix check to discover new module directories if a new module was added.
   - *Alternative:* Since adding/removing modules is a relatively rare operation that usually coincides with changing `pom.xml` (which updates `workspace.json` during sync), we can simply trigger a full scan only when the manifest changes.
4. If any file has a modified timestamp greater than our recorded timestamp, trigger a workspace reload.

```java
boolean poll() {
  boolean changed = false;

  // 1. Check manifest
  if (checkModified(manifestPath)) {
    changed = true;
  }

  // 2. Check known params
  for (final Path path : watchedParamsPaths) {
    if (checkModified(path)) {
      changed = true;
    }
  }

  return changed;
}
```

### 3. Dynamic Path Updates on Reload

When the workspace reloads (triggered by a change in `workspace.json` or a params file):
* Re-scan the `.lathe/` directory for any newly created `lsp-params-*.json` files.
* Update the watcher's `watchedParamsPaths` set with the new set of module configuration paths.

## Threading

The watcher runs within the single-threaded `lathe-worker` event loop via `scheduleAtFixedRate`.
Because the checks are reduced to simple `Files.getLastModifiedTime()` metadata calls on a small number of paths (typically $<50$ paths), the poll execution will complete in under a millisecond, completely eliminating performance spikes on the main event loop.
