# Lathe — Roadmap

## Current Position

Lathe is externally installable.
The compiler shim, Maven plugin, workspace manifest, dependency/JDK sync, server-side manifest loading,
server launcher distribution, and dev tooling are all in place.

Current lifecycle shape:

- `lathe:init` creates `.lathe/` during `initialize`.
- The compiler shim writes `lsp-params-*.json` and mirrors class/generated-source outputs under `.lathe/<module>/`.
- `lathe:sync` runs during `process-test-classes`,
  resolves dependency source JARs,
  extracts dependency/JDK sources into `~/.cache/lathe/`,
  writes `.lathe/workspace.json`,
  and installs `~/.cache/lathe/servers/<version>/lathe-launcher.sh` (idempotent).
- The server reads params files and the workspace manifest,
  watches for changes,
  and compiles opened files — including external dependency/JDK sources.
- Editors launch `~/.cache/lathe/current/lathe-launcher.sh`.

Threading model: one server worker thread (`lathe-worker`) owns `WorkspaceSession`, `WorkspaceModuleRegistry`,
open-document snapshots, routing, stale-result checks, and client publishing.
One module worker thread per javac-backed `SourceAnalysisSession`.
LSP4J threads capture immutable request data and enqueue work.
Compile results cross back to `lathe-worker` before diagnostics or semantic-token refreshes are published.
Architecture is documented in [lathe-server-data-flow-recipe.md](done/lathe-server-data-flow-recipe.md).

---

## Completed

- **JSON state format** — params and workspace state moved from ad hoc property files to shared JSON schema records in `lathe-core`.
- **Workspace sync slices** — dependency and JDK source resolution, extraction, and server-side manifest loading are implemented.
- **Lifecycle binding** — both Maven goals declare default phases; user POM executions can omit `<phase>`.
- **`compileWith` simplification** — `LatheTextDocumentService.compileWith` is a small dispatcher with focused helper methods for each path.
- **Shim correctness** — lock cleanup moved to `finally`; silent javac failure surfaces as `IOException`; `LatheServer.main` acquires stdout before any logging can write to it.
- **IT verify module** — dead `verify.sh` replaced by a `verify/` JUnit submodule that runs as part of the normal invoker lifecycle; `@property@` tokens pin the plugin version.
- **Completion engine** — `SentinelInjector`, `SentinelParser`, `CompletionEngine`, `ProposalGenerator`,
  and `TypeResolver` are implemented and tested.
  Handles member access, simple name, argument position, type reference, import, static import,
  constructor call, lambda body, and variable declaration contexts.
  Keyword completion and argument-position type ranking are in place.
  Typed-slot filtering excludes candidates not assignable to the expected type across variable
  initializers, return positions, assignment RHS, method arguments (including chained receivers),
  and constructor arguments; boolean candidates follow the same gate as `true`/`false` keywords.
  Completions are suppressed when the cursor sits immediately after a complete expression with no
  new token started (`m()§`), detected via AST source positions in `SentinelParser`.
  All planned behavioural gaps are closed; Gap J (method references) is deferred.
  See [completion-design.md](done/completion-design.md) and [completion-gap-fixes.md](done/completion-gap-fixes.md).
- **Type index** — `lathe:sync` builds static type-index shards for dependency JARs and the JDK runtime image
  (`jrt:/`), writes shard paths into `workspace.json`,
  and the server loads and merges them with in-memory reactor output shards in `WorkspaceTypeIndex`.
  `CompletionEngine` queries the index for type-name prefix matches and validates candidates through
  `elements.getTypeElement()` and `elements.isAccessible()`.
  See [lathe-type-index.md](planned/lathe-type-index.md).
- **Maven-managed server distribution** — `lathe:sync` resolves `lathe-server` and all transitive runtime deps via Aether,
  renders `lathe-launcher.sh` with colon-separated `--module-path` pointing at `.m2` JAR paths,
  writes it to `~/.cache/lathe/servers/<version>/`,
  and updates the `~/.cache/lathe/current` symlink.
  `dev/nvim.sh` and `dev/lsp.py` updated to launch via the installed script.
