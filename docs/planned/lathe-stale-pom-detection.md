# Lathe — Stale-POM Detection

## Problem

When a POM file changes — because the user edited a dependency, or switched git branches
with the editor open — the server continues serving the previous workspace state silently.
Classpath, source roots, and dependency sources all remain from the previous `lathe:sync`
run. Diagnostics and completions may be wrong, with no indication that a re-sync is needed.

Branch switching is the primary motivating case:
`git switch feature-branch` updates POM files on disk, but `.lathe/` is gitignored and
retains the previous branch's compiled state. The server has no signal that anything changed.

## Goals

- Detect when any reactor POM file changes after the last sync.
- Notify the user in the editor to re-run the documented sync command.
- Allow the user to trigger the sync from within Neovim without leaving the editor.
- Avoid spurious reloads during a sync in progress.
- Keep the implementation simple: no new lock files, no new inter-process protocol.

## Non-goals

- Automatically re-running Maven on behalf of the server.
- Detecting source file changes outside of the normal `didOpen`/`didChange`/`didSave` flow.
- Sibling recompilation for closed files (deferred to post-beta).
- Cross-module live diagnostics.

---

## Part 1 — Simplify `WorkspaceWatcher`

### Current behavior

`WorkspaceWatcher.poll()` checks two independent signals:

1. `workspace.json` mtime
2. A fingerprint over all `lsp-params-*.json` files: `(count, maxMtime)`

The params fingerprint changes on **every** Maven compilation, even when the classpath and
source roots are identical (the shim always rewrites params files).
This triggers a full workspace reload after every `mvn compile`, most of which are
structurally no-ops from the server's perspective.

### New behavior

Remove params file polling entirely.
`poll()` returns a `PollResult` enum distinguishing three outcomes:

- `NO_CHANGE` — nothing to do
- `RELOAD` — `workspace.json` mtime changed; server must reload workspace state
- `POM_STALE` — one or more reactor POM files changed since the last sync baseline;
  server must prompt the user to re-sync

`workspace.json` is written by `lathe:sync` as its final step, atomically, and only when
content changes. It updates when:

- Dependencies change (new or updated `dependencySources` entries)
- JDK version changes
- Server version changes
- The reactor module set or POM path list changes

It does **not** update when Maven recompiles with an identical classpath and source roots,
or when invoked with `-pl` (single-module build). In both cases a workspace reload is
unnecessary — the server's existing state is correct.

### First-checkout case

`.lathe/workspace.json` does not exist yet.
The server starts with no manifest and shows the "Run `mvn process-test-classes`" prompt.
After the first sync, `workspace.json` appears and the watcher detects the new mtime
(from zero), returning `RELOAD`. Covered.

### POM fingerprint tracking inside `WorkspaceWatcher`

`WorkspaceWatcher` maintains a per-POM baseline of `(mtime, size)`:

```java
private record PomFingerprint(long mtime, long size) {}
private Map<Path, PomFingerprint> pomBaseline = Map.of();
```

This is the same pattern as `lastManifestMtime` for `workspace.json` — a lightweight
in-memory snapshot compared against current disk state on each poll.

Size is included alongside mtime to reduce false positives from operations that touch
a POM without changing its content (e.g. `touch pom.xml`, certain git operations):

| Change | mtime | size | detected? |
|---|---|---|---|
| Branch switch (real dep change) | ✅ | ✅ | ✅ |
| `touch pom.xml` (no content change) | ✅ | ❌ | ❌ no false positive |
| Same-length edit (e.g. version bump) | ✅ | ❌ | ✅ via mtime |
| No change | ❌ | ❌ | ❌ correct |

The baseline is updated by `WorkspaceWatcher.updatePomPaths(List<Path>)`, called after
every workspace reload with the POM path list read from the new manifest.
On the initial call the watcher records the current disk fingerprint as the starting
baseline — so no spurious `POM_STALE` fires immediately after a sync.

### `WorkspaceWatcher` changes summary

- Remove `lastParams` field, `Fingerprint` record, and `paramsFingerprint()` method.
- `poll()` returns `PollResult` (enum: `NO_CHANGE`, `RELOAD`, `POM_STALE`).
- Add `updatePomPaths(List<Path> absPomPaths)` — replaces the baseline map and records
  current disk fingerprints as the new starting point.
- `detectPomStaleness()` iterates `pomBaseline`, compares each path's current
  `(mtime, size)` to the stored value; returns `true` on first difference.

---

## Part 2 — POM Paths in `workspace.json`

### `lathe:sync` changes

At the end of `SyncMojo`, before writing `workspace.json`:

1. Enumerate all reactor module POMs — the Maven session provides the full project list,
   so their POM paths are available without additional scanning.
2. Record each POM as a relative path from the workspace root (forward slashes).
3. Write into `workspace.json` as a new `pomPaths` list:

```json
{
  "schemaVersion": "1",
  "workspaceRoot": "/workspace",
  "pomPaths": [
    "pom.xml",
    "module-a/pom.xml",
    "module-b/pom.xml"
  ],
  "jdk": { ... },
  "dependencySources": [ ... ]
}
```

Only paths are stored — **not** mtimes.
The watcher owns the mtime/size baseline; `workspace.json` only needs to tell the server
which files to watch.

### `WorkspaceManifestData` schema change

Add `List<String> pomPaths` to the record.
Field is optional: a missing or null value means "no POMs recorded" —
the server initializes the watcher with an empty path list and sends no notification.
Old manifests without the field degrade gracefully.

