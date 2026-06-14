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
  Fix is documented in section 15 of the design doc.
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
- **Code actions infrastructure + providers** — `DiagnosticPayload` record with JSON codec, `enrichWithContext()` classification pass, `CodeActionProvider` interface, dispatcher, and three providers: `ImportQuickFixProvider` (TYPE_REF), `AddThrowsProvider` (UNREPORTED_EXCEPTION), `DeclareVariableProvider` (VARIABLE_REF assignment LHS). Gap regression tests cover lambda/closure and MISSING_METHOD_IMPL gaps. Items 10–11 deferred to post-beta. See [lathe-code-actions.md](done/lathe-code-actions.md).
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

---

## Planned Design Documents

Designs for future, beta, and follow-up work live in `docs/planned/`.
Some planned docs also describe work that has since been implemented;
the status in this roadmap is the source of truth when the two disagree.
Use these docs as the starting point when reprioritizing or slicing new work:

- [lathe-call-hierarchy.md](planned/lathe-call-hierarchy.md) — exact-match method call tree exploration (incoming and outgoing calls).
- [lathe-class-import-semantic-highlighting.md](planned/lathe-class-import-semantic-highlighting.md) — semantic token highlighting for class, interface, and enum type references in import statements and code bodies.
- [lathe-google-indent.md](planned/lathe-google-indent.md) — conservative `textDocument/onTypeFormatting`
  indentation hints using google-java-format.
- [lathe-jdk-cache-key.md](done/lathe-jdk-cache-key.md) — unified JDK source and type-index cache pathing (implemented).
- [lathe-launcher-jvm-opts.md](planned/lathe-launcher-jvm-opts.md) — generated launcher support for
  user-provided `LATHE_JVM_OPTS`.
- [lathe-architecture-test-improvements.md](planned/lathe-architecture-test-improvements.md) —
  beta workspace and test-fixture cleanup.
- [completion/](planned/completion/) —
  active completion expectations,
  explorer-based gap discovery,
  and current completion-quality gap log.
- [lathe-code-quality-refactoring.md](planned/lathe-code-quality-refactoring.md) —
  verified refactoring plan for completion internals,
  worker-confined workspace coordination,
  and stale code-review findings that should not drive work.
- [lathe-completion-presentation.md](planned/lathe-completion-presentation.md) —
  JDT LS-style completion `labelDetails` and generic type display.
- [lathe-lightweight-watcher.md](planned/lathe-lightweight-watcher.md) —
  lightweight module-targeted workspace watcher to replace recursive directory walking.
- [lathe-missing-import-code-action.md](done/lathe-missing-import-code-action.md) —
  LSP quick-fix code actions for unresolved types,
  reusing the existing completion import insertion behavior without replacing completion-side edits.
- [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md) — implemented reactor type-index design and
  remaining follow-ups such as generated-source cleanup and SNAPSHOT freshness.
- [lathe-rich-javadoc-rendering.md](planned/lathe-rich-javadoc-rendering.md) — AST-backed Markdown formatting for Javadoc using DocTreeScanner.
- [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) — Maven-delegated run, test, debug commands and streamed
  session events.
- [lathe-structural-navigation.md](planned/lathe-structural-navigation.md) — Document symbols (outline view) and folding ranges.
- [lathe-source-uri-scheme.md](done/lathe-source-uri-scheme.md) — superseded; `file://` approach implemented instead, see [lathe-file-uri-scheme.md](done/lathe-file-uri-scheme.md).
- [lathe-unused-code-diagnostics.md](done/lathe-unused-code-diagnostics.md) — unused private methods, fields, and locals (implemented).
- [lathe-stale-pom-detection.md](done/lathe-stale-pom-detection.md) — POM fingerprint recording,
  `WorkspaceWatcher` simplification, and `showMessageRequest`-based Neovim sync prompt.
- [lathe-type-index.md](planned/lathe-type-index.md) — implemented type-index design and remaining work such as
  missing-import suggestions, package-prefix completion, workspace symbols, and freshness checks.
- [lathe-sibling-recompilation.md](planned/lathe-sibling-recompilation.md) — reference-index guided background
  recompilation of closed intra-module callers after an API signature change.
