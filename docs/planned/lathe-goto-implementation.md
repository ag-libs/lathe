# Lathe — Goto Implementation & Type Hierarchy Design

Working design draft.
This document describes `textDocument/implementation`, `textDocument/prepareTypeHierarchy`,
`typeHierarchy/supertypes`, and `typeHierarchy/subtypes` as a unified feature group.

---

## 1. Goal

Four editor questions, one coherent feature:

> "Where is this interface/abstract method actually implemented?" (`textDocument/implementation`)
> "What are the direct supertypes of this type?" (`typeHierarchy/supertypes`)
> "What types directly extend or implement this type?" (`typeHierarchy/subtypes`)
> "What type is at this cursor — give me a type hierarchy item so I can explore up and down." (`textDocument/prepareTypeHierarchy`)

All four endpoints share one scanner, one candidate-lookup strategy, and one identity record.
The implementation adds very little new infrastructure; the heavy lifting is already in `ReferenceCandidateIndex`, `ReferenceCandidatePlanner`, the module-graph fan-out, and `DefinitionLocator`.

---

## 2. LSP Overview

| Endpoint | Request | Response | File scan? |
|---|---|---|---|
| `textDocument/implementation` | URI + position | `List<Location>` | Yes — all-subtypes OR method overrides |
| `textDocument/prepareTypeHierarchy` | URI + position | `TypeHierarchyItem[]` | No — position resolution only |
| `typeHierarchy/supertypes` | `TypeHierarchyItem` | `TypeHierarchyItem[]` | No — element introspection only |
| `typeHierarchy/subtypes` | `TypeHierarchyItem` | `TypeHierarchyItem[]` | Yes — direct subtypes only |

`textDocument/implementation` covers two sub-cases:
- **Type cursor** — returns locations of all (direct and transitive) subtypes of the target type.
- **Method cursor** — returns locations of all methods that `elements.overrides(m, target, enclosingType(m))`.

`typeHierarchy/subtypes` covers only the type case (direct subtypes, one level at a time).

---

## 3. Shared Building Blocks

### 3.1 `ImplementationLocator` — one scanner for three endpoints

`ImplementationLocator` is a new `TreePathScanner<Void, Void>`. It runs against an already-attributed `AttributedFileAnalysis` and returns `List<ImplementationMatch>`:

```java
record ImplementationMatch(TypeElement element, Location location) {}
```

`element` carries the simple name and `ElementKind` needed to build a `TypeHierarchyItem` in `subtypes`. `location` is all `implementation` needs. A single `boolean directOnly` flag controls depth:

**Type target** (kind is CLASS, INTERFACE, ENUM, or RECORD):

```java
// directOnly = false  (textDocument/implementation, typeHierarchy/subtypes with all-levels)
types.isSubtype(classEl.asType(), targetEl.asType())
    && !types.isSameType(classEl.asType(), targetEl.asType())

// directOnly = true  (typeHierarchy/subtypes)
types.directSupertypes(classEl.asType()).stream()
    .anyMatch(s -> types.isSameType(types.erasure(s), types.erasure(targetEl.asType())))
```

Erasure is applied in the direct-only check so that `class Foo implements Comparable<Foo>` matches a query on `Comparable`.

**Method target** (kind is METHOD):

Reconstruct the target `ExecutableElement` once per file by calling `elements.getTypeElement(target.qualifiedName().replace('$', '.'))` and scanning its members for the entry matching `target.simpleName()` and `target.erasedDescriptor()`. Then for each method `m` declared in every `ClassTree` in the file:

```java
m.getSimpleName().toString().equals(target.simpleName())
    && elements.overrides(m, targetMethod, enclosingClassEl)
    && !types.isSameType(m.getEnclosingElement().asType(), targetMethod.getEnclosingElement().asType())
```

The last condition excludes a re-encounter of the target declaration itself. `getAllMembers` is not used for the override scan — only `ClassTree`-local methods are emitted so each override appears once at the declaring class rather than at every inheriting subclass.

Location reporting uses `SourceLocator.findIdentifierFrom()` (same approach as `ReferenceLocator.addDeclarationMatch`) to get the precise class-name or method-name identifier span.

