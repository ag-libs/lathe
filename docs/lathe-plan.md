# Lathe — Next Task: Rework `init` and Refresh Mechanism

## Design decisions (settled)

**`init` is kept but repurposed** — it no longer requires manual invocation.
Its only job is `Files.createDirectories(latheDir)`.
It auto-binds to the `initialize` Maven phase (the first phase, before `compile`),
so the shim always finds `.lathe/` on the very first build.
It must not be run directly by the developer; no interaction, no output beyond a debug log.

The user POM declares both goals without specifying phases (default phases apply):

```xml
<executions>
  <execution>
    <id>lathe-init</id>
    <goals><goal>init</goal></goals>  <!-- binds to initialize -->
  </execution>
  <execution>
    <id>lathe-sync</id>
    <goals><goal>sync</goal></goals>  <!-- binds to process-test-classes -->
  </execution>
</executions>
```

**Workspace detection** — shim and sync detect a Lathe workspace by walking up to find the
`.lathe/` directory (not `root.marker`).
`init` creates it at `initialize` phase, so the shim can always find it during `compile`.

**Opt-out** — replace the explicit opt-in with an implicit opt-out checked in all three goals:
- If system property `lathe.skip=true` → disabled.
- Else if env var `CI` is set → disabled.
- Else if system property `lathe.skip=false` → enabled (overrides `CI`).
- Otherwise → enabled.

**Server refresh — two independent signals:**

| Signal | Writer | Server response |
|---|---|---|
| params file mtime changed | shim, after each compile | reload that module's `ModuleCompiler` only |
| params file gone | module deleted or branch switch | drop that module's `ModuleCompiler` |
| `workspace.json` mtime changed | sync, only when content changed | full rescan: reload manifest + re-discover all modules |

No stamp file.
No `root.marker`.

**Content-aware manifest write** — `WorkspaceManifestWriter` serializes the new manifest,
reads the existing `workspace.json` (if present), and skips the write if content is identical.
This prevents a no-op build from triggering a full server reload.

---

## Implementation steps

Read each step fully before starting it.

### Step 1 — Repurpose `InitMojo` (`lathe-maven-plugin`)

File: `lathe-maven-plugin/src/main/java/io/github/aglibs/lathe/maven/InitMojo.java`

Replace the body of `execute()` entirely:
1. Add the CI/skip disabled check at the top (see opt-out logic above).
   Log at debug level and return if disabled.
2. Resolve `workspaceRoot` from the session top-level project as today.
3. `Files.createDirectories(workspaceRoot.resolve(LatheLayout.LATHE_DIR))` — that is the
   entire job.
   No `root.marker` write.
   No `workspace.json` delete.
4. Change `@Mojo` annotation: set `defaultPhase = LifecyclePhase.INITIALIZE`.
   Keep `aggregator = true` and `threadSafe = true`.

### Step 2 — Content-aware manifest write (`lathe-maven-plugin`)

File: `lathe-maven-plugin/src/main/java/io/github/aglibs/lathe/maven/WorkspaceManifestWriter.java`

In the `write` method, after serializing the new manifest to a string:
1. Read the existing `workspace.json` as a string (empty string if absent).
2. If identical, log `[sync] workspace unchanged — skipping write` and return.
3. Otherwise write as before.

Update `WorkspaceManifestWriterTest` to cover the skip-on-same-content case.

### Step 3 — Shim: swap workspace detection, add CI/skip, drop marker touch (`lathe-compiler`)

**`WorkspaceDetector`**

Replace the walk-up-for-`root.marker` logic with walk-up-for-`.lathe/`-directory.
Add the CI/skip `isDisabled()` check (same logic as Step 1).
Return `Optional.empty()` from `findWorkspaceRoot` when disabled.

Update `WorkspaceDetectorTest`.

**`LatheCompiler`**

Remove the touch of `root.marker` after compile — the `Files.setLastModifiedTime` call on
`ctx.get().latheDir().resolve(LatheLayout.ROOT_MARKER)`.
The params file mtime is now the signal; no extra touch needed.

### Step 4 — `SyncMojo`: drop marker guard, add CI/skip (`lathe-maven-plugin`)

File: `lathe-maven-plugin/src/main/java/io/github/aglibs/lathe/maven/SyncMojo.java`

1. Add the CI/skip disabled check at the top of `execute()`.
   Log `[sync] disabled (CI or lathe.skip) — skipping` and return if disabled.
2. Remove the `root.marker` existence guard entirely — `init` guarantees `.lathe/` exists.
3. Remove the `isDirectSyncInvocation` guard and its error message.
   That guard existed because running sync before init would fail;
   with `init` auto-bound to `initialize`, that ordering is now guaranteed by Maven.

Update `SyncMojoTest`.

### Step 5 — Server watch loop: per-module params + `workspace.json` (`lathe-server`)

File: `lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheTextDocumentService.java`

Replace `startWatching` (polls `root.marker`, full reload on any change) with two independent
watches in the same poll loop:

**Params file watch (per-module):**
- Maintain a `Map<Path, Long> paramsMtimes` of known params files → last-seen mtime.
- On each poll tick, scan `.lathe/` for all `lsp-params-*.properties` files.
- For each file:
  - If mtime changed → `registry.reload(moduleDir)`.
  - If new (not in map) → add to map; `getOrCreate` handles it on next request.
  - If previously known but now absent → `registry.remove(moduleDir)`, remove from map.
- Update map entries after processing.

**Workspace manifest watch:**
- Maintain `long manifestMtime` of `workspace.json`.
- On each poll tick, check current mtime.
- If changed → full rescan: `setRegistry(ModuleRegistry.scan(workspaceRoot))` +
  `setManifest(WorkspaceManifest.load(workspaceRoot))`.

**`ModuleRegistry`** needs two new methods:
- `void reload(Path moduleDir)` — close the existing `ModuleCompiler` for that dir and remove
  it from the map so `getOrCreate` recreates it fresh on next access.
- `void remove(Path moduleDir)` — close and remove without recreating.

### Step 6 — Remove `ROOT_MARKER` from `LatheLayout` (`lathe-core`)

Remove the `ROOT_MARKER` constant from `LatheLayout`
(first verify it is no longer referenced anywhere after Steps 1–5).

### Step 7 — Docs pass

- `docs/lathe-design.md`: update the plugin components section — init is now a silent
  auto-bound setup goal; remove internal implementation detail (ParamStore internals,
  DependencyEntry internals) that belongs in code/Javadoc not the design doc.
- Replace this file (`docs/lathe-plan.md`) with a short `docs/roadmap.md`:
  bullet-level future items, no recipes.
- `dev/README.md`: remove `root.marker` reference in the `lsp.py` section.
- `AGENTS.md`: update the plugin description (init is auto-bound, not manual);
  update the design documents section to reference `docs/roadmap.md`.

---

## Key invariants to preserve

- The shim must still write params files and manage `lathe.lock` exactly as today.
- The server's per-module `ModuleCompiler` lifecycle (create on first access, no LRU eviction)
  is unchanged; the watch loop adds a way to force-close a specific entry.
- `ExternalFileCompiler` and its `AnalysisEngine` are unaffected.
- Neovim root detection (`vim.fs.root(0, '.lathe')`) already works — no Lua changes needed.
- The invoker IT project's `pom.xml` needs a new `lathe-init` execution added (Step 1 above).
