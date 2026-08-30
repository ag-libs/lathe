# Lathe — Current Status

This document records the implemented baseline and known user-visible gaps.
The [roadmap](roadmap.md) defines milestone scope; the [design index](design-index.md) links detailed designs.

Status last reviewed: 2026-08-29.

## Release State

Lathe is at the M1 Internal Preview stage.
It must be built from source and is supported for the Neovim workflow only.
Maven Central publication is in the Backlog (scheduled after public-beta feedback).

## Build and Workspace Lifecycle

| Capability | Status | Notes |
|---|---|---|
| Compiler parameter capture | Implemented | Plexus compiler shim delegates to javac and writes JSON params. |
| Maven lifecycle integration | Implemented | `lathe:init` and `lathe:sync` have default lifecycle phases. |
| Automatic build wiring | Implemented | A Maven core extension (`.mvn/extensions.xml`) injects the compiler shim, `init`/`sync` goals, and the capture dependency into the effective model in memory — no `pom.xml` edits. Manual POM setup is also supported. |
| Reactor output mirroring | Implemented | Classes, test classes, and generated sources are mirrored under `.lathe/`. |
| Dependency/JDK source sync | Implemented | Sources are extracted under `~/.cache/lathe/`. |
| Type-index shards | Implemented | Dependency, JDK, and reactor type candidates are available. |
| Workspace manifest | Implemented | Server version, source roots, type indexes, and POM fingerprints are recorded. |
| Server launcher installation | Implemented | Maven installs versioned launchers and updates the `current` symlink. |
| POM staleness detection | Implemented | Neovim receives a sync prompt after Maven project changes. |
| Inheritance index | Implemented | Dependency, JDK, and reactor entries include direct supertypes in immutable snapshots. |
| Maven Central distribution | Backlog | Current setup requires a source build. |

## LSP Capability Matrix

