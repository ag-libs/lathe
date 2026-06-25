# Lathe — M1 Refactoring

Working design document.
Single authoritative refactoring plan for the M1 Internal Preview milestone.

This document is the output of the June 2026 systematic codebase and test-corpus review.
It is the **single** refactoring document for Lathe. It absorbed and replaced three earlier drafts —
`lathe-maintainability-refactoring.md`, `lathe-test-suite-refactoring.md`, and
`lathe-code-quality-refactoring.md` — which have been deleted. Every still-actionable finding from those
drafts is folded in below; findings that were already implemented or no longer match the current tree are
dropped.

Status last reviewed: 2026-06-25.

### Already-implemented since the earlier drafts (do not redo)

Confirmed against the current tree on 2026-06-25:

- `DocumentRegistry` is already extracted from `WorkspaceSession` (`server/DocumentRegistry.java`).
- `DiagnosticPublisher` is already extracted (`server/DiagnosticPublisher.java`).
- The semantic-token refresh correctness fix is done — `DiagnosticPublisher.refreshTokensIfCurrent()`
  now refreshes only for the current (non-stale) generation.
- `TypeHierarchyItemDataCodec` already uses `encode` / `decode` naming.

### Stale findings — do NOT act on these

Earlier drafts referenced code shapes that no longer exist. Do not resurrect work on:
`LatheCompileMojo` / `LatheTestCompileMojo` duplication, `CacheLayout` vs `LatheLayout`, a top-level
`AfterExtract`, `StringUtil`, `WorkspaceCompiler`, `CompletionBuilder`, a standalone `ReferenceFinder`,
the `typeSearchFutures`/`toImplLocations` renames, and hand-rolled flat JSON parsing in `Json`.
None match the current tree.

---

## 1. Goal and scope

Make the server easier to read, maintain, and extend by:

- correcting documentation that no longer matches the code (highest leverage, lowest risk);
- removing concrete, verified duplication;
- decomposing the four classes whose size genuinely impedes comprehension;
- applying consistent naming and a single fail-fast error-handling policy;
- removing test flakiness and consolidating test fixtures.

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

- **Section 4 (documentation) and Section 9 (test hygiene)** are low-risk and self-contained; an agent may
  implement them slice-by-slice without the approval gate, showing the diff before commit.
- **Sections 5–8 (extractions, god-class splits, renames, core nits)** introduce new classes / move
  responsibilities / change signatures. Each slice MUST be presented as a design summary and approved
  before implementation.

Never run `git commit` or `git push` autonomously; show the diff and wait for authorization.
Run `mvn spotless:apply` immediately after editing any `.java` file.

---

## 4. Documentation accuracy fixes

The design documents instruct every agent to read `docs/lathe-design.md` before non-trivial work, so stale
names actively mislead future work.

**Guiding principle (applied 2026-06-25):** the design doc states the *architectural choice and guarantee*;
it does not name internal classes or javac call-points that the code is free to change. This is what keeps
the design from drifting on implementation details. Implementation specifics live in the code.

### 4.1–4.3 Remove fictional component names — DONE (2026-06-25)

`lathe-design.md` referenced a `CustomFileManager` (5×, supposedly intercepting `getJavaFileForOutput`) and
a `ModuleAnalysis` (1×) — neither class exists; the code uses a plainly-configured `StandardJavaFileManager`
and writes output via standard location config. `status.md` described `lathe-worker` (a thread name) as if
it were a component.

Rather than re-document the mechanism, the affected passages were rewritten to architecture level:

- §6 "LS output redirection" now states that each pass writes output under `.lathe/<rel>/` (never
  `target/`), with no file-manager-interception detail.
- §6 "Orphan handling" now states the guarantee (stale class files from a prior pass are removed) without
  the snapshot / `getJavaFileForOutput` recipe.
- §6 startup, the `didSave`/`didOpen` flow blocks, and the §6 sibling-resolution sentence dropped the
  `CustomFileManager` references.
- §7 external-sources sentence now describes `ExternalCompiler`'s reusable compilation state without the
  fictional `ModuleAnalysis`.
- `status.md` now reads "`WorkspaceSession`, confined to the single server worker thread (`lathe-worker`),
  owns mutable workspace state …".

