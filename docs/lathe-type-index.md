# Lathe — Type Index Design

Working design draft.
This document captures the planned v1 type-index shape for completion and missing-import support.
It is intentionally scoped: the index discovers possible type candidates, while javac remains the final authority for
whether a candidate is legal from a specific source file.

---

## 1. Purpose

Lathe needs a fast way to answer type-name prefix queries:

```java
Arr|
Immutable|
FooSer|
```

The server cannot scan every reactor output directory, dependency JAR, and JDK module during completion.
The type index provides a precomputed candidate set that completion can query in milliseconds.

The index is used for:

- simple type-name completion
- missing-import suggestions
- future workspace symbol and package-prefix completion

The index is not used as the final visibility model for Java or JPMS.

---

## 2. Design Principle

### Index Is Candidate Discovery

The index answers:

> Which types might exist whose simple name starts with this prefix?

It stores broad, cheap facts:

- simple name
- qualified name
- package name
- origin
- optional module/source metadata

It does not attempt to fully decide Java accessibility.

### Javac Is Final Visibility Authority

Completion validates top candidates with the current javac context when available:

```java
TypeElement type = elements.getTypeElement(candidate.qualifiedName());
if (type != null && elements.isAccessible(type, enclosingType)) {
  keep(candidate);
}
```

This keeps JPMS readability, exports, classpath/modulepath behavior, and ordinary Java accessibility aligned with the
same javac invocation Lathe already captured from Maven.

If no attributed snapshot or probe is available, completion may return broad index candidates with
`isIncomplete=true`.

---

## 3. Scope

### In Scope for v1

- public top-level dependency classes
- public top-level reactor classes
- static dependency shards written by `lathe:sync`
- reactor shards scanned by the server from `.lathe/<module>/classes` and `.lathe/<module>/test-classes`
- in-memory merged `WorkspaceTypeIndex` snapshot in the server
- prefix lookup by simple name
- javac validation over a capped candidate set
- dependency shard freshness by schema version, JAR path, JAR size, and JAR mtime

### Explicitly Deferred

- nested type completion such as `Map.Entry`
- package-private reactor candidates
- multi-release JAR precision
- strong content hashing for every dependency JAR
- persisted reactor shards
- JDK shard generation
- fully module-aware filtering without javac
- package-prefix completion
- workspace symbols
- server-side JAR change detection and index rebuild without Maven sync (requires `ClassFileTypeScanner`
  to move from `lathe-maven-plugin` to `lathe-core` so the server can rebuild shards independently)

---

## 4. Index Ownership

### Static Shards Written by `lathe:sync`

Dependency type indexes are static relative to a Maven sync.
They are written by `lathe-maven-plugin` under `~/.cache/lathe/type-index/`.

`lathe:sync` already has the inputs:

- resolved dependency artifact JARs
- dependency GAVs
- source-JAR status

Indexing static artifacts during sync keeps expensive scanning out of completion and out of the normal server edit path.

JDK indexing is a follow-up slice.
It should also be sync-time work, but it has different mechanics and should not block the first dependency/reactor
candidate pipeline.

### Reactor Shards Owned by the Server

Reactor classes are not static.
They change when:

- Maven recompiles a module and the shim mirrors outputs into `.lathe/`
- Lathe runs a full `didSave` compile and writes `.class` files under `.lathe/`
- a source file is deleted and Lathe removes matching class files

The server therefore scans reactor output directories itself and refreshes only affected module/source-tree shards.

### Merged Workspace Snapshot

The server loads static shards and reactor shards into one immutable `WorkspaceTypeIndex` snapshot.
The snapshot is replaced on workspace reload.
Individual reactor shards may also be replaced after save-time refreshes.

Old in-flight completion requests may finish against an older snapshot.
That is acceptable because javac validation and normal stale-result guards remain in place.

---

## 5. Type Candidate Model

The first model should be intentionally small:

```java
record TypeCandidate(
    String simpleName,
    String qualifiedName,
    String packageName,
    TypeOrigin origin,
    TypeKind kind) {}
```

`TypeKind` starts as:

```java
enum TypeKind {
  CLASS,
  INTERFACE,
  ENUM,
  RECORD,
  ANNOTATION,
  UNKNOWN
}
```