- **Server threading model** — LSP request/notification handlers and workspace reload now route through a single server worker.
  Mutable workspace state stays on that worker,
  javac-backed compiler state is behind module workers.
  `WorkspaceSession` encapsulates worker-confined workspace state and routing;
  `LatheTextDocumentService` is a thin LSP dispatcher.
- **Stale-result guard** — `publishIfCurrent` compares the content that triggered a compile against the latest open content before publishing diagnostics,
  so rapid edits never overwrite newer results with an older compile's output.
- **`textDocument/onTypeFormatting` stub** — server advertises `onTypeFormatting` support for `\n` triggers and dispatches the LSP request;
  the handler currently returns no edits.
  Actual google-java-format indentation is deferred; see [lathe-google-indent.md](planned/lathe-google-indent.md).
- **Find references** — Exact javac-backed `textDocument/references` is implemented across all planned slices.
  Same-file, open-file, same-module disk, and transitive reactor-module search are all live.
  `ReferenceCandidateIndex` replaces per-request disk scanning with an O(1) token lookup.
  Scope tightening routes private/local symbols to the declaring file only and restricts
  package-private to the same package directory.
  `ReferenceMatch` with `ReferenceRole` provides the rename-ready internal result type.
  See [lathe-find-references.md](planned/lathe-find-references.md).
  Known gap: JDK and third-party dependency symbols are not yet restricted to open files only —
  they trigger a full reactor scan, attributing hundreds of files for common types like `String`.
  Current correctness, scope-policy, fail-fast handling, and end-to-end coverage gaps are tracked in
  [lathe-find-references-gaps.md](planned/lathe-find-references-gaps.md).
- **Completion presentation** — JDT LS-style completion rows are implemented across four slices.
  Type labels show simple names with package in `labelDetails.description`.
  Method labels are bare names with parameter list in `labelDetails.detail` and return type in `labelDetails.description`.
  The `TypeMirror` display formatter handles generic, array, wildcard, and primitive types.
  Generic receiver substitution via `types.asMemberOf()` is in place for member-access completions.
  Annotation-element completions route through `CompletionItemPresenter`.
  See [lathe-completion-presentation.md](planned/lathe-completion-presentation.md).
- **Stale-POM detection** — `WorkspaceWatcher` simplified to watch `workspace.json` and reactor POM file modification times and sizes, returning `PollResult` facts.
  `WorkspaceSession` polls every 2 seconds, displaying a `window/showMessageRequest` when POM changes are detected, protected by an event-loop-safe pending guard.
  `SyncMojo` writes reactor POM relative paths into `workspace.json` during `lathe:sync`.
  See [lathe-stale-pom-detection.md](done/lathe-stale-pom-detection.md).
- **Capability advertisement cleanup** — stopped advertising `documentRangeFormattingProvider` (stubbed to format full-document) and `documentOnTypeFormattingProvider` (stubbed to return empty edits list) to prevent incorrect editor behavior.
- **Missing-import code action** — quick-fix code actions (`window/codeAction`) for unresolved type symbols query the type index and insert the appropriate `import` statement. Reuses the existing completion import insertion range and checks. Tested with various file package/import structures and end-to-end integration tests. See [lathe-missing-import-code-action.md](done/lathe-missing-import-code-action.md).
- **Code actions infrastructure + providers** — `DiagnosticPayload` record with JSON codec, `enrichWithContext()` classification pass, `CodeActionProvider` interface, dispatcher, and four providers: `ImportQuickFixProvider` (TYPE_REF), `AddThrowsProvider` (UNREPORTED_EXCEPTION), `DeclareVariableProvider` (VARIABLE_REF assignment LHS), `TryCatchWrapProvider` (UNREPORTED_EXCEPTION in lambda/closure context).
  `AddThrowsProvider` is suppressed inside lambda and anonymous-class bodies (exception cannot escape).
  `compiler.err.does.not.override.abstract` is classified as `MISSING_METHOD_IMPL` with the enclosing class name extracted for future provider dispatch.
  `CodeActionSupport` provides enclosing-statement and path-at-position helpers.
  Gap regression tests for lambda/closure and MISSING_METHOD_IMPL classification pass. Items 10–11 deferred to post-beta. See [lathe-code-actions.md](done/lathe-code-actions.md).
