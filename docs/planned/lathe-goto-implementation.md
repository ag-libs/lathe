# Lathe — Goto Implementation and Type Hierarchy

Proposed M1 design.
Builds on [lathe-design.md](../lathe-design.md) and the existing type-index implementation.

---

## 1. Goal

Implement four related LSP operations:

- `textDocument/implementation` for types and methods;
- `textDocument/prepareTypeHierarchy`;
- `typeHierarchy/supertypes`;
- `typeHierarchy/subtypes`.

Type implementation and hierarchy navigation cover reactor, dependency, and JDK classes when indexed source is
available.
Method implementation is intentionally limited to implementations declared in reactor source.

The design extends Lathe's existing type index with direct class-file inheritance metadata.
It does not search for type relationships by attributing every source file containing a matching token.

---

## 2. Design Summary

`ClassFileTypeScanner` records each class's direct superclass and interfaces in `TypeIndexEntry`.
The Maven plugin writes this metadata into cached dependency and JDK index shards during `lathe:sync`.
The server uses the same scanner for reactor class outputs at startup and after successful saves.

`WorkspaceTypeIndex` builds immutable forward and reverse relationship maps:

```text
ArrayList → AbstractList, List, RandomAccess, Cloneable, Serializable
List      → ArrayList, LinkedList, CopyOnWriteArrayList, ...
```

Type hierarchy and type implementation use these maps directly.
Method implementation uses the graph to select reactor subtype source files and then asks javac to validate exact
overrides.

The current ownership model remains unchanged:

- `WorkspaceSession` and its active index field remain confined to `ServerEventLoop`;
- an index is immutable after construction;
- startup and workspace reload replace the complete index;
- save and deletion refresh replace the index with a new snapshot that reuses already-deserialized static entries.

No additional executor, mutable graph, database, or Maven project model is introduced.

---

## 3. Current Baseline

The existing index already provides most of the required lifecycle:

- Maven caches one type-index shard per dependency and one aggregate JDK shard.
- `WorkspaceSession` scans mirrored reactor class directories at startup.
- Successful save compilation refreshes the affected reactor entries.
- `WorkspaceTypeIndex.withReactorEntries()` reuses dependency/JDK entries and creates a new immutable lookup snapshot.
- Workspace reload rereads static shards and creates a complete replacement snapshot.
- Completion and code-action requests capture one index snapshot before crossing to module workers.

Measured on Helidon before adding inheritance metadata:

```text
[type-index] loaded index: 32835 simple names from 203/203 shard(s)
             + 332 reactor shard(s) 105ms

[type-index] refreshed reactor index: 32835 simple names from 37425 static type(s)
             + 332 reactor shard(s) 23ms
```

These measurements do not justify asynchronous index construction for M1.
Construction remains on `ServerEventLoop` and keeps its timing logs.
If inheritance metadata materially changes the measurements, background construction can be designed from evidence.

---

## 4. Scope

### 4.1 Included

- Named class inheritance.
- Interface implementation and interface extension.
- Direct and transitive subtype discovery.
- Generic inheritance matched by erased binary type identity.
- Reactor implementations of JDK and dependency types.
- Source-backed dependency and JDK type implementations.
- Reactor method implementations validated by javac.
- Cross-reactor implementation navigation.
- Internal class-file nodes needed to preserve graph connectivity.
- Immutable snapshot replacement on startup, reload, save, and deletion refresh.

Examples:

```text
java.util.List
├── java.util.ArrayList
├── java.util.LinkedList
└── com.example.CustomList
```

```text
com.example.Service.run()
├── com.example.DefaultService.run()
└── com.example.TestService.run()
```

### 4.2 Deferred

- Method implementations declared in dependency or JDK classes.
- Lambda expressions as functional-interface implementations.
- Anonymous and local class locations as user-facing implementation results.
- Unsaved source inheritance overlays.
- A binary index format.
- Background index construction.
- Persisted reactor type-index shards.

Reactor hierarchy reflects the last successful editor or Maven compilation captured under `.lathe/`.
Dependency and JDK hierarchy reflects the last successful `lathe:sync` for those cached artifacts.

---

## 5. Index Schema

Extend the existing value type rather than introducing a parallel inheritance-record hierarchy:

```java
record TypeIndexEntry(
    String simpleName,
    String binaryName,
    String packageName,
    TypeKind kind,
    boolean discoverable,
    List<String> directSupertypes) {}
```

### 5.1 Field semantics

`binaryName` is the exact JVM class identity derived from the class-file path, for example:

```text
java.util.ArrayList
com.example.Outer$Inner
```

`simpleName` remains the user-facing name used by completion and symbol presentation.

`discoverable` means the entry is eligible for type-name completion and workspace-symbol results.
The initial policy remains public top-level classes only.