Verified: `grep -rn 'CustomFileManager\|ModuleAnalysis\|getJavaFileForOutput' docs/` matches only this
refactoring doc. No code changed.

### 4.4 Reconcile `CachedFileAnalysis` vs `AttributedFileAnalysis` — OPEN (optional)

`lathe-design.md` §6–§7 mention only `CachedFileAnalysis`. The code has both: `CachedFileAnalysis`
(cache entry: content, version, analysis) wrapping `AttributedFileAnalysis`. Both names are accurate; the
relationship is just undocumented. Low priority — add one clarifying sentence to §6 only if §6 is otherwise
being edited. Per the guiding principle above, prefer wording that does not over-specify the cache layering.

### Follow-up (behavior, not documentation)

While de-specifying "Orphan handling" (4.1), no implementation of orphan class-file cleanup was found in
the server code. The doc now states the *intent*; that does not make the behavior exist. Track separately
whether orphan cleanup after shrinking compilation output is actually implemented, and add it or downgrade
the guarantee accordingly.

---

## 5. Error-handling policy (preserved invariant)

Carried forward unchanged — it remains correct and governs all slices below.

Lathe fails fast when an internal invariant is violated. Code must not convert unexpected exceptions into
empty results, `null`, or `Optional.empty()` merely to keep a request running.

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

## 6. DRY / structural extractions (verified)

Each slice is independently reviewable, must keep the worker-ownership model, and must pass
`mvn spotless:apply` + `mvn test -pl lathe-server` before the next begins.

### Slice 6.0 — Unify URI→Path conversion into `LatheUri.toPath(String)`

**Evidence (verified 2026-06-25):** `Path.of(URI.create(uri))` is duplicated across six sites — two
wrapped in a private `toPath()` (`WorkspaceSession:1320`, `ReferenceCandidatePlanner:96`) and four inline
(`LatheLanguageServer:45`, `workspace/WorkspaceManifest:371`, `module/ModuleSourceCompiler:65`,
`module/ExternalCompiler:64`). Any future URI-encoding change (e.g. percent-encoded spaces) must currently
be applied in six places.

Action: add a `LatheUri.toPath(String uri)` static in the server module; replace all six sites; remove the
two private `toPath()` wrappers. Purely mechanical, zero behavioral change, independent of every other
slice — do it first. (`SourceParser`'s `URI.create(uri)` builds a `SimpleJavaFileObject` URI, not a Path —
leave it alone.)

Tests: add a focused `LatheUriTest` (normal path, percent-encoded path); existing tests cover the call
sites.

### Slice 6.1 — `SourceTreeLocator` base for the `*Locator` classes

**Evidence (verified):** `ReferenceLocator`, `CallHierarchyIncomingLocator`, and
`CallHierarchyOutgoingLocator` each declare a 7–8-argument private constructor, each repeat
`this.positions = trees.getSourcePositions();`, and each static factory repeats
`analysis.tree().getSourceFile().getCharContent(<bool>).toString()` before instantiating and calling
`scan()`. Note one uses `getCharContent(true)`, two use `(false)` — reconcile this discrepancy
deliberately during the extraction.

Action: introduce a package-private base (e.g. `SourceTreeLocator`) holding the shared fields
(`trees`, `cu`, `content`, `positions`, `target`, `types`, `elements`, and `uri` where present) and a
shared `sourceContent(AttributedFileAnalysis)` helper. The three locators extend it; subclasses keep only
their distinct `scan` logic and result construction.

Tests: existing `ReferenceLocatorTest`, `CallHierarchyIncomingLocatorTest`,
`CallHierarchyOutgoingLocatorTest` must pass unchanged. Add a case pinning the `getCharContent` charset
decision so the reconciliation does not silently regress.

### Slice 6.2 — Share compiler setup/teardown between `ModuleSourceCompiler` and `ExternalCompiler`

**Evidence (candidate — confirm exact line ranges during implementation):** both implement
`JavaSourceCompiler` (a legitimate two-implementation interface — keep it) and appear to duplicate:
option building (`-proc:none`, release/encoding/parameters/preview), temp-directory create/write/cleanup,
and file-manager close with `WARNING` logging.