- **Standard `file://` URIs for external sources** — reverted `lathe-source://` custom scheme back to standard `file://` URIs. Extracted source files in `~/.cache/lathe/` are now marked read-only (`444`) at extraction time; `deleteDir` clears write permission before deletion. `LatheUri` deleted; call sites inlined. Neovim plugin uses `BufReadPre`/`BufReadPost` path-pattern autocommands instead of a `BufReadCmd` virtual-buffer handler. See [lathe-file-uri-scheme.md](done/lathe-file-uri-scheme.md).
- **Neovim plugin packaging** — `neovim/` contains a complete distributable plugin: `lua/lathe.lua` (LSP config, format-on-save, cache autocommands), `lua/lathe/` submodules, `ftplugin/java.lua`, and `after/indent/` for indentation. Plugin launches `~/.cache/lathe/current/lathe-launcher.sh` with no client-side project model.
- **Import optimization on formatting** — full-document formatting now uses google-java-format's import-fixing formatter,
  so format-on-save can reorder imports and remove unused imports.
  See [lathe-import-optimization.md](done/lathe-import-optimization.md).
- **Pre-beta codebase cleanups** — all items in the refactoring/renaming plan completed: DRY/KISS helpers, `setLocationFromPaths` migration, walk depth limit, `var` rule enforcement, test naming and `@Nested` flattening, four conceptual renamings, and test co-location fixes (`SyncMojoTest` → `LatheFlagsTest` in `lathe-core`; `DependencySourceTest`, `DependencySourceSyncTest`, `JdkSourceResolverTest` moved to matching subpackages).
  See [lathe-refactoring-renaming.md](done/lathe-refactoring-renaming.md).
- **JDK cache key unification** — `JdkSource` carries a single `cacheKey` computed once in `JdkSourceResolver` from `$JAVA_HOME/release` (`IMPLEMENTOR_VERSION` → `IMPLEMENTOR`+`JAVA_VERSION` with legal-suffix stripping → `java.vendor.version` → `java.vendor`+`java.version`).
  Both JDK source extraction and type-index shard paths derive from the same key, eliminating the duplicated vendor/version path construction.
  See [lathe-jdk-cache-key.md](done/lathe-jdk-cache-key.md).
- **Unused code diagnostics** — `UnusedDeclarationScanner` (single-pass `TreePathScanner`) detects unused private methods, fields, and local variables.
  Private method reachability uses BFS through a private-to-private call graph, correctly handling mutual recursion and self-recursion.
  Diagnostics are emitted as `DiagnosticSeverity.Hint` + `DiagnosticTag.Unnecessary` with a range covering only the declaration name,
  which Neovim uses to fade out unused code.
  See [lathe-unused-code-diagnostics.md](done/lathe-unused-code-diagnostics.md).
- **Signature help** — `textDocument/signatureHelp` implemented with trigger characters `(` and `,`.
  `SignatureHelpResolver` locates the enclosing `MethodInvocationTree` or `NewClassTree`, discovers all
  overloads via `elements.getAllMembers`, resolves the exact overload javac chose, and computes the
  active parameter index from AST argument source positions (no raw source scanning).
  `super()`/`this()` constructor invocations are supported via constructor-kind detection in the
  `MethodInvocationTree` branch.
  Parameter names for class-file dependencies compiled without `-parameters` are resolved on demand
  from source via `SourceParser.resolveParamNames`; `SourceLocator.declarationPath` handles
  constructors correctly (`MethodTree.getName()` returns `<init>`).
  `SignatureInformation.documentation` is populated from javadoc via `JavadocLocator`, covering both
  same-file and cross-file source lookup.
  `TypeDisplayFormatter` moved to `analysis/` package for shared use.
  `dev/explore.py` gains a `sig` command.
  See [lathe-signature-help.md](done/lathe-signature-help.md).
- **Rich Javadoc rendering** — `JavadocMarkdownPrinter` replaces the `cleanDoc()` regex hack with a
  `DocTreeScanner` AST walker. HTML tags (`<b>`, `<i>`, `<code>`, `<pre>`), inline tags
  (`{@link}`, `{@code}`, `{@literal}`), HTML entities, and block tags (`@param`, `@return`,
  `@throws`, `@see`) are all converted to Markdown. `JavadocLocator` now returns
  `Optional<DocCommentTree>` so callers can select the appropriate entry point:
  hover/completion use `format()` for the full doc; `SignatureHelpResolver` uses
  `mainDescription()` for `SignatureInformation.documentation` and `paramDocs()` to populate
  per-argument `ParameterInformation.documentation`.
  See [lathe-rich-javadoc-rendering.md](done/lathe-rich-javadoc-rendering.md).
