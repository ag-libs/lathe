# Lathe — M1 Refactoring

Single authoritative refactoring plan for the M1 Internal Preview milestone.
Verified against the current tree on 2026-06-27.

---

## 1. Goal and scope

Make the server easier to read, maintain, and extend by:

- removing concrete, verified duplication;
- decomposing the four classes whose size genuinely impedes comprehension;
- applying consistent naming and a single fail-fast error-handling policy;
- consolidating test fixtures and eliminating resource leaks.

The architecture is **sound and is not being restructured**.
The module split (`core → compiler → server`, plus `maven-plugin`), the single-event-loop threading model,
and the "no long-lived javac symbol state" decision are all confirmed coherent and are preserved.

Work is delivered as small, independently reviewable slices.
A class is split only where a cohesive responsibility can be named and tested.

## 2. Non-goals

This is not a rewrite or a line-count exercise. This design does **not**:

- change the server threading model or the `ServerEventLoop` / `CompilationWorker` ownership boundaries;
- replace explicit LSP dispatch with a generic request framework;
- introduce interfaces for single-implementation classes (forbidden by the coding guide);
- introduce a custom URI abstraction around standard `file://` conversion;
- merge test fixtures that intentionally model different compiler lifecycles;
- touch the M2-deferred `@Disabled` semantic-highlighting tests in `SemanticTokensTest` (those are
  correct placeholders for planned M2 work, not defects).

## 3. Process gate (read before implementing)

Per the project coding guide, any change that touches multiple classes, changes a public API, or
introduces a new abstraction **requires a structured design summary and an explicit "Approved" from the
maintainer before code is written**.

- **Section 8 (core nits) and Section 9 (test suite)** are low-risk and self-contained; an agent may
  implement them slice-by-slice without the approval gate, showing the diff before commit.
- **Sections 4–7 (extractions, god-class splits, renames)** introduce new classes / move
  responsibilities / change signatures. Each slice MUST be presented as a design summary and approved
  before implementation.

Never run `git commit` or `git push` autonomously; show the diff and wait for authorization.
Run `mvn spotless:apply` immediately after editing any `.java` file.

---

## 4. Error-handling policy (preserved invariant)

Lathe fails fast when an internal invariant is violated.
Code must not convert unexpected exceptions into empty results, `null`, or `Optional.empty()` merely to
keep a request running.

1. Internal invariant violations throw immediately with a specific, useful message.
2. Lower-level code adds context (affected path/symbol/operation) only when it improves diagnosis; it
   wraps and preserves the cause, it does not log-and-rethrow.
3. Upstream operation boundaries (LSP dispatch, notification handling, workspace lifecycle, async worker
   completion) log once, using the JUL operation-tag format, including the `Throwable`.
4. Expected absence (no definition, no supertypes, no candidates) remains an empty value, not an
   exception. Malformed encoded data is **not** expected absence.
5. External/client input is validated explicitly and fails at the request boundary.
6. A failure is logged at exactly one layer.

When a responsibility moves between classes in the slices below, this policy moves with it.

---

## 5. DRY / structural extractions

Each slice is independently reviewable, must keep the worker-ownership model, and must pass
`mvn spotless:apply` + `mvn test -pl lathe-server` before the next begins.

### Slice 5.1 — Unify URI→Path conversion into `LatheUri.toPath(String)`

**Evidence:** `Path.of(URI.create(uri))` is duplicated across six sites — two wrapped in a private
`toPath()` helper (`WorkspaceSession`, `ReferenceCandidatePlanner`) and four inline
(`LatheLanguageServer`, `WorkspaceManifest`, `ModuleSourceCompiler`, `ExternalCompiler`).
Any future URI-encoding change must currently be applied in six places.