---

## Part 3 — POM Change Detection in the Server

### Detection

`WorkspaceWatcher.poll()` checks POM staleness on **every poll cycle**,
independent of whether `workspace.json` changed.
This means branch switching is detected as soon as the watcher's next 2-second poll fires —
no sync is required to trigger the check.

Priority: `RELOAD` takes precedence over `POM_STALE` in the same poll cycle.
If `workspace.json` changed (a sync just ran), the watcher returns `RELOAD`;
`WorkspaceSession` reloads and calls `updatePomPaths()` with the new list,
resetting the baseline. No `POM_STALE` notification fires for the just-completed sync.

### Notification

When `WorkspaceSession.checkForChanges()` receives `POM_STALE`:

Send `window/showMessageRequest` at `WARNING` level:

```
Maven project changed. Run 'mvn process-test-classes' to refresh Lathe.
```

with two actions: `"Sync"` and `"Later"`.

Send at most once per staleness detection cycle.
After the user responds (either action), the watcher suppresses further `POM_STALE`
returns until the POM baseline is reset by the next `updatePomPaths()` call
(i.e., after the next sync and reload).

### "Later" response

Server acknowledges; watcher marks the current staleness as acknowledged.
No further notification until a POM changes again (new mtime/size) or a sync resets
the baseline.

---

## Part 4 — Neovim Plugin: "Sync" Response

The Neovim plugin handles the `window/showMessageRequest` response.
If the user picks `"Sync"`, the plugin runs `mvn process-test-classes` locally:

```lua
vim.fn.jobstart(
  { 'mvn', 'process-test-classes' },
  {
    cwd = client.config.root_dir,
    on_exit = function(_, code)
      if code ~= 0 then
        vim.notify('Lathe sync failed (exit ' .. code .. ')', vim.log.levels.ERROR)
      end
    end,
  }
)
```

Maven output goes to a hidden job (not a terminal buffer) by default.

### Suppressing duplicate dialogs while sync is running

The plugin tracks whether a sync job is currently running.
If another `showMessageRequest` arrives while a job is active, the plugin responds
with `"Later"` immediately rather than showing a second dialog.
When the job completes, the tracking state is cleared.

---

## Part 5 — Reload Sequencing During Sync

`mvn process-test-classes` from the root:

1. Compilation phases — shim writes params files for each module.
   Params file changes do **not** trigger server reloads (Part 1 removed params watching).
2. `process-test-classes` — `lathe:sync` writes new `workspace.json` atomically.
3. `WorkspaceWatcher` detects `workspace.json` mtime change → returns `RELOAD`.
4. `WorkspaceSession` reloads, then calls `watcher.updatePomPaths(newManifest.pomPaths())`.
5. `updatePomPaths()` records fresh disk fingerprints as the new baseline →
   watcher's next poll sees no difference → no `POM_STALE`.

Exactly one reload, at the end of the sync. No partial reloads mid-build.
No lock files, no debounce, no inter-process coordination.

---

## Part 6 — Branch Switching Flow

```
git switch feature-branch
  → POMs updated on disk by git (mtime + possibly size changes)
  → .lathe/ unchanged (gitignored — retains previous branch state)
  → workspace.json mtime unchanged

  At next WorkspaceWatcher poll (within 2s):
  → workspace.json unchanged → not RELOAD
  → POM mtime/size differs from baseline → POM_STALE
  → WorkspaceSession sends showMessageRequest

  [user picks "Sync"]
  → Neovim plugin runs mvn process-test-classes

  [sync completes]
  → lathe:sync writes new workspace.json
  → watcher returns RELOAD
  → WorkspaceSession reloads, calls updatePomPaths()
  → baseline reset → no further POM_STALE
```

If the user switched to a branch with identical POM content (same size and same mtime —
unlikely but possible if the branch was created without modifying the POM),
no notification fires — the workspace is already correct.

---

## Summary of Changes

| Component | Change |
|---|---|
| `WorkspaceWatcher` | Remove params fingerprint; add `PollResult` enum; add per-POM `(mtime, size)` baseline; add `updatePomPaths()`  |
| `WorkspaceManifestData` | Add `List<String> pomPaths` (optional field, no mtimes) |
| `WorkspaceManifestWriter` (SyncMojo) | Populate `pomPaths` from Maven reactor project list |
| `WorkspaceSession` | Switch on `PollResult`; call `watcher.updatePomPaths()` after reload; send `showMessageRequest` on `POM_STALE` |
| Neovim plugin | Handle `showMessageRequest` response; run `mvn process-test-classes` via `jobstart`; suppress duplicate dialogs |

## Tests

- `WorkspaceWatcher`: params file mtime change alone returns `NO_CHANGE`.
- `WorkspaceWatcher`: `workspace.json` mtime change returns `RELOAD`.
- `WorkspaceWatcher`: POM mtime change after `updatePomPaths()` returns `POM_STALE`.
- `WorkspaceWatcher`: POM size-only change (same mtime) returns `NO_CHANGE` (touch simulation).
- `WorkspaceWatcher`: after `updatePomPaths()` resets baseline, previously stale POM
  returns `NO_CHANGE`.
- `WorkspaceManifestData`: round-trip JSON serialization of `pomPaths`; missing field
  deserializes as empty list.
- `WorkspaceSession`: `POM_STALE` result triggers `showMessageRequest`; second `POM_STALE`
  in same cycle is suppressed after user responds.