- **Workspace symbols** — `workspace/symbol` implemented via `WorkspaceSymbolResolver` querying `WorkspaceTypeIndex`.
  Returns `SymbolInformation` with file URI and position for each matching type.
  Correctly maps `TypeKind` to `SymbolKind` (Interface, Enum, Class).
  `dev/explore.py` gains a `sym` command for live probing.
- **Completion engine refactoring** — `ImportAnalyzer` gains `needsImport()` (java.lang, same-package, and
  already-imported checks in one place) and `importEdit()` (single `"import %s;\n"` construction site).
  `AddThrowsProvider` and `DeclareVariableProvider` migrated to `importEdit()`.
  `CompletionEngine` cleaned up: double-import deduplication in `addAdditionalTextEdit`,
  second-reattribution avoided in `complete()` post-pass, `resolvePackageCandidates()` helper extracted,
  `blankMemberAccessContext()` renamed from the 1-arg `memberAccessSemanticContext()` overload,
  `noFinalCombination()` renamed from `modifierAfterFinalCombination()` with inverted body.
  `Stream.of("").§` regression fixed: method-chain receivers now resolve through the type-index
  fallback and receive an `additionalTextEdits` import suggestion.

---

## Planned Design Documents

Designs for future, beta, and follow-up work live in `docs/planned/`.
Some planned docs also describe work that has since been implemented;
the status in this roadmap is the source of truth when the two disagree.
Use these docs as the starting point when reprioritizing or slicing new work:

- [lathe-event-loop-starvation.md](planned/lathe-event-loop-starvation.md) — ServerEventLoop blocked by synchronous `WorkspaceTypeIndex.build` during init and post-save refresh; causes type-hierarchy and implementation timeouts on large workspaces.
- [lathe-call-hierarchy.md](planned/lathe-call-hierarchy.md) — exact-match method call tree exploration (incoming and outgoing calls).
- [lathe-class-import-semantic-highlighting.md](planned/lathe-class-import-semantic-highlighting.md) — semantic token highlighting for class, interface, and enum type references in import statements and code bodies.
- [lathe-find-references-gaps.md](planned/lathe-find-references-gaps.md) — current correctness,
  external-symbol scope policy,
  failure-propagation,
  and invoker coverage gaps for `textDocument/references`.
- [lathe-goto-implementation.md](planned/lathe-goto-implementation.md) — `textDocument/implementation`, `prepareTypeHierarchy`, `supertypes`, `subtypes`: subtype and override navigation reusing Find References infrastructure. Fix event-loop starvation and directOnly bug during this work.
- [lathe-google-indent.md](planned/lathe-google-indent.md) — conservative `textDocument/onTypeFormatting`
  indentation hints using google-java-format.
- [lathe-jdk-cache-key.md](done/lathe-jdk-cache-key.md) — unified JDK source and type-index cache pathing (implemented).
- [lathe-launcher-jvm-opts.md](planned/lathe-launcher-jvm-opts.md) — generated launcher support for
  user-provided `LATHE_JVM_OPTS`.
- [lathe-architecture-test-improvements.md](done/lathe-architecture-test-improvements.md) —
  beta workspace and test-fixture cleanup (implemented; remaining items in [lathe-maintainability-refactoring.md](planned/lathe-maintainability-refactoring.md)).
- [completion/](planned/completion/) —
  active completion expectations,
  explorer-based gap discovery,
  and current completion-quality gap log.
- [lathe-code-actions-gaps.md](planned/lathe-code-actions-gaps.md) —
  code-action gap tracker: Gap 3B (MissingMethodImplProvider) and Gap 4 (type-index freshness) are beta-scope.
- [lathe-code-quality-refactoring.md](planned/lathe-code-quality-refactoring.md) —
  historical refactoring review retained for context;
  stale findings in it should not drive current work.
