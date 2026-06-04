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

Threading model: one server worker thread (`lathe-worker`) owns `WorkspaceSession`, `WorkspaceModules`,
open-document snapshots, routing, stale-result checks, and client publishing.
One module worker thread per javac-backed `SourceAnalysisSession`.
LSP4J threads capture immutable request data and enqueue work.
Compile results cross back to `lathe-worker` before diagnostics or semantic-token refreshes are published.
Architecture is documented in [lathe-server-data-flow-recipe.md](done/lathe-server-data-flow-recipe.md).

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
  All planned behavioural gaps are closed; Gap J (method references) is deferred.
  See [completion-design.md](done/completion-design.md).
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

## Future Work

## Planned Design Documents

Designs for future or follow-up work live in `docs/planned/`.
Use these as the starting point when reprioritizing or slicing new work:

- [lathe-google-indent.md](planned/lathe-google-indent.md) — conservative `textDocument/onTypeFormatting`
  indentation hints using google-java-format.
- [lathe-import-optimization.md](planned/lathe-import-optimization.md) —
  semantic import cleanup before full-document formatting,
  including unused import removal and conservative wildcard expansion.
- [lathe-missing-import-code-action.md](planned/lathe-missing-import-code-action.md) —
  LSP quick-fix code actions for unresolved types,
  reusing the existing completion import insertion behavior without replacing completion-side edits.
- [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md) — implemented reactor type-index design and
  remaining follow-ups such as generated-source cleanup and SNAPSHOT freshness.
- [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) — Maven-delegated run, test, debug commands and streamed
  session events.
- [lathe-source-uri-scheme.md](planned/lathe-source-uri-scheme.md) — `lathe-source://` URIs for read-only external
  JDK/dependency source files.
- [lathe-type-index.md](planned/lathe-type-index.md) — implemented type-index design and remaining work such as
  missing-import suggestions, package-prefix completion, workspace symbols, and freshness checks.
- [lathe-vscode-semantic-tokens.md](planned/lathe-vscode-semantic-tokens.md) — expanded semantic-token coverage for
  VS Code parity.

**Stale-POM detection**
Record POM fingerprints in `workspace.json` during `lathe:sync`.
When `WorkspaceWatcher` sees a POM change after the last sync timestamp,
`LatheWorkspaceService.didChangeWatchedFiles` prompts the user in the editor to re-run the documented Maven lifecycle
command.
`WorkspaceManifestData`, `WorkspaceManifestWriter`, and `WorkspaceWatcher` all need additions;
`didChangeWatchedFiles` currently handles deleted Java source files only.

**Reactor type-index follow-up**
Static dependency, JDK, and reactor output shards are in place.
The server scans loaded reactor module output directories (`.lathe/<module>/classes/` and
`.lathe/<module>/test-classes/`) on startup/reload,
merges them into the in-memory `WorkspaceTypeIndex`,
and refreshes the affected reactor shard after successful save-time full compiles.
Deleted Java sources also remove matching `.class`/nested `.class` outputs and refresh the affected reactor shard.
The remaining reactor-index work is any later performance optimization if startup scanning becomes measurable.
This also unlocks missing-import suggestions and workspace symbols once those features query the reactor candidates.
See [lathe-type-index.md](planned/lathe-type-index.md) and [lathe-reactor-type-index.md](planned/lathe-reactor-type-index.md).

**Module metadata in the manifest**
Add reactor module entries to `workspace.json` after the params-file model is stable,
to support staleness detection, UX hints, and faster server startup without duplicating classpaths.
`WorkspaceManifestData` currently holds only schema version, workspace root, JDK source, and dependency sources;
`WorkspaceModules` still discovers modules by scanning `lsp-params-*.json` at startup.

**Missing-import code action**
Completion already inserts imports through completion item `additionalTextEdits`.
Add `textDocument/codeAction` quick fixes for unresolved types that return the same import insertion as a
`WorkspaceEdit`.
Users can then invoke "Import ..." from diagnostics without changing completion behavior.
See [lathe-missing-import-code-action.md](planned/lathe-missing-import-code-action.md).

**Import optimization**
Run conservative semantic import cleanup before full-document formatting:
deduplicate imports,
remove unused explicit imports,
sort normal/static groups,
and replace wildcard imports with direct imports when javac can prove the used symbols.
See [lathe-import-optimization.md](planned/lathe-import-optimization.md).

**Editor integrations**
Keep Neovim/VS Code clients thin: they launch `~/.cache/lathe/current/lathe-launcher.sh`
and ask the server for Lathe-specific state via custom LSP requests.
No client-side project model parsing.

**Run, test, and debug**
Adopt the design in [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) to let the server manage Maven test/run executions
and stream results back to the editor as LSP notifications.
Depends on distribution and stale-workspace handling being solid first.

**`lathe-source://` URI scheme for external sources**
Definition jumps into JDK and dependency sources currently return `file://` URIs pointing
into `~/.cache/lathe/`, causing swap file dialogs in Neovim and requiring path-based
detection logic in every editor plugin.
Replace with a `lathe-source://` scheme: one line in `SourceAnalysisSession.definition()`;
editors read the file from the path embedded in the URI and open it as a read-only
`nofile` buffer — no server round-trip, no per-editor path heuristics.
See [lathe-source-uri-scheme.md](planned/lathe-source-uri-scheme.md) for the full design.

**Semantic token coverage for VS Code**
VS Code uses TextMate grammars rather than tree-sitter, so it relies on the LSP for all
identifier-level highlighting. The current `TokenScanner` legend only covers static/deprecated
methods and fields, enum constants, type parameters, and annotations.
Full VS Code parity requires adding `class`, `parameter`, and `variable` token types and
widening `method`/`property` to emit for all instances.
See [lathe-vscode-semantic-tokens.md](planned/lathe-vscode-semantic-tokens.md) for the implementation plan.

**Post-v1 language features**
Rename, inlay hints, signature help, and richer code actions,
after the sync/distribution/type-index foundation is in place.
