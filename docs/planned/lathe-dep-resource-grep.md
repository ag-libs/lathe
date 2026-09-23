# Lathe — Dependency Resource Grep (extract at `lathe:sync`, native Telescope search)

## Status

Proposed. The **simpler win** chosen over a server-side jar-scanning picker (see §3): `lathe:sync`
already extracts dependency *sources* to `~/.cache/lathe/deps/<gav>/`; this adds unpacking of the
**main jar's resource entries** into that same tree, so the resources become **real files on disk** and
the user's existing Telescope `find_files` / `live_grep` search them — including **content search** —
with no custom picker engine and no runtime jar scanning.

No LSP server feature change is required for the core win. The only server/plugin change is in
`lathe-maven-plugin` (extraction) plus a thin Neovim wrapper that scopes the native search to *this
workspace's* dep dirs.

---

## 1. Goal

Answer the motivating case — *"I know the name of a resource in a dependency and I want to find its
content"* — by making dependency resources greppable with the tooling the user already relies on. You
know roughly which dependency ships `logback.xml` (or a `.properties`, `.sql`, migration script, etc.),
but manually locating the jar, unzipping it, and reading the entry is the tedious part. After this
change, `find_files`/`live_grep` scoped to the workspace's dependency cache finds the file **and its
content** directly.

---

## 2. Current State

- `lathe:sync` extracts each dependency's `-sources.jar` to `~/.cache/lathe/deps/<gav>/`:
  - `SyncCoordinator` → `DependencySourceSync.extract(...)` → `ZipCache.extract(jar, targetDir, hook)`
    → `FileUtil.unzip(...)`.
  - `FileUtil.unzip` / `extractZipEntry` extract **all** entries (no extension filter) and mark them
    read-only.
  - Per-dep destination: `DependencySourceResolver.sourceCacheDir` =
    `userCacheRoot()/deps/<groupId:artifactId:version>` (`LatheLayout.CACHE_DIR="~/.cache"`,
    `CACHE_LATHE_DIR="lathe"`, `CACHE_DEPS_DIR="deps"`; overridable via `-Dlathe.cache`).
  - A marker `.lathe-source.json` records the source jar's size/mtime/schema for cache invalidation.
- The extracted dir is recorded in `workspace.json` as `DependencyData.dir` (written by
  `WorkspaceManifestWriter` from `DependencySource.toData()`), and read server-side into
  `WorkspaceManifest` (`jarToSourceDir`, `depSourceDirs()`).
- **Gap:** only the *sources* jar is unpacked. Resources live in the **main artifact jar**, which is
  never extracted, so no filesystem tool can see or grep them. Dep resources are therefore invisible to
  `find_files`/`live_grep` today.

---

## 3. Why This Shape (vs. a server-scan picker)

Two designs were considered:

- **A — server scans jars on demand + custom picker.** The server reads jar central directories per
  invocation, returns filename matches, and extracts one entry on open. Filename-only; no content
  search; new commands + a bespoke `lua/lathe/resources.lua`.
- **B — extract at sync, native grep (this doc).** Resources land on disk once at sync; the user's
  existing `find_files`/`live_grep` do the rest.

The decisive fact: **ripgrep and `find_files` cannot look inside a `.jar`** (a zip), so the resource
bytes must exist as real files before any grep can touch them either way. Given that, B:

- gives **content search** (`live_grep`) for free — which is the user's actual goal — whereas A is
  filename-only;
- **reuses the user's loved Telescope workflow** instead of introducing a new picker engine;
- needs **no runtime jar scanning** and **no new LSP query** for the core win;
- costs some extra unpack time + disk at sync — incremental, since the source-extraction path already
  exists and runs at the same point.

B was chosen. The extra disk/sync cost is the only real trade-off, and it is bounded (resources are a
small fraction of a jar; `.class` files are excluded — see §5.1).

---

## 4. User-Facing Behavior

At `mvn process-test-classes` (which runs `lathe:sync`), each dependency's resource files are unpacked
into `~/.cache/lathe/deps/<gav>/` alongside its extracted sources, read-only.

Then, from Neovim:

- `:LatheDepGrep [pattern]` → Telescope `live_grep` scoped to **this workspace's** dep cache dirs,
  filtered to resource file types. Content search across every dependency resource.
- `:LatheDepFiles [name]` → Telescope `find_files` scoped to the same dirs — the filename finder, when
  you just want to open a known file.
- Both prefill the prompt with the optional argument (matching the IntelliJ "prefill last query"
  preference); with no argument they open over the full scoped set.
- Opening a hit shows the file read-only (extracted files are already read-only on disk).

