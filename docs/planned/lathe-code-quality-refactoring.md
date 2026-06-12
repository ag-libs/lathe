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

### Completion engine size and coupling

`CompletionEngine` is the largest current server class.
It owns request classification dispatch,
Javac candidate collection,
type-index candidate merging,
expected-value filtering,
completion item presentation glue,
and statement edit post-processing.

`TypeResolver` is also large.
It combines expected-value inference,
receiver resolution,
argument-position inference,
lambda return inference,
and assorted AST fallback logic.

These classes are the best first refactoring target because recent exploratory work keeps finding subtle completion behavior gaps.
Reducing coupling here directly improves the feature area with the highest active churn.

### WorkspaceSession breadth

`WorkspaceSession` is still broad,
but its broadness is partly intentional.
The server design uses one `lathe-worker` thread to own mutable workspace state,
open-document snapshots,
routing,
stale-result checks,
and client publishing.

`LatheTextDocumentService` already performs LSP wire dispatch.
Therefore the useful split is not a generic `LanguageFeatureHandler`.
The useful split is to extract cohesive services that preserve worker confinement:

- diagnostics publishing
- compile scheduling and stale-result publishing
- workspace refresh / watcher handling
- reference planning and candidate-index coordination

`WorkspaceSession` should remain the event-loop-owned coordinator.

### Naming clarity

`ExternalCompiler` really does compile external source,
but its purpose is external source analysis for hover,
definition,
references,
and diagnostics.
The name is acceptable,
but it may benefit from either a short class comment or a later rename to `ExternalSourceCompiler`.

Trivial `toPath()` wrappers around `LatheUri.toPath()` exist in a few server classes.
They are harmless,
but removing them opportunistically reduces indirection.

---

## 4. Refactoring Principles

Use behavior-preserving slices.
Each slice should compile and pass existing tests before the next slice starts.

Prefer extracting named policy objects over utility bags.
For example,
`ExpectedValueResolver` is useful if it owns expected-slot inference policy.
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

### Slice 1: Expected-value completion resolution

Extract expected-value inference from `TypeResolver` into a focused package-private class,
for example `ExpectedValueResolver`.

Responsibilities:

- initializer expected type
- assignment RHS expected type
- method and constructor argument expected type
- lambda body expected return type
- switch case selector expected value

`TypeResolver` should keep receiver and symbol-resolution helpers until later slices prove a better boundary.

Expected outcome:

- completion typed-slot behavior becomes easier to test and extend
- future generic,
  stream,
  lambda,
  and assignment completion gaps have one obvious policy location

### Slice 2: Type-index completion merging and validation

Extract type-index query,
validation,
deduplication,
and simple-name merge policy from `CompletionEngine`.

Responsibilities:

- query `WorkspaceTypeIndex`
- validate candidates through Javac when needed
- apply package accessibility and import-fit checks
- merge type-index results with Javac simple-name candidates
- preserve existing ranking and result limits

Expected outcome:

- import and type-reference completion behavior becomes easier to reason about
- invalid type-index candidates and sorting improvements get a single policy location

### Slice 3: Member-access candidate policy

Extract member-access candidate generation and ranking from `CompletionEngine`.

Responsibilities:

- instance receiver candidates
- static member candidates
- generic receiver substitution through `types.asMemberOf()`
- inaccessible,
  synthetic,
  duplicate,
  and irrelevant item filtering
- member result sorting

Expected outcome:

- member chains,
  stream chains,
  `collect`,
  generic receivers,
  and static import completion gaps become easier to isolate

### Slice 4: Statement edit post-processing

Extract completion post-processing that mutates item edits,
including semicolon insertion and replacement-range application where practical.

Responsibilities:

- preserve primary text edit range
- preserve additional import edits
- apply semicolon edits only when syntactically safe
- avoid cursor-placement assumptions that conflict with additional text edits

Expected outcome:

- completion edit behavior can be tested without re-entering the full completion engine
- future dev explorer probes can distinguish server edit bugs from probe simulation bugs

### Slice 5: Diagnostics publishing extraction

Extract diagnostics publishing from `WorkspaceSession` into a worker-confined collaborator.

Responsibilities:

- publish current diagnostics
- clear diagnostics on change,
  close,
  and delete
- publish only if a compile result still matches the latest open content
- keep logging at the levels defined in `AGENTS.md`

Expected outcome:

- `WorkspaceSession` loses one cohesive responsibility
- stale-result checks become easier to audit

### Slice 6: Compile scheduling extraction

Extract debounce,
compile submission,
and after-compile callbacks from `WorkspaceSession` only after diagnostics publishing is separated.

Responsibilities:

- cancel and schedule file compiles
- route open,
  fast,
  and full compile modes
- run after-compile hooks on the server worker
- preserve module-worker boundaries

Expected outcome:

- open/change/save flows become smaller without breaking the threading model

---

## 6. Deferred or Opportunistic Work

Remove trivial `toPath()` wrappers when editing nearby files.
Do not create a standalone cleanup unless no functional work is in flight.

Add a short class comment to `ExternalCompiler`,
or rename it to `ExternalSourceCompiler` during a nearby module-package refactor.

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
also run targeted explorer probes against `../helidon` and `../dropwizard` for the contexts affected by the slice.

For slices that only move code,
the minimum acceptable verification is the focused server test package plus Spotless.
Broader Maven verification can be reserved for slices touching module loading,
workspace manifests,
or Maven plugin code.
