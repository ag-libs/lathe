# Lathe — Goto Implementation & Type Hierarchy Design

Working design draft.
Builds on `lathe-design.md` and the existing type-index implementation.

---

## 1. Goal

Implement four related LSP operations:

- `textDocument/implementation` for types and methods;
- `textDocument/prepareTypeHierarchy`;
- `typeHierarchy/supertypes`;
- `typeHierarchy/subtypes`.

Type implementation and hierarchy navigation cover reactor, dependency, and JDK types when their class files are
indexed and navigable source is available.
Method implementation is intentionally limited to implementations declared in reactor source.

The design must remain responsive on large workspaces.
It must not discover type relationships by attributing every source file that happens to contain a target's simple
name.
It must also remove the current whole-index rebuild from the server event loop.

---

## 2. Design Summary

Extend the existing class-file type-index shards with minimal direct-inheritance metadata.
Each indexed type records its binary name, kind, visibility, and direct superclass/interfaces.

At server startup, eagerly load all JDK, dependency, and reactor shards into immutable collections.
Construct two immutable index components:

- a static index for JDK and dependency entries;
- a reactor index for project entries.

The active `WorkspaceTypeIndex` is an immutable snapshot containing both components.
Index construction happens outside `ServerEventLoop`; the completed snapshot is installed with one atomic reference
replacement.
Workspace reload replaces the complete snapshot.
After a successful reactor save, the static component is reused unchanged and a newly built immutable reactor
component replaces the previous reactor component.

Type hierarchy and type implementation are graph lookups.
Method implementation uses the graph to find reactor subtype source files, then asks javac to validate exact overrides.

This design keeps javac authoritative for Java semantics without using javac as a workspace search engine.

---

## 3. Scope

### 3.1 Included

- Direct and transitive named subtype discovery.
- Class inheritance.
- Interface implementation and interface extension.
- Generic inheritance matched by erased binary type identity.
- Reactor implementations of JDK and dependency types.
- Dependency and JDK type implementations when indexed source is available.
- Method implementations declared in reactor source.
- Immutable index snapshots eagerly loaded at startup.
- Immutable snapshot replacement on workspace reload and reactor refresh.
- Existing completion and workspace-symbol lookup from the same type-index files.

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

### 3.2 Deferred

- Method implementations declared in JDK or dependency classes.
- Lambda expressions as implementations of functional-interface methods.
- Anonymous-class implementation locations.
- A live unsaved-source inheritance overlay.
- Hot incremental mutation of index maps.
- A binary index format.

The initial reactor index reflects the last successful compilation.
An open file whose inheritance clause has changed but has not compiled successfully may temporarily use stale graph
edges.

---

## 4. Index Schema

Keep completion metadata and inheritance metadata conceptually separate.
Completion should continue to expose only useful visible type candidates, while inheritance traversal must retain
internal nodes that connect public types.

One possible schema is:

```java
record TypeIndexFile(
    String schema,
    TypeIndexOrigin origin,
    List<TypeIndexEntry> types,
    List<InheritanceEntry> inheritance) {}

record InheritanceEntry(
    String binaryName,
    TypeKind kind,
    TypeVisibility visibility,
    List<String> directSupertypes) {}
```

`directSupertypes` contains erased binary names from the class file's `super_class` and `interfaces[]` entries.
It does not contain generic arguments or a transitive closure.

For example:

```json
{
  "binaryName": "java.util.ArrayList",
  "kind": "CLASS",
  "visibility": "PUBLIC",
  "directSupertypes": [
    "java.util.AbstractList",
    "java.util.List",
    "java.util.RandomAccess",
    "java.lang.Cloneable",
    "java.io.Serializable"
  ]
}
```

Binary names preserve nested-class identity, for example `com.example.Outer$Inner`.
Display and source lookup may convert them to canonical names where required by javac or extracted source layout.

Changing the schema invalidates existing cached JDK and dependency shards.
`lathe:sync` rebuilds them using the existing origin fingerprints.