Action: extract the shared option-building and resource-lifecycle logic into a package-private helper used
by both compilers. `ModuleSourceCompiler` keeps its richer location setup (`initLocations()`);
`ExternalCompiler` keeps its classpath-only setup. Do not force a single location-setup method onto both —
their location requirements legitimately differ.

Tests: `ModuleSourceCompilerTest`, `ExternalCompilerTest`, and `CompilationWorkerTest` pass unchanged.

### Slice 6.3 — Consolidate workspace search-input planning

**Evidence (verified):** `WorkspaceSession.searchFutures()` and `WorkspaceSession.incomingCallFutures()`
independently build near-identical open-document + disk-candidate inputs (select open docs in module
scope, collect their URIs, ask `ReferenceCandidatePlanner` for disk candidates, exclude already-open URIs,
filter by package scope, read disk sources).

Action: extract one package-private planner returning immutable inputs, e.g.

```java
record WorkspaceSearchInputs(List<OpenDocument> openDocuments, List<DiskSource> diskSources) {}
```

References and incoming calls map the shared inputs to their own worker operations and result types.
Do **not** build a generic search-execution framework — only the input planning is shared; result
aggregation and search semantics are not.

Tests: add a planner unit test (open-doc selection, URI exclusion, package-scope filtering); existing
reference and call-hierarchy service tests pass unchanged.

### Slice 6.4 — Extract type-hierarchy resolution from `SourceAnalysisSession`

Move hierarchy behavior (prepare item at position, resolve direct supertypes, map subtype matches to
items, locate declarations, construct `TypeHierarchyItem` + encoded data) into a package-private
`TypeHierarchyResolver`. `SourceAnalysisSession` remains the compiler-state facade and delegates.
The resolver owns no mutable compiler lifecycle state and introduces no new worker boundary.

As part of this slice, extract the one implementation-match operation shared by implementation-location
search and subtype-item search instead of duplicating attribution + locator invocation + `IOException`
propagation.