`directSupertypes` contains erased binary names from the class file's `super_class` and `interfaces[]` entries.
It does not contain generic arguments or a precomputed transitive closure.

For example:

```json
{
  "simpleName": "ArrayList",
  "binaryName": "java.util.ArrayList",
  "packageName": "java.util",
  "kind": "CLASS",
  "discoverable": true,
  "directSupertypes": [
    "java.util.AbstractList",
    "java.util.List",
    "java.util.RandomAccess",
    "java.lang.Cloneable",
    "java.io.Serializable"
  ]
}
```

Changing `TypeIndexEntry` increments the shared schema version.
The existing cache validation then rebuilds dependency and JDK shards on the next `lathe:sync`.

### 5.2 Internal graph nodes

The current scanner excludes nested and non-public classes entirely.
The inheritance scanner must retain them with `discoverable=false`.

Otherwise an internal intermediate type breaks traversal:

```text
PublicInterface
    ↑
PackagePrivateBase
    ↑
PublicImplementation
```

Internal nodes participate in graph traversal without appearing in completion or workspace-symbol results.
Anonymous and local class files may also appear as non-discoverable graph nodes, but M1 does not return them as
user-facing locations.

All lists stored in index entries and all index lookup collections are immutable.

---

## 6. Duplicate Type Names

M1 supports named modules, automatic modules, and unnamed/classpath projects.
It does not model JPMS package ownership or artifact precedence inside the index.

Split packages do not require special handling when their classes have different binary names.
Two indexed classes with the same package and class name produce the same binary name and are unsupported for hierarchy
and implementation queries.

During index construction, collect duplicate binary names in an immutable set.
When a hierarchy traversal reaches an ambiguous name:

1. Log one warning for that binary name.
2. Omit that ambiguous node and its outgoing branch from the result.
3. Continue processing unambiguous branches where possible.

Example warning:

```text
[type-index] duplicate type com.example.Service; hierarchy navigation skipped
```

Warnings are deduplicated per workspace snapshot so repeated editor requests do not flood the log.
Completion keeps its existing javac candidate-validation behavior.

This is a narrow duplicate-class restriction, not a general JPMS or classpath restriction.

---

## 7. Class-File Scanning

`ClassFileTypeScanner` already opens every dependency, JDK, and reactor class file used by type indexing.
Extend its minimal reader to retain the constant-pool values required to decode:

- `this_class`;
- `super_class`;
- `interfaces[]`;
- access flags.

The scanner does not parse:

- fields;
- methods;
- bytecode;
- annotations;
- generic signatures.

Class-file inheritance is already erased and resolved, which avoids recreating import and source-name resolution.

The scanner's public entry points remain shared:

- `scanJar()` for dependencies;
- `scanDirectory()` for JDK modules and reactor outputs.

The Maven plugin serializes dependency/JDK results exactly as it does today.
The server keeps reactor results in memory exactly as it does today.

---

## 8. Immutable In-Memory Index

Extend `WorkspaceTypeIndex` with immutable relationship lookups:

```java
final class WorkspaceTypeIndex {
  private final List<TypeIndexEntry> staticEntries;
  private final NavigableMap<String, List<TypeIndexEntry>> bySimpleNameLower;
  private final Map<String, List<TypeIndexEntry>> byBinaryName;
  private final Map<String, List<TypeIndexEntry>> directSubtypes;
  private final Set<String> duplicateBinaryNames;
}
```

`bySimpleNameLower` preserves current prefix completion.
Search filters out entries where `discoverable=false`.

`byBinaryName` resolves unique graph nodes and identifies unsupported duplicates.

`directSubtypes` is built by reversing every direct-supertype edge:

```text
child.directSupertypes = [parent]
directSubtypes[parent] += child
```

### 8.1 Full load

Startup and workspace reload:

1. Deserialize all dependency/JDK shards.
2. Scan all reactor output directories.
3. Build all immutable maps.
4. Replace `WorkspaceSession.typeIndex` on `ServerEventLoop`.

### 8.2 Reactor refresh

Successful save or source deletion:

1. Rescan the affected reactor output directory.
2. Replace its entry list in `WorkspaceSession.reactorShards`.
3. Call `typeIndex.withReactorEntries(...)`.
4. Rebuild immutable lookup maps while reusing static entries.
5. Replace the active snapshot on `ServerEventLoop`.

No dependency or JDK JSON is reread during reactor refresh.
Previous snapshots remain unchanged for requests that already captured them.

---

## 9. Source Locations

Navigation results require a source-backed location.
Use the existing source roots and extracted source directories:

- reactor type → owning module source roots;
- dependency type → extracted dependency source directory;
- JDK type → extracted JDK module source directory.

For a top-level binary name, derive the conventional source path from package and simple name.
For a nested binary name, derive the outermost top-level source filename before the first `$`.