`UNKNOWN` is acceptable for early scanner slices.
Completion icons can improve when kind detection is added.

Origins:

```java
sealed interface TypeOrigin {
  record Reactor(String moduleRel, SourceTree sourceTree) implements TypeOrigin {}
  record Dependency(String gav, Path jar) implements TypeOrigin {}
  record Jdk(String vendor, String version, String moduleName) implements TypeOrigin {}
}
```

`SourceTree` distinguishes main and test output:

```java
enum SourceTree {
  MAIN,
  TEST
}
```

Main source completion should not see test output.
Test source completion may see both main and test outputs according to the module's captured test params.

---

## 6. Dependency Static Shards

### Cache Layout

Static shards live in the user cache:

```text
~/.cache/lathe/type-index/
└── deps/
    └── com.google.guava/
        └── guava/
            └── 32.0.0-jre/
                └── index.json
```

The exact path is an implementation detail.
`workspace.json` should point at the shard file paths the server needs to load.

### Shard Metadata

Dependency shard:

```json
{
  "schemaVersion": "1",
  "gav": "com.google.guava:guava:32.0.0-jre",
  "jar": "/home/user/.m2/repository/com/google/guava/guava/32.0.0-jre/guava-32.0.0-jre.jar",
  "size": 3043932,
  "mtimeMillis": 1780000000000,
  "types": [
    {
      "simpleName": "ImmutableList",
      "qualifiedName": "com.google.common.collect.ImmutableList",
      "packageName": "com.google.common.collect",
      "kind": "CLASS"
    }
  ]
}
```

The shard intentionally does not duplicate dependency source metadata.
`workspace.json` owns source status, source directory, external-source classpath, and the shard path.
The shard keeps `gav` for inspection, logging, and sanity checks, but freshness is based on schema version, JAR path,
JAR size, and JAR mtime.

### Freshness Checks

`lathe:sync` reuses an existing dependency shard when:

- the schema version matches
- the JAR path matches
- the recorded size matches `Files.size(jar)`
- the recorded mtime matches `Files.getLastModifiedTime(jar).toMillis()`

Otherwise, it rebuilds the shard.

This is deliberately cheaper than hashing every JAR.
The worst stale case is stale completion candidates, not incorrect compilation.

### SNAPSHOT Dependency Policy

SNAPSHOT dependencies can change under the same GAV.
The size/mtime check detects ordinary local repository updates after Maven resolves the dependency.

If the local repository preserves size and mtime across a SNAPSHOT update, the index may be stale.
That failure mode is acceptable for v1:

- javac diagnostics and attribution still use the actual updated JAR
- javac validation drops deleted/inaccessible stale candidates
- newly-added classes may be missing from completion until the shard is rebuilt

An escape hatch may be added:

```bash
mvn process-test-classes -Dlathe.typeIndex.force=true
```

### Atomic Writes

Shard writes follow the existing Lathe cache pattern:

1. write a complete temporary file in the target cache area
2. atomically move it over `index.json` where the platform supports it
3. never leave a partially written shard at the final path

---

## 7. Manual Class Scanning

Lathe will initially scan class files itself instead of depending on a classpath scanning library.
The scanner only needs public top-level class names.

### Directory Scanning

For exploded class directories:

```text
walk classesDir
  keep *.class
  skip module-info.class
  skip package-info.class
  skip names containing '$'
  read access_flags
  keep ACC_PUBLIC
  derive FQN from relative path
```

Example:

```text
com/google/common/collect/ImmutableList.class
  -> com.google.common.collect.ImmutableList
```

### JAR Scanning

For dependency JARs:

```text
open JarFile
  iterate entries
  keep *.class
  skip module-info.class
  skip package-info.class
  skip names containing '$'
  read access_flags from entry stream
  keep ACC_PUBLIC
  derive FQN from entry name
```

Multi-release JAR precision is deferred.
For v1, root and versioned entries may be handled conservatively or versioned entries may be skipped.
Javac validation remains the final authority.

### Public Top-Level Class Filtering

Top-level Java classes can only be public or package-private.
For external dependency shards, v1 keeps only public top-level classes.

This avoids noisy fallback completions when no javac snapshot is available.