### 3.2 `ReferenceCandidatePlanner` — unchanged

`planCandidates(config, target)` already returns the right superset for both cases:

- **Type target**: files containing the type's simple name appear in the candidate set because every file that `extends`/`implements` the type must reference its name.
- **Method target**: files containing both the method's simple name and the enclosing type's simple name appear because every override must name the method and extend/implement the owning type.

False positives (files that use the type/method without subtyping/overriding) are filtered by the AST scan.

### 3.3 `TypeHierarchyItemData` — round-trip payload

A small serialisable record stored in `TypeHierarchyItem.data`:

```java
record TypeHierarchyItemData(
    String qualifiedName,          // canonical name (dots, no $) for elements.getTypeElement()
    String simpleName,
    ElementKind kind,              // CLASS, INTERFACE, ENUM, RECORD
    ReferenceTarget.SearchScope scope, // REACTOR_MODULES / DECLARING_MODULE / DECLARING_FILE
    String routingUri              // source URI for supertypes worker routing
) {}
```

`routingUri` is the URI of the type's own source file (from `DefinitionLocator.findSourceFile()`) or, if unavailable, the cursor file's URI. Every module worker can call `elements.getTypeElement()` for types reachable on its classpath, so routing to the declaring file's worker covers all cases including JDK types (which route to the external worker via `manifest.containsFile()`).

`TypeHierarchyItemData` is serialised to/from `TypeHierarchyItem.data` as JSON using the existing `Json` utility — the same codec pattern as `DiagnosticPayload`.

---

## 4. `textDocument/implementation`

### Phase 1 — element resolution (cursor worker)

Reuse the existing `CompilationWorker.resolveTarget(SourceFeatureRequest)` → `ReferenceTarget`. Return empty immediately if `target` is null or its kind is not a type or METHOD.

### Phase 2 — fan-out (shared with `typeHierarchy/subtypes`)

For **type targets**, delegate to the shared `typeSearchFutures(target, directOnly=false)` helper in `WorkspaceSession`.

For **method targets**, fan out to candidate files using `planCandidates(config, target)` and call `CompilationWorker.searchImplementations(uri, content, version, target)` on each. Combine results the same way as `referencesFuture`.

Result: `Either.forLeft(List<Location>)` — extract `.location()` from each `ImplementationMatch`.

---

## 5. `textDocument/prepareTypeHierarchy`

Cursor position → one `TypeHierarchyItem` (or empty array).

### In `SourceAnalysisSession.prepareTypeHierarchy(SourceFeatureRequest)`

1. Resolve cursor: `SourceLocator.elementAt(trees, path)`.
2. Accept `TypeElement` only (not methods, fields, locals).
3. Determine file location: try `trees.getPath(element)` first (same-file); fall back to `DefinitionLocator.findSourceFile(element, sourceRoots)`.
4. Build `TypeHierarchyItem`:
   - `name` = `element.getSimpleName()`
   - `kind` = map `ElementKind` → `SymbolKind` (INTERFACE → Interface, ENUM → Enum, else → Class)
   - `detail` = package name (`((PackageElement) topLevelClass.getEnclosingElement()).getQualifiedName()`)
   - `selectionRange` = name identifier span from `DefinitionLocator.parsePosition()` (a zero-length range at the name start, same as definition)
   - `range` = full class declaration span from `trees.getSourcePositions().getStartPosition(cu, classTree)` and `getEndPosition(cu, classTree)`, converted via `SourceLocator.offsetToPosition()`
   - `uri` = source file URI
   - `data` = serialized `TypeHierarchyItemData` (qualifiedName, simpleName, kind, scope, routingUri)

Return `Optional<TypeHierarchyItem>`. `WorkspaceSession.prepareTypeHierarchyFuture` wraps it as `List.of(item)` or `List.of()`.

---

## 6. `typeHierarchy/supertypes`

No file scan. Pure element introspection.

### In `SourceAnalysisSession.supertypes(TypeHierarchyItemData data, List<Path> sourceRoots, WorkspaceManifest manifest)`

