# Lathe — Reactor Type Index Design

This document describes the focused server-side slice for adding reactor type candidates to
`WorkspaceTypeIndex`.
It builds on [lathe-type-index.md](lathe-type-index.md) and uses the current server worker model from
[lathe-server-data-flow-recipe.md](../done/lathe-server-data-flow-recipe.md).

---

## Goal

Add reactor type candidates to `WorkspaceTypeIndex` so completion can suggest public top-level types from the
current Maven reactor.

The index remains candidate discovery only.
Javac remains the authority for dependency scope, JPMS readability, exports, and accessibility.

---

## Implementation Status

Static dependency and JDK shards are produced during `lathe:sync` and loaded from `.lathe/workspace.json`.

Reactor classes under `.lathe/<moduleRel>/classes` and `.lathe/<moduleRel>/test-classes` are now indexed in memory by
the server.
`WorkspaceSession` scans loaded `ModuleSourceConfig.latheClassesDir()` directories on startup/reload,
merges the resulting entries with static dependency/JDK shards,
and refreshes the routed reactor shard after successful save-time full compiles.

Deleted Java sources remove matching top-level and nested `.class` outputs and refresh the routed reactor shard.
Generated-source cleanup remains future work.

---

## Ownership

`WorkspaceSession` owns the current immutable `WorkspaceTypeIndex` snapshot and the mutable shard state used to
rebuild it.

`WorkspaceModuleRegistry` owns loaded `ModuleSourceConfig` entries and lazy `CompilationWorker`s.

Each `CompilationWorker` owns one `SourceAnalysisSession` and one `ModuleSourceCompiler` for a single module source
tree.

A module source tree is represented by one loaded `ModuleSourceConfig`.
Examples of the logical module/source-tree identity:

```text
app/classes
app/test-classes
platform/core/classes
```

The implementation uses `ModuleSourceConfig` directly as the shard key because each loaded config already
represents one module/source-tree pair and carries `relativePath`, `sourceTree`, and `latheClassesDir()`.
No separate `ModuleSourceKey` record was needed.

---

## Index State

Keep `WorkspaceTypeIndex` immutable.

`WorkspaceSession` owns mutable index state:

```java
private Map<ModuleSourceConfig, List<TypeIndexEntry>> reactorShards;
private WorkspaceTypeIndex typeIndex;
```

Updates replace the snapshot:

```text
old WorkspaceTypeIndex remains untouched
build new WorkspaceTypeIndex from static shards + reactor shards
swap WorkspaceSession.typeIndex
```

---

## Startup And Reload

On initialize/reload:

```text
lathe-worker:
  load WorkspaceManifest
  scan WorkspaceModuleRegistry from .lathe/lsp-params-*.json
  scan reactor output dir for each ModuleSourceConfig
  build merged WorkspaceTypeIndex from manifest shard paths + reactor entries
  create/swap WorkspaceModuleRegistry and WorkspaceTypeIndex snapshot
```

For each `ModuleSourceConfig`, scan only:

```java
config.latheClassesDir()
```

Because main and test are already separate configs, this naturally scans:

```text
.lathe/<moduleRel>/classes
.lathe/<moduleRel>/test-classes
```

Do not scan arbitrary `.lathe/**/classes` directories outside loaded params.

---

## Save-Time Update

For one save event, only the saved document gets a `CompileMode.FULL` compile.

After the full compile, `lathe-worker` already has the routed `ModuleSourceConfig`, so it can refresh the index
directly.

Flow:

```text
didSave
  -> lathe-worker routes saved document to ModuleSourceConfig
  -> lathe-worker submits FULL CompileRequest to CompilationWorker
  -> module-source worker compiles the saved document
  -> javac writes .class files under config.latheClassesDir()
  -> CompileResponse returns to lathe-worker
  -> lathe-worker publishIfCurrent(...)
  -> lathe-worker schedules existing AST/open-document refreshes
  -> lathe-worker scans config.latheClassesDir()
  -> lathe-worker replaces reactor shard for ModuleSourceConfig
  -> lathe-worker rebuilds immutable WorkspaceTypeIndex snapshot
```

This means save-time scanning is simple and server-worker-owned.
It scans one module source tree, not the whole project.

No `ReactorIndexUpdate` needs to cross from module worker to server worker in the first implementation.

If profiling later shows this blocks `lathe-worker`, move the scan off-thread and return immutable shard updates.
Do not add that complexity initially.

---

## Completion Access

Use request-local snapshots, not providers.

`WorkspaceSession.completionFuture()` captures:

```java
WorkspaceTypeIndex indexSnapshot = typeIndex;
```

Then passes it through:

