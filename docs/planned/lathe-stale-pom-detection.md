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
Watch only `workspace.json` mtime.

`workspace.json` is written by `lathe:sync` as its final step, atomically, and only when
content changes. It updates when:

- Dependencies change (new or updated `dependencySources` entries)
- JDK version changes
- Server version changes
- POM fingerprints change (added in Part 2)
- The reactor module set changes (new module added or removed)

It does **not** update when Maven recompiles with an identical classpath and source roots,
or when invoked with `-pl` (single-module build). In both cases a workspace reload is
unnecessary — the server's existing state is correct.

### First-checkout case

`.lathe/workspace.json` does not exist yet.
The server starts, finds no manifest, and shows the "Run `mvn process-test-classes`" prompt.
After the first sync, `workspace.json` appears and the watcher detects the new mtime
(from zero). Covered.

### `WorkspaceWatcher` changes

- Remove `lastParams` field and `paramsFingerprint()` method.
- `poll()` returns `true` only when `workspace.json` mtime changes.
- Log message updated to reflect the single trigger.

The result is a ~40-line class watching one file.

---

## Part 2 — POM Fingerprints in `workspace.json`

### `lathe:sync` changes

At the end of `SyncMojo`, before writing `workspace.json`:

1. Enumerate all reactor module POMs — the Maven session provides the full project list,
   so their POM paths are available without additional scanning.
2. Record each POM as a relative path from the workspace root and its current mtime.
3. Write into `workspace.json` as a new `pomFingerprints` map:

```json
{
  "schemaVersion": "1",
  "workspaceRoot": "/workspace",
  "pomFingerprints": {
    "pom.xml": 1749312000000,
    "module-a/pom.xml": 1749312000000,
    "module-b/pom.xml": 1749298000000
  },
  "jdk": { ... },
  "dependencySources": [ ... ]
}
```

Mtime is stored as milliseconds since epoch (same type as `Files.getLastModifiedTime().toMillis()`).
Relative paths use forward slashes on all platforms.

### `WorkspaceManifestData` schema change

Add `Map<String, Long> pomFingerprints` to the record.
Field is optional: a missing or null value means "no fingerprints recorded" —
the server treats the first sync after an upgrade as establishing a new baseline
and sends no notification.

---

## Part 3 — POM Change Detection in the Server

### Detection

When `WorkspaceWatcher.poll()` fires (i.e., `workspace.json` mtime changed),
`WorkspaceSession` loads the new manifest and compares `pomFingerprints` against the
current mtime of each recorded POM path on disk.

If any POM's current mtime differs from the recorded fingerprint, the POMs have changed
since the last sync. This covers:

- The user edited a dependency in a POM after the last sync.
- The user switched branches: git updated POMs on disk, but `lathe:sync` has not run yet
  on the new branch. The new `workspace.json` (written by the previous branch's sync)
  still holds the previous branch's POM fingerprints; the current disk mtimes differ.

If all fingerprints match, no notification — the sync is current.

### Notification

Send `window/showMessageRequest` at `WARNING` level:

```
Maven project changed. Run 'mvn process-test-classes' to refresh Lathe.
```

with two actions: `"Sync"` and `"Later"`.

`showMessageRequest` is a request, not a fire-and-forget notification.
The server sends it once per reload cycle, then waits.
After the user responds (either action), suppress further notifications until
`workspace.json` changes again (i.e., a new sync runs and produces a new reload).

### "Later" response

Server acknowledges and does nothing further until the next `workspace.json` change.
The user is responsible for running the sync when convenient.

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
An optional terminal-buffer variant can be provided for users who want to watch the build.

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
3. `WorkspaceWatcher` detects `workspace.json` mtime change → single workspace reload.
4. Server loads new manifest, compares POM fingerprints → fingerprints now match
   (sync just ran and recorded fresh mtimes) → no `showMessageRequest` sent.

Exactly one reload, at the end of the sync. No partial reloads mid-build.
No lock files, no debounce, no inter-process coordination.

---

## Part 6 — Branch Switching Flow

```
git switch feature-branch
  → POMs updated on disk by git
  → .lathe/ unchanged (gitignored — retains previous branch state)
  → workspace.json mtime unchanged

  [user opens a file]
  → server compiles with previous branch classpath
  → workspace.json has not changed, so no reload yet
  → POM mtime comparison not triggered yet

  [user runs mvn process-test-classes — manually or via "Sync" dialog]
  → lathe:sync resolves deps for new branch
  → writes new workspace.json with updated POM fingerprints
  → WorkspaceWatcher detects workspace.json change → reload
  → server loads new manifest and new classpath
  → POM fingerprints in new manifest match current disk mtimes → no notification
```

If the user switched to a branch with identical POMs (same dependency set), no
notification fires after the sync — the workspace was already correct structurally.

**Accepted limitation:** between the branch switch and the sync the server operates
with the previous branch's classpath. This is the same state as before any first sync
on a fresh checkout, and is covered by the existing "Run `mvn process-test-classes`"
startup prompt if the workspace state diverges enough to invalidate `workspace.json`.

---

## Summary of Changes

| Component | Change |
|---|---|
| `WorkspaceWatcher` | Remove params fingerprint; watch only `workspace.json` mtime |
| `WorkspaceManifestData` | Add `Map<String, Long> pomFingerprints` (optional field) |
| `WorkspaceManifestWriter` | Populate `pomFingerprints` from Maven reactor POM list |
| `WorkspaceSession` | After manifest reload, compare POM fingerprints to disk mtimes; send `showMessageRequest` if any differ; suppress until next reload |
| Neovim plugin | Handle `showMessageRequest` response; run `mvn process-test-classes` via `jobstart`; suppress duplicate dialogs while job is running |

## Tests

- `WorkspaceWatcher`: params file mtime change alone does not trigger reload;
  `workspace.json` mtime change triggers reload.
- `WorkspaceManifestData`: round-trip JSON serialization of `pomFingerprints`;
  missing field deserializes as empty map.
- POM fingerprint comparison: matching fingerprints → no notification;
  any differing mtime → notification sent; empty fingerprints (first sync after upgrade)
  → no notification.
- Notification suppression: `showMessageRequest` is not sent again after user responds
  within the same reload cycle.
