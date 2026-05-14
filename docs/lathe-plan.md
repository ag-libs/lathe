# Lathe — Next Task: Drop `init`, Rework Refresh Mechanism

## Design decisions (settled)

**Workspace detection** — shim and sync detect a Lathe workspace by walking up to find the
`.lathe/` directory (not `root.marker`).
The directory is created by `sync` on first run.
Before first sync, `.lathe/` does not exist, so the shim correctly skips.

**Opt-out** — replace the explicit `lathe:init` opt-in with an implicit opt-out:
- If system property `lathe.skip=true` → disabled.
- Else if env var `CI` is set → disabled.
- Else if system property `lathe.skip=false` → enabled (overrides `CI`).
- Otherwise → enabled.
Check this in both the shim (`LatheCompiler`/`WorkspaceDetector`) and `SyncMojo`.

**Server refresh — two independent signals:**

| Signal | Writer | Server response |
|---|---|---|
| params file mtime changed | shim, after each compile | reload that module's `ModuleCompiler` only |
| params file gone | module deleted or branch switch | drop that module's `ModuleCompiler` |
| `workspace.json` mtime changed | sync, only when content changed | full rescan: reload manifest + re-discover all modules |

No stamp file.
No `root.marker`.
No `init` goal.

**Content-aware manifest write** — `WorkspaceManifestWriter` serializes the new manifest,
reads the existing `workspace.json` (if present), and skips the write if content is identical.
This prevents a no-op build from triggering a full server reload.

---

## Implementation steps

Read each step fully before starting it.

### Step 1 — Content-aware manifest write (`lathe-maven-plugin`)

File: `lathe-maven-plugin/src/main/java/io/github/aglibs/lathe/maven/WorkspaceManifestWriter.java`

In the `write` method, after serializing the new manifest to a string:
1. Read the existing `workspace.json` as a string (empty string if absent).
2. Compare. If identical, log `[sync] workspace unchanged — skipping write` and return.
3. Otherwise write as before.

Update `WorkspaceManifestWriterTest` to cover the skip-on-same-content case.

### Step 2 — Shim: swap workspace detection, add CI/skip, drop marker touch (`lathe-compiler`)

**`WorkspaceDetector`**

Replace the walk-up-for-`root.marker` logic with walk-up-for-`.lathe/`-directory.
Add CI/skip check as a static method `isDisabled()`:
```
lathe.skip=true  → true
lathe.skip=false → false  (explicit false overrides CI)
CI env var set   → true
otherwise        → false
```

Return `Optional.empty()` from `findWorkspaceRoot` when `isDisabled()`.

Update `WorkspaceDetectorTest`.

**`LatheCompiler`**

Remove the touch of `root.marker` after compile (the `Files.setLastModifiedTime` call on
`ctx.get().latheDir().resolve(LatheLayout.ROOT_MARKER)`).
The params file mtime is now the signal; no extra touch needed.

### Step 3 — `SyncMojo`: drop marker guard, add CI/skip, create `.lathe/` (`lathe-maven-plugin`)

File: `lathe-maven-plugin/src/main/java/io/github/aglibs/lathe/maven/SyncMojo.java`

1. At the top of `execute()`, add a disabled check (same three-line logic as Step 2).
   Log `[sync] disabled (CI or lathe.skip) — skipping` and return if disabled.
2. Remove the `root.marker` existence guard entirely.
3. Replace it with: `Files.createDirectories(workspaceRoot.resolve(LatheLayout.LATHE_DIR))`.
   Sync now owns creating `.lathe/` on first run.
4. Remove the `isDirectSyncInvocation` guard and its error message
   (that guard only made sense when `init` had to run first).

Update `SyncMojoTest`.

### Step 4 — Server watch loop: per-module params + `workspace.json` (`lathe-server`)

File: `lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheTextDocumentService.java`

Replace the current `startWatching` method (which polls `root.marker` mtime and does a full
reload on any change) with two independent watches in the same poll loop:

**Params file watch (per-module):**
- Maintain a `Map<Path, Long> paramsMtimes` of known params files → last-seen mtime.
- On each poll tick, scan `.lathe/` for all `lsp-params-*.properties` files.
- For each file:
  - If mtime changed → call `registry.reload(moduleDir)` to close and recreate that module's
    `ModuleCompiler`.
  - If new (not in map) → add to map; `getOrCreate` handles it on next request.
  - If previously known but now absent → call `registry.remove(moduleDir)` and remove from map.
- Update map entries after processing.

**Workspace manifest watch:**
- Maintain `long manifestMtime` of `workspace.json`.
- On each poll tick, check current mtime.
- If changed → full rescan: `setRegistry(ModuleRegistry.scan(workspaceRoot))` +
  `setManifest(WorkspaceManifest.load(workspaceRoot))`.
  This handles added/removed modules, dep changes, JDK changes.

**`ModuleRegistry`** needs two new methods:
- `void reload(Path moduleDir)` — close the existing `ModuleCompiler` for that dir and remove
  it from the map so `getOrCreate` recreates it fresh on next access.
- `void remove(Path moduleDir)` — close and remove without recreating.

### Step 5 — Delete `InitMojo`, clean up `LatheLayout` (`lathe-maven-plugin`, `lathe-core`)

- Delete `lathe-maven-plugin/src/main/java/io/github/aglibs/lathe/maven/InitMojo.java`.
- Remove `ROOT_MARKER` constant from `LatheLayout` in `lathe-core`
  (first verify it is no longer referenced anywhere after Steps 2–4).
- Remove any invoker IT `pom.xml` references to `lathe:init`
  (check `lathe-maven-plugin/src/it/`).
- Update `AGENTS.md` module table: remove `lathe:init` from the plugin description.

### Step 6 — Docs pass

- `docs/lathe-design.md`: update the components section — remove `init` from the plugin
  description, remove internal implementation detail (ParamStore internals, DependencyEntry
  internals) that belongs in code/Javadoc not the design doc.
- Replace this file (`docs/lathe-plan.md`) with a short `docs/roadmap.md`:
  bullet-level future items, no recipes.
- `dev/README.md`: remove `root.marker` reference in the `lsp.py` section.
- `AGENTS.md`: update the design documents section to reference `docs/roadmap.md`
  instead of `docs/lathe-plan.md`.

---

## Key invariants to preserve

- The shim must still write params files and manage `lathe.lock` exactly as today.
- The server's per-module `ModuleCompiler` lifecycle (create on first access, no LRU eviction)
  is unchanged; the watch loop adds a way to force-close a specific entry.
- `ExternalFileCompiler` and its `AnalysisEngine` are unaffected.
- Neovim root detection (`vim.fs.root(0, '.lathe')`) already works — no Lua changes needed.
- The invoker IT project's `pom.xml` already has no `init` execution; verify and leave as-is.