Use existing source parsing/location helpers to find the declaration identifier and range.
If source is unavailable or a precise M1-supported named declaration cannot be located, retain the node for graph
traversal but omit it from user-facing results.

---

## 10. Type Hierarchy Operations

### 10.1 `textDocument/prepareTypeHierarchy`

Use the cursor file's attributed analysis to resolve a `TypeElement`.
Create one `TypeHierarchyItem` with an opaque payload:

```java
record TypeHierarchyItemData(
    String binaryName,
    String routingUri) {}
```

`routingUri` identifies source/compiler routing for javac-backed fallback and method implementation.

Return an empty list when the cursor does not resolve to a type or when the binary name is ambiguous.

### 10.2 `typeHierarchy/supertypes`

Read the selected entry's `directSupertypes`.
Resolve each unambiguous, source-backed parent to a `TypeHierarchyItem`.

This is a forward-edge lookup and does not perform a workspace source scan.

### 10.3 `typeHierarchy/subtypes`

Read `directSubtypes` for the selected binary name.
Return only immediate children, as required by the LSP operation.

Do not return the transitive closure from `subtypes`.
Editors request later levels as users expand the hierarchy.

---

## 11. Type Implementation

For a type cursor:

1. Resolve the target `TypeElement` and binary name with javac.
2. Reject an ambiguous target name with the deduplicated warning.
3. Traverse `directSubtypes` transitively with an explicit queue and visited set.
4. Traverse through non-discoverable internal nodes.
5. Return precise locations for M1-supported source-backed named types.
6. Exclude the target declaration itself.

Traversal is imperative because it is stateful graph processing.

No source attribution is needed to prove the inheritance relationship: the edge came from successfully produced class
files.

---

## 12. Method Implementation

Method implementation remains reactor-only and javac-validated:

1. Resolve the target `ExecutableElement` and declaring type.
2. Traverse `directSubtypes` to collect transitive reactor subtype candidates.
3. Group candidates by reactor source file.
4. Attribute only those source files on their owning module workers.
5. Inspect methods declared directly in each candidate class.
6. Validate matches with `elements.overrides(candidate, target, enclosingType)`.
7. Return precise method-name locations.

The graph supplies a small semantic candidate set.
Javac remains authoritative for overloads, erasure, generic substitution, visibility, and module rules.

Abstract overriding declarations remain graph intermediates but are not returned as concrete method implementations.
Methods inherited without a new declaration are not emitted at each subclass.

No method names or descriptors are added to the type index in M1.

---

## 13. Threading and Snapshot Ownership

```text
LSP4J thread
  → LatheTextDocumentService captures immutable request data
  → ServerEventLoop captures WorkspaceTypeIndex snapshot
  → graph lookup runs against immutable collections
  → reactor method candidates fan out to module workers for javac validation
  → results return to ServerEventLoop
  → LSP response completes
```

Only `ServerEventLoop` reads or replaces `WorkspaceSession.typeIndex`.
The field is neither `volatile` nor atomic.

Module workers continue to own javac-backed `SourceAnalysisSession` state.
The index stores records and collections only; it does not retain javac elements, trees, tasks, or file managers.

Index construction remains synchronous on `ServerEventLoop` for M1 because measured load and refresh times are small.
The existing logs provide evidence if that assumption changes.

---

## 14. Logging and Failure Handling

Keep the existing timed index lifecycle logs:

```text
[type-index] loaded index: ... Xms
[type-index] refreshed reactor index: ... Xms
```

Add one deduplicated warning for unsupported duplicate binary names encountered by hierarchy navigation.

Expected absence returns an empty list:

- no type at cursor;
- no superclass;
- no subtype;
- no implementation;
- unavailable source location.

Unexpected scanner, index, compiler, source-reading, or payload failures propagate to the upstream LSP operation
boundary.
They must not be converted into empty successful results.

---

## 15. Capability Advertisement

Advertise capabilities only after their complete endpoint groups pass integration tests:

```java
capabilities.setImplementationProvider(true);
capabilities.setTypeHierarchyProvider(true);
```

Do not advertise type hierarchy when only prepare or one traversal direction is implemented.
Do not advertise implementation when only type or method targets work.

---

## 16. Alternatives Considered

### 16.1 Reuse Find References candidate scanning

The previous design found files containing the target's simple name, attributed every candidate, and filtered with
`Types.isSubtype()` or `Elements.overrides()`.

This scales with lexical name frequency rather than actual subtype count.
Rejected because class files already contain exact direct inheritance edges.

### 16.2 Persist reactor index shards from Maven

`lathe:sync` could write one reactor index file per module and add fingerprints to `workspace.json`.

This duplicates the current server-side reactor scan, loses immediate successful-save freshness unless an overlay is
added, and requires manifest/reload changes.
Rejected for M1.

### 16.3 Track artifact origin and visibility