- [lathe-maintainability-refactoring.md](planned/lathe-maintainability-refactoring.md) —
  current systematic maintainability review covering correctness prerequisites,
  focused DRY/KISS extractions,
  naming consistency,
  fail-fast exception propagation,
  and test-fixture cleanup. Beta-scope.
- [lathe-completion-presentation.md](done/lathe-completion-presentation.md) —
  JDT LS-style completion `labelDetails` and generic type display (implemented).
- [lathe-lightweight-watcher.md](planned/lathe-lightweight-watcher.md) —
  lightweight module-targeted workspace watcher; description partially stale (WorkspaceWatcher already simplified).
- [lathe-missing-import-code-action.md](done/lathe-missing-import-code-action.md) —
  LSP quick-fix code actions for unresolved types (implemented).
- [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md) — implemented reactor type-index design and
  remaining follow-ups such as generated-source cleanup and SNAPSHOT freshness.
- [lathe-rich-javadoc-rendering.md](done/lathe-rich-javadoc-rendering.md) — AST-backed Markdown formatting for Javadoc using DocTreeScanner (implemented).
- [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) — Maven-delegated run, test, debug commands and streamed
  session events.
- [lathe-structural-navigation.md](done/lathe-structural-navigation.md) — Document symbols, folding ranges, and workspace symbols (implemented).
- [lathe-folding-ranges.md](done/lathe-folding-ranges.md) — Folding ranges (implemented).
- [lathe-source-uri-scheme.md](done/lathe-source-uri-scheme.md) — superseded; `file://` approach implemented instead, see [lathe-file-uri-scheme.md](done/lathe-file-uri-scheme.md).
- [lathe-unused-code-diagnostics.md](done/lathe-unused-code-diagnostics.md) — unused private methods, fields, and locals (implemented).
- [lathe-stale-pom-detection.md](done/lathe-stale-pom-detection.md) — POM fingerprint recording,
  `WorkspaceWatcher` simplification, and `showMessageRequest`-based Neovim sync prompt.
- [lathe-type-index.md](planned/lathe-type-index.md) — implemented type-index design and remaining work such as
  freshness checks.
- [lathe-sibling-recompilation.md](planned/lathe-sibling-recompilation.md) — reference-index guided background
  recompilation of closed intra-module callers after an API signature change.
- [lathe-vscode-semantic-tokens.md](planned/lathe-vscode-semantic-tokens.md) — expanded semantic-token coverage for
  VS Code parity.
- [lathe-unused-record-components.md](done/lathe-unused-record-components.md) — false "Unused" hints for record component backing fields (fixed in `UnusedDeclarationScanner`).
- [lathe-find-references.md](done/lathe-find-references.md) — original Find References design (implemented; gap tracker is [lathe-find-references-gaps.md](planned/lathe-find-references-gaps.md)).

## Potential Design Documents

Potential designs preserve architectural exploration without committing the work to beta, v1, or the active roadmap:

- [lathe-shared-workspace-server.md](potential/lathe-shared-workspace-server.md) — history and detailed designs for
  per-workspace or global shared server processes with isolated LSP client sessions and workspace runtimes.

---

## Milestone: v0.1.0-beta (Release Scope)

The beta is a build-from-source release focused on making the current Neovim workflow installable,
closing the known beta-scope completion and code-action gaps found so far,
adding small high-value editor feedback features,
and landing narrow maintainability improvements that reduce beta risk.
The beta is distributed as source only;
users clone the repo and build locally.

### Installation and Neovim setup documentation
✅ README updated with build-from-source instructions, Neovim plugin installation (symlink and lazy.nvim),
`LATHE_DEBUG=1` debugging, and troubleshooting for missing `.lathe/` and missing params.

### Completion Engine Gaps
✅ All beta-scope gaps in `planned/completion/gap-log.md` are fixed.
Method-reference completion (`CQ-0002`) and generic-bound receiver completion (`CQ-0029`, `CQ-0030`)
are explicitly deferred until after beta.

### Code Action Gaps
✅ Gaps 1 (`TryCatchWrapProvider`), 2 (`DeclareVariableProvider`), and Gap 3 classification are closed.
Gap 3 provider and Gap 4 are deferred to post-beta.

