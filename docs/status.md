# Lathe — Current Status

This document records the implemented baseline and known user-visible gaps.
The [roadmap](roadmap.md) defines milestone scope; the [design index](design-index.md) links detailed designs.

## Release State

Lathe is in the M2 Neovim Public Beta stage, published to Maven Central.
It installs via the `lathe-maven-extension` build extension and is supported for the Neovim workflow only.
An MCP server (`lathe-mcp-server`) additionally exposes the same engine to AI coding agents; see [AI Agent Integration](#ai-agent-integration-mcp) below.
The tag-driven release pipeline (CI GPG signing + publish; see [RELEASING.md](../RELEASING.md)) is in use. A stable GA release follows after beta feedback.

## Build and Workspace Lifecycle

| Capability | Status | Notes |
|---|---|---|
| Compiler parameter capture | Implemented | Plexus compiler shim delegates to javac and writes JSON params. |
| Maven lifecycle integration | Implemented | `lathe:init` and `lathe:sync` have default lifecycle phases. Both run only when the build is rooted at the true multi-module root (`getTopLevelProject().getBasedir()` == the request's multi-module project directory), so a `mvn -pl <module>` build — whose reactor top-level shifts to the selected module — no longer drops a stray `.lathe` into a submodule (which would mis-root the editor). Single-module builds from their root still run. |
| Automatic build wiring | Implemented | A Maven core extension (`.mvn/extensions.xml`) injects the compiler shim, `init`/`sync` goals, and the capture dependency into the effective model in memory — no `pom.xml` edits. Manual POM setup is also supported. |
| Reactor output mirroring | Implemented | Classes, test classes, and generated sources are mirrored under `.lathe/`. |
| Dependency/JDK source sync | Implemented | Sources are extracted under `~/.cache/lathe/`. |
| Type-index shards | Implemented | Dependency, JDK, and reactor type candidates are available. |
| Workspace manifest | Implemented | Server version, source roots, type indexes, and POM fingerprints are recorded. |
| Server launcher installation | Implemented | Maven installs versioned launchers and updates the `current` symlink. |
| POM staleness detection | Implemented | Neovim receives a sync prompt after a POM / project-structure change (or a bulk, branch-switch-scale source change). |
| In-process workspace sync | Implemented | Sources changed outside the editor (a git pull, branch switch, or agent) are recompiled in-process into the `.lathe/` mirror on the idle tick — the changed files in dependency order, deletions removing their mirrored classes, plus a refresh of open dependents — no Maven round trip. |
| Server-exit surfacing & manual start | Implemented | Neovim notifies on an unexpected server exit (pointing at the LSP log); `:LatheStart` starts the server for a directory with no Java file open. |
| Inheritance index | Implemented | Dependency, JDK, and reactor entries include direct supertypes in immutable snapshots. |
| Maven Central distribution | Published | Tag-driven CI (GPG signing + `central-publishing-maven-plugin`), `versions:set` stamping, `release.sh`, and `RELEASING.md` are in place. Releases are live on Maven Central. |

## LSP Capability Matrix

| Area | Status | Current behavior and gaps |
|---|---|---|
| Diagnostics | Implemented | Fast change diagnostics, full save diagnostics, and unused private/local hints. Duplicate `cant.resolve` errors on the same line are deduplicated; unused-declaration scan is suppressed when compilation has errors. The unnamed variable `_` (JEP 456) is excluded from unused-declaration hints (EG-051, resolved). |
| Hover | Implemented | Includes source-backed Javadoc rendering. |
| Definition | Implemented | Supports reactor and extracted dependency/JDK sources where available. An unresolved target returns no result instead of jumping to the file top (EG-049, resolved); go-to-definition on a record accessor lands on the component in the record header (EG-047, resolved); go-to-definition/hover on an annotation-processor–generated type (a record's `@Builder`) resolves to the fresh `.lathe` generated-sources mirror the server regenerates on save, not the stale Maven `target/` copy (EG-052, resolved). |
| Declaration | Implemented | `textDocument/declaration` navigates an overriding method (at its declaration site or a call site) to its root contract method in the superclass or interface; falls back to `definition` for non-overriding symbols. |
| Completion | Implemented | Member, type, import, constructor, lambda, argument, keyword, typed-slot, and declaration-name completion. Type-name candidates are ranked by reactor usage frequency — the document frequency of each type across reactor classes, read from the constant pool — so commonly-used types surface first without package special-casing, project-specifically (slf4j over `java.util.logging`); package tiers survive only as the cold-start tie-break for equal counts (CQ-0059, resolved). Includes array-typed receivers (`length`, `clone()`, inherited `Object` members — CQ-0053, resolved). Declaration-name completion suggests variable/field/parameter/catch names from the declared type (identifier, qualified, generic, and generic-element — `List<User>` → `users`), with acronym-aware naming (`IOException` → `ioException`) and the `e`/`ex`/`exception` idiom for catch, via a shared `VariableNameSuggester` reused by Extract Variable; type parameters, enhanced-for iterable singularization, and `static final` SCREAMING_SNAKE are non-goals. Method references and generic-bound receivers are deferred to the backlog. |
| Completion presentation | Implemented | Label details, generic display, receiver substitution, documentation, and import edits. |
| Signature help | Implemented | Overloads, active parameters, constructors, parameter names, and Javadoc. |
| Find References | Implemented | Exact same-file, module, and reactor search with transient closed-file analysis, process-wide compilation admission, work-done progress, optional cancellation, and fatal `Error` handling. Candidate-planning gaps for `var`/chained receivers, same-package generated builders, constructors, and compact-constructor component uses are resolved (FR-011/012/013/014). Generated-code highlight range is hardened (FR-010, receiver-anchored range lookup). Invoking references from an external (dependency/JDK) symbol returns reactor results (search scope tops out at reactor modules); returning references located *inside* external sources (source browsing) is deliberately deferred, not an active gap — see `lathe-find-references.md`. |
| Document highlight | Implemented | `textDocument/documentHighlight` reuses the single-file reference search to mark read/write/other uses of the symbol under the cursor within the open buffer; no workspace/candidate index is touched. In Neovim it is the standard `vim.lsp.buf.document_highlight()` endpoint — no custom command. |
| Implementation | Implemented | Type implementations use indexed transitive subtypes; method implementations are reactor-only and javac-validated. |
| Type hierarchy | Implemented | The standard `typeHierarchy/*` endpoints (prepare, direct supertypes, direct subtypes) cover source-backed reactor, dependency, and JDK types. `:LatheTypeHierarchy` (server command `lathe.typeHierarchy`) returns the full transitive hierarchy in both directions at once — every ancestor and descendant of the type under the cursor, each row tagged by its relation to the anchor — in a single Telescope picker (NV-6, resolved). |
| Call hierarchy | Implemented | `prepareCallHierarchy`, `incomingCalls`, and `outgoingCalls`. Incoming calls reuse the reference candidate pipeline with work-done progress and cancellation. |
| Workspace symbols | Implemented | Type-name lookup uses `WorkspaceTypeIndex`. |
| Document symbols | Implemented | File outline support is available. |
| Folding ranges | Implemented | Java structural folding is available. |
| Semantic tokens | Implemented | Dense identifier-level classification: distinct `class`/`interface`/`enum` types (declarations, references, and import type names), `variable` vs `property` (distinct local-variable-vs-field colouring) and `parameter`, all methods and fields (modifiers preserved), plus enum constants, type parameters, annotations, and package/module names. Reference tokens are source-verified (synthetic annotation `value` element rejected) and package qualifiers are not mislabeled as types. `readonly`/`abstract`/visibility modifiers and distinct `recordComponent`/`annotationMember` types are deferred (low value). |
| Full-document formatting | Implemented (opt-in) | Advertised only when the client sets `formatter = "google"`; off by default so non-GJF projects are not rewritten. google-java-format also reorders and removes imports. Indentation is a separate client-side profile (`indent_style`). See `lathe-formatting-profiles.md`. |
| On-type formatting | Deferred | Stub; capability not advertised. Deferred feature work in `lathe-formatting-profiles.md`, depending on range-aware formatting — low priority and mainly relevant to a later VS Code integration, not the Neovim focus. |
| Code actions | Implemented | Missing imports, add-throws, try/catch wrapping, variable declaration, missing-method stubs, a request-driven "replace `var` with the inferred type" refactor (CA-5, resolved), and a request-driven "extract variable" refactor (single-occurrence plus a replace-all-occurrences action; `var` variant tracked in CA-6), a request-driven "extract constant" refactor (compile-time constant expressions → a `private static final` field, single plus replace-all-in-class), and a request-driven "extract field" refactor (expressions that read no locals/parameters, in a non-static context of a class or enum → a `private final` instance field placed after the last field, single plus replace-all-in-class with a value-stability gate) all work. Each extract refactor carries a distinct `refactor.extract.{variable,constant,field}` sub-kind so an editor can bind each to its own shortcut. A request-driven "add constructor parameter" refactor turns a `final`, non-static, initializer-less instance field of a class or enum into a constructor parameter — appending a `final` parameter and a `this.field = field;` binding to each constructor, forwarding the argument through `this(...)`-delegating constructors, and generating a constructor when the class declares none (CA-10). Missing-import actions now offer reactor types from a prior sync or from an open, already-compiled file (CA-4). Types created or renamed in a closed file are recompiled into the mirror in-process on the idle tick, so they resolve without a sync (WS-1). A whole-file "add missing imports" command (`:LatheMissingImports` / server `lathe.missingImports`, also the "Add missing imports…" code action) resolves every unresolved type in one pass off the live buffer — unambiguous names auto-added, ambiguous ones prompted (CA-7, add-missing slice). |
| Rename | Implemented | `textDocument/prepareRename` + `rename` for locals, parameters, exception/lambda parameters, type parameters, fields, methods (incl. override family), record components, and constructors (redirect to the enclosing type). Cross-module for public/protected members, matched by the reference pipeline's declaration identity; emitted as one atomic `WorkspaceEdit`. Only the new-name validity is checked (permissive; collisions surface as diagnostics). Public top-level type file rename and enum-constant rename are deferred (refused). Neovim drives it via the built-in `grn` (0.11+). See `lathe-rename.md`. |
| Inlay hints | Deferred (backlog) | Not implemented. |
| Run/test | Implemented (Neovim) | neotest adapter: discovery, run at every level, live-streamed output, inline failure diagnostics, cancel/stop, re-run the first failing test (`run_first_failed`, self-shrinking, `<leader>tF`), a one-line completion toast (counts + elapsed, INFO/WARN), and the replay command shown as the first output line. Replays from captured `.lathe/` bytecode, no Maven. Runs a `main()` at any scope, including one located in test sources of a modular module (routed through the module's captured test launch). Named run configurations — auto-applied `defaults` baselines plus name-keyed `configs` that pin a target — are selectable by name (`:LatheRun`/`:LatheDebug {name}`, server completion) and saved from the cursor (`:LatheRunSave[!]`); the active config shows on the console header and the run log. |
| Debug | Implemented (Neovim) | In-process DAP adapter (Microsoft java-debug, attach-only) over JDWP to a suspended replay; `lathe.debug.test`/`lathe.debug.main` (test, main, and test-scope main) and an `nvim-dap` client (`:LatheDebug`). Breakpoints, stepping, inspection, conditional breakpoints, expression evaluation for watches/hover/console (reads, method/constructor invocation, `String` concat, force-loading cold classes, and object-scoped evaluation for collection/map logical views), and debug-console code completion. Debugging a test routes through the neotest `dap` strategy, so the gutter/summary update live and the shared docked console + terminal pass/fail behave like a run (the neotest summary `d`/`D` drive it too). Gaps: assignment (`setVariable`) and array creation. |
| New-type scaffold | Implemented (Neovim) | `:LatheNew` creates a class/interface/record/enum through the server: pick the kind, then narrow module → package (or `＋ New package…`), name it; the server resolves placement, renders the skeleton, and returns the caret while the thin client only writes/opens the file. Works from any Java file (current package preselected) or with nothing open (cold start walks module → package); main/test comes from the chosen package, asked only when a new package's module has both roots. Server commands: `lathe.createType`, `lathe.modules`, `lathe.packages`, `lathe.resolveContext`. |

## Editor Support

| Editor | Status |
|---|---|
| Neovim | Current and supported target; distributable plugin is in `neovim/`. |
| VS Code | Backlog; no supported extension or full semantic-token parity. |
| Other LSP clients | May work, but are not qualified or supported before their roadmap scope is defined. |

## AI Agent Integration (MCP)

`lathe-mcp-server` exposes the same build-derived engine to AI coding agents over the Model Context Protocol (stdio), calling the engine in-process through the `LatheEngine` facade — no second JVM, behaviour identical to the LSP path by construction. One process per agent session; the reactor is resolved from the working directory; a populated `.lathe/` is required (the server refuses without it). The launcher (`lathe-mcp-launcher.sh`) is installed by `lathe:sync` alongside the editor launcher. See [ai-agents.md](guide/ai-agents.md).

| Tool | Status | Notes |
|---|---|---|
| `get_diagnostics` | Implemented | Single-file compiler diagnostics on the captured build classpath — no Maven. |
| `get_definition` | Implemented | Resolves into reactor, dependency, JDK, and generated sources. |
| `find_references` | Implemented | Reactor-wide, javac-accurate; resolves overloads/inheritance. |
| `find_implementations` | Implemented | Interface implementers / method overrides across the reactor. |
| `call_hierarchy` | Implemented | Incoming (callers) and outgoing (callees) calls across modules. |
| `search_symbols` | Implemented | Type lookup by name (CamelHumps) across reactor, dependencies, and JDK. |
| `describe_symbol` | Implemented | Rendered signature, type, and Javadoc (hover) as Markdown. |
| `rename_symbol` | Implemented | Reactor-wide rename applied to disk; refuses to touch non-reactor files. |
| `run_test` | Implemented | Replays a test method/class/package from captured bytecode — no reactor build. |
| `verify_change` | Implemented | After edits, recompiles the changed set in-process (auto-detected from disk, or scoped by `files`), reports new diagnostics per module, and emits the scoped `mvn -pl … -amd` command for the cross-module remainder — no Maven run. |
| `analyze_change` | Implemented | Pre-edit impact of a symbol: override/implementation family, production vs test reference counts, affected reactor modules, and relevant test classes. Read-only. |

Cross-cutting: every located result carries a source snippet and an origin (reactor / dependency / JDK / generated); results append a `Stale:` advisory when a module's source is newer than its compiled classes; task→tool routing instructions are served at connection time; and each tool call is logged (`[tool] <name> <args> <ms> <outcome>`) to stderr for usage analysis.

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
- Server-driven new-type scaffold (`:LatheNew`): the server owns module/source-root/package resolution, skeleton
  rendering, and caret placement; the Neovim client is a thin picker shell that writes the returned file.
- Maven-managed server distribution, unified JDK cache keys, POM staleness prompts, and packaged Neovim setup.
- Consolidated compiler and filesystem test fixtures plus the Maven invoker verification module.

Detailed implementation designs and historical decisions are indexed under
[Completed Designs](design-index.md#completed-designs).

## Known Blockers

- None outstanding. CA-4 (missing-import actions for not-yet-synced reactor types) is resolved for
  the common cases — types from a prior sync and types declared in an open, already-compiled file.
- Sources changed outside the editor (a git pull, branch switch, or agent) are recompiled in-process
  into the `.lathe/` mirror on the idle tick — the changed files in dependency order, deletions
  removing their mirrored classes, plus a refresh of open dependents (WS-1). Types created or renamed
  in closed files appear without a sync; the Maven prompt now fires only for a POM/structure change or
  a bulk (branch-switch-scale) change.