| Input | Result |
|---|---|
| `:LatheDepGrep "db.changelog"` | Every dep resource whose *content* matches, across all deps |
| `:LatheDepFiles logback` | Every dep resource file whose *name* matches `logback` |
| Either, no arg | Picker over all dep resources in this workspace's cache; type to filter |
| No dependencies with resources | Clean "no dependency resources found" notification |
| Telescope not installed | Fall back to the in-house `lathe.pick` over a filename list (as `:LatheTypeHierarchy` does) |

**Scope is strictly this workspace's dependencies.** `~/.cache/lathe/deps` is shared across every
project on the machine; the wrapper passes only the current workspace's dep dirs as `search_dirs`, so
results never leak deps from unrelated projects.

---

## 5. Technical Design

### 5.1 Maven plugin — extract main-jar resources at sync

Extend the existing source-extraction path in `lathe-maven-plugin` (`DependencySourceSync`), which
already runs per dependency at sync.

1. **Resolve the main artifact jar** for each dependency (the resolver already handles the sources jar;
   the main jar is the primary artifact, resolvable via the same `DependencySourceResolver` path).
2. **Extract its resource entries** into the same per-dep dir (`sourceCacheDir` =
   `~/.cache/lathe/deps/<gav>/`), reusing `ZipCache.extract`, but with an **entry filter** that keeps
   only resources:
   - **skip** directories and `*.class` (the bulk of a main jar — never wanted);
   - **skip** `module-info.class` / `META-INF/versions/**/*.class` implicitly (covered by the `.class`
     rule);
   - keep everything else (`.xml`, `.properties`, `.yml`, `.yaml`, `.sql`, `.txt`, `.json`, `.conf`,
     `META-INF/**` text, etc.).
   - The filter is a small denylist (`.class` + directories), not an allowlist, so unusual resource
     extensions are still surfaced.
3. **Filter support in the util.** `FileUtil.unzip` / `ZipCache.extract` currently extract *all*
   entries. Add an overload taking an entry `Predicate<ZipEntry>` (or filename predicate), keeping the
   existing no-arg behavior for source extraction. This is the one genuinely new util capability, kept
   narrowly scoped per the "no custom file-walking in local classes" rule (extend `FileUtil`, don't
   open-code a `ZipFile` loop in the Mojo).
4. **Idempotence / invalidation.** Write a separate marker `.lathe-resources.json` (constant in
   `LatheLayout`, mirroring `DEPENDENCY_SOURCE_FILENAME`) keyed on the **main jar's** size/mtime/schema,
   so resource re-extraction is skipped when fresh and redone when the main jar changes — independent of
   the sources-jar marker (a dep may have a main jar but no sources jar, or vice versa).
5. **Read-only**, atomic temp-dir + rename, exactly as the source path already does (inherited from
   `ZipCache`/`FileUtil`).

Because resources unpack into the **same `<gav>` dir** already recorded as `DependencyData.dir`, the
manifest needs **no schema change** — the existing `dir` now also contains resources.

### 5.2 Neovim client — scoped native search

New module `lua/lathe/depsearch.lua` (thin; no jar/Java knowledge), registered via `M.setup()` from
`lathe.lua` (mirroring `typehierarchy`/`instances`).

1. **Discover this workspace's dep dirs.** Read `dependencySources[].dir` from
   `<workspaceRoot>/.lathe/workspace.json` (via `vim.json.decode`). This is a plain, cwd-relative file
   the client can read directly — no server round-trip. (Alternative considered: a tiny
   `lathe.dependencyRoots` `executeCommand`; direct read is chosen for the "simpler win," with the
   command as a fallback if the manifest read proves brittle — see §10.)
2. **`:LatheDepGrep [pattern]`** → `require('telescope.builtin').live_grep({ search_dirs = <dep dirs>,
   default_text = pattern, glob_pattern = <resource globs> })`.
3. **`:LatheDepFiles [name]`** → `require('telescope.builtin').find_files({ search_dirs = <dep dirs>,
   default_text = name })`.
4. **Telescope is a soft dependency:** `pcall(require, 'telescope.builtin')`; if absent, fall back to
   `lathe.pick` over a filename list gathered by walking the dep dirs (filenames only — no content
   search in the fallback), consistent with `typehierarchy.lua`.
5. Suggested `<leader>`-keymaps documented in the Neovim cheatsheet.

### 5.3 Reuse map

| Concern | Reused component |
|---|---|
| Per-dep cache dir + GAV naming | `DependencySourceResolver.sourceCacheDir` / `ReactorProjects.gav` |
| Extraction (atomic, read-only) | `ZipCache.extract` + `FileUtil.unzip` (**new** filtered overload) |
| Cache invalidation marker | mirror `.lathe-source.json` → **new** `.lathe-resources.json` |
| Extracted dir already in manifest | `DependencyData.dir` (no schema change) |
| Native search UI | Telescope `live_grep` / `find_files`; fallback `lathe.pick` |

---

## 6. Required Changes

