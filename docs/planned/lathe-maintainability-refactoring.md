# Lathe — Maintainability Refactoring

Working design draft.
This document records the findings from the June 2026 systematic codebase review.

It supersedes overlapping recommendations in
[lathe-code-quality-refactoring.md](lathe-code-quality-refactoring.md) where the current code has
already changed.
The older document remains as design history.

---

## 1. Goal

Make the server easier to read, maintain, and extend by reducing concrete duplication,
clarifying responsibility boundaries,
and applying consistent naming and error-handling policy.

The work should be delivered as small, independently reviewable slices.
Large classes should be split only where a cohesive responsibility can be named and tested.

---

## 2. Non-Goals

This is not a broad rewrite or a class-size reduction exercise.

This design does not propose:

- changing the server threading model;
- replacing explicit LSP dispatch with a generic request framework;
- introducing general-purpose utility classes for unrelated helpers;
- recreating a URI abstraction around standard `file://` URI conversion;
- merging test fixtures that model different compiler lifecycles;
- renaming classes or fields independently of nearby functional work.

`WorkspaceSession` remains the event-loop-owned coordinator.
`SourceAnalysisSession` remains the owner and facade for javac-backed analysis state.

---

## 3. Error-Handling Policy

Lathe should fail fast when an internal invariant is violated.
Code must not convert unexpected exceptions into empty results, `null`, or `Optional.empty()` merely
to keep a request running.

Exceptions should propagate to the nearest upstream operation boundary that has enough context to
identify the failed LSP operation, URI, module, or workspace action.
That boundary logs the exception once and completes the request exceptionally or publishes the
appropriate failure response.

The policy is:

1. **Internal invariant violations fail immediately.**
   Missing required fields, impossible enum values, inconsistent compiler state, and broken ownership
   assumptions are programming or protocol errors.
   They should throw a specific exception with a useful message.
2. **Lower-level code adds context only when it materially improves diagnosis.**
   It may wrap a checked exception with the affected path, symbol, or operation, preserving the cause.
   It should not log and rethrow.
3. **Upstream operation boundaries log once.**
   LSP request dispatch, notification handling, workspace lifecycle operations, and asynchronous worker
   completion handlers are the normal logging boundaries.
   Logging uses the existing JUL operation-tag format and includes the `Throwable`.
4. **Expected absence remains a value, not an exception.**
   A symbol that legitimately has no definition, no supertypes, or no candidates may return an empty
   result.
   Invalid encoded hierarchy data is not expected absence and must not be silently treated as one.
5. **External input is validated explicitly.**
   Malformed client data should produce a clear protocol/input exception that reaches the request
   boundary.
   The boundary logs it close to the upstream request and completes that request exceptionally.
6. **Do not log the same failure at multiple layers.**
   A lower layer either handles a failure completely or propagates it.
   It does not log defensively before propagation.

This policy applies to the refactorings below and should be preserved when responsibility moves
between classes.

---

## 4. Correctness Fixes Before Refactoring

These changes correct current behavior and require regression tests.
They should be separate from structural refactoring commits.

### 4.1 Refresh semantic tokens only for the current document generation

`DiagnosticPublisher.refreshTokensIfCurrent()` currently refreshes tokens when
`DocumentRegistry.isStale()` is true.
The condition conflicts with the method name and the stale-result policy used for diagnostics.

Change the condition so refresh occurs only for the current generation.

Add focused tests for:

- a current snapshot refreshing semantic tokens;
- a stale snapshot not refreshing semantic tokens.

### 4.2 Return direct subtypes from `typeHierarchy/subtypes`

`WorkspaceSession.subtypeItemFutures()` currently passes `directOnly=false` into subtype search.
The LSP hierarchy operation should return direct children, while transitive subtype search remains
available for implementation navigation.

Pass `directOnly=true` for hierarchy subtype requests.

Add a configured service-level test with `Base -> Middle -> Leaf` and verify that requesting the
subtypes of `Base` returns `Middle` but not `Leaf`.

---

## 5. Refactoring Slices

The slices are ordered by expected maintenance value and dependency.
Each slice should pass formatting and the relevant module tests before the next begins.

### Slice 1: Consolidate workspace search input planning

`WorkspaceSession` independently constructs nearly identical open-file and disk-file search inputs
for references, implementations, and subtypes.

Extract one package-private planner that returns immutable search inputs, for example:

