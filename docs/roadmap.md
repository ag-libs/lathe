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
- **Pre-beta codebase cleanups** — all items in the refactoring/renaming plan completed: DRY/KISS helpers, `setLocationFromPaths` migration, walk depth limit, `var` rule enforcement, test naming and `@Nested` flattening, four conceptual renamings, and test co-location fixes (`SyncMojoTest` → `LatheFlagsTest` in `lathe-core`; `DependencySourceTest`, `DependencySourceSyncTest`, `JdkSourceResolverTest` moved to matching subpackages).
  See [lathe-refactoring-renaming.md](done/lathe-refactoring-renaming.md).

---

## Planned Design Documents

Designs for future or follow-up work live in `docs/planned/`.
Use these as the starting point when reprioritizing or slicing new work:

- [lathe-class-import-semantic-highlighting.md](planned/lathe-class-import-semantic-highlighting.md) — semantic token highlighting for class, interface, and enum type references in import statements and code bodies.
- [lathe-google-indent.md](planned/lathe-google-indent.md) — conservative `textDocument/onTypeFormatting`
  indentation hints using google-java-format.
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
- [lathe-import-optimization.md](planned/lathe-import-optimization.md) —
  semantic import cleanup before full-document formatting,
  including unused import removal and conservative wildcard expansion.
- [lathe-lightweight-watcher.md](planned/lathe-lightweight-watcher.md) —
  lightweight module-targeted workspace watcher to replace recursive directory walking.
- [lathe-missing-import-code-action.md](done/lathe-missing-import-code-action.md) —
  LSP quick-fix code actions for unresolved types,
  reusing the existing completion import insertion behavior without replacing completion-side edits.
- [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md) — implemented reactor type-index design and
  remaining follow-ups such as generated-source cleanup and SNAPSHOT freshness.
- [lathe-refactoring-renaming.md](planned/lathe-refactoring-renaming.md) — planned codebase and test suite refactorings (DRY, KISS, renamings, and package alignment).
- [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) — Maven-delegated run, test, debug commands and streamed
  session events.
- [lathe-signature-help.md](planned/lathe-signature-help.md) — parameter types and names display during method/constructor invocation.
- [lathe-source-uri-scheme.md](planned/lathe-source-uri-scheme.md) — `lathe-source://` URIs for read-only external
  JDK/dependency source files.
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

### Installation and Neovim setup documentation
Write user-facing setup documentation for the build-from-source beta.
Cover Maven POM configuration (compiler shim + plugin executions),
building Lathe from source (`mvn install`),
the `mvn process-test-classes` workflow,
Neovim LSP client configuration (native `vim.lsp.config` for Neovim 0.11+),
and basic troubleshooting (`LATHE_DEBUG=1`, missing `.lathe/`, missing params).
The beta is distributed as source only — users clone the repo and build locally.



### `lathe-source://` URI scheme for external sources
Definition jumps into JDK and dependency sources currently return `file://` URIs pointing
into `~/.cache/lathe/`, causing swap file dialogs in Neovim and requiring path-based
detection logic in every editor plugin.
Replace with a `lathe-source://` scheme: one line in `SourceAnalysisSession.definition()`;
editors read the file from the path embedded in the URI and open it as a read-only
`nofile` buffer — no server round-trip, no per-editor path heuristics.
See [lathe-source-uri-scheme.md](planned/lathe-source-uri-scheme.md) for the full design.



### Neovim plugin packaging
Package the `neovim/` plugin for distribution.
The plugin launches `~/.cache/lathe/current/lathe-launcher.sh`
and keeps the client thin — no client-side project model parsing.
VS Code plugin and tooling integration is deferred to post-beta.

---

## Milestone: Post-Beta Backlog

### Lightweight Watcher
Replace `Files.walk` with targeted polling to eliminate disk I/O spikes.
See [lathe-lightweight-watcher.md](planned/lathe-lightweight-watcher.md).

### Reactor type-index follow-up
Static dependency, JDK, and reactor output shards are in place.
The remaining reactor-index work is any later performance optimization if startup scanning becomes measurable.
This also unlocks missing-import suggestions and workspace symbols once those features query the reactor candidates.
See [lathe-type-index.md](planned/lathe-type-index.md) and [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md).

### Signature Help
Display method and constructor parameter names and types during argument entry.
Parse enclosing invocation contexts and count commas at the cursor's nesting level to highlight the active parameter.
See [lathe-signature-help.md](planned/lathe-signature-help.md).

### onTypeFormatting
Implement conservative `textDocument/onTypeFormatting` indentation hints for newline triggers.
Currently stubbed and removed from advertised capabilities for the beta.
See [lathe-google-indent.md](planned/lathe-google-indent.md).

### Run, test, and debug
Adopt the design in [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) to let the server manage Maven test/run executions
and stream results back to the editor as LSP notifications.
Depends on distribution and stale-workspace handling being solid first.

### Module metadata in the manifest
Add reactor module entries to `workspace.json` after the params-file model is stable,
to support staleness detection, UX hints, and faster server startup without duplicating classpaths.
`WorkspaceManifestData` currently holds only schema version, workspace root, JDK source, and dependency sources;
`WorkspaceModuleRegistry` still discovers modules by scanning `lsp-params-*.json` at startup.

### Semantic token coverage for VS Code
VS Code uses TextMate grammars rather than tree-sitter, so it relies on the LSP for all
identifier-level highlighting.
The current `TokenScanner` legend only covers static/deprecated methods and fields, enum constants, type parameters, and annotations.
Full VS Code parity requires adding `class`, `parameter`, and `variable` token types and
widening `method`/`property` to emit for all instances.
See [lathe-vscode-semantic-tokens.md](planned/lathe-vscode-semantic-tokens.md) for the implementation plan.

### Import optimization
Run conservative semantic import cleanup before full-document formatting:
deduplicate imports,
remove unused explicit imports,
sort normal/static groups,
and replace wildcard imports with direct imports when javac can prove the used symbols.
See [lathe-import-optimization.md](planned/lathe-import-optimization.md).

### Sibling recompilation
When a saved file's public API changes, recompile closed intra-module callers in the background
and publish diagnostics for them.
Deferred because closed-file diagnostics have low visibility in Neovim without a workspace-diagnostics
plugin (e.g. `trouble.nvim`); the primary beneficiary is VS Code.
See [lathe-sibling-recompilation.md](planned/lathe-sibling-recompilation.md).

### Post-beta language features
Rename, inlay hints, and richer code actions,
after the sync/distribution/type-index foundation is in place.