- [lathe-vscode-semantic-tokens.md](planned/lathe-vscode-semantic-tokens.md) — expanded semantic-token coverage for
  VS Code parity.

---

## Milestone: v0.1.0-beta (Release Scope)

The beta is a build-from-source release focused on making the current Neovim workflow installable,
closing the known beta-scope completion and code-action gaps found so far,
adding small high-value editor feedback features,
and landing narrow maintainability improvements that reduce beta risk.
The beta is distributed as source only;
users clone the repo and build locally.

### Installation and Neovim setup documentation
Write user-facing setup documentation for the build-from-source beta.
Cover Maven POM configuration (compiler shim + plugin executions),
building Lathe from source (`mvn install`),
the `mvn process-test-classes` workflow,
Neovim LSP client configuration (native `vim.lsp.config` for Neovim 0.11+),
and basic troubleshooting (`LATHE_DEBUG=1`, missing `.lathe/`, missing params).

### Completion Engine Gaps
Close the known beta-scope gaps in the completion engine documented in `planned/completion/gap-log.md`.
Method-reference completion (`CQ-0002`) is explicitly deferred until after beta.
These are highly visible to Neovim users relying on accurate completions.

### Code Action Gaps
Close the remaining gaps documented in `lathe-code-actions-gaps.md`.
`VARIABLE_REF` assignment-LHS declaration is already implemented through `DeclareVariableProvider`;
the remaining beta scope is:

- **Gap 1 — lambda/closure** (`UNREPORTED_EXCEPTION` in a lambda body): suppress `AddThrowsProvider`
  when the throw site is inside a `LambdaExpressionTree` or anonymous class; implement
  `TryCatchWrapProvider` to wrap the enclosing statement in `try { } catch (E e) { }`.
  Requires completing `CodeActionSupport` with the enclosing-method and enclosing-statement finders.
- **Gap 3 — `MISSING_METHOD_IMPL`**: add `compiler.err.does.not.override.abstract` classification
  in `enrichWithContext()`; implement `MissingMethodImplProvider` to generate `@Override` stubs
  for all unimplemented abstract methods using `elements().getAllMembers()`.
- **Gap 4 — type-index freshness for new reactor types**: ensure newly-created project types become available to
  missing-import code actions without requiring a manual full `mvn process-test-classes` round trip when Lathe already
  has enough local source or reactor-index information to answer safely.

### Rich Javadoc Rendering
Upgrade `textDocument/hover` and completion item documentation to render Javadoc as formatted Markdown instead of raw text.
Replaces regex-based comment stripping with `DocTreeScanner` AST walking to support HTML tags, inline links, and block tags natively.
See [lathe-rich-javadoc-rendering.md](planned/lathe-rich-javadoc-rendering.md).

### Structural Navigation
Add `textDocument/documentSymbol` and `textDocument/foldingRange` using read-only `SourceParser` AST passes.
Powers the editor "Outline" view, breadcrumbs, and code folding for classes, methods, and imports.
See [lathe-structural-navigation.md](planned/lathe-structural-navigation.md).

### Architecture and Test Improvements
Land the narrowly-scoped maintainability improvements documented in
[lathe-architecture-test-improvements.md](planned/lathe-architecture-test-improvements.md).
Beta scope is limited to worker-confined `WorkspaceSession` extractions that keep `WorkspaceSession` as owner,
plus shared test-only compiler and zip/JAR fixtures.

---

## Milestone: Post-Beta Backlog

### Call Hierarchy
Implement `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, and `callHierarchy/outgoingCalls` to allow users to navigate method call trees.
This heavily reuses the existing "Find References" infrastructure (AST scanning and Candidate Index) to answer "Who calls this?" and "What does this call?" on-demand.
See [lathe-call-hierarchy.md](planned/lathe-call-hierarchy.md).

### Lightweight Watcher
Replace `Files.walk` with targeted polling to eliminate disk I/O spikes.
See [lathe-lightweight-watcher.md](planned/lathe-lightweight-watcher.md).

### Reactor type-index follow-up
Static dependency, JDK, and reactor output shards are in place.
The remaining reactor-index work is any later performance optimization if startup scanning becomes measurable.
This also unlocks package-prefix completion and workspace symbols once those features query the reactor candidates.
See [lathe-type-index.md](planned/lathe-type-index.md) and [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md).

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