```java
record WorkspaceSearchInputs(
    List<OpenDocument> openDocuments,
    List<DiskSource> diskSources) {}
```

The planner owns:

- selecting open documents belonging to the module search scope;
- collecting their URIs;
- asking `ReferenceCandidatePlanner` for disk candidates;
- excluding files already represented by open documents;
- reading immutable disk-source inputs before worker submission.

Feature-specific code remains explicit.
References, implementations, and subtypes map the shared inputs to their own worker operations and
result types.

Rename helpers while touching this code:

- `searchFutures()` to `referenceFutures()`;
- `typeSearchFutures()` to `implementationFutures()`;
- `toImplLocations()` to `toImplementationLocations()`.

Do not create a generic workspace-search execution framework.
The repeated input planning is the shared concept; result aggregation and search semantics are not.

### Slice 2: Extract type hierarchy resolution

Move the cohesive hierarchy behavior currently in `SourceAnalysisSession` into a package-private
`TypeHierarchyResolver`.

It owns:

- preparing a hierarchy item for the symbol at a source position;
- resolving direct supertypes;
- mapping subtype matches to hierarchy items;
- locating declarations used for hierarchy navigation;
- constructing `TypeHierarchyItem` values and encoded data.

`SourceAnalysisSession` remains the compiler-state facade.
It obtains or validates attributed analysis and delegates hierarchy construction to the resolver.
The resolver must not own mutable compiler lifecycle state or introduce another worker boundary.

As part of this slice, extract one internal implementation-match operation shared by implementation
location search and subtype-item search.
Do not duplicate attribution, locator invocation, and `IOException` propagation in both paths.

Failures from compiler or source lookup propagate through `SourceAnalysisSession` to the module
worker completion and then to the upstream LSP operation boundary for logging.

### Slice 3: Centralize method identity matching

`ReferenceTarget` and `ImplementationLocator` independently construct erased method descriptors.
`ImplementationLocator` also recreates target-matching logic represented by
`ReferenceTarget.matches()`.

Make `ReferenceTarget` the single owner of method identity policy.
`ImplementationLocator` should delegate matching to it rather than constructing and comparing its
own descriptor.

Add or retain tests for overloaded methods, generic erasure, constructors, and methods inherited
through interfaces.

### Slice 4: Centralize LSP symbol-kind mapping

`WorkspaceSymbolResolver`, `DocumentSymbolScanner`, and type hierarchy construction map Java type
kinds independently.
Records are currently represented inconsistently as `Class` or `Struct` depending on the feature.

Create a focused package-private `SymbolKinds` mapper with entry points for the Java compiler and
Lathe type-index representations.
Choose one record mapping and use it across workspace symbols, document symbols, and type hierarchy.

This mapper contains only symbol-kind policy.
It must not become a general LSP conversion utility.

### Slice 5: Extract javac diagnostic mapping

Move diagnostic enrichment and javac-to-LSP conversion from `SourceAnalysisSession` into a focused
`JavacDiagnosticMapper`.

It owns:

- converting javac ranges and severities to LSP diagnostics;
- classifying supported diagnostic kinds;
- extracting structured payload data such as missing methods and unreported exceptions;
- attaching code-action context.

Start with one mapper rather than separate classifier, enricher, and converter abstractions.
Split it later only if independent reuse appears.

Unknown diagnostic kinds remain valid diagnostics without structured code-action payloads.
Malformed compiler data or impossible source positions fail fast and propagate to the compile
operation boundary; they are not silently dropped.

### Slice 6: Extract annotation completion

Move annotation argument and annotation value completion from `CompletionEngine` into
`AnnotationCompletionProvider`.

The provider owns annotation-specific candidate discovery and construction.
`CompletionEngine` retains top-level request classification, shared analysis resolution, merging,
and presentation coordination.

Pass a resolved analysis context into the provider rather than giving it access to all engine
internals.
Do not introduce a generic provider registry as part of this slice.

### Slice 7: Unify method-path scanning in `TypeResolver`

`findScopeMethodPath()` and `findMethodPath()` contain overlapping AST scanners for class and method
selection.

Implement one scanner with an explicit selection policy:

- exact enclosing method selection; or
- cursor-aware nearest scope selection.

Remove no-op visitor overrides that do not alter traversal.
Complete this focused consolidation before considering a larger expected-value resolver extraction.

---