Each indexed node could carry artifact/module origin and queries could recreate classpath/module-path visibility.

This adds identity, routing, and Maven-resolution policy to a candidate index.
Rejected for M1.
Duplicate package and binary names are explicitly unsupported instead.

### 16.4 Separate completion and inheritance records

`TypeIndexFile` could contain independent completion and inheritance lists.

That duplicates type identity and scanner output.
Rejected in favor of one entry with an explicit `discoverable` policy.

### 16.5 Mutable or incrementally patched graph

A mutable graph could update individual edges after saves.

This complicates ownership and exposes partial-update risks.
Rejected because rebuilding an immutable reactor snapshot currently takes approximately 23 ms on Helidon.

### 16.6 Background index construction

Loading could happen on a dedicated executor with readiness and generation state.

Rejected for M1 because current measured type-index load is approximately 105 ms and refresh is approximately 23 ms.
Reconsider only from new measurements after inheritance metadata lands.

### 16.7 Pre-index external methods

Method names and descriptors could enable dependency/JDK method implementation results.

Deferred because it expands the schema and override policy substantially.
The direct-inheritance graph supplies most navigation value while reactor methods remain exactly validated by javac.

---

## 17. Implementation Slices

### Slice 1 — Class-file inheritance metadata

- Extend the minimal constant-pool reader.
- Add binary name, discoverability, and direct supertypes to `TypeIndexEntry`.
- Retain non-discoverable graph nodes.
- Increment the schema version.
- Update JAR, JDK, directory, JSON, and multi-release tests.

### Slice 2 — Immutable graph lookups

- Add binary-name and reverse-subtype maps to `WorkspaceTypeIndex`.
- Detect duplicate binary names.
- Preserve current completion and workspace-symbol behavior.
- Preserve static-entry reuse during reactor refresh.
- Add direct, transitive, internal-node, duplicate, and snapshot tests.

### Slice 3 — Type hierarchy

- Add hierarchy item payload serialization.
- Implement prepare, direct supertypes, and direct subtypes.
- Resolve reactor, dependency, and JDK source locations.
- Add service and end-to-end integration tests.

### Slice 4 — Type implementation

- Add transitive reverse-edge traversal.
- Traverse internal nodes while filtering unsupported results.
- Add reactor, dependency, JDK, cross-module, and duplicate-name coverage.

### Slice 5 — Reactor method implementation

- Select candidate files through subtype traversal.
- Validate declared overrides with javac.
- Cover overloads, generic overrides, abstract intermediates, and cross-module implementations.

### Slice 6 — Qualification

- Advertise both capability groups.
- Verify with the explorer on representative reactor, dependency, and JDK targets.
- Record startup, refresh, direct hierarchy, transitive type implementation, and method implementation timings on
  Helidon.
- Run all server and Maven plugin verification layers.

Each slice runs `mvn spotless:apply` and its relevant tests before the next slice begins.

---

## 18. Test Strategy

### Class-file scanner

- superclass and multiple interfaces are decoded;
- interface extension is decoded;
- erased generic inheritance has the expected binary supertype;
- public top-level classes remain discoverable;
- package-private and nested classes remain graph nodes but are not discoverable;
- multi-release JAR behavior remains unchanged;
- malformed class files follow the existing scanner failure policy.

### Index graph

- direct subtype lookup excludes grandchildren;
- transitive traversal includes descendants exactly once;
- internal intermediate nodes preserve public descendants;
- duplicate binary names are marked ambiguous and skipped;
- warning deduplication is scoped to the snapshot;
- reactor refresh replaces reactor edges without rereading static shards;
- previous snapshots remain unchanged.

### Type hierarchy

- explicit superclass and implemented interfaces are returned;
- interface supertypes are returned;
- `Object` has no supertype;
- `List` includes `ArrayList` as a direct subtype;
- direct subtype requests omit transitive descendants;
- unavailable-source nodes are traversed but not returned.

### Type implementation

- reactor interface returns direct and transitive reactor implementations;
- JDK interface returns source-backed JDK and reactor implementations;
- dependency interface returns source-backed dependency and reactor implementations;
- the target declaration is excluded;
- ambiguous duplicate targets or branches are skipped with one warning.

### Method implementation

- concrete reactor overrides are returned;
- overloaded methods are distinguished by javac;
- generic overrides are validated correctly;
- abstract intermediate declarations are not reported as concrete implementations;
- dependency and JDK method declarations are not returned as implementations;
- broad interfaces attribute only graph-selected reactor source candidates.

### Lifecycle and performance

- startup and reload replace the complete immutable snapshot;
- save and deletion replace only reactor entries while reusing static entries;
- captured old snapshots remain usable during later requests;
- index lifecycle logs include elapsed time;
- Helidon measurements remain within an explicitly reviewed budget after inheritance metadata is added.