Action: add a `LatheUri.toPath(String uri)` static in the server module; replace all six sites; remove
the two private `toPath()` wrappers.
Purely mechanical, zero behavioral change, independent of every other slice — do it first.
(`SourceParser`'s `URI.create(uri)` builds a `SimpleJavaFileObject` URI, not a Path — leave it alone.)

Tests: add a focused `LatheUriTest`; existing tests cover the call sites.

### Slice 5.2 — `SourceTreeLocator` base for the `*Locator` classes

**Evidence:** `ReferenceLocator` has an 8-argument private constructor;
`CallHierarchyIncomingLocator` and `CallHierarchyOutgoingLocator` each have a 6-argument private
constructor.
All three repeat `this.positions = trees.getSourcePositions()` and each static factory repeats
`analysis.tree().getSourceFile().getCharContent(<bool>).toString()` before instantiating and calling
`scan()`.
`ReferenceLocator` uses `getCharContent(true)`; both call-hierarchy locators use `(false)` — reconcile
this discrepancy deliberately during the extraction.

Action: introduce a package-private base (`SourceTreeLocator`) holding the shared fields (`trees`, `cu`,
`content`, `positions`, `target`, `types`, `elements`, and `uri` where present) and a shared
`sourceContent(AttributedFileAnalysis)` helper.
The three locators extend it; subclasses keep only their distinct `scan` logic and result construction.

Tests: existing `ReferenceLocatorTest`, `CallHierarchyIncomingLocatorTest`,
`CallHierarchyOutgoingLocatorTest` must pass unchanged.
Add a case pinning the `getCharContent` decision so the reconciliation does not silently regress.

### Slice 5.3 — Consolidate workspace search-input planning

**Evidence:** `WorkspaceSession.searchFutures()` and `WorkspaceSession.incomingCallFutures()`
independently build near-identical open-document + disk-candidate inputs (select open docs in module
scope, collect their URIs, ask `ReferenceCandidatePlanner` for disk candidates, exclude already-open URIs,
filter by package scope, read disk sources).

Action: extract one package-private planner returning immutable inputs:

```java
record WorkspaceSearchInputs(List<OpenDocument> openDocuments, List<DiskSource> diskSources) {}
```

References and incoming calls map the shared inputs to their own worker operations and result types.
Do **not** build a generic search-execution framework — only the input planning is shared; result
aggregation and search semantics are not.

Tests: add a planner unit test (open-doc selection, URI exclusion, package-scope filtering); existing
reference and call-hierarchy service tests pass unchanged.

### Slice 5.4 — Extract type-hierarchy resolution from `SourceAnalysisSession`

Move hierarchy behaviour (`prepareTypeHierarchy`, `typeHierarchySupertypes`, `typeHierarchySubtypes`,
`typeHierarchyItem` — lines ~467–650 in `SourceAnalysisSession`) into a package-private
`TypeHierarchyResolver`.
`SourceAnalysisSession` remains the compiler-state facade and delegates.
The resolver owns no mutable compiler lifecycle state and introduces no new worker boundary.

As part of this slice, extract the one implementation-match operation shared by implementation-location
search and subtype-item search instead of duplicating attribution + locator invocation + `IOException`
propagation.

Also verify that `TypeHierarchyItemDataCodec.decode` validates every required field and throws
`IllegalArgumentException` (naming the bad field) on malformed data; add the missing-field /
invalid-enum tests if absent.

### Slice 5.5 — Extract javac diagnostic mapping from `SourceAnalysisSession`

Move diagnostic enrichment and javac→LSP conversion (range/severity conversion, `resolveKind()`
classification, structured payload extraction for missing methods and unreported exceptions, code-action
context attachment) into a focused `JavacDiagnosticMapper`.
Start with one mapper; split later only if independent reuse appears.
Unknown diagnostic kinds remain valid diagnostics without structured payloads; malformed compiler data
fails fast to the compile boundary.

### Slice 5.6 — Extract annotation completion from `CompletionEngine`

Move annotation-argument and annotation-value completion into an `AnnotationCompletionProvider`.
`CompletionEngine` retains request classification, shared analysis resolution, merging, and presentation.
Pass a resolved analysis context into the provider; do not give it access to all engine internals and do
not introduce a generic provider registry in this slice.

### Slice 5.7 — Unify method-path scanning in `TypeResolver`

`TypeResolver.findScopeMethodPath()` and `TypeResolver.findMethodPath()` contain overlapping AST
scanners; `SimpleNameProvider` independently duplicates `findScopeMethodPath`.
Implement one scanner with an explicit selection policy (exact-enclosing vs cursor-nearest), remove no-op
visitor overrides, and have all callers use it.
Keep this focused; do not attempt the larger expected-value resolver extraction here (see 6.4).

### Slice 5.8 — Centralize LSP symbol-kind mapping

`WorkspaceSymbolResolver`, `DocumentSymbolScanner`, and type-hierarchy construction map Java kinds
independently.
Records are mapped inconsistently: `WorkspaceSymbolResolver` uses `SymbolKind.Class`;
`DocumentSymbolScanner` uses `SymbolKind.Struct`.
Create a package-private `SymbolKinds` mapper (entry points for the javac model and the type-index
representation), pick one record mapping, and use it everywhere.
Symbol-kind policy only — not a general LSP conversion utility.

---

## 6. God-class decomposition (largest value, full approval gate)

Four classes dominate their packages.
Sizes verified 2026-06-27:

| Class | LOC | Responsibility clusters to separate |
|---|---|---|
| `analysis/completion/CompletionEngine` | 1694 | type-reference completion; annotation completion (Slice 5.6); member-access completion; semicolon/edit synthesis; type-reference role filtering |
| `WorkspaceSession` | 1365 | document lifecycle; compile scheduling; reference/call-hierarchy orchestration (Slice 5.3); type-hierarchy/impl routing; 17× feature-routing boilerplate; mutable index/state holder; class-output cleanup |
| `analysis/completion/TypeResolver` | 1110 | expected-value resolution; scope/element finding (Slice 5.7); lambda/switch context; position-based receiver resolution |
| `analysis/SourceAnalysisSession` | 952 | feature dispatcher facade; hierarchy (Slice 5.4); diagnostic mapping (Slice 5.5); code-action dispatch; hover/definition/symbols |

Slices 5.3–5.7 already chip the cohesive sub-responsibilities out of these classes; do those first.
After they land, evaluate whether further extraction is warranted with a fresh measurement.
Candidate follow-on extractions, **each requiring its own design summary + approval**:

### 6.1 `WorkspaceSession` feature-routing helper

The 17 `*Future()` methods (hover, signatureHelp, definition ×3, documentSymbol, foldingRange,
completion, codeAction, semanticTokens, prepareCallHierarchy, incomingCalls, outgoingCalls,
prepareTypeHierarchy, typeHierarchySupertypes, typeHierarchySubtypes) repeat the identical
"get open doc → null-check fallback → route to worker → `exceptionally`" shape.
Collapse into one parameterized helper.
This is a contained, high-clarity win and can be proposed independently.

### 6.2 `WorkspaceSession` immutable state holder

The cluster of mutable fields reassigned across `initialize()` / `reload()` / `refreshReactorShard()`
(`manifest`, `workspace`, `moduleGraph`, `candidateIndex`, `typeIndex`, `reactorShards`) could move
behind a single immutable snapshot swapped atomically on the worker thread.
Propose only after 6.1.

### 6.3 `CompletionEngine` completer extraction

After Slice 5.6 (annotation completion — the highest-value, fully instance-field-independent
extraction), evaluate these further cohesive boundaries, each as its own approved slice and each ordered
after a fresh re-measurement:

- **Type-index completion merging** — query `WorkspaceTypeIndex`, javac-validate candidates, apply
  package-accessibility / import-fit checks, merge with javac simple-name candidates, preserve ranking
  and result limits.
- **Member-access candidate policy** — instance/static receiver candidates, generic substitution via
  `types.asMemberOf()`, filtering of inaccessible/synthetic/duplicate items, member sorting.
- **Statement-edit post-processing** — primary edit range and additional import edits, semicolon edits
  only when syntactically safe, no cursor assumptions that conflict with additional edits.
- **`TypeReferenceCompleter`** for the ~350-line type-reference group.

The completion providers must stay concrete (no shared `CompletionProvider` interface — forbidden for
single-impl).
Extract named policy objects, never a `CompletionUtil` bag.

Opportunistic nit while editing nearby completion code: `CompletionEngine.memberAccessSemanticContext`
and its callers construct `SemanticCompletionContext` directly in two places instead of via the
`SemanticCompletionContext.from()` factory used elsewhere.
Normalize to the factory where the context is built fresh.

### 6.4 `TypeResolver` expected-value resolver (deferred / conditional)

The expected-value portion may eventually warrant its own resolver, but its helpers are currently shared
with receiver and static-member resolution; extracting now creates awkward callback dependencies.
Defer until Slice 5.7 lands and the coupling is re-measured.

### 6.5 Move `deleteClassOutputs` out of `WorkspaceSession` (opportunistic)

`WorkspaceSession.deleteClassOutputs(ModuleSourceConfig, Path)` is a `static` package-visible method
with no coupling to session state — its visibility is an artifact of test access.
Move it to a filesystem utility (extend `FileUtil` or a focused server-side helper) when next editing
nearby file-cleanup code.
Do not create a standalone commit solely for this.

### 6.6 Code-action dispatcher (deferred / conditional)

`SourceAnalysisSession` dispatches code actions through a readable switch despite a `CodeActionProvider`
interface with five implementations (`AddThrowsProvider`, `DeclareVariableProvider`,
`TryCatchWrapProvider`, `ImportQuickFixProvider`, plus the planned missing-method provider).
A `CodeActionDispatcher` backed by an immutable provider map becomes worthwhile when the next provider
lands — do it then, not solely to remove the switch.

---

## 7. Naming cleanup (apply within the related slice only)

Rename only while the containing code is already being changed by a slice above; do not create
standalone rename-only commits unless a name is actively causing incorrect use.

| Current | Proposed | Reason | Apply during |
|---|---|---|---|
| `WorkspaceSession.workspace` | `moduleRegistry` | field is a `WorkspaceModuleRegistry`, not the whole workspace | 6.2 |
| `WorkspaceSession.docs` | `documents` | avoid an abbreviation in the central coordinator | 6.1/6.2 |
| `WorkspaceSession.worker` | `eventLoop` | makes thread ownership explicit | 6.1/6.2 |
| `WorkspaceSession.searchFutures()` | `referenceFutures()` | matches what it produces | 5.3 |
| `ExternalCompiler` | `ExternalSourceCompiler` | it compiles external *source*, not an external process | opportunistic |

---

## 8. Core and Maven-plugin rule nits (low risk)

### 8.1 `ModuleConfigData` validation

`lathe-core/.../schema/ModuleConfigData` uses `Objects.requireNonNullElse(...)` in its compact
constructor for `encoding` and `compilerArgs`.
The records rule requires `ValidCheck`-based invariants.
Convert to `ValidCheck` (and defensively copy `compilerArgs` to an immutable list).
Add a positive + a null/edge case.

### 8.2 Hardcoded literals into `LatheLayout`

`ServerInstaller` hardcodes `"lathe-server"` as artifact id and `"jar"` as classifier.
Move these into `LatheLayout` / `PluginProps` constants per the no-hardcoded-names rule.
Verify each candidate is genuinely shared before centralizing property keys that are used in one place.

### 8.3 Low-priority observations (record, do not necessarily act)

- `core/typeindex/TypeIndexOrigin` is a nullable-union record guarded by `ValidCheck`; a sealed
  hierarchy would express the dependency/jdk/reactor variants more safely.
  Defer unless the type is being changed.
- `WorkspaceManifest.load` repeats four near-identical `toUnmodifiableMap` stream collects; a small
  private collector helper would tidy it.
  Opportunistic only.

---

## 9. Test suite

### 9.1 Do not change

- **Naming:** all test methods follow `methodName_condition_result`. No action.
- **`@Nested`:** none. No action.
- **`junit-platform.properties`:** present in all four modules. No action.
- **`LoggingConfig`:** auto-applied via `META-INF/services`; **no `@ExtendWith` needed**. No action.
- **`@Disabled` (5, all in `SemanticTokensTest`):** M2 placeholders. Leave as-is.
- **Mockito in `lathe-compiler`:** none. No action.

### 9.2 `ParsedSource` resource ownership

`TestCompiler.ParsedSource` is a record holding a `StandardJavaFileManager` that is never closed.
Many analysis tests (`DefinitionLocatorTest`, `JavadocLocatorTest`, `SampleFixture`,
`WorkspaceManifestTest`, `CompletionFixture`) obtain one and leak it.

Action: make `ParsedSource` implement `AutoCloseable` (closing its parser and file manager); update
callers to try-with-resources or a JUnit lifecycle fixture.
Close failures propagate to the test — they are not suppressed or logged as warnings.

### 9.3 Consolidate the duplicated test compilation pipeline

`TempSourceCompiler` (test) reimplements the javac compile/parse path that production `JavacRunner`
owns.
Refactor `TempSourceCompiler` to reuse `JavacRunner` (make `JavacRunner` package-visible to a matching
test package, or add a small shared dispatcher) so AST/diagnostic/token changes in production are
reflected in tests automatically.
Keep `TestCompiler` and `TempSourceCompiler` distinct only where they model genuinely different
lifecycles.

### 9.4 System-property JUnit extension

`DependencyTypeIndexSyncTest` and `JdkTypeIndexSyncTest` duplicate identical `@BeforeEach`/`@AfterEach`
backup-and-restore of the `lathe.cache` system property.
Extract a reusable JUnit 5 extension (`SystemPropertyExtension` + a `@SystemProperty` annotation, or a
registry) and register it in both.

### 9.5 Shared zip fixture

`ZipFixture` exists in both `lathe-core` and `lathe-maven-plugin` test sources.
They diverge intentionally (the plugin version has extra overloads).
Either unify behind a shared test-utility location if compile boundaries permit, or leave split and add a
comment recording the intent.
Lowest priority.

### 9.6 Large completion-test repetition (optional)

`CompletionMemberAccessTest` (~1018 LOC, ~51 methods) and `CompletionSimpleNameTest` (~818 LOC,
~62 methods) repeat a "compile inline source → `fixture.complete(...)` → assert labels" shape.
The clusters of single-pattern cases are candidates for JUnit parameterized tests.
Optional — only collapse cases that are pure data variations; keep semantically distinct cases as named
methods.
Where a test only asserts presence/absence, add the missing ranking assertion.

---

## 10. Implementation order

1. **Section 9.2** — `ParsedSource` AutoCloseable (resource fix, no approval gate; de-leak before
   refactoring).
2. **Slice 5.1** (`LatheUri.toPath`), then **Slice 5.2** (`SourceTreeLocator`) — contained, mechanical
   DRY wins.
3. **Slice 5.3** (search-input planning) → **5.4** (type hierarchy, with codec validation) → **5.5**
   (diagnostic mapping).
4. **Slice 5.6** (annotation completion) → **5.7** (method-path scanning).
5. **Slice 5.8** (symbol kinds).
6. **Section 6.1** (feature-routing helper), then re-measure before **6.2** and **6.3**.
7. **Section 8** core nits and **Section 9.3–9.6** test consolidation, opportunistically alongside
   related slices.
8. Apply **Section 7** renames inside whichever slice touches the code.

Behavior fixes are never hidden inside structural-refactoring commits.
Each slice is independently reviewable and preserves the worker-ownership model.

---

## 11. Verification

For every slice:

```bash
mvn spotless:apply
mvn test -pl lathe-server
```

When a slice changes shared core code, Maven integration, or public LSP behaviour, run full
verification:

```bash
mvn verify
```

Verification must include focused negative tests for stale state, malformed payloads, and asynchronous
failure propagation where applicable.