### 4.1 Internal and nested types

`ClassFileTypeScanner` currently excludes nested and non-public top-level classes from completion indexing.
Inheritance scanning must retain them as graph nodes.

Otherwise an internal intermediate type breaks valid traversal:

```text
PublicInterface
    ↑
PackagePrivateBase
    ↑
PublicImplementation
```

Visibility controls completion and result presentation, not graph participation.
An internal node may be traversed without being returned as a user-facing navigation result.

### 4.2 Type identity and origins

Binary name alone is not globally unique when multiple modules or dependency versions provide the same class.
In memory, associate every entry with its shard origin:

```java
record TypeId(String originId, String binaryName) {}
```

Inheritance edges read from class files initially carry binary names.
They are resolved against the requesting module's visible reactor modules, module path, and classpath.
If more than one origin remains plausible, retain all candidates and let javac validation reject inaccessible or
incorrect reactor method candidates.

This prevents the index from inventing one global classpath that does not exist in Maven.

---

## 5. Class-File Scanning

`ClassFileTypeScanner` already opens every JDK, dependency, and reactor class file used to build type-index entries.
Extend its existing minimal class-file reader to retain the constant-pool values needed to read:

- `this_class`;
- `super_class`;
- `interfaces[]`;
- access flags.

The scanner does not need to parse fields, methods, bytecode, annotations, or generic signatures.
The additional sync-time I/O is therefore negligible because the class-file streams are already opened.

The scanner emits completion entries using the existing visibility policy and inheritance entries for all useful graph
nodes.
Malformed or unsupported class files are reported consistently with the current scanner policy and do not fail the
entire artifact unless the existing index build treats that failure as fatal.

---

## 6. Immutable In-Memory Index

The index collections are immutable after construction.
Nested collection values must also be immutable.

```java
final class WorkspaceTypeIndex {
  private final StaticTypeIndex staticIndex;
  private final ReactorTypeIndex reactorIndex;
}
```

Both components provide:

```java
Map<String, List<IndexedType>> bySimpleNameLower();
Map<TypeId, IndexedType> byId();
Map<String, List<IndexedType>> directSubtypesByBinaryName();
```

`StaticTypeIndex` contains all eagerly loaded JDK and dependency shards.
It is built once during initialization and reused after reactor saves.

`ReactorTypeIndex` contains project outputs grouped by module configuration.
After a successful save, Lathe rescans the affected module and builds a new complete immutable reactor component from
the already available per-module entry lists.
It does not reread or regroup static JDK and dependency shards.

The implementation may initially rebuild all reactor maps in memory after replacing one module's entry list.
That work runs on the indexing executor and is expected to be small compared with static shard loading.
Persistent collections or edge-level mutation are unnecessary until measurements show otherwise.

Queries combine the two immutable components:

```java
Stream.concat(
    staticIndex.directSubtypes(binaryName).stream(),
    reactorIndex.directSubtypes(binaryName).stream())
```

No query mutates or lazily fills an index collection.

---

## 7. Loading, Reload, and Refresh

### 7.1 Startup

Load all configured shards eagerly on a dedicated single-threaded indexing executor:

```text
read all static and reactor shards
    ↓
construct immutable maps
    ↓
publish one WorkspaceTypeIndex snapshot
    ↓
report index ready
```

`ServerEventLoop` must not read shard files, scan class directories, parse JSON, or group index entries.
Non-index-dependent operations may continue while loading.
Index-dependent requests wait on the initialization future rather than observing a partially built collection.

The existing empty index remains a valid bootstrap value for workspace configurations with no shards.

### 7.2 Workspace reload

Workspace reload builds a complete replacement snapshot on the indexing executor.
The current snapshot remains available until construction succeeds.
The event loop installs the completed snapshot with one reference assignment.

If reload fails, Lathe retains the previous valid snapshot and surfaces the reload failure.
It must not publish a partially rebuilt index.

### 7.3 Successful reactor save

After a successful full save compilation:

