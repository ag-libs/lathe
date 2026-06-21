# Lathe — Current Status

This document records the implemented baseline and known user-visible gaps.
The [roadmap](roadmap.md) defines milestone scope; the [design index](design-index.md) links detailed designs.

Status last reviewed: 2026-06-21.

## Release State

Lathe is at the M1 Internal Preview stage.
It must be built from source and is supported for the Neovim workflow only.
Maven Central publication is planned for M3.

## Build and Workspace Lifecycle

| Capability | Status | Notes |
|---|---|---|
| Compiler parameter capture | Implemented | Plexus compiler shim delegates to javac and writes JSON params. |
| Maven lifecycle integration | Implemented | `lathe:init` and `lathe:sync` have default lifecycle phases. |
| Reactor output mirroring | Implemented | Classes, test classes, and generated sources are mirrored under `.lathe/`. |
| Dependency/JDK source sync | Implemented | Sources are extracted under `~/.cache/lathe/`. |
| Type-index shards | Implemented | Dependency, JDK, and reactor type candidates are available. |
| Workspace manifest | Implemented | Server version, source roots, type indexes, and POM fingerprints are recorded. |
| Server launcher installation | Implemented | Maven installs versioned launchers and updates the `current` symlink. |
| POM staleness detection | Implemented | Neovim receives a sync prompt after Maven project changes. |
| Inheritance index | Implemented | Dependency, JDK, and reactor entries include direct supertypes in immutable snapshots. |
| Maven Central distribution | M3 planned | Current setup requires a source build. |

## LSP Capability Matrix

| Area | Status | Current behavior and gaps |
|---|---|---|
| Diagnostics | Implemented | Fast change diagnostics, full save diagnostics, and unused private/local hints. Duplicate `cant.resolve` errors on the same line are deduplicated; unused-declaration scan is suppressed when compilation has errors. |
| Hover | Implemented | Includes source-backed Javadoc rendering. |
| Definition | Implemented | Supports reactor and extracted dependency/JDK sources where available. |
| Completion | Implemented with M2 gaps | Member, type, import, constructor, lambda, argument, keyword, and typed-slot completion. Method references and generic-bound receivers remain. |
| Completion presentation | Implemented | Label details, generic display, receiver substitution, documentation, and import edits. |
| Signature help | Implemented | Overloads, active parameters, constructors, parameter names, and Javadoc. |
| Find References | Implemented with gaps | Exact same-file, module, and reactor search. External-source scope and fail-fast gaps remain. |
| Implementation | Implemented | Type implementations use indexed transitive subtypes; method implementations are reactor-only and javac-validated. |
| Type hierarchy | Implemented | Prepare, direct supertypes, and direct subtypes cover source-backed reactor, dependency, and JDK types. |
| Call hierarchy | M2 planned | Incoming and outgoing calls are not implemented. |
| Workspace symbols | Implemented | Type-name lookup uses `WorkspaceTypeIndex`. |
| Document symbols | Implemented | File outline support is available. |
| Folding ranges | Implemented | Java structural folding is available. |
| Semantic tokens | Partially implemented | Static/deprecated members, enum constants, type parameters, and annotations are covered. Class and import highlighting remain planned for M2. |
| Full-document formatting | Implemented | google-java-format also reorders and removes imports. |
| On-type formatting | M2 planned | Stub is not advertised; conservative indentation remains planned. |
| Code actions | Implemented with M1 gaps | Missing imports, add throws, try/catch wrapping, and variable declaration work. Missing-method stubs and new-type freshness remain. |
| Rename | M2 planned | Existing reference identity and roles provide part of the foundation. |
| Inlay hints | M2 planned | Not implemented. |
| Run/test/debug | Post-M3 | No execution or DAP surface is advertised. |

## Editor Support

| Editor | Status |
|---|---|
| Neovim | Current and supported target; distributable plugin is in `neovim/`. |
| VS Code | Post-M3; no supported extension or full semantic-token parity. |
| Other LSP clients | May work, but are not qualified or supported before their roadmap scope is defined. |

## Implemented Architecture

- JSON schemas in `lathe-core` define compiler params and workspace state.
- `lathe-worker` owns mutable workspace state and client publication.
- One module worker owns each javac-backed `SourceAnalysisSession`.
- LSP4J threads capture immutable inputs and enqueue work.
- `DocumentRegistry` owns open-document generations and stale-result validation.
- `DiagnosticPublisher` owns diagnostic publication and semantic-token refresh requests.
- `ReferenceCandidateIndex` maps Java identifier tokens to source files for reference search.
- `WorkspaceTypeIndex` merges dependency/JDK shards with reactor output entries for type discovery.
- `WorkspaceTypeIndex` also provides immutable direct-supertype, direct-subtype, and transitive-subtype queries.
- External sources use standard read-only `file://` files under the Lathe cache.

See [lathe-server-data-flow-recipe.md](done/lathe-server-data-flow-recipe.md) for the threading and data-flow recipe.

## Implemented Feature Highlights

- Completion contexts, typed-slot filtering, type-index discovery, import insertion, and JDT-style presentation.
- Exact javac-backed references with scope tightening and indexed candidate discovery.
- Missing-import, add-throws, try/catch, and variable-declaration quick fixes.
- Rich AST-backed Markdown Javadoc for hover, completion, and signature help.
- Workspace/document symbols, folding ranges, formatting, import optimization, and unused-code diagnostics.
- Maven-managed server distribution, unified JDK cache keys, POM staleness prompts, and packaged Neovim setup.
- Consolidated compiler and filesystem test fixtures plus the Maven invoker verification module.

Detailed implementation designs and historical decisions are indexed under
[Completed Designs](design-index.md#completed-designs).

## Known M1 Blockers

- Workspace fan-out boundaries still convert some unexpected failures to empty successful results.
- Synchronous whole-index construction can block `ServerEventLoop` on large workspaces.
- `MissingMethodImplProvider` is not implemented.
- New reactor types may require Maven sync before missing-import actions discover them.
- Some asynchronous tests still use timing-based synchronization.