### Goto Implementation & Type Hierarchy
`textDocument/implementation`, `textDocument/prepareTypeHierarchy`, `typeHierarchy/supertypes`, and `typeHierarchy/subtypes` — planned but not yet implemented.
One `ImplementationLocator` scanner (with a `directOnly` flag) will serve all four endpoints.
See [lathe-goto-implementation.md](planned/lathe-goto-implementation.md).

Known correctness issues to address during implementation:
- **ServerEventLoop starvation** — `WorkspaceTypeIndex.build` runs synchronously on the event loop during initialization and after each save; blocks all follow-up requests for tens of seconds on large workspaces (Helidon: 535 shards). Fix tracked in [lathe-event-loop-starvation.md](planned/lathe-event-loop-starvation.md). Must be fixed before the feature is usable.
- **`typeHierarchy/subtypes` directOnly bug** — `subtypes` must pass `directOnly=true` to return only immediate subtypes per the LSP spec; passing `false` returns the full transitive closure instead.
- **`textDocument/implementation` returns empty for declarations in other modules** — references requested from cached JDK/dependency source or external-module declarations search no project modules. Same root cause as the Find References external-symbol gap.

### Structural Navigation
✅ `workspace/symbol`, `textDocument/documentSymbol`, and `textDocument/foldingRange` are all implemented.

### Known Correctness Bugs (beta-blocking)
These small defects are confirmed in code; each needs a fix plus one or two regression tests before beta ships.

- **Semantic-token refresh condition inverted** (`DiagnosticPublisher.java:39`) — tokens are refreshed for stale results instead of current ones. Causes semantic tokens to update on old compile output and to be skipped on current output. Two-line fix and two regression tests.
- **Exceptions silently converted to empty reference results** (`WorkspaceSession.java` around the fan-out futures) — caught exceptions are logged and the request returns an empty list, hiding real failures as "no references found". Should propagate as LSP errors per the fail-fast policy in [lathe-maintainability-refactoring.md](planned/lathe-maintainability-refactoring.md).

### Architecture and Test Improvements
✅ `TestCompiler` fixture is consolidated and in use across server tests.
✅ **`DocumentRegistry`** extracted from `WorkspaceSession` — open-document lifecycle with `ValidCheck`-validated `OpenDocument` record.
✅ **`DiagnosticPublisher`** extracted — stale-result checks, `PublishDiagnosticsParams` construction, empty/missing/error publish paths.
✅ **Zip/JAR test fixture** — `ZipFixture` added to `lathe-core` test sources; `ZipCacheTest` and `FileUtilTest` private helpers removed.
See [lathe-architecture-test-improvements.md](done/lathe-architecture-test-improvements.md).

### Maintainability Refactoring (beta-scope)
Systematic correctness and code-quality fixes from the June 2026 review.
Delivered as small independent slices; do not restructure the threading model.
Key items:
- **Fail-fast exception propagation** — replace empty-result swallowing with proper LSP error responses at all fan-out boundaries in `WorkspaceSession`.
- **Test-fixture cleanup** — remove flaky `Thread.sleep` from async tests; standardize `Workbench`/`WorkbenchFixture`-based test construction.
- **Naming and DRY** — targeted helper extraction and renaming passes from the review findings.
See [lathe-maintainability-refactoring.md](planned/lathe-maintainability-refactoring.md).

### MissingMethodImplProvider (code action Gap 3B)
Generate `@Override` stubs for all unimplemented abstract methods when a class fails
`compiler.err.does.not.override.abstract`.
Classification (`MISSING_METHOD_IMPL` payload) is already done.
One new provider file and a one-line dispatcher change.
See [lathe-missing-method-impl.md](planned/lathe-missing-method-impl.md) and [lathe-code-actions-gaps.md](planned/lathe-code-actions-gaps.md) Gap 3.

### Type-index freshness for new reactor types (code action Gap 4)
Ensure newly-created project types become available to missing-import code actions
without requiring a manual `mvn process-test-classes` round trip.
See [lathe-code-actions-gaps.md](planned/lathe-code-actions-gaps.md) Gap 4 and [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md).

---

## Milestone: Post-Beta Backlog

### External-source Find References
References requested from cached JDK/dependency source declarations or from external-module types return no project results — the workspace scope selector doesn't widen to reactor modules when the cursor file is outside the reactor.
Add invoker test coverage first, then fix scope selection in `WorkspaceSession` to route external-symbol cursors through the full reactor fan-out.
Tracked in [lathe-find-references-gaps.md](planned/lathe-find-references-gaps.md).