| Area | Status | Current behavior and gaps |
|---|---|---|
| Diagnostics | Implemented | Fast change diagnostics, full save diagnostics, and unused private/local hints. Duplicate `cant.resolve` errors on the same line are deduplicated; unused-declaration scan is suppressed when compilation has errors. |
| Hover | Implemented | Includes source-backed Javadoc rendering. |
| Definition | Implemented with M2 gaps | Supports reactor and extracted dependency/JDK sources where available. Go-to-definition on a record accessor lands on the file rather than the component (EG-047), and an unresolved target falls back to the file top instead of returning no result (EG-049). |
| Declaration | Implemented | `textDocument/declaration` navigates an overriding method (at its declaration site or a call site) to its root contract method in the superclass or interface; falls back to `definition` for non-overriding symbols. |
| Completion | Implemented with M2 gaps | Member, type, import, constructor, lambda, argument, keyword, and typed-slot completion. Array-typed-receiver member completion is the accepted M2 gap (CQ-0053); method references and generic-bound receivers are deferred. |
| Completion presentation | Implemented | Label details, generic display, receiver substitution, documentation, and import edits. |
| Signature help | Implemented | Overloads, active parameters, constructors, parameter names, and Javadoc. |
| Find References | Implemented | Exact same-file, module, and reactor search with transient closed-file analysis, process-wide compilation admission, work-done progress, optional cancellation, and fatal `Error` handling. Candidate-planning gaps for `var`/chained receivers, same-package generated builders, constructors, and compact-constructor component uses are resolved (FR-011/012/013/014). Generated-code highlight range is hardened (FR-010, receiver-anchored range lookup). Invoking references from an external (dependency/JDK) symbol returns reactor results (search scope tops out at reactor modules); returning references located *inside* external sources (source browsing) is deliberately deferred, not an active gap — see `lathe-find-references.md`. |
| Implementation | Implemented | Type implementations use indexed transitive subtypes; method implementations are reactor-only and javac-validated. |
| Type hierarchy | Implemented | Prepare, direct supertypes, and direct subtypes cover source-backed reactor, dependency, and JDK types. |
| Call hierarchy | Implemented | `prepareCallHierarchy`, `incomingCalls`, and `outgoingCalls`. Incoming calls reuse the reference candidate pipeline with work-done progress and cancellation. |
| Workspace symbols | Implemented | Type-name lookup uses `WorkspaceTypeIndex`. |
| Document symbols | Implemented | File outline support is available. |
| Folding ranges | Implemented | Java structural folding is available. |
| Semantic tokens | Partially implemented | Static/deprecated members, enum constants, type parameters, and annotations are covered. Class, import, and local-variable-vs-field highlighting are deferred to the backlog. |
| Full-document formatting | Implemented (opt-in) | Advertised only when the client sets `formatter = "google"`; off by default so non-GJF projects are not rewritten. google-java-format also reorders and removes imports. Indentation is a separate client-side profile (`indent_style`). See `lathe-formatting-profiles.md`. |
| On-type formatting | Deferred | Stub; capability not advertised. Deferred feature work in `lathe-formatting-profiles.md`, depending on range-aware formatting — low priority and mainly relevant to a later VS Code integration, not the Neovim focus. |
| Code actions | Implemented | Missing imports, add-throws, try/catch wrapping, variable declaration, and missing-method stubs all work. Missing-import actions now offer reactor types from a prior sync or from an open, already-compiled file (CA-4). Types created or renamed in a closed file await a sync — see the source/branch-switch staleness gap WS-1. |
| Rename | M2 planned | Existing reference identity and roles provide part of the foundation. |
| Inlay hints | Deferred (backlog) | Not implemented. |
| Run/test | Implemented (Neovim) | neotest adapter: discovery, run at every level, live-streamed output, inline failure diagnostics, cancel/stop, and the replay command shown as the first output line. Replays from captured `.lathe/` bytecode, no Maven. Runs a `main()` at any scope, including one located in test sources of a modular module (routed through the module's captured test launch). |
| Debug | Implemented (Neovim) | In-process DAP adapter (Microsoft java-debug, attach-only) over JDWP to a suspended replay; `lathe.debug.test`/`lathe.debug.main` (test, main, and test-scope main) and an `nvim-dap` client (`:LatheDebug`). Breakpoints, stepping, inspection, conditional breakpoints, expression evaluation for watches/hover/console (reads, method/constructor invocation, `String` concat, force-loading cold classes, and object-scoped evaluation for collection/map logical views), and debug-console code completion. Gaps: assignment (`setVariable`) and array creation. |

## Editor Support

| Editor | Status |
|---|---|
| Neovim | Current and supported target; distributable plugin is in `neovim/`. |
| VS Code | Backlog; no supported extension or full semantic-token parity. |
| Other LSP clients | May work, but are not qualified or supported before their roadmap scope is defined. |

## Implemented Architecture

- JSON schemas in `lathe-core` define compiler params and workspace state.
- `WorkspaceSession`, confined to the single server worker thread (`lathe-worker`), owns mutable workspace state and client publication.
- One module worker owns each javac-backed `SourceAnalysisSession`.
- LSP4J threads capture immutable inputs and enqueue work.
- `DocumentRegistry` owns open-document generations and stale-result validation.
- `DiagnosticPublisher` owns diagnostic publication and semantic-token refresh requests.
- `ReferenceCandidateIndex` maps Java identifier tokens to source files for reference search.
- `CompilationAdmission` bounds concurrent javac tasks across reference search and interactive compilation.
- `WorkspaceTypeIndex` merges dependency/JDK shards with reactor output entries for type discovery.
- `WorkspaceTypeIndex` also provides immutable direct-supertype, direct-subtype, and transitive-subtype queries.
- External sources use standard read-only `file://` files under the Lathe cache.

See [lathe-server-data-flow-recipe.md](done/lathe-server-data-flow-recipe.md) for the threading and data-flow recipe.

## Implemented Feature Highlights

- Completion contexts, typed-slot filtering, type-index discovery, import insertion, and JDT-style presentation.
- Exact javac-backed references with scope tightening, indexed candidate discovery, bounded transient closed-file
  analysis, work-done progress, and optional cancellation.
- Missing-import, add-throws, try/catch wrapping, variable declaration, and missing-method quick fixes.
- Rich AST-backed Markdown Javadoc for hover, completion, and signature help.
- Workspace/document symbols, folding ranges, formatting, import optimization, and unused-code diagnostics.
- Maven-managed server distribution, unified JDK cache keys, POM staleness prompts, and packaged Neovim setup.
- Consolidated compiler and filesystem test fixtures plus the Maven invoker verification module.

Detailed implementation designs and historical decisions are indexed under
[Completed Designs](design-index.md#completed-designs).

## Known M1 Blockers

- None outstanding. CA-4 (missing-import actions for not-yet-synced reactor types) is resolved for
  the common cases — types from a prior sync and types declared in an open, already-compiled file.
- Known limitation, not M1-scoped: the reactor mirror and type index go stale after a source change
  or branch switch until the next `mvn process-test-classes`, so types created or renamed in closed
  files are not discovered until a sync. Tracked as WS-1.