Nested classes are skipped in v1 by ignoring class names containing `$`.
That avoids private/protected/package-private nested visibility rules.

### Classfile Access Flags Reader

The scanner does not need to parse method bodies, fields, attributes, or bytecode.
It reads only enough of the class file to reach `access_flags`.

Classfile layout prefix:

```text
u4 magic
u2 minor_version
u2 major_version
u2 constant_pool_count
cp_info constant_pool[constant_pool_count - 1]
u2 access_flags
```

Algorithm:

```text
read magic and versions
read constant_pool_count
for each constant-pool entry:
  read tag
  skip payload according to tag
  Long and Double consume two constant-pool slots
read access_flags
keep if ACC_PUBLIC is set
skip if ACC_MODULE is set
```

Relevant flags:

```text
ACC_PUBLIC = 0x0001
ACC_MODULE = 0x8000
```

If the reader sees an unknown constant-pool tag, it should skip that class and log at debug level.
It must not fail the entire shard.

### Skipped Entries

v1 skips:

- `module-info.class`
- `package-info.class`
- names containing `$`
- invalid or unreadable class files
- class files with unknown constant-pool tags
- non-public top-level classes

---

## 8. JPMS Handling

### What the Index Does Not Decide

The index does not decide:

- whether the requesting module reads the provider module
- whether the provider package is exported to the requester
- whether a qualified export permits the requester
- whether a candidate is accessible in the current enclosing class

Javac validation decides those when a snapshot or probe is available.

### Optional Module Metadata

The scanner may store module metadata as an optimization:

```java
record ModuleIndexFacts(
    String moduleName,
    Set<String> exportedPackages) {}
```

This metadata can reduce candidate noise when no javac snapshot exists, but it is not the correctness boundary.

### `ModuleDescriptor` and `ModuleFinder`

For exploded reactor output:

```java
Path moduleInfo = classesDir.resolve("module-info.class");
try (InputStream in = Files.newInputStream(moduleInfo)) {
  ModuleDescriptor descriptor = ModuleDescriptor.read(in);
}
```

For modular and automatic dependency JARs:

```java
ModuleFinder.of(jarPath).findAll()
```

`ModuleFinder` can derive descriptors for explicit modular JARs and automatic modules.
This should be added only when simple javac validation leaves too much fallback noise.

### Javac Validation as the Authority

The authoritative filter remains:

```java
TypeElement type = elements.getTypeElement(candidate.qualifiedName());
if (type == null) {
  drop(candidate);
}

if (enclosingType != null && !elements.isAccessible(type, enclosingType)) {
  drop(candidate);
}
```

If `enclosingType` cannot be determined, `getTypeElement` alone is still useful because it runs inside the current
module/classpath context.

---

## 9. Reactor Indexing

### Startup and Workspace Reload

On startup and workspace reload, the server:

1. loads `.lathe/workspace.json`
2. scans params files and builds `WorkspaceModules`
3. loads static dependency shards referenced by the manifest
4. scans reactor output directories
5. builds a merged immutable `WorkspaceTypeIndex`

Workspace reload replaces the whole index snapshot.

### Save-Time Shard Refresh

After a full save compile:

```text
didSave full compile
  -> module worker compiles one file
  -> javac/file manager writes class output under .lathe/
  -> orphan cleanup runs
  -> module worker returns compile result
  -> lathe-worker publishes diagnostics if current
  -> lathe-worker schedules reactor shard refresh
```

The refresh scans only:

```text
.lathe/<moduleRel>/classes       for main source
.lathe/<moduleRel>/test-classes  for test source
```

### Source Deletion

When a source file is deleted and Lathe deletes corresponding class files from `.lathe/`, the affected reactor shard is
refreshed.

### Coalescing Refreshes

Shard refreshes should be coalesced:

```java
Set<ModuleSourceKey> pendingIndexRefreshes;
```

If the same module/source-tree key is already pending, do not schedule another scan.
This prevents repeated scans during save storms.

---

## 10. Server Loading and Merging

### Loading Static Shards from `workspace.json`

The manifest should point at static shard paths:

```json
{
  "dependencySources": [
    {
      "gav": "com.google.guava:guava:32.0.0-jre",
      "jar": "/home/user/.m2/.../guava.jar",
      "status": "PRESENT",
      "dir": "/home/user/.cache/lathe/deps/com.google.guava/guava/32.0.0-jre",
      "classpath": [],
      "typeIndex": "/home/user/.cache/lathe/type-index/deps/com.google.guava/guava/32.0.0-jre/index.json"
    }
  ]
}
```

`typeIndex` is optional.
Older manifests and dependencies whose shard failed to build simply omit it.
If a shard is missing or unreadable, the server logs the issue and continues with the remaining shards.

When merging a shard, the server should prefer the active `DependencyData.gav()` and `DependencyData.jar()` from
`workspace.json` for the candidate origin.
The shard's `gav` is a debug/sanity field, not the active workspace authority.

### Scanning Reactor Outputs

Reactor output scanning is local to the server because the output changes during editing.

### Immutable `WorkspaceTypeIndex`

`WorkspaceTypeIndex` should live in the `analysis` package, not the `workspace` package.
`WorkspaceManifest` (in `workspace`) already imports `DefinitionLocator` from `analysis`, so placing
`WorkspaceTypeIndex` in `workspace` would create a cyclic package dependency with `analysis.completion`.
Keeping it in `analysis` avoids the cycle and reflects that it is analysis infrastructure consumed by
`CompletionEngine`.

Internal shape:

```java
final class WorkspaceTypeIndex {
  private final NavigableMap<String, List<TypeCandidate>> bySimpleNameLower;
  private final Map<String, TypeCandidate> byQualifiedName;
  private final Map<ModuleSourceKey, ModuleTypeIndex> reactorShards;
}
```

Prefix lookup can use a sorted map or sorted array.
A trie is unnecessary until measurement proves otherwise.

### Replacement on Reload

`WorkspaceSession` owns the current index snapshot together with the current `WorkspaceModules`.
Reload builds a new workspace and a new index from the same manifest/params snapshot.

---

## 11. Completion Flow

### Prefix Lookup

Completion asks the index for broad candidates:

```java
List<TypeCandidate> broad = workspaceTypeIndex.search(prefix, 200);
```

### Cheap Ranking and Limits

Before javac validation, candidates are ranked cheaply:

- exact case prefix before case-insensitive prefix
- same package before other packages
- imported packages before unrelated packages
- `java.lang` before unrelated packages
- shorter qualified name before longer qualified name

Then validation is capped.

### Javac Validation

When a cached analysis exists:

```java
for (TypeCandidate candidate : rankedCandidates) {
  TypeElement type = elements.getTypeElement(candidate.qualifiedName());
  if (type == null) {
    continue;
  }

  if (enclosingType != null && !elements.isAccessible(type, enclosingType)) {
    continue;
  }

  keep(candidate);
  if (kept.size() == limit) {
    break;
  }
}
```

For the first simple-name type-reference slice, only `getTypeElement` is used as the classpath gate.
`enclosingType` is not yet determined, so `isAccessible` is skipped.
`getTypeElement` alone is still meaningful: it runs inside the current module/classpath context, so types
not on the module's classpath return `null` and are dropped.
`isAccessible` can be layered in once `enclosingType` resolution is wired.

If validation yields too few items, completion may validate another page of candidates.

Validation should be measured before adding cache complexity.
The measured operation is the pair:

```text
candidate FQN -> Elements.getTypeElement(FQN) -> Elements.isAccessible(...)
```

Completion should record structured timing fields:

```json
{
  "event": "typeCandidateValidation",
  "prefix": "Arr",
  "candidateCount": 200,
  "checked": 64,
  "resolved": 18,
  "accessible": 12,
  "elapsedMicros": 2400
}
```

The first implementation should use a validation cap and a small deadline, for example:

```text
index query limit: 200 candidates
validation page: 50 candidates
completion result limit: 50 items
validation deadline: 20-30ms
```

If the deadline is reached, completion returns the accessible candidates found so far with `isIncomplete=true`.

### Optional Accessibility Cache

If measurement shows repeated validation is expensive, cache validation results with the cached analysis.
The cache should not live on the workspace index because accessibility is source-context-specific.

Suggested key:

```java
record AccessibilityCacheKey(
    String candidateQualifiedName,
    String enclosingTypeQualifiedName) {}
```

