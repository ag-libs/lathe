# Lathe — Staleness Detection via Compile Stamps

Status: proposed.
Replaces the class-file-derivation heuristic in the source-staleness scan (shipped as WS-5) with a
recorded, per-source compile stamp.
Tracked by the staleness-robustness WS gap; sibling to the package/path-mismatch gap (WS-9).

## Problem

The staleness scan (`WorkspaceSession.isStaleSource`) decides "this closed source needs a Maven
sync" by **inferring the class file from the source's path**:

```java
classFile = classesDir / <root-relative dir> / <filename>.class
stale = !classFile.exists() || sourceMtime > classFileMtime
```

That inference is only valid when the file's declared package matches its directory and its type
name matches its filename.
When it doesn't — a missing/wrong `package`, or a package-private type whose name differs from the
file name — the class is emitted somewhere else (or under a different name), the derived path never
exists, the source is reported stale on every 2-second tick, and the sync prompt recurs.
A sync cannot clear it (Maven produces the same layout), and each reload/refresh re-arms the
acknowledgement high-water mark, so the prompt is effectively inescapable.

This was confirmed empirically: javac compiles a default-package class inside a named module
*without error* and places it at the module top level; the failure is a *runtime* module-resolution
error, not a compile error.
So the scan's assumption — not the user's file — is the thing to fix.

See the mechanism write-up in the WS gap for the full failure analysis; this document is the fix for
the detection half.

## Goal / non-goals

**Goal.** Make "does this source need a rebuild?" a fact recorded at compile time, not a guess
re-derived from the filesystem layout — so no package/path or name mismatch can produce a phantom
"stale" that a sync cannot clear.

**Non-goals.**

- The **misplacement diagnostic** (warn the user that a file's `package` does not match its
  directory) is a separate, companion change surfaced on open/save — not part of the staleness
  detection and not covered here.
- The **runtime/launch** experience for an already-corrupt module (a friendlier error than the raw
  `FindException`) is out of scope.
- Detecting a class file that vanished from the mirror while its source is unchanged (a full sync
  fixes it) is explicitly not handled — see *Option comparison*.
- **Resources are out of scope — this design neither needs nor affects them.** Resource freshness is
  a *separate* path (`reconcileResources` → `copyIfStale`) that maps each file 1:1 by an explicit
  `manifest.resourceDestination` path — no compilation, package, or type-name derivation — so the
  package/path-mismatch phantom and the un-clearable sync-prompt loop this design fixes cannot arise
  for resources, and the Java-source scan already excludes them (`FileUtil::isJavaFile`). That is the
  only claim made here: it does **not** assert resource copying is *correct*. The incremental copy
  diverges from a Maven build for filtered resources (raw `${…}` left un-interpolated), deleted
  resources (an orphan copy lingers), and resources that are compilation inputs (dependent classes are
  not rebuilt). Those are a distinct, pre-existing resource-freshness gap — tracked separately, not by
  this design.

## Design

Record, per source, **when it was last compiled**, and compare the source's current mtime against
that stamp.
The class file — its location, its name, its very existence — drops out of the staleness decision
entirely, which is exactly the fragility being removed.

```
stamp = recorded compile-time mtime for this source
current source mtime > stamp   → stale  (edited since we built it)
no stamp for this source       → stale  (never compiled — a new file)
otherwise                      → fresh
```

### The record

A small JSON file **per source tree** (one for `main`, one for `test`) under `.lathe/<module>/`,
mirroring the existing `lsp-params-<tree>.json` convention — decided over a single per-module file so
it aligns with the per-tree `ModuleSourceConfig` the scan already iterates.
It holds a flat `source-root-relative path → mtime` map under a `stamps` key (a thin wrapper record so
it deserialises through the existing `Json.read(Class)` helper):

```json
{ "stamps": { "com/example/app/Foo.java": 1725000000000 } }
```

A single shared helper in `lathe-core` (used by both writers and the reader — DRY) owns the format:

- `load(moduleDir, sourceTree) → Map<String,Long>`
- `writeAll(moduleDir, sourceTree, Map<String,Long>)` — full rewrite (a whole-module build)
- `record(moduleDir, sourceTree, relPath, mtime)` — load-put-write for one source (a save)

Format via the existing `Json` helper; the filename is a constant in `LatheLayout`
(`compiledStampsFileName(sourceTree)` alongside `paramsFileName(sourceTree)`).
The file carries **no schema version** — it is a trivial regenerable map, and the no-backward-
compatibility rule means a format change just rewrites it on the next build.
`lathe-core` is already the shared home for both `lathe-compiler` and `lathe-server`, so the helper
lives there with no new cross-module dependency.

### Two writers (the one real constraint)

The stamp map is only correct if **every** path that refreshes the mirror also updates it:

1. **Maven build** — `LatheCompiler.syncOutput` already mirrors `target/classes` into
   `.lathe/<module>/classes` and calls `ParamsWriter.write`. Add one call that walks the compiled
   source roots and `writeAll`s their current mtimes. Authoritative and complete: a full build
   rewrites the whole map, so deletions and renames are pruned for free.
