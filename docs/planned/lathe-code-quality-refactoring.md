# Lathe — Code Quality Refactoring Plan

Working design draft.
This document records the verified follow-up work from the June 2026 code-quality review evaluation.

---

## 1. Goal

Improve maintainability in the parts of Lathe that now carry the most behavioral risk:
completion,
source analysis orchestration,
and workspace-level server coordination.

The refactoring should make future completion and references fixes easier to reason about,
without changing user-visible behavior in the same slice.
Behavioral changes should continue to be driven by the completion and find-references gap logs.

---

## 2. Non-Goals

This is not a broad rename pass.
Names should change only when the surrounding code is already being edited and the new name clarifies a real responsibility.

This is not a generic class-size cleanup.
Large classes are a problem when they mix independent concepts,
hide policy decisions,
or make focused tests difficult.
Do not split code only to reduce line counts.

This plan does not address stale findings from older code shapes:

- `LatheCompileMojo` / `LatheTestCompileMojo` duplication.
- `CacheLayout` versus `LatheLayout`.
- top-level `AfterExtract`.
- `StringUtil`.
- `WorkspaceCompiler`.
- `CompletionBuilder`.
- `ReferenceFinder`.
- hand-rolled flat JSON parsing in `Json`.

Those findings do not match the current tree and should not drive work.

---

## 3. Current Verified Findings

### WorkspaceSession (871 lines)

`WorkspaceSession` is a single package-private `final class` with eight distinct responsibility groups:

| Concern | Fields / Methods |
|---|---|
| Open document registry | `openDocuments`, `nextGeneration`, `OpenDocument` record, `putOpenFile()`, `snapshotForSave()`, `isStale()` |
| Lifecycle events | `onOpen`, `onChange`, `onClose`, `onSave`, `onDeletedFile` |
| Compile scheduling / debounce | `scheduleOpenFile()`, `scheduleAllOpenFiles()`, `scheduleOpenFilesInModule()`, `scheduleAstRefresh()` |
| Compile dispatch | `compileAndPublish()`, `submitCompile()` (three overloads), `submitTo()`, `routeCompiler()`, `CompilerRoute`, `AfterCompile` |
| Diagnostic publishing | `publishIfCurrent()`, `refreshTokensIfCurrent()`, `publishIfCurrentThen()`, `publishMissingDiagnostic()`, `publishCompileError()`, `singleDiag()` |
| Workspace state | `workspace`, `manifest`, `moduleGraph`, `candidateIndex`, `typeIndex`, `reactorShards`, `watcher`, `workspaceRoot` |
| Workspace init / reload | `initialize()`, `reload()`, `close()`, `checkForChanges()`, `scanReactorShards()`, `refreshReactorShard()` |
| Feature request dispatch | `referencesFuture()`, `hoverFuture()`, `signatureHelpFuture()`, `definitionFuture()`, `completionFuture()`, `codeActionFuture()`, `semanticTokensFuture()`, `format()`, `workspaceSymbol()` |

`WorkspaceSession` should remain the event-loop-owned coordinator.
The useful split is to extract cohesive helpers that preserve worker confinement.

#### Additional WorkspaceSession findings not in the original design

`deleteClassOutputs()` is a `static` method with package-visible access that takes only a `ModuleSourceConfig` and `Path`.
It has no coupling to session state.
Its package-visibility is likely an artifact of test access.
It should live in a filesystem utility class, not in a session class.

The references feature (`referencesFuture()` plus the inline `searchFutures()` helper) accounts for ~120 lines.
This is not addressed by any current design doc and warrants its own extraction when the references
feature is next touched.

### CompletionEngine (1,613 lines)

`CompletionEngine` is the largest server class.
It owns request classification dispatch, Javac candidate collection, type-index candidate merging,
expected-value filtering, completion item presentation, and statement edit post-processing.

Method groups by responsibility:

| Group | Approx. lines |
|---|---|
| Top-level dispatch (`complete`) | 70 |
| Simple-name completion | 180 |
| Type-reference completion | 350 |
| Member-access completion | 155 |
| Annotation argument completion | 200 |
| Post-processing (semicolons, import edits) | 130 |
| Merge / dedup | 70 |
| Static member fit | 90 |
| Misc helpers | 150 |

#### Annotation completion — highest-value missed extraction

The annotation group (`completeAnnotationArgument`, `completeAnnotationArgumentValue`,
`resolveAnnotationType`, `samePackageType`, `importedType`, and five candidate factory methods)
is ~200 lines and is entirely instance-field-independent.
It calls `resolveAnalysis()`, which can be passed as a parameter.
Moving these to `AnnotationCompletionProvider` follows the naming convention already established
in the package and reduces `CompletionEngine` by ~13% with a cleaner boundary than any slice
currently proposed.
This extraction is not mentioned in the original design doc.

#### SemanticCompletionContext factory bypass

`memberAccessSemanticContext()` has two overloads.
The second overload (4 lines) constructs `SemanticCompletionContext` directly with
`new ExpectedValue.Unknown()`, bypassing the `from()` factory used everywhere else.
This should be normalized to use the factory.

### TypeResolver (1,110 lines)

`TypeResolver` is a package-private utility class with seven public static entry points.
The expected-value logic (~450 lines) is the largest single responsibility,
but the proposed `ExpectedValueResolver` split is messier than the original design doc suggests.

Three private helpers are shared between the expected-value group and the receiver-resolution group:

- `findClassElement()` — used by expected-value logic **and** `resolveReceiver()` / `scanForLocalDeclaration()`
- `cursorOutside()` — used by expected-value logic **and** `resolveExpectedArgumentValue()`
- `findScopeMethodPath()` — used by expected-value logic **and** `resolveStaticMemberResultContext()`

A clean `ExpectedValueResolver` extraction requires a conscious decision on where these shared helpers
live — either left in `TypeResolver` (with `ExpectedValueResolver` calling back) or pulled into a
third shared location.
Slice 1 should not be the first extraction attempted.

#### Duplicated method-scanner logic

`findMethodPath()` (line 781) and `findScopeMethodPath()` (line 717) both scan for a method
by class and name.
`findScopeMethodPath` adds cursor-offset proximity ranking.
They share overlapping logic.
A bug fix applied to one will not automatically apply to the other.
These should be unified before or alongside Slice 1.

### `toPath()` — six independent copies

`Path.of(URI.create(uri))` appears in six independent locations:

- `WorkspaceSession.toPath()` — private static
- `ReferenceCandidatePlanner.toPath()` — private static
- Inlined in `ExternalCompiler.compile()`
- Inlined in `ModuleSourceCompiler.compile()`
- Inlined in `LatheLanguageServer.initialize()`
- Inlined in `WorkspaceManifest`

Six copies is a maintenance risk for any URI encoding change (e.g. percent-encoded spaces in paths).
A `LatheUri.toPath(String)` static in the server module resolves all six at once.
This is a mechanical refactor with zero behavioral risk and should be done independently of the
larger slices.

### Completion engine size and coupling (original finding, revised)

`CompletionEngine` is the largest current server class (see breakdown above).
The originally proposed slices 2–4 remain valid but should be prioritized after the annotation
completion extraction, which provides a larger and cleaner win.

### WorkspaceSession breadth (original finding, preserved)

`WorkspaceSession` is still broad but its breadth is partly intentional.
The server design uses one `lathe-worker` thread to own mutable workspace state.
`LatheTextDocumentService` already performs LSP wire dispatch.
The useful split is to extract cohesive services that preserve worker confinement.

### Naming clarity (original finding, revised)