```java
TypeElement el = elements.getTypeElement(data.qualifiedName());
if (el == null) return List.of();

List<TypeMirror> directSupers = new ArrayList<>();
if (el.getSuperclass().getKind() != TypeKind.NONE) {
  directSupers.add(el.getSuperclass());
}
directSupers.addAll(el.getInterfaces());

return directSupers.stream()
    .map(t -> (TypeElement) types.asElement(t))
    .filter(Objects::nonNull)
    .flatMap(superEl -> buildItem(superEl, sourceRoots, manifest).stream())
    .toList();
```

`buildItem` calls `DefinitionLocator.findSourceFile(superEl, sourceRoots)` and constructs a `TypeHierarchyItem` with a new `TypeHierarchyItemData` payload (routing URI = the supertype's own source path, or the caller's routing URI if the source is not found).

Route the call via `data.routingUri()` — same `routeFeature` path as definition and hover.

`java.lang.Object` is included (editors typically show it as the root of the hierarchy). If editors omit it by convention, a flag can suppress it later.

---

## 7. `typeHierarchy/subtypes`

Reuses `typeSearchFutures(target, directOnly=true)` — the same fan-out helper as `implementation` for type targets.

Deserialize `TypeHierarchyItemData` from the request item. Build a synthetic `ReferenceTarget`:

```java
new ReferenceTarget(data.kind(), data.qualifiedName(), data.simpleName(), null, data.scope())
```

Then call `typeSearchFutures(target, directOnly=true)`. The resulting `List<ImplementationMatch>` is converted to `List<TypeHierarchyItem>` in `WorkspaceSession`: each `ImplementationMatch.element()` provides the simple name and `ElementKind` directly — no filename parsing needed. Build a `TypeHierarchyItem` per match using the element's name and kind, the `location()` for `uri`/`selectionRange`, and a new `TypeHierarchyItemData` payload. The full `range` is already in the `Location` (set by `ImplementationLocator` from the class tree's start/end positions).

---

## 8. Shared Fan-Out Helper in `WorkspaceSession`

Both `implementation` (type case) and `subtypes` execute the same fan-out pattern. Extract one private method:

```java
private CompletableFuture<List<Location>> typeSearchFutures(
    ReferenceTarget target, boolean directOnly) { ... }
```

This mirrors `searchFutures(config, target, includeDeclaration, packageRel)` from `referencesFuture` but calls `CompilationWorker.searchImplementations(uri, content, version, target, directOnly)` instead of `searchReferences`.

`implementationFuture` calls `typeSearchFutures(target, directOnly=false)` for type targets, and a local method-override fan-out for METHOD targets.

`subtypesFuture` calls `typeSearchFutures(target, directOnly=true)`.

---

## 9. New Components — Complete List

**New classes:**

| Class | Size estimate | Notes |
|---|---|---|
| `ImplementationLocator` | ~120 lines | One scanner for type subtypes (direct and transitive) + method overrides; returns `List<ImplementationMatch>` |
| `ImplementationMatch` | ~5 lines | `record(TypeElement element, Location location)` — carries name/kind for `TypeHierarchyItem` construction |
| `TypeHierarchyItemData` | ~10 lines | Round-trip payload record serialized into `TypeHierarchyItem.data` |

**New methods on existing classes:**

| Class | New methods |
|---|---|
| `SourceAnalysisSession` | `searchImplementations(uri, content, version, target, directOnly) → List<ImplementationMatch>`, `prepareTypeHierarchy(request) → Optional<TypeHierarchyItem>`, `supertypes(data, sourceRoots, manifest) → List<TypeHierarchyItem>` |
| `CompilationWorker` | Matching `CompletableFuture<T>` delegation methods for the three above |
| `WorkspaceSession` | `implementationFuture(uri, pos)`, `typeSearchFutures(target, directOnly)` (private), `prepareTypeHierarchyFuture(uri, pos)`, `supertypesFuture(item)`, `subtypesFuture(item)` |
| `LatheTextDocumentService` | `implementation(params)`, `prepareTypeHierarchy(params)`, `typeHierarchySupertypes(params)`, `typeHierarchySubtypes(params)` |