The cache is naturally invalidated when the file's `CachedFileAnalysis` is replaced, dropped on `didClose`, or cleared on
workspace reload.
Do not add this cache until timing logs show it is useful.

### Fallback Without Cached Analysis

If no cached analysis exists, completion should not block on broad attribution.
It may:

- return public index candidates with `isIncomplete=true`
- or return an empty list for strict modes

The default should favor useful completions:

```text
return public broad candidates, isIncomplete=true
```

### Import Edit Later Slice

The first slice may return only:

```text
label = simpleName
insertText = simpleName
detail = qualifiedName
```

Adding import edits is a later slice.

---

## 12. Performance Model

### Sync-Time Cost

Dependency scans run during `lathe:sync`.
This is the right place to pay static indexing cost because Maven is already resolving dependencies and refreshing
workspace state.

### Server Startup Cost

Server startup loads shard JSON and scans reactor outputs.
Static dependency JARs are not rescanned by the server.

### Completion-Time Budget

Completion must not scan filesystems or JARs.
It should only:

1. query the in-memory prefix index
2. rank/cap candidates
3. optionally validate top candidates with javac

### Why Not Scan on Completion

Scanning during completion has unpredictable latency and repeats work.
Large dependency sets make recursive scans especially risky.

---

## 13. Failure Modes

### Stale SNAPSHOT Index

The index may be stale if a SNAPSHOT JAR changes without size/mtime changes.
Compilation remains correct because javac uses the actual JAR.
Completion may miss new classes until the shard is rebuilt.

A further gap: even when a SNAPSHOT JAR does change size or mtime, the server has no mechanism to
detect this between Maven sync runs and rebuild the shard autonomously.
The current path is: re-run `mvn process-test-classes` → `lathe:sync` detects the mtime/size change →
rebuilds the shard → writes the updated path into `workspace.json` → server reloads on next file-watcher
tick.
Server-side autonomous rebuild would require `ClassFileTypeScanner` to move to `lathe-core` and a
`WorkspaceWatcher` extension to monitor JAR mtimes.
This is deferred.

### Missing Shard

If a static shard is missing, the server continues without candidates from that origin.
It may surface a low-priority log message or diagnostic hint to rerun `mvn process-test-classes`.

### Unknown Classfile Constant-Pool Tag

The scanner skips the affected class and logs at debug level.
The shard build continues.

### No Cached Javac Snapshot

Completion returns public broad candidates as incomplete.
The next debounced attribution pass enables stricter validation.

### Partial Workspace State

When Maven is invoked with `-pl`, `lathe:sync` normally skips writing `workspace.json`.
Static shards for resolved dependencies may still be valid, but the server should rely only on the manifest it loaded.

---

## 14. Implementation Plan

### Slice 1 — Manual Public Class Scanner

Implement directory and JAR scanning with a streaming classfile access-flags reader.
Add unit tests for public, package-private, nested, module-info, package-info, and invalid class files.

**Status: done.**

### Slice 2 — Static Dependency Shards + Server Load + `getTypeElement` Validation

The dependency shard infrastructure in `lathe-maven-plugin` is nearly complete after Slice 1.
This slice finishes the end-to-end dependency pipeline before the reactor slice, because it unblocks
testing the full type-completion path with real JAR types:

1. Fix `ClassAccess.kind()` to return `TypeKind.CLASS` for plain classes (was `UNKNOWN`).
2. Add `typeIndex` field to `DependencyData` and `DependencySource`; update `toData()` and factory
   methods.
3. Make `DependencyTypeIndexSync.indexPath()` public.
4. In `SyncMojo`, build a `jar → typeIndex path` map from `externalArtifacts` after calling
   `DependencyTypeIndexSync.index()`; enrich each `DependencySource` with its path via
   `withTypeIndex()` before writing the manifest.
5. In `WorkspaceManifest.load()`, collect `typeIndex` paths from `DependencyData`; expose via
   `typeIndexShardPaths()`.
6. Add `WorkspaceTypeIndex` in the `analysis` package with `build(List<Path>)`, `empty()`, and
   `search(prefix, limit)` backed by a `NavigableMap<String, List<TypeIndexEntry>>`.