1. Scan only the affected module's `.lathe` class output.
2. Replace that module's immutable entry list in the reactor-shard input map.
3. Build a new immutable `ReactorTypeIndex` on the indexing executor.
4. Combine it with the unchanged `StaticTypeIndex`.
5. Install the resulting `WorkspaceTypeIndex` snapshot on `ServerEventLoop`.

Rapid refresh requests may be coalesced.
Every build captures a generation; an older result must not replace a newer snapshot.

The snapshot field may be `volatile` because the value and all reachable collections are immutable.
Each request captures the current snapshot once and uses it for the request's complete lifetime.

---

## 8. Type Hierarchy Operations

### 8.1 `textDocument/prepareTypeHierarchy`

Use the cursor file's existing attributed analysis to resolve a `TypeElement`.
Create a `TypeHierarchyItem` containing a serialized identity payload:

```java
record TypeHierarchyItemData(
    String originId,
    String binaryName,
    String routingUri) {}
```

The payload is opaque to clients.
`routingUri` supports javac-backed fallback and source routing.

### 8.2 `typeHierarchy/supertypes`

Read the selected entry's `directSupertypes` and resolve each visible type through the captured index snapshot.
This is a forward-edge lookup and does not attribute source files.

### 8.3 `typeHierarchy/subtypes`

Read `directSubtypesByBinaryName` from the static and reactor components.
Return only immediate visible children, as required by the LSP operation.

Do not return the transitive closure from `subtypes`.
Editors request subsequent levels as users expand the hierarchy.

### 8.4 Source locations

Resolve locations using existing source metadata:

- reactor type → module source roots;
- JDK type → extracted JDK source directory;
- dependency type → extracted dependency source directory.

A graph node without available source still participates in traversal but is omitted from user-facing navigation
results unless Lathe later adds class-file or decompiled navigation.

---

## 9. `textDocument/implementation`

### 9.1 Type target

Resolve the cursor target with javac, then traverse reverse inheritance edges transitively from its binary name.
Traversal uses an explicit queue and visited set.
It must not use recursive calls or stream state for graph traversal.

Return visible, source-backed matching types.
The target declaration itself is excluded.

The traversal crosses internal graph nodes so public implementations remain discoverable through non-public
intermediate types.

### 9.2 Method target

Method implementation remains javac-validated and reactor-only:

1. Resolve the target `ExecutableElement` and its declaring type.
2. Traverse the inheritance graph to collect transitive reactor subtypes of the declaring type.
3. Group candidates by reactor source file.
4. Attribute only those source files on their owning module workers.
5. Inspect methods declared in each candidate class.
6. Validate matches with `elements.overrides(candidate, target, enclosingType)`.
7. Return precise method-name locations.

The graph provides the candidate set; javac remains authoritative for overloads, erasure, generic substitution,
visibility, and JPMS rules.

Abstract overriding declarations may participate in continued inheritance traversal but are not returned as concrete
implementations unless the LSP behavior is explicitly changed later.

No method names or descriptors are added to the index in this design.

---

## 10. Threading and Ownership

```text
LSP4J thread
  → LatheTextDocumentService captures immutable request data
  → ServerEventLoop captures WorkspaceTypeIndex snapshot
  → graph lookup runs against immutable collections
  → reactor method candidates fan out to module workers for javac validation
  → results return to ServerEventLoop
  → LSP response completes
```

Index construction uses a separate single-threaded indexing executor.
It never runs on `ServerEventLoop` or a module compilation worker.

Module workers continue to own javac-backed `SourceAnalysisSession` state.
The index contains metadata only and does not retain javac elements, trees, tasks, or file managers.

Closing the workspace shuts down and reaps the indexing executor after pending work is cancelled or completed
according to the server shutdown policy.

---

## 11. Performance Characteristics

Expected query complexity:

| Operation | Work |
|---|---|
| Prepare hierarchy | Existing cursor attribution plus one index lookup |
| Direct supertypes | O(number of direct supertypes) |
| Direct subtypes | O(number of direct subtypes) |
| Type implementation | O(reachable subtype nodes and edges) |
| Method implementation | Graph traversal plus attribution of reactor subtype files |

The design removes lexical workspace candidate scans from type queries.
It also prevents static shard disk reads and regrouping after each save.

Memory grows by one inheritance record per indexed class and one reverse-edge reference per direct superclass or
interface edge.
This is expected to be substantially smaller than caching attributed javac analyses for broad lexical candidate sets.

Initial acceptance measurements should include:

- startup index time and peak heap on Helidon;
- `List` direct-subtype and transitive-implementation latency;
- reactor interface method implementation latency;
- post-save reactor index refresh duration;
- event-loop responsiveness while startup and refresh builds run.

No fixed latency budget should be claimed until these measurements exist.

---

## 12. Failure Handling

- Startup index failure completes initialization exceptionally with the affected shard path or origin.
- Workspace reload failure retains the prior valid immutable snapshot.
- Reactor refresh failure retains the prior valid reactor snapshot and reports the module and output directory.
- Invalid hierarchy payload data fails the LSP request; it is not converted to an empty result.
- Legitimate absence, such as a type with no subtypes, returns an empty list.
- Failures are logged once at the upstream operation boundary according to the server fail-fast policy.

---

## 13. Alternatives Considered

### 13.1 Reuse Find References candidate scanning

The original design used `ReferenceCandidateIndex` to find files containing the target's simple name, attributed every
candidate, and filtered matches with `Types.isSubtype()` or `Elements.overrides()`.

This minimizes new index metadata but scales with lexical candidate count rather than actual subtype count.
Common names can attribute hundreds of unrelated files, retain expensive compiler analyses, and create unpredictable
latency.

Rejected as the primary design.
The reference scanner remains useful for reference search, but it is not an appropriate type-relationship index.

### 13.2 Reactor-only inheritance index

Indexing only reactor classes is smaller and covers most application-defined implementations.
It cannot answer `List → ArrayList` or dependency-to-dependency hierarchy questions even though Lathe already scans the
relevant class files for completion.

Rejected because adding direct-supertype extraction to existing JDK and dependency scans is a contained extension and
provides substantially better type hierarchy coverage.
Method implementation remains reactor-only to preserve the useful scope boundary.

### 13.3 One mutable global graph

A mutable graph could update individual edges after every save.
It would require synchronization or event-loop confinement and would expose readers to partial updates unless mutation
were carefully transactional.

Rejected in favor of immutable snapshots.
Whole reactor-component replacement is simpler to reason about, test, and publish safely.

### 13.4 One rebuilt global immutable index

Rebuilding one global index from JDK, dependency, and reactor entries after every save preserves immutability but repeats
static grouping work unnecessarily.

Rejected in favor of separate immutable static and reactor components.
The static component is shared across reactor snapshot replacements.

### 13.5 Independently queried per-shard maps

Each shard could retain its own reverse map and every query could probe all visible shards.
This makes replacement cheap and aligns with artifact boundaries.
It also complicates transitive traversal, duplicate handling, and query filtering because every graph step becomes a
multi-shard operation.

Not selected because eagerly merged immutable static/reactor components provide a simpler query model.
The file format remains sharded, so this alternative can be revisited if merge cost becomes material.

### 13.6 Lazy dependency loading

Dependency inheritance shards could load on first use to reduce startup work.
That produces unpredictable first-query latency and additional loading states.

Rejected in favor of eager startup loading and a single clear readiness boundary.
The serialized format can be optimized later if eager JSON loading becomes expensive.

### 13.7 Source-header inheritance index

Lathe could parse `extends` and `implements` clauses from source without attribution.
This would provide immediate unsaved updates but would need to approximate imports, nested names, wildcard imports, and
Java resolution rules.

Rejected as the authoritative index because class files already contain resolved direct relationships.
A source or attributed-analysis overlay remains a possible follow-up for unsaved-file freshness.