**Capability changes in `LatheLanguageServer.createCapabilities()`:**

```java
capabilities.setImplementationProvider(true);
capabilities.setTypeHierarchyProvider(true);
```

That is the entire delta. No new modules, no new infrastructure, no new index structures.

---

## 10. Threading

All four endpoints follow the same threading model as references:

```
LSP4J thread
  -> LatheTextDocumentService (captures immutable params)
  -> worker.submit(() -> session.XxxFuture(...))
       [lathe-worker thread — reads candidateIndex, routes to workers]
       -> Phase 1: cursorWorker.resolveTarget() or deserialize TypeHierarchyItemData
       -> Phase 2: fan-out to N CompilationWorker threads
            -> ImplementationLocator (or element introspection for supertypes)
       -> combine futures
  -> thenApply (LSP shape conversion on calling thread)
```

`typeHierarchy/supertypes` skips Phase 2 fan-out — it routes directly to a single worker via `routingUri` and calls `ctx.supertypes(...)` synchronously on that worker thread.

---

## 11. Test Strategy

### `ImplementationLocatorTest`

Covers the scanner in isolation using `WorkbenchFixture`:

```
typeSubtypes_directOnly_findsDirectImplementor
typeSubtypes_directOnly_excludesTransitiveSubtype
typeSubtypes_allLevels_findsTransitiveSubtype
typeSubtypes_excludesTargetItself
typeSubtypes_parameterizedInterface_matchesViaErasure  (e.g. Comparable<Foo>)
methodOverride_findsConcreteOverride
methodOverride_excludesTargetDeclaration
methodOverride_concreteBaseMethod_findsSubclassOverride
methodOverride_abstractMethod_notReturnedAsImplementation
```

### `PrepareTypeHierarchyTest`

```
prepare_onInterface_returnsItemWithCorrectName
prepare_onClass_returnsItemWithPackageDetail
prepare_onConcreteClass_returnsItem
prepare_onNonTypeElement_returnsEmpty
```

### `SupertypesTest`

```
supertypes_classWithExplicitSuperclass_returnsSuperclass
supertypes_interfaceImplementation_returnsInterface
supertypes_multipleInterfaces_returnsAll
supertypes_javaLangObject_returnsEmpty (no supertype of Object)
```

### Integration scenario — `TypeHierarchyIT`

Multi-file workbench:

```
Drawable.java      interface Drawable { void draw(); }
Shape.java         abstract class Shape implements Drawable {}
Circle.java        class Circle extends Shape { @Override void draw() {} }
Square.java        class Square extends Shape { @Override void draw() {} }
```

| Cursor | Endpoint | Expected |
|---|---|---|
| `Drawable` in `Drawable.java` | `implementation` | `Shape`, `Circle`, `Square` |
| `draw()` in `Drawable.java` | `implementation` | `Circle.draw`, `Square.draw` |
| `Drawable` in `Drawable.java` | `prepareTypeHierarchy` | item with name=`Drawable`, kind=Interface |
| item for `Shape` | `supertypes` | item for `Drawable` |
| item for `Drawable` | `subtypes` | item for `Shape` only (directOnly) |
| item for `Shape` | `subtypes` | items for `Circle` and `Square` |

---

## 12. Deferred

- **JDK / dependency subtypes**: implementations in JDK classes (e.g., `ArrayList` as a subtype of `List`) are not returned because `ReferenceCandidateIndex` only covers reactor source files. Same known gap as Find References. Fix strategy: restrict JDK/dep queries to open files, or add a type-hierarchy shard to the type index.
- **Anonymous class implementations**: `types.isSubtype` returns true for anonymous classes, but their class-name span is empty. v1 omits them. A follow-up can report the `NewClassTree` location as the result range.
- **Lambda implementations of functional interfaces**: similarly not reported in v1.
- **`textDocument/implementation` on concrete methods in final classes**: returns no results (correct). On overridable concrete methods: returns subclass overrides (correct via `elements.overrides`).