`ExternalCompiler` may benefit from either a short class comment or a later rename to
`ExternalSourceCompiler`.
The inline `toPath()` wrapper methods in `WorkspaceSession` and `ReferenceCandidatePlanner` should
be removed as part of the `LatheUri.toPath()` unification (see above).

---

## 4. Refactoring Principles

Use behavior-preserving slices.
Each slice should compile and pass existing tests before the next slice starts.

Prefer extracting named policy objects over utility bags.
For example, `ExpectedValueResolver` is useful if it owns expected-slot inference policy.
`CompletionUtil` is not useful.

Keep threading boundaries explicit.
Anything extracted from `WorkspaceSession` must either run only on the server worker,
or receive immutable request data and return immutable results.

Do not mix refactoring with completion behavior changes.
If a gap fix needs refactoring first,
commit or review the refactoring separately from the behavior change.

Add tests when extraction exposes a previously implicit policy.
A pure move with no behavior change can rely on existing tests,
but policy extraction should get focused unit coverage where practical.

---

## 5. Proposed Slices

Slices are ordered by ROI.
The first three are mechanical or near-mechanical; the later slices require more design judgment.

### Slice 0: Unify `toPath()` into `LatheUri.toPath(String)`

Create a `LatheUri.toPath(String uri)` static method in the server module.
Replace all six independent copies of `Path.of(URI.create(uri))` with a call to this utility.
Remove the now-redundant `WorkspaceSession.toPath()` and `ReferenceCandidatePlanner.toPath()` wrappers.

This slice is independent of all others and should be done first.

### Slice 1: `DocumentRegistry` extraction from `WorkspaceSession`

Extract open-document lifecycle from `WorkspaceSession` into a focused package-private class.

Responsibilities:

- URI → latest text mapping
- version / generation tracking
- `OpenDocument` record
- `putOpenFile()`, `snapshotForSave()`, `isStale()` / `isCurrent(snapshot)`
- open/close/delete cleanup facts needed by `WorkspaceSession`

`WorkspaceSession` becomes the consumer: it holds a `DocumentRegistry` and calls into it.

Expected outcome:

- `WorkspaceSession` shrinks by ~60 lines
- generation-staleness logic becomes directly testable without a full session
- unblocks the `DiagnosticPublisher` extraction (Slice 5)

### Slice 2: `AnnotationCompletionProvider` extraction from `CompletionEngine`

Extract the annotation argument completion group from `CompletionEngine`.

Responsibilities:

- `completeAnnotationArgument()`
- `completeAnnotationArgumentValue()`
- `annotationValueCompletionType()`
- `annotationEnumConstantCandidates()` / `annotationEnumConstantCandidate()`
- `annotationElementCandidate()`
- `resolveAnnotationElementType()` / `resolveAnnotationType()`
- `samePackageType()` / `importedType()`

`resolveAnalysis()` is passed as a parameter (or the `SourceAnalysisSession` is injected).

Expected outcome:

- `CompletionEngine` shrinks by ~200 lines
- annotation completion behavior gets a direct test surface
- follows the existing `ImportCompletionProvider` / `KeywordProvider` naming convention

### Slice 3: Expected-value completion resolution

Extract expected-value inference from `TypeResolver` into a focused package-private class,
for example `ExpectedValueResolver`.

Before this slice, unify `findMethodPath()` and `findScopeMethodPath()` into a single scanner
and decide where the three shared helpers (`findClassElement`, `cursorOutside`, `findScopeMethodPath`)
live after the split.

Responsibilities of `ExpectedValueResolver`:

- initializer expected type
- assignment RHS expected type
- method and constructor argument expected type
- lambda body expected return type
- switch case selector expected value

`TypeResolver` should keep receiver and symbol-resolution helpers until later slices prove a better
boundary.

Expected outcome:

- completion typed-slot behavior becomes easier to test and extend
- future generic, stream, lambda, and assignment completion gaps have one obvious policy location

### Slice 4: Type-index completion merging and validation

