# Lathe — Resource Finder (find a resource by name, reactor + dependencies)

## Status

Proposed.
Supersedes the earlier *Dependency Resource Grep* design, which extracted **every** dependency
main-jar resource to disk at `lathe:sync` so native Telescope `live_grep` could search them.
That optimized for full-text search at the cost of bulk unpacking (disk, binaries, sync time) — but
the real need is narrower: **find a resource by name and open it** (e.g. a `.graphql`/`.graphqls`
schema shipped by a dependency).
Reframed accordingly: list resource names cheaply and extract only the one you open.
Content search is out of scope (see [Deferred](#9-deferred)).

---

## 1. Goal

Answer the motivating case — *"I know a resource's name; find it and open it"* — across **this
workspace's reactor and dependency resources** in one picker, and show **where each resource comes
from** (its origin), the way located class results already carry a reactor/dependency/JDK origin.

A jar is a zip: reading its **central directory** (the entry list) is fast and needs no
decompression and no extraction.
So the finder lists entry names across the workspace's dependency jars and reactor resource dirs,
lets the user pick by name, and extracts **only the single entry they open** — no bulk unpacking.

---

## 2. Current State

- Reactor resource directories are already captured from the effective Maven model (not the
  `src/main/resources` convention): `ModuleResourcesReader` → `WorkspaceManifestData.resourceRoots`
  → server-side `ResourceRootIndex`, which exposes `sourceDirs()` (the real, editable resource source
  dirs) and `destinationFor(...)`.
- Dependency jars are known to the server: `lathe:sync` extracts each dependency's `-sources.jar` to
  `~/.cache/lathe/deps/<gav>/`, and the manifest records the jar → source-dir mapping
  (`WorkspaceManifest.jarToSourceDir` / dependency data).
- **Gap:** dependency **resources** live inside the main artifact jar, which is never opened, so no
  tool can list or open them; and there is no single "find a resource by name" surface that spans
  reactor and dependency resources together.

---

## 3. Why this shape (vs. extract-all)

- The need is **find-by-name + open**, not full-text search, so extracting every resource to disk is
  unnecessary — a central-directory listing gives every name for free, and only the opened entry
  needs bytes on disk.
- Avoids the extract-all trade-offs: no disk bloat from binaries (native libs, fonts, images), no
  added sync time, no cache to invalidate for unopened files.
- Still reuses the user's Telescope workflow, now as a custom finder over the resource list rather
  than `find_files`/`live_grep` over an unpacked tree.
- The one thing given up is dependency **content** search; that is deferred, and if it is ever wanted
  the escalation is explicit (extract-on-demand, or an in-JVM jar grep) — see [Deferred](#9-deferred).

---

## 4. User-facing behavior

`:LatheResourceFind [name]` opens a picker over every resource in this workspace's reactor modules
and dependency jars, each row showing the resource name and its **origin**.
The optional argument prefills the prompt (matching the "prefill last query" preference); with no
argument it opens over the full set and the user types to filter.

On `<CR>`:

- a **reactor** resource opens its real source file, editable;
- a **dependency** resource is extracted (that one entry) to `~/.cache/lathe/deps/<gav>/…` and opened
  read-only.

| Input | Result |
|---|---|
| `:LatheResourceFind schema.graphqls` | Every reactor/dep resource named like `schema.graphqls`, each tagged by origin |
| `:LatheResourceFind`, no arg | Picker over all resources in this workspace; type to filter |
| Two deps ship the same `logback.xml` | Both shown, disambiguated by origin (`dep:groupId:artifactId:version`) |
| No resources found | Clean "no resources found" notification |
| Telescope not installed | Fall back to `lathe.pick` over the name+origin list (as `:LatheTypeHierarchy` does) |

**Origin** mirrors the class-result convention: `reactor:<moduleRel>` for a local resource,
`dep:<groupId:artifactId:version>` for a dependency resource.
**Scope is strictly this workspace** — reactor resource roots plus this workspace's dependency jars;
the shared `~/.cache/lathe/deps` of unrelated projects is never listed.

---

## 5. Technical design

No bulk extraction anywhere.
Two server commands plus a thin client; all data already exists in the manifest.

### 5.1 Server — list and open

- `lathe.resources` → a unified, deduped list of `ResourceEntry`:
  - **reactor**: `{ name, origin: "reactor:<moduleRel>", kind: FILE, path }` — walk each dir in
    `ResourceRootIndex.sourceDirs()`, skipping directories (real files, so `path` is editable).
  - **dependency**: `{ name, origin: "dep:<gav>", kind: JAR, jar, entry }` — read each dependency
    jar's **central directory only** (`java.util.zip.ZipFile.entries()`), no decompression.
  - Filter: a `.class` + directory **denylist** (so `.graphql`/`.graphqls` and any unusual extension
    surface); applied to both sources.
  - Same name from several origins is kept as distinct rows (origin disambiguates).
- `lathe.resourceOpen { jar, entry }` → extract exactly that entry to `~/.cache/lathe/deps/<gav>/…`,
  read-only, and return its path (reusing `ZipCache`/`FileUtil` for the single-entry extract).
  Reactor resources need no command — the list already carries their real `path`.

The list is computed on demand.
Central-directory reads are cheap, but a large reactor has many dependency jars; if the scan proves
slow it can be cached and invalidated on manifest change (see [Open questions](#10-open-questions)).

### 5.2 Client — `lua/lathe/resources.lua`

Thin, no jar/Java knowledge; registered from `lathe.lua` (mirroring `typehierarchy`/`instances`).

- `:LatheResourceFind [name]` → request `lathe.resources`, drive a Telescope **custom finder** over
  the returned rows, displaying `name  ⟨origin⟩` and prefilling `name`.
- On select: `kind == FILE` → `:edit <path>`; `kind == JAR` → request `lathe.resourceOpen`, then
  `:edit` the returned path and set it read-only (`nomodifiable`/`readonly`).
- Telescope is a soft dependency (`pcall`); absent → `lathe.pick` over the name+origin list (no
  content in either path).
- **Matching is client-side, fuzzy, and case-insensitive — taken for free.** Telescope's built-in
  sorter provides it, and the `lathe.pick` fallback is built on Neovim's `matchfuzzypos`, so the feel
  is the same with or without Telescope. Matched on `"<name>  <origin>"`, so a query can narrow by
  name or origin (`schema helidon`). No server-side matching.

### 5.3 Reuse map

| Concern | Reused component |
|---|---|
| Reactor resource dirs (real Maven model) | `ResourceRootIndex.sourceDirs()` |
| Dependency jars + `<gav>` origin | manifest dependency data / `jarToSourceDir`, `ReactorProjects.gav` |
| Single-entry extraction (atomic, read-only) | `ZipCache` / `FileUtil` |
| Result-origin convention | same reactor/dependency tagging class results already use |
| Picker UI | Telescope custom finder; fallback `lathe.pick` |

---

## 6. Required changes

| Change | File | Notes |
|---|---|---|
| `ResourceEntry` record (name, origin, kind, path/jar/entry) | `lathe-server` | wire type for the list |
| `lathe.resources` list (reactor walk + jar central-dir scan, denylist) | `WorkspaceSession` / `LatheTextDocumentService` | no extraction |
| `lathe.resourceOpen` single-entry extract | `WorkspaceSession` / `LatheTextDocumentService` | reuse `ZipCache` |
| Command constants + registration | `LatheWorkspaceService`, `LatheLanguageServer` | two new commands |
| Central-dir list + single-entry extract helpers | `FileUtil`/`ZipCache` (`lathe-core`) if not already present | keep util-owned, per "no ad-hoc zip loops" |
| Client finder + command | `lua/lathe/resources.lua` (new) + `lathe.lua` | Telescope soft dep, `lathe.pick` fallback |
| Docs | `README.md`, `docs/guide/editors/neovim.md`, `docs/status.md`, `design-index.md` | command + keymap |

---

## 7. Test plan

Read neighbouring tests first (per the testing rules); mirror `WorkspaceSessionTest` and the existing
`ZipCache` tests.

**Server / core** (`@TempDir`; build a small real jar in-test):
- `resources_listsReactorAndDependencyEntriesWithOrigin` — a reactor resource dir plus a jar with a
  `.graphqls` yield both, tagged `reactor:<module>` / `dep:<gav>`.
- `resources_skipsClassesAndDirectories` — `.class` entries and directories are excluded (both sources).
- `resources_sameNameDifferentOrigin_keptDistinct` — two origins with the same name stay separate rows.
- `resourceOpen_extractsSingleEntryReadOnly` — only the requested entry lands on disk, read-only.
- `resourceOpen_unknownEntry_returnsEmpty` — negative.

**Client** (`resources_spec.lua`; stub Telescope + a fake `lathe.resources` reply):
- `latheResourceFind_withArg_prefillsPrompt`.
- `latheResourceFind_fileEntry_opensPathEditable`.
- `latheResourceFind_jarEntry_callsResourceOpenThenOpensReadOnly`.
- `latheResourceFind_noTelescope_fallsBackToLathePick`.

**Invoker** (`mvn verify`, `multi-module`): after sync, `lathe.resources` returns a known reactor
resource and a known dependency resource with correct origins, and `resourceOpen` extracts a single
entry (and no `.class`).

---

## 8. Non-goals

- **No content / full-text search.** Find-by-name only; content search is deferred (§9).
- **No bulk extraction.** Only the opened dependency entry is written to disk.
- **No `.class` surface** and no JDK-resource surface.
- **No editing of dependency resources** (extracted read-only); reactor resources open as their real
  editable source.
- **No global-cache search** — scoped to this workspace's reactor roots and dependency jars.
- **No manifest/schema change** — reactor resource roots and dependency jars are already recorded.

---

## 9. Deferred

Dependency **content** search (the old extract-all goal).
If demand appears, the escalation is explicit and additive: either extract-on-demand into the cache
and reuse native `live_grep`, or an in-JVM jar-entry grep in the server.
Reactor content search already works — reactor resources are ordinary files any grep tool sees.

---

## 10. Resolved decisions

1. **Matching is client-side, fuzzy, case-insensitive — taken for free.** The server returns the full
   list; the client filters it with Telescope's built-in sorter, or the `lathe.pick` fallback's
   `matchfuzzypos` (both fuzzy and case-insensitive). Matched on `"<name>  <origin>"` so a query can
   narrow by name or origin. No server-side matching. Revisit only if a large reactor measures slow —
   then a broad case-insensitive substring pre-filter server-side, with the client still fuzzy-refining.

## 11. Open questions

1. **List cost on large reactors.** Central-directory reads are cheap per jar, but a big reactor has
   many dependency jars. Scan on demand first; if slow, cache the listing and invalidate on manifest
   change.
2. **Reactor origin granularity.** `reactor:<moduleRel>` is proposed; confirm that reads well in the
   picker versus a bare module name.
3. **Same resource in a dep's sources jar and main jar.** The list should not double-count; prefer the
   main-jar entry as the runtime artifact and dedup by (origin, name).