### 13.8 Persistent database

SQLite or another persistent graph store would support indexed reverse queries and fine-grained updates.
It would also add database lifecycle, locking, migration, corruption recovery, and another dependency.

Rejected as unnecessary for the expected graph size.

### 13.9 Pre-index external methods

Indexing method names, erased descriptors, modifiers, and locations would enable dependency/JDK method implementation
queries without source attribution.
It substantially expands the schema and still requires careful Java override semantics.

Deferred.
The minimal inheritance graph supplies most navigation value while reactor method matches remain exactly validated by
javac.

---

## 14. Implementation Slices

### Slice 1 — Class-file inheritance metadata

- Extend the minimal class-file reader with class-name, superclass, and interface decoding.
- Add inheritance records to the type-index schema.
- Retain internal and nested graph nodes.
- Update JDK, dependency, and directory scanner tests.
- Invalidate and rebuild old cached shards through the schema version.

### Slice 2 — Immutable index components

- Introduce immutable static and reactor index components.
- Add forward-supertype and reverse-subtype lookup.
- Keep existing completion search behavior.
- Add graph traversal and duplicate/cycle tests.

### Slice 3 — Background loading and replacement

- Move initial shard reading and map construction off `ServerEventLoop`.
- Install complete snapshots atomically.
- Retain the previous snapshot on reload failure.
- Replace only the immutable reactor component after successful saves.
- Add generation checks and shutdown handling.

### Slice 4 — Type hierarchy

- Implement prepare, supertypes, and direct subtypes.
- Add source-location resolution for reactor, dependency, and JDK types.
- Advertise `typeHierarchyProvider` only after all endpoints pass integration tests.

### Slice 5 — Type implementation

- Implement transitive reverse-edge traversal.
- Filter internal and source-unavailable results while traversing through internal nodes.
- Add JDK `List → ArrayList`, dependency, and cross-reactor integration coverage.

### Slice 6 — Reactor method implementation

- Use subtype traversal to select reactor source candidates.
- Validate declared overrides with javac.
- Add overloaded, generic, abstract-intermediate, and cross-module tests.
- Advertise `implementationProvider` after type and method behavior is complete.

Each slice runs `mvn spotless:apply` and the relevant tests before the next slice begins.

---

## 15. Test Strategy

### Class-file scanner

- superclass and multiple interfaces are decoded;
- interface extension is decoded;
- nested and package-private classes participate in inheritance records;
- completion visibility remains unchanged;
- multi-release JAR selection remains correct;
- malformed class files follow the existing scanner failure policy.

### Index graph

- direct subtypes exclude transitive descendants;
- transitive traversal includes all descendants exactly once;
- cycles or malformed duplicate edges do not loop;
- internal intermediate nodes preserve public descendants;
- static and reactor results combine without mutating either component;
- replacing a reactor component does not rebuild or modify the static component.

### Type hierarchy

- class superclass and implemented interfaces are returned;
- interface supertypes are returned;
- `Object` has no supertype;
- `List` includes `ArrayList` as an indexed subtype;
- direct subtype requests omit grandchildren;
- unavailable-source nodes are traversed but not returned.

### Type implementation

- reactor interface returns direct and transitive reactor implementations;
- JDK interface returns source-backed JDK and reactor implementations;
- dependency interface returns dependency and reactor implementations;
- target declaration is excluded;
- duplicate binary names are filtered using request visibility and validation.

### Method implementation

- concrete reactor overrides are returned;
- overloaded methods are distinguished by javac;
- generic overrides are validated correctly;
- abstract intermediate declarations are not reported as concrete implementations;
- dependency and JDK method implementations remain outside the result scope;
- broad interfaces attribute only reactor subtype source candidates.

### Concurrency and lifecycle

- requests do not observe partially built indexes;
- event-loop work proceeds during startup and refresh construction;
- stale refresh generations cannot replace newer snapshots;
- failed reload retains the prior snapshot;
- shutdown closes the indexing executor cleanly.