Extract type-index query, validation, deduplication, and simple-name merge policy from `CompletionEngine`.

Responsibilities:

- query `WorkspaceTypeIndex`
- validate candidates through Javac when needed
- apply package accessibility and import-fit checks
- merge type-index results with Javac simple-name candidates
- preserve existing ranking and result limits

Expected outcome:

- import and type-reference completion behavior becomes easier to reason about
- invalid type-index candidates and sorting improvements get a single policy location

### Slice 5: Member-access candidate policy

Extract member-access candidate generation and ranking from `CompletionEngine`.

Responsibilities:

- instance receiver candidates
- static member candidates
- generic receiver substitution through `types.asMemberOf()`
- inaccessible, synthetic, duplicate, and irrelevant item filtering
- member result sorting

Expected outcome:

- member chains, stream chains, `collect`, generic receivers, and static import completion gaps
  become easier to isolate

### Slice 6: Statement edit post-processing

Extract completion post-processing that mutates item edits.

Responsibilities:

- preserve primary text edit range
- preserve additional import edits
- apply semicolon edits only when syntactically safe
- avoid cursor-placement assumptions that conflict with additional text edits

Expected outcome:

- completion edit behavior can be tested without re-entering the full completion engine
- future dev explorer probes can distinguish server edit bugs from probe simulation bugs

### Slice 7: Diagnostics publishing extraction

Depends on Slice 1 (`DocumentRegistry`) being in place first.

Extract diagnostics publishing from `WorkspaceSession` into a worker-confined collaborator.

Responsibilities:

- publish current diagnostics
- clear diagnostics on change, close, and delete
- publish only if a compile result still matches the latest open content (via `DocumentRegistry.isCurrent()`)
- keep logging at the levels defined in `AGENTS.md`

Expected outcome:

- `WorkspaceSession` loses one cohesive responsibility
- stale-result checks become easier to audit

### Slice 8: Compile scheduling extraction

Depends on Slice 7 (diagnostics publishing) being separated first.

Extract debounce, compile submission, and after-compile callbacks from `WorkspaceSession`.

**Note:** The scheduling methods (`scheduleOpenFile`, `scheduleAllOpenFiles`, etc.) all call back into
`compileAndPublish()` / `submitCompile()`.
A `CompileScheduler` would need a reference back to `WorkspaceSession` for the actual compile step,
creating a back-dependency.
This slice has lower ROI than the others and should only proceed if the back-dependency can be
cleanly resolved.

Responsibilities:

- cancel and schedule file compiles
- route open, fast, and full compile modes
- run after-compile hooks on the server worker
- preserve module-worker boundaries

---

## 6. Deferred or Opportunistic Work

Move `deleteClassOutputs()` out of `WorkspaceSession` into a filesystem utility class when editing
nearby files.
Do not create a standalone cleanup.

Extract the references feature (`referencesFuture()` + inline `searchFutures()`, ~120 lines) from
`WorkspaceSession` when the references feature is next touched.

Normalize the `memberAccessSemanticContext()` overload that bypasses `SemanticCompletionContext.from()`
when editing nearby completion code.

Add a short class comment to `ExternalCompiler`, or rename it to `ExternalSourceCompiler` during a
nearby module-package refactor.

Do not rename `WorkspaceSession` unless its responsibilities have first been reduced.
The current name is defensible while it owns the worker-confined workspace session.

Do not split `SyncMojo` only for naming consistency.
The Maven plugin is not the current maintainability bottleneck.

---

## 7. Verification

Each refactoring slice should run:

```bash
mvn spotless:apply
mvn test -pl lathe-server
```

When completion behavior is touched,
also run targeted explorer probes against `../helidon` and `../dropwizard` for the contexts affected
by the slice.

For slices that only move code,
the minimum acceptable verification is the focused server test package plus Spotless.
Broader Maven verification can be reserved for slices touching module loading,
workspace manifests,
or Maven plugin code.