7. Thread `WorkspaceTypeIndex` through `WorkspaceModules` → `ModuleSourceWorker` → `SourceAnalysisSession` →
   `CompletionEngine`.
8. In `CompletionEngine.completeTypeReference` when `receiverText == null`: query the index for
   prefix matches, filter through `elements.getTypeElement(fqn) != null`, and return items with
   `label = simpleName` and `detail = qualifiedName`.
9. In `WorkspaceSession.initialize()`, build `WorkspaceTypeIndex` from
   `manifest.typeIndexShardPaths()` and pass to `WorkspaceModules.scan()`.

### Slice 3 — Reactor Index and Unvalidated Completion

Scan reactor `.lathe/` output directories on server startup/reload.
Merge reactor candidates into `WorkspaceTypeIndex` alongside static dependency candidates.

### Slice 4 — `isAccessible` Validation and Timing

Extend validation to include `Elements.isAccessible` once `enclosingType` resolution is wired.
Cap validation work, add structured timing, and keep fallback behavior permissive.

### Slice 6 — Save-Time Reactor Shard Refresh

After full save compiles and source deletions, refresh only the affected reactor module/source-tree shard.
Coalesce refreshes by `ModuleSourceKey`.

### Slice 7 — JDK `jrt:/` Shard

Add JDK type candidates by scanning the `jrt:/` filesystem under `/modules`.
Reuse the same classfile access-flags reader.

### Slice 8 — Optional JPMS Metadata

If fallback results are too noisy, add module metadata through `ModuleDescriptor` and `ModuleFinder`.
Use it only as an optimization.

---

## 15. Testing Strategy

### Unit Tests

- classfile access flag reader
- JAR scanner
- directory scanner
- shard JSON read/write
- prefix index lookup and ranking

Scanner fixtures should include:

- public top-level class included
- package-private top-level class excluded
- public interface included
- public enum included
- public record included
- public annotation included
- nested public class skipped in v1
- `module-info.class` skipped
- `package-info.class` skipped
- invalid class file skipped without failing the whole scan

### Maven Plugin Sync Tests

Invoker projects should verify:

- dependency shards are written
- shards are reused when fresh
- shards are rebuilt when schema/path/size/mtime changes
- `workspace.json` contains shard paths

### Server Index Loading Tests

Server unit/integration tests should verify:

- missing shards do not fail startup
- reactor classes are scanned from `.lathe/`
- static and reactor candidates merge correctly
- reload replaces the index snapshot
- main/test source-tree filtering works

### Completion Tests

Completion tests should cover:

- reactor type-name completion
- dependency type-name completion
- no-snapshot fallback with `isIncomplete=true`
- javac validation dropping inaccessible candidates
- validation timing records checked/resolved/accessible/elapsed fields when logging is enabled

### SNAPSHOT/Freshness Tests

Tests should simulate a dependency JAR mtime/size change and assert the shard is rebuilt.

---

## 16. Future Work

### Nested Public Types

Index public nested classes and validate with javac before returning them.

### Multi-Release JAR Precision

Respect `META-INF/versions/<N>` according to the active JDK or `--release`.

### JDK `jrt:/` Indexing

Implement a JDK shard scanner by walking the runtime image filesystem:

```text
jrt:/modules/java.base/java/lang/String.class
jrt:/modules/java.base/java/util/List.class
jrt:/modules/java.sql/java/sql/Connection.class
```

Path shape:

```text
/modules/<module-name>/<package-path>/<ClassName.class>
```

The scanner should:

- walk `jrt:/modules`
- skip `module-info.class`, `package-info.class`, and names containing `$`
- reuse the classfile access-flags reader
- keep only `ACC_PUBLIC` top-level classes
- store the JDK module name on the candidate origin

`ModuleFinder` is useful for module metadata, but `jrt:/` is the primary class enumeration mechanism for the JDK shard.

### Persistent Reactor Shards

Persist reactor shards under `.lathe/` only if server startup scanning becomes measurable.

### Package Prefix Completion

Add indexes for package names and package-qualified type completion.

### Module-Aware No-Snapshot Filtering

Use stored module descriptors to improve fallback results when no javac snapshot exists.
This remains an optimization; javac validation is still authoritative when available.