| Change | File | Notes |
|---|---|---|
| Filtered zip-extract overload | `FileUtil.java`, `ZipCache.java` (`lathe-core`) | `Predicate<ZipEntry>`; existing no-arg behavior kept |
| Main-jar resource extraction at sync | `DependencySourceSync.java` (+ resolver) | reuse `ZipCache.extract` with `.class`/dir denylist |
| Resource marker filename constant | `LatheLayout.java` | mirror `DEPENDENCY_SOURCE_FILENAME` |
| Resource marker read/write + freshness | `DependencySourceSync.java` | keyed on the **main** jar |
| Client scoped search + commands | `lua/lathe/depsearch.lua` (new) + registration in `lathe.lua` | Telescope soft dep, `lathe.pick` fallback |
| Docs | `README.md`, `docs/guide/editors/neovim.md`, `docs/status.md`, `design-index.md` | commands + keymaps |

No `lathe-server` change is required for the core feature.

---

## 7. Test Plan

Read at least two neighbouring tests before writing new ones (per the testing rules).

**Maven plugin / core** (`@TempDir`, build small real jars in-test — mirror the existing
`DependencySourceSync` / `ZipCache` tests):

- `unzip_withResourceFilter_extractsResourcesSkipsClasses` (positive — a jar with `.class` + `.xml` +
  `.properties` yields only the resources on disk, read-only).
- `unzip_noFilter_extractsAllEntries` (regression — existing source behavior unchanged).
- `extractResources_freshMarker_skipsReExtraction` /
  `extractResources_mainJarChanged_reExtracts` (invalidation, positive + edge).
- `extractResources_depWithoutSourcesJar_stillExtractsResources` (edge — resource extraction is
  independent of sources availability).
- `extractResources_noResourceEntries_writesMarkerNoFiles` (negative).

**Client** (`depsearch_spec.lua`; stub Telescope + a fake `workspace.json`):

- `latheDepGrep_scopesSearchDirsToWorkspaceDeps` (positive — `search_dirs` == this workspace's dirs,
  not the whole cache).
- `latheDepFiles_withArg_prefillsPrompt` (positive — `default_text`).
- `latheDepGrep_noTelescope_fallsBackToLathePick` (fallback).
- `latheDepSearch_noDependencies_notifies` (negative).

**Invoker** (`lathe-maven-plugin`, `mvn verify`): after sync on the `multi-module` workspace, assert a
known dependency **resource** file (not a `.class`) exists under `~/.cache/lathe/deps/<gav>/` and that no
`.class` files were extracted from the main jar (per the "invoker fixtures assert server-visible
behavior / `.lathe` layout" convention).

```bash
mvn -pl lathe-maven-plugin -Dtest=DependencySourceSyncTest test
mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module
mvn spotless:apply          # immediately after any Java edit
```

---

## 8. Non-Goals

- **No server-side jar scanning or custom picker engine.** Search is delegated to the user's native
  Telescope tooling over on-disk files.
- **No `.class` extraction.** Only resource entries of the main jar are unpacked; compiled classes are
  never written to the cache.
- **No manifest/schema change.** Resources reuse the existing `DependencyData.dir`.
- **No reactor-resource surface.** Reactor resources are ordinary files already reachable by any
  file-finder; scope is dependency resources.
- **No editing of dependency resources.** Extracted files are read-only, as sources already are.
- **No global-cache search.** The client scopes to this workspace's dep dirs only.

---

## 9. Resolved Decisions

1. **Extract at `lathe:sync` (eager)**, not lazily on first search — always ready to grep; follows the
   existing source-extraction point. (User: *"stick to lathe:sync."*)
2. **Native Telescope `find_files`/`live_grep`** over the cache, not a bespoke picker — content search
   for free, reuses the workflow the user already likes. (User: *"do a file grep on .cache certain
   files … a simpler win."*)
3. **Same `<gav>` cache dir as sources**, so no manifest schema change and one search tree per dep.
4. **`.class` + directory denylist** (not an allowlist) so unusual resource extensions are still found.
5. **Separate resource marker** keyed on the main jar, independent of the sources-jar marker.

## 10. Open Questions

1. **Dep-dir discovery in the client.** Read `workspace.json` directly (chosen — simplest) vs. a tiny
   `lathe.dependencyRoots` `executeCommand`. If direct manifest reads prove brittle across schema
   revisions, switch to the command.
2. **Resource glob for `live_grep`.** Default to a broad resource set or search all non-`.java` files
   in the dep dirs? Broad-but-bounded first cut; refine if noise appears.
3. **Disk footprint.** Measure the added cache size on a large reactor (Helidon/Dropwizard). If it is
   material, reconsider lazy extraction or an allowlist of resource extensions.
4. **Sources jar already carries some resources.** When a dep's `-sources.jar` and main jar both carry
   a resource, extraction order/`REPLACE_EXISTING` decides which wins; prefer the main jar's copy
   (the runtime artifact) and document it.