Note: `TypeHierarchyItemDataCodec` already exposes `encode`/`decode` (the older doc's rename is done).
Verify `decode` validates every required field and throws `IllegalArgumentException` (naming the bad
field) on malformed data rather than returning null; add the missing-field / invalid-enum tests if absent.

### Slice 6.5 — Extract javac diagnostic mapping from `SourceAnalysisSession`

Move diagnostic enrichment and javac→LSP conversion (range/severity conversion, supported-kind
classification, structured payload extraction for missing methods and unreported exceptions, code-action
context attachment) into a focused `JavacDiagnosticMapper`. Start with one mapper; split later only if
independent reuse appears. Unknown diagnostic kinds remain valid diagnostics without structured payloads;
malformed compiler data fails fast to the compile boundary.

### Slice 6.6 — Extract annotation completion from `CompletionEngine`

Move annotation-argument and annotation-value completion into an `AnnotationCompletionProvider`.
`CompletionEngine` retains request classification, shared analysis resolution, merging, and presentation.
Pass a resolved analysis context into the provider; do not give it access to all engine internals and do
not introduce a generic provider registry in this slice.

### Slice 6.7 — Unify method-path scanning in `TypeResolver`

`findScopeMethodPath()` and `findMethodPath()` contain overlapping AST scanners; the same scanning is also
duplicated in `SimpleNameProvider` (`findScopeMethodPath`, `findClassElement`). Implement one scanner with
an explicit selection policy (exact-enclosing vs cursor-nearest), remove no-op visitor overrides, and have
both callers use it. Keep this focused; do not attempt the larger expected-value resolver extraction here
(see 7.4).

### Slice 6.8 — Centralize method-identity matching

`ReferenceTarget` and the implementation locator independently construct erased method descriptors, and
the locator re-implements `ReferenceTarget.matches()`. Make `ReferenceTarget` the single owner of method
identity; the locator delegates. Retain/add tests for overloads, generic erasure, constructors, and
interface-inherited methods.

### Slice 6.9 — Centralize LSP symbol-kind mapping

`WorkspaceSymbolResolver`, `DocumentSymbolScanner`, and type-hierarchy construction map Java kinds
independently, representing records inconsistently as `Class` vs `Struct`. Create a package-private
`SymbolKinds` mapper (entry points for the javac model and the type-index representation), pick one record
mapping, and use it everywhere. Symbol-kind policy only — not a general LSP conversion utility.

---

## 7. God-class decomposition (largest value, full approval gate)

Four classes dominate their packages. Sizes verified 2026-06-25:

| Class | LOC | Responsibility clusters to separate |
|---|---|---|
| `analysis/completion/CompletionEngine` | 1640 | type-reference completion; annotation completion (Slice 6.6); member-access completion; semicolon/edit synthesis; type-reference role filtering |
| `WorkspaceSession` | 1336 | document lifecycle; compile scheduling; reference/call-hierarchy orchestration (Slice 6.3); type-hierarchy/impl routing; ~8× feature-routing boilerplate; mutable index/state holder; class-output cleanup |
| `analysis/completion/TypeResolver` | 1110 | expected-value resolution; scope/element finding (Slice 6.7); lambda/switch context; position-based receiver resolution |
| `analysis/SourceAnalysisSession` | 899 | feature dispatcher facade; hierarchy (Slice 6.4); diagnostic mapping (Slice 6.5); code-action dispatch; hover/definition/symbols |

Slices 6.3–6.7 already chip the cohesive sub-responsibilities out of these classes; do those first. After
they land, evaluate whether further extraction is warranted with a fresh measurement. Candidate follow-on
extractions, **each requiring its own design summary + approval**:

### 7.1 `WorkspaceSession` feature-routing helper
The ~8 `*Future()` methods (hover, signatureHelp, definition, documentSymbol, foldingRange, …) repeat the
"get open doc → fallback → route to worker → `exceptionally`" shape. Collapse into one parameterized helper.
This is a contained, high-clarity win and can be proposed independently.

### 7.2 `WorkspaceSession` immutable state holder
The cluster of mutable fields reassigned across `initialize()` / `reload()` / `refreshReactorShard()`
(`manifest`, `workspace`, `moduleGraph`, `candidateIndex`, `typeIndex`, `reactorShards`) could move behind
a single immutable snapshot swapped atomically on the worker thread. Propose only after 7.1.

### 7.3 `CompletionEngine` completer extraction
After Slice 6.6 (annotation completion — the highest-value, fully instance-field-independent extraction),
evaluate these further cohesive boundaries, each as its own approved slice and each ordered after a fresh
re-measurement:

- **Type-index completion merging** — query `WorkspaceTypeIndex`, javac-validate candidates, apply
  package-accessibility / import-fit checks, merge with javac simple-name candidates, preserve ranking and
  result limits.
- **Member-access candidate policy** — instance/static receiver candidates, generic substitution via
  `types.asMemberOf()`, filtering of inaccessible/synthetic/duplicate items, member sorting.
- **Statement-edit post-processing** — primary edit range and additional import edits, semicolon edits only
  when syntactically safe, no cursor assumptions that conflict with additional edits.
- **`TypeReferenceCompleter`** for the ~350-line type-reference group.

The completion providers must stay concrete (no shared `CompletionProvider` interface — forbidden for
single-impl, and introducing one across many would violate the guide). Extract named policy objects, never
a `CompletionUtil` bag.

Opportunistic nit while editing nearby completion code: `CompletionEngine.memberAccessSemanticContext` and
its callers construct `SemanticCompletionContext` directly (`CompletionEngine:539`, and the
`new ExpectedValue.Unknown()` path at `:1503`) instead of via the `SemanticCompletionContext.from()`
factory used elsewhere. Normalize to the factory where the context is built fresh (the derived-from-base
constructions inside `memberAccessSemanticContext` may legitimately stay direct).

### 7.4 `TypeResolver` expected-value resolver (deferred / conditional)
The expected-value portion may eventually warrant its own resolver, but its helpers are currently shared
with receiver and static-member resolution; extracting now creates awkward callback dependencies. Defer
until 6.7 lands and the coupling is re-measured.

### 7.5 Move `deleteClassOutputs` out of `WorkspaceSession` (opportunistic)
`WorkspaceSession.deleteClassOutputs(ModuleSourceConfig, Path)` (`WorkspaceSession:1065`, called at `:200`)
is a `static` package-visible method with no coupling to session state — its visibility is an artifact of
test access. Move it to a filesystem utility (extend `FileUtil` or a focused server-side helper) when next
editing nearby file-cleanup code. Do not create a standalone commit solely for this.

### 7.6 Code-action dispatcher (deferred / conditional)
`SourceAnalysisSession` dispatches code actions through a readable switch despite a `CodeActionProvider`
interface with five implementations (`AddThrowsProvider`, `DeclareVariableProvider`, `TryCatchWrapProvider`,
`ImportQuickFixProvider`, plus the planned missing-method provider). A `CodeActionDispatcher` backed by an
immutable provider map becomes worthwhile when the next provider lands — do it then, not solely to remove
the switch.

---

## 8. Naming cleanup (apply within the related slice only)

Rename only while the containing code is already being changed by a slice above; do not create standalone
rename-only commits unless a name is actively causing incorrect use.

| Current | Proposed | Reason | Apply during |
|---|---|---|---|
| `WorkspaceSession.workspace` | `moduleRegistry` | field is a `WorkspaceModuleRegistry`, not the whole workspace | 7.2 |
| `WorkspaceSession.docs` | `documents` | avoid an abbreviation in the central coordinator | 7.1/7.2 |
| `WorkspaceSession.worker` | `eventLoop` | makes thread ownership explicit | 7.1/7.2 |
| `WorkspaceSession.searchFutures()` | `referenceFutures()` | matches what it produces | 6.3 |
| `ExternalCompiler` | `ExternalSourceCompiler` | it compiles external *source*, not an external process | 6.2 |

(The older doc's `typeSearchFutures`/`toImplLocations` renames are dropped — those symbols are no longer
present in `WorkspaceSession`.)

---

## 9. Core and Maven-plugin rule nits (low risk)

### 9.1 `ModuleConfigData` validation
`lathe-core/.../schema/ModuleConfigData` uses `Objects.requireNonNullElse(...)` in its compact constructor
for `encoding` and `compilerArgs`. The records rule requires `ValidCheck`-based invariants. Convert to
`ValidCheck` (and defensively copy `compilerArgs` to an immutable list). Add a positive + a null/edge case.

### 9.2 Hardcoded literals into `LatheLayout`
Move stray literals such as the `"lathe-server"` artifact id in `ServerInstaller` (and any `"sources"` /
`"jar"` classifier strings) into `LatheLayout`/`PluginProps` constants, per the no-hardcoded-names rule.
Verify each candidate is genuinely shared before centralizing JDK-property keys that are used in one place.

### 9.3 Low-priority observations (record, do not necessarily act)
- `core/typeindex/TypeIndexOrigin` is a nullable-union record guarded by `ValidCheck`; a sealed hierarchy
  would express the dependency/jdk/reactor variants more safely. Defer unless the type is being changed.
- `WorkspaceManifest.load` repeats four near-identical `toUnmodifiableMap` stream collects; a small private
  collector helper would tidy it. Opportunistic only.

---

## 10. Test suite

### 10.1 Already-correct items (do not "fix")
- **Naming:** all ~700 test methods follow `methodName_condition_result`. No action.
- **`@Nested`:** none. No action.
- **`junit-platform.properties`:** present in all four modules. No action.
- **`LoggingConfig`:** registered via `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`
  (contents: `io.github.aglibs.lathe.server.LoggingConfig`) plus `autodetection.enabled=true`. It is
  auto-applied; **no `@ExtendWith` is needed**. No action.
- **`@Disabled` (5, all in `SemanticTokensTest`):** placeholders for M2 class/import semantic highlighting.
  Leave as-is; out of M1 scope.
- **Mockito in `lathe-compiler`:** none. No action.

### 10.2 Eliminate `Thread.sleep` synchronization (10 occurrences, 2 files)
Replace timing-based synchronization with deterministic signals. Files (verified):
- `ServerEventLoopTest` — 6 sleeps; use `CountDownLatch` / `CompletableFuture` completion, retaining a
  bounded timeout only as failure protection.
- `LatheTextDocumentServiceTest` — 4 sleeps; use `Mockito.verify(client, timeout(N))` (the import is
  already present).

Wall-clock elapsed time must not be the primary completion signal. Do not add a production `awaitIdle()`
API solely for tests unless no observable completion signal exists.

### 10.3 `ParsedSource` resource ownership
`TestCompiler.ParsedSource` is a record holding a `StandardJavaFileManager` that is never closed; many
analysis tests (e.g. `DefinitionLocatorTest`, `JavadocLocatorTest`, `SampleFixture`, `WorkspaceManifestTest`,
`CompletionFixture`) obtain one and leak it.

Action: make `ParsedSource` implement `AutoCloseable` (closing its parser and file manager); update callers
to try-with-resources or a JUnit lifecycle fixture. Close failures propagate to the test — they are not
suppressed or logged as warnings.

### 10.4 Consolidate the duplicated test compilation pipeline
`TempSourceCompiler` (test) reimplements the javac compile/parse path that production `JavacRunner` owns.
Refactor `TempSourceCompiler` to reuse `JavacRunner` (make `JavacRunner` package-visible to a matching test
package, or add a small shared dispatcher) so AST/diagnostic/token changes in production are reflected in
tests automatically. Keep `TestCompiler` and `TempSourceCompiler` distinct only where they model genuinely
different lifecycles.

### 10.5 System-property JUnit extension
`DependencyTypeIndexSyncTest` and `JdkTypeIndexSyncTest` duplicate identical `@BeforeEach`/`@AfterEach`
backup-and-restore of `lathe.cache`-style properties. Extract a reusable JUnit 5 extension
(`SystemPropertyExtension` + a `@SystemProperty` annotation, or a registry) and register it in both.

### 10.6 Shared zip fixture
`ZipFixture` exists in both `lathe-core` and `lathe-maven-plugin` test sources. They diverge intentionally
(the plugin version has extra overloads). Either unify behind a shared test-utility location if compile
boundaries permit, or leave split and add a comment recording the intent. Lowest priority.

### 10.7 Large completion-test repetition (optional)
`CompletionMemberAccessTest` (1018 LOC, ~51 methods) and `CompletionSimpleNameTest` (818 LOC, ~62 methods)
repeat a "compile inline source → `fixture.complete(...)` → assert labels" shape. The clusters of
single-pattern cases (member-access contains/excludes; variable-initializer ranking) are candidates for
JUnit parameterized tests. Optional — only collapse cases that are pure data variations; keep semantically
distinct cases as named methods. Where a test only asserts presence/absence (e.g.
`staticMethodBody_simpleName_doesNotSuggestInstanceMembers`), add the missing ranking assertion.

---

## 11. Implementation order

1. **Section 4** — documentation accuracy (no code, no approval gate).
2. **Section 10.2 + 10.3** — async-sleep removal and `ParsedSource` closing (de-flake before refactoring).
3. **Slice 6.0** (`LatheUri.toPath`), then **Slice 6.1** (`SourceTreeLocator`) and **Slice 6.2** (compiler
   setup) — contained, mechanical DRY wins.
4. **Slice 6.3** (search-input planning) → **6.4** (type hierarchy, with codec validation) → **6.5**
   (diagnostic mapping).
5. **Slice 6.6** (annotation completion) → **6.7** (method-path scanning).
6. **Slice 6.8 / 6.9** (method identity, symbol kinds).
7. **Section 7.1** (feature-routing helper), then re-measure before **7.2** and **7.3**.
8. **Section 9** core nits and **Section 10.4–10.7** test consolidation, opportunistically alongside related
   slices.
9. Apply **Section 8** renames inside whichever slice touches the code.

Behavior fixes are never hidden inside structural-refactoring commits. Each slice is independently
reviewable and preserves the worker-ownership model.

---

## 12. Verification

For every slice:

```bash
mvn spotless:apply
mvn test -pl lathe-server
```

When a slice changes shared core code, Maven integration, or public LSP behavior, run full repository
verification:

```bash
mvn verify
```

Verification must include focused negative tests for stale state, malformed payloads, and asynchronous
failure propagation where applicable.

## 13. M1 exit alignment

This plan satisfies the roadmap M1 "Correctness and maintainability" bullets:
fail-fast propagation (Section 5), naming/DRY/fixture slices (Sections 6–10), removal of hard-coded test
sleeps (10.2), and preservation of the event-loop and module-worker ownership model (Section 2).
It adds the documentation-accuracy work (Section 4) that the prior plans omitted.