## 6. Defensive Data Handling Without Silent Recovery

`TypeHierarchyItemCodec` decodes data returned by the client without validating required fields or
enum values.
The caller currently checks for absent decoded data, but malformed data can instead produce an
incidental `NullPointerException` or `IllegalArgumentException`.

Rename codec operations to `encode()` and `decode()` and validate every required field explicitly.
On malformed data, throw a dedicated `IllegalArgumentException` with the missing or invalid field in
the message.
Do not return `null` or `Optional.empty()` for malformed encoded data.

The exception should propagate to the `typeHierarchy/supertypes` or `typeHierarchy/subtypes` request
boundary, where it is logged once with the operation tag and item URI before the request completes
exceptionally.

Tests should cover:

- a valid round trip;
- absent item data;
- each required field missing;
- an invalid target-kind value;
- propagation through the LSP request boundary.

The same policy should be applied to other client-round-tripped payload codecs when they are touched.

---

## 7. Naming Cleanup During Related Work

Rename only when the containing code is already being changed:

| Current | Proposed | Reason |
|---|---|---|
| `WorkspaceSession.workspace` | `moduleRegistry` | The field is a `WorkspaceModuleRegistry`, not the whole workspace. |
| `WorkspaceSession.docs` | `documents` | Avoid an abbreviation in the central coordinator. |
| `WorkspaceSession.worker` | `eventLoop` | Makes thread ownership explicit. |
| `ExternalCompiler` | `ExternalSourceCompiler` | It compiles external source; it is not an external compiler process. |

Do not create standalone rename-only work unless a name is actively causing incorrect use.

---

## 8. Test Maintainability

### 8.1 Type hierarchy fixture

Introduce a small `TypeHierarchyFixture` for repeated source compilation, session construction,
cursor lookup, and hierarchy source definitions.

Keep focused tests for preparation, subtypes, and supertypes.
Keep the navigation test focused on the complete prepare-to-navigation chain rather than repeating
all focused positive cases.

### 8.2 Deterministic asynchronous tests

Replace fixed `Thread.sleep()` synchronization in `LatheTextDocumentServiceTest` and
`ServerEventLoopTest` with `CountDownLatch`, `CompletableFuture`, or bounded asynchronous verification.

Tests may retain a bounded timeout as failure protection.
Elapsed wall-clock time must not be the primary signal that an asynchronous operation completed.

Do not expose a production `awaitIdle()` API solely for tests unless no existing observable completion
signal can represent the behavior.

### 8.3 Compiler fixture resource ownership

Make compiler test-resource ownership explicit:

- `TestCompiler.compileToDir()` closes its `StandardJavaFileManager` with try-with-resources;
- `ParsedSource` implements `AutoCloseable` and closes its parser and file manager;
- callers use try-with-resources or a JUnit lifecycle fixture.

Resource close failures should propagate to the test.
They should not be suppressed or logged as cleanup warnings.

---

## 9. Deferred and Conditional Work

`SourceAnalysisSession` currently dispatches code actions through a readable switch despite having a
`CodeActionProvider` interface.
A `CodeActionDispatcher` backed by an immutable provider map becomes worthwhile when another provider
or diagnostic kind is added.
Do not introduce it solely to remove the current switch.

The expected-value portion of `TypeResolver` may eventually warrant its own resolver.
Its helpers are currently shared with receiver and static-member resolution, so extracting it now
would create awkward callback dependencies.

---

## 10. Implementation Order

1. Correct semantic-token refresh and direct-subtype behavior, with regression tests.
2. Consolidate workspace search input planning.
3. Extract type hierarchy resolution and harden hierarchy data decoding.
4. Centralize method identity and symbol-kind policy.
5. Extract diagnostic mapping.
6. Extract annotation completion and unify method-path scanning.
7. Improve asynchronous tests, hierarchy fixtures, and compiler resource ownership.
8. Apply opportunistic naming changes within the related slices.

Behavior fixes should not be hidden inside structural refactoring commits.
Each slice should be independently reviewable and preserve the established worker ownership model.

---

## 11. Verification

For every slice:

```bash
mvn spotless:apply
mvn test -pl lathe-server
```

Run the complete repository verification when a slice changes shared core code, Maven integration,
or public LSP behavior:

```bash
mvn verify
```

Verification must include focused negative tests for stale state, malformed payloads, and asynchronous
failure propagation where applicable.
