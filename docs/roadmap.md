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

Threading choice:
Lathe is being refactored to use one server worker thread for workspace/session state
and one module worker thread per javac-backed compilation context.
The current recipe is [lathe-server-data-flow-recipe.md](lathe-server-data-flow-recipe.md).
LSP4J threads capture immutable request data and enqueue work.
The server worker owns `WorkspaceSession`, `ModuleWorkspace`, open-file snapshots, routing,
stale-result checks, and client publishing.
Module workers own `CompilationContext` instances and javac-backed compiler state.
Compile results cross back to the server worker before diagnostics or semantic-token refreshes are published.

Member-access completion work has started,
but it should be revisited after the server data-flow recipe is implemented and reviewed.

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
  while javac-backed compiler state is moving behind module workers.
  `WorkspaceSession` encapsulates worker-confined workspace state and routing;
  `LatheTextDocumentService` is a thin LSP dispatcher.
- **Stale-result guard** — `publishIfCurrent` compares the content that triggered a compile against the latest open content before publishing diagnostics,
  so rapid edits never overwrite newer results with an older compile's output.

## Near-Term

**Completion (member access)**
Initial `textDocument/completion` work exists,
but it was built during the server refactor and should be redone or tightened after
[lathe-server-data-flow-recipe.md](lathe-server-data-flow-recipe.md) lands.
The intended member-access approach is still the sentinel strategy described in
[lathe_completion_design.md](lathe_completion_design.md).

Core flow:
1. Take an immutable source snapshot at the trigger position.
2. Inject `__LATHE_SENTINEL__` after the dot.
3. Compile with `proc=none` — no AP, no bytecode.
4. Locate the `MemberSelectTree` whose selector is the sentinel identifier;
   check that its receiver `TypeMirror` is non-null and non-ERROR.
5. Enumerate accessible members via `Elements.getAllMembers` + `Types.asMemberOf` for correct generic substitution.
6. If attribution fails, apply one bounded repair round (insert `)`, `]`, `}`, `;` guided by parse diagnostics)
   and recompile.
7. Return items within the 200 ms budget; return partial or empty on timeout.

The `ModuleCompiler`/`ExternalCompiler` file-manager and temp-dir approach already handles single-file compilation.
Completion should reuse the same infrastructure through `CompilationContext` and `ModuleWorker`
with a sentinel-injected source copy.

Files to revisit:

- `CompletionProvider` — sentinel injection, compile, member enumeration, and any bounded repair round.
- `CompilationContext.complete(...)` — keep content flow explicit and avoid storing source text in `FileAnalysis`.
- `WorkspaceSession.completionFuture(...)` — route from current open-file content through the correct `ModuleWorker`.
- `LatheLanguageServer` — keep `completionProvider` capability aligned with implementation status.
- Unit tests for sentinel injection and member enumeration on well-formed and broken source.

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

**Post-v1 language features**
Rename, inlay hints, signature help, and richer code actions,
after the sync/distribution/type-index foundation is in place.
