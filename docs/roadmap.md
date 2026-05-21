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

Threading model: one server worker thread (`lathe-worker`) owns `WorkspaceSession`, `ModuleWorkspace`,
open-file snapshots, routing, stale-result checks, and client publishing.
One module worker thread per javac-backed `CompilationContext`.
LSP4J threads capture immutable request data and enqueue work.
Compile results cross back to `lathe-worker` before diagnostics or semantic-token refreshes are published.
Architecture is documented in [lathe-server-data-flow-recipe.md](lathe-server-data-flow-recipe.md).

## Completed

- **JSON state format** — params and workspace state moved from ad hoc property files to shared JSON schema records in `lathe-core`.
- **Workspace sync slices** — dependency and JDK source resolution, extraction, and server-side manifest loading are implemented.
- **Lifecycle binding** — both Maven goals declare default phases; user POM executions can omit `<phase>`.
- **`compileWith` simplification** — `LatheTextDocumentService.compileWith` is a small dispatcher with focused helper methods for each path.
- **Shim correctness** — lock cleanup moved to `finally`; silent javac failure surfaces as `IOException`; `LatheServer.main` acquires stdout before any logging can write to it.
- **IT verify module** — dead `verify.sh` replaced by a `verify/` JUnit submodule that runs as part of the normal invoker lifecycle; `@property@` tokens pin the plugin version.
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

## Near-Term

**Completion (member access)**

Design: [completion_engine_implementation_guide.md](completion_engine_implementation_guide.md)

`CompletionEngine` is a diagnostic stub returning empty results.
Implementation proceeds in four layers, each independently testable:

1. **`SentinelInjector`** — backward/forward scan + injection, pure string manipulation with no javac.
   Extracts prefix, detects STATEMENT/EXPRESSION context, extracts receiver text,
   balances braces, preserves the file tail for lambda compatibility.
2. **`SentinelParser`** — parse-only javac invocation on the injected buffer (~10ms).
   Finds `__LATHE_SENTINEL__` in the AST, extracts `SentinelResult`
   (context enum, receiver text, enclosing class/method, cursor line).
3. **Sentinel cache** — `SentinelResult` stored per file in `CompilationContext`.
   Invalidated on structural characters (`.`, `(`, `{`, newline, etc.).
   Fast path re-filters on prefix-only extension with no parse.
4. **Receiver resolution + member proposals** — `CachedAnalysis` (background attributed snapshot)
   used read-only to resolve receiver type and enumerate members via `getAllMembers`.

Routing still needed:

- `WorkspaceSession.completionFuture(...)` — route completion through the correct `ModuleWorker`.
- `LatheLanguageServer` — align `completionProvider` capability with implementation status.
- Timeout guard — return partial or empty within the 200ms budget.

## Future Work

**Stale-POM detection**
Record POM fingerprints in `workspace.json` during `lathe:sync`.
When `WorkspaceWatcher` sees a POM change after the last sync timestamp,
`StubWorkspaceService.didChangeWatchedFiles` prompts the user in the editor to re-run the documented Maven lifecycle command.
`WorkspaceManifestData`, `WorkspaceManifestWriter`, and `WorkspaceWatcher` all need additions;
`didChangeWatchedFiles` is currently empty.

**Type indexes**
Build the shared JAR/JDK/reactor type index described in the design doc,
giving the server a fast lookup over all known types without scanning source on every request.
This unlocks reliable missing-import suggestions, workspace symbols, and broader classpath type discovery for non-JPMS projects.
There is no type-index package or cache writer yet,
and `LatheLanguageServer` does not advertise workspace-symbol or code-action capabilities.

**Module metadata in the manifest**
Add reactor module entries to `workspace.json` after the params-file model is stable,
to support staleness detection, UX hints, and faster server startup without duplicating classpaths.
`WorkspaceManifestData` currently holds only schema version, workspace root, JDK source, and dependency sources;
`ModuleWorkspace` still discovers modules by scanning `lsp-params-*.json` at startup.

**Run, test, and debug**
Adopt the design in `lathe-run-test-debug.md` to let the server manage Maven test/run executions
and stream results back to the editor as LSP notifications.
Depends on distribution and stale-workspace handling being solid first.

**Editor integrations**
Keep Neovim/VS Code clients thin: they launch `~/.cache/lathe/current/lathe-launcher.sh`
and ask the server for Lathe-specific state via custom LSP requests.
No client-side project model parsing.

**`lathe-source://` URI scheme for external sources**
Definition jumps into JDK and dependency sources currently return `file://` URIs pointing
into `~/.cache/lathe/`, causing swap file dialogs in Neovim and requiring path-based
detection logic in every editor plugin.
Replace with a `lathe-source://` scheme: one line in `CompilationContext.definition()`;
editors read the file from the path embedded in the URI and open it as a read-only
`nofile` buffer — no server round-trip, no per-editor path heuristics.
See [lathe-source-uri-scheme.md](lathe-source-uri-scheme.md) for the full design.

**Semantic token coverage for VS Code**
VS Code uses TextMate grammars rather than tree-sitter, so it relies on the LSP for all
identifier-level highlighting. The current `TokenScanner` legend only covers static/deprecated
methods and fields, enum constants, type parameters, and annotations.
Full VS Code parity requires adding `class`, `parameter`, and `variable` token types and
widening `method`/`property` to emit for all instances.
See [lathe-vscode-semantic-tokens.md](lathe-vscode-semantic-tokens.md) for the implementation plan.

**Post-v1 language features**
Rename, inlay hints, signature help, and richer code actions,
after the sync/distribution/type-index foundation is in place.