### Call Hierarchy
Implement `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, and `callHierarchy/outgoingCalls` to allow users to navigate method call trees.
This heavily reuses the existing "Find References" infrastructure (AST scanning and Candidate Index) to answer "Who calls this?" and "What does this call?" on-demand.
See [lathe-call-hierarchy.md](planned/lathe-call-hierarchy.md).

### Lightweight Watcher
`WorkspaceWatcher` already polls only `workspace.json` and reactor POM fingerprints — it no longer does recursive `.lathe/` directory walking.
Any remaining spike work (e.g. source-file watching for closed-file invalidation) should be re-evaluated against the current watcher before reopening this item.
See [lathe-lightweight-watcher.md](planned/lathe-lightweight-watcher.md) (description may be stale).

### Reactor type-index follow-up
Static dependency, JDK, and reactor output shards are in place.
The remaining reactor-index work is any later performance optimization if startup scanning becomes measurable.
This also unlocks package-prefix completion and workspace symbols once those features query the reactor candidates.
See [lathe-type-index.md](planned/lathe-type-index.md) and [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md).

### Document Symbols and Folding Ranges
✅ `textDocument/documentSymbol` and `textDocument/foldingRange` are implemented.
See [lathe-structural-navigation.md](done/lathe-structural-navigation.md) and [lathe-folding-ranges.md](done/lathe-folding-ranges.md).

### onTypeFormatting
Implement conservative `textDocument/onTypeFormatting` indentation hints for newline triggers.
Currently stubbed and removed from advertised capabilities for the beta.
See [lathe-google-indent.md](planned/lathe-google-indent.md).

### Method-reference completion
Implement method-reference completion after `Type::`, `this::`, `super::`, and expression receivers.
This is deferred until after beta because it needs a new sentinel/parser site,
completion routing without call-parenthesis insertion,
and eventually target functional-interface compatibility filtering.
Tracked as `CQ-0002` in [gap-log.md](planned/completion/gap-log.md).

### Generic-bound receiver completion
Complete member access for wildcard and type-variable receivers by expanding effective upper bounds.
This is deferred until after beta because it requires careful generic substitution and capture handling.
Tracked as `CQ-0029` and `CQ-0030` in [gap-log.md](planned/completion/gap-log.md).

### Run, test, and debug
Adopt the design in [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) to let the server manage Maven test/run executions
and stream results back to the editor as LSP notifications.
Depends on distribution and stale-workspace handling being solid first.

### Launcher JVM options
Honor `LATHE_JVM_OPTS` in the generated launcher so users can set heap and GC options without editing generated files.
See [lathe-launcher-jvm-opts.md](planned/lathe-launcher-jvm-opts.md).

### Module metadata in the manifest
Add reactor module entries to `workspace.json` after the params-file model is stable,
to support staleness detection, UX hints, and faster server startup without duplicating classpaths.
`WorkspaceManifestData` currently holds schema version,
workspace root,
server version,
JDK source,
dependency sources,
and reactor POM paths;
`WorkspaceModuleRegistry` still discovers modules by scanning `lsp-params-*.json` at startup.

### Semantic token coverage for VS Code
VS Code uses TextMate grammars rather than tree-sitter, so it relies on the LSP for all
identifier-level highlighting.
The current `TokenScanner` legend only covers static/deprecated methods and fields, enum constants, type parameters, and annotations.
Full VS Code parity requires adding `class`, `parameter`, and `variable` token types and
widening `method`/`property` to emit for all instances.
See [lathe-vscode-semantic-tokens.md](planned/lathe-vscode-semantic-tokens.md) for the implementation plan.

### Sibling recompilation
When a saved file's public API changes, recompile closed intra-module callers in the background
and publish diagnostics for them.
Deferred because closed-file diagnostics have low visibility in Neovim without a workspace-diagnostics
plugin (e.g. `trouble.nvim`); the primary beneficiary is VS Code.
See [lathe-sibling-recompilation.md](planned/lathe-sibling-recompilation.md).

### Post-beta language features
Rename, inlay hints, and richer code actions,
after the sync/distribution/type-index foundation is in place.