```java
CompilationWorker.complete(..., indexSnapshot)
SourceAnalysisSession.complete(..., indexSnapshot)
CompletionRequest(..., indexSnapshot)
```

`CompletionEngine` reads `req.typeIndex()`.

This keeps thread ownership clear:

```text
lathe-worker chooses immutable snapshot
module-source worker consumes immutable request data
```

---

## Dependency Boundaries

Do not encode reactor dependency visibility in the index for correctness.

The merged index may contain all public top-level reactor types.
Completion validates candidates using the current file's javac context:

```java
elements.getTypeElement(candidate.qualifiedName()) != null
```

Because the current `SourceAnalysisSession` was built from the current `ModuleSourceConfig`,
javac sees the exact classpath/modulepath for that source tree.

The module compiler also places `config.latheClassesDir()` on `CLASS_PATH`.
`CLASS_OUTPUT` controls where javac writes fresh `.class` files,
but it is not enough for later completion validation to resolve already-compiled reactor classes.
Putting the Lathe classes directory on `CLASS_PATH` lets `TypeIndexValidator` resolve same-module reactor candidates.

This respects:

- main vs test classpaths
- reactor dependency graph
- JPMS readability
- exported packages
- Maven-remapped `.lathe/` outputs
- packaged reactor JAR remapping

Later, if candidate volume becomes expensive, add dependency-aware prefiltering as an optimization only.

---

## Scanning

Move reusable classfile scanning from `lathe-maven-plugin` to `lathe-core` so `lathe-server` can scan reactor output
directories without depending on the Maven plugin.

Move:

```text
ClassFileTypeScanner
ClassAccess
ClassAccessReader
```

from plugin package to core type-index package or nearby core package.

**Status: done.**
`ClassFileTypeScanner`, `ClassAccess`, and `ClassAccessReader` now live in `lathe-core`.
`ClassFileTypeScanner.scanDirectory(...)` returns an empty list when the output directory does not exist yet.

The scanner should include:

- public top-level class
- public interface
- public enum
- public record
- public annotation

It should skip:

- package-private top-level types
- nested classes
- `module-info.class`
- `package-info.class`
- invalid class files

---

## Source Deletion

When Java source deletion support removes class files from `.lathe/<moduleRel>/<sourceTree>`, refresh the affected
reactor shard.

The implemented flow stays server-worker-owned:

```text
lathe-worker handles deletion
  identify ModuleSourceConfig
  delete matching top-level and nested class files
  scan config.latheClassesDir()
  replace reactor shard
  rebuild WorkspaceTypeIndex snapshot
```

Generated-source cleanup is deferred until a concrete annotation-processor stale-file case appears.

---

## SNAPSHOT Dependency Follow-Up

SNAPSHOT dependency changes are separate from reactor indexing.

Once scanner code lives in `lathe-core`, `lathe-worker` can detect stale static shards by comparing dependency JAR
size/mtime against shard origin metadata.

Initial behavior can be:

```text
detect stale dependency shard
drop shard or warn user to rerun mvn process-test-classes
```

Later behavior:

```text
background scan stale JAR
lathe-worker applies rebuilt static shard
swap WorkspaceTypeIndex
```

---

## Testing

Unit tests:

- core classfile scanner directory scan
- public types included
- package-private/nested/module-info/package-info skipped
- invalid class file skipped

Server tests:

- startup/reload scans reactor `.lathe/<module>/<sourceTree>`
- main/test source trees produce separate `ModuleSourceConfig` shards
- static dependency shards and reactor shards merge
- completion gets reactor type candidate
- javac validation drops inaccessible/unreachable reactor candidate
- save-time full compile refreshes exactly one routed module source shard
- Java source deletion removes matching class files and refreshes the routed module source shard

Current committed coverage includes:

- `WorkspaceTypeIndex` merging static and reactor entries
- module compiler completion resolving a reactor-only type scanned from `latheClassesDir`
- class-literal completion at a Dropwizard-style `SomeType.class` site

Manual validation also confirmed that a saved new source file is compiled,
the routed reactor shard is refreshed,
and completion in the same running LSP process then suggests the new type.

Threading tests:

- `CompileMode.FULL` save path triggers reactor shard refresh on `lathe-worker`
- `CompileMode.FAST` does not update index
- `WorkspaceSession` swaps index snapshot only on server worker

---

## Suggested Implementation Slices

1. Pass request-local `WorkspaceTypeIndex` snapshot through completion.
2. Move classfile scanner to `lathe-core`.
3. Add reactor shard state and merged snapshot rebuild.
4. Add reactor scanning on startup/reload.
5. Add save-time refresh on `lathe-worker` after successful full save compile.
6. Add Java source-deletion refresh.
7. Add SNAPSHOT dependency freshness detection.