2. **Server save-compile** — after a successful `ModuleSourceCompiler` FULL compile
   (`afterModuleSave`, next to the existing `refreshReactorShard`), `record` the saved source's
   mtime.

Both call the same `lathe-core` helper.
This two-writer requirement is the cost of the approach and the thing to get right; it is called out
again under *Trade-off*.

### The reader (net deletion of code)

`isStaleSource` collapses to a map lookup and **deletes** the fragile parts:

```java
rel     = root.relativize(source).toString();
stamp   = stampsFor(config).get(rel);          // cached per (file, mtime)
return stamp == null || mtimeMillis(source) > stamp;
```

Removed by this change:

- the `packageRel` / `typeNameFrom` class-path derivation,
- the `package-info.java` special-case (it existed only because that file may emit no class — a
  class-derivation problem that no longer exists),
- any notion of "where is the class."

The stamp map is loaded once per module and cached, keyed on the stamp file's own mtime (same
cache-by-mtime pattern already used elsewhere), so a steady-state tick does no I/O beyond the
source `stat`s it already does.

### No fallback path — the absent-stamp case is self-healing

A `.lathe/` written before this feature has no stamp file, so no source has a stamp — and the
per-source rule already covers that: *no stamp → stale*.
The first post-upgrade scan therefore flags those modules stale and raises the ordinary sync prompt;
the sync runs the new writer and produces the stamps.
No schema bump, no version check, no second code path — the reader stays a single branch, and the
degraded state is an honest one-time nudge rather than a silent wrong answer.

### Deletions need no handling

The scan walks **existing** source files and looks each one up in the map.
An orphaned stamp (source deleted, entry lingering) is never looked up, so it is inert; the next full
build rewrites the map and drops it.
KISS: no delete-event bookkeeping.

### Stamp value: mtime (decided)

The stamp is the source's **mtime** — it matches today's mechanism, and the source `stat` is already
done, so the healthy tick adds no I/O.
A `git checkout` touches mtimes, so identical content can read as stale (the same behavior as today).
A content **hash** would be robust to that but costs a file read per suspicious source; it is recorded
here as a **later hardening**, explicitly out of scope for phase one.

## Option comparison (why not record the class map)

The alternative was recording `source → produced class files` and stat-ing those.
It additionally detects a class file that vanished from the mirror while the source is unchanged, but
it keeps the scan coupled to class-file locations and requires capturing javac's output list.
The compile-stamp option is strictly simpler (no class-file coupling, no output capture) and covers
every mismatch case; the only thing it gives up is the vanished-class detection, which a full sync
already repairs.
Chosen: compile stamp.

## Consequences

| Situation | Old behavior | New behavior |
|---|---|---|
| Missing / wrong `package` | phantom stale, un-clearable loop | not stale (stamp matches) — corruption surfaced by the *diagnostic*, not the prompt |
| Package-private type, name ≠ filename | phantom stale (looked for `<file>.class`) | not stale |
| New, unbuilt file | stale → prompt | stale → prompt (no stamp) — unchanged, correct |
| External edit (git pull, branch, agent) | stale → prompt | stale → prompt (mtime > stamp) — unchanged, correct |
| Deleted source | not scanned | not scanned; orphan stamp inert |
| `.lathe/` predating this feature (no stamp file) | n/a | every source reads stale → one sync prompt writes stamps; self-healing, no fallback code |

Residual: a source that fails to **parse/compile** for a genuine syntax error still has no stamp and
reads as stale — indistinguishable at this layer from a legitimately-new file.
That surfaces as a normal diagnostic when opened; a sync cannot fix a syntax error, but neither could
the old scan, so this is not a regression.

## Trade-off

The compile-stamp map is only as correct as its writers: the Maven compile path **and** the server
save path must both keep it current, or a save-then-close on a path that forgot to stamp reintroduces
a false signal.
That two-writer discipline is the whole cost of the approach — weighed against a scan that can never
again be fooled by where a class file landed.

## Regression targets

- `staleness_recordedStampMatchesSource_notStale` (positive)
- `staleness_sourceNewerThanStamp_stale` (positive)
- `staleness_noStampForSource_stale` (new file)
- `staleness_missingPackage_notStale` (the WS-9 case — no phantom)
- `staleness_packagePrivateNameMismatch_notStale`
- `staleness_orphanStampForDeletedSource_ignored`
- `LatheCompilerTest.syncOutput_writesStampsForCompiledSources` (Maven writer)
- server `afterModuleSave_recordsStamp` (save writer)
- `CompiledStampsTest` round-trip (helper load/writeAll/record)

Reduce a real fixture only where a full module build is essential; prefer the existing
`WorkspaceSession` staleness unit tests for the scan logic.

## Relationship to other work

- Fixes the detection half of the package/path-mismatch cluster (**WS-9**); the **diagnostic** half
  and the **`:LatheNew`** prevention (**NV-6** /
  [`:LatheNew` ergonomics rethink](lathe-new-type-ergonomics.md)) are separate.
- Evolves the shipped WS-5 source-staleness scan; the WS-3 sync prompt and WS-4 mirror-refresh paths
  are unchanged (this only changes how a source is judged stale).
