# Lathe — Roadmap

## Current Position

Lathe is in the post-bootstrap sync phase.
The compiler shim, Maven plugin, JSON schemas, workspace manifest, dependency-source sync,
JDK-source sync, and server-side manifest loading are now in place.

Current lifecycle shape:

- `lathe:init` creates `.lathe/` during `initialize`.
- The compiler shim writes `lsp-params-*.json` and mirrors class/generated-source outputs under `.lathe/<module>/`.
- `lathe:sync` runs during `process-test-classes`,
  resolves dependency source JARs,
  extracts dependency/JDK sources into `~/.cache/lathe/`,
  and writes `.lathe/workspace.json`.
- The server scans params files for reactor modules,
  watches params and `workspace.json`,
  uses the manifest for dependency/JDK source lookup,
  and can compile opened external source files from synced dependency/JDK sources.

The project is not yet externally installable.
Users still need a locally built server and local editor wiring.
The next milestone is making Maven install and update the server distribution.

## Completed Recently

**JSON state format**
Params and workspace state moved from ad hoc property files to shared JSON schema records in `lathe-core`.
The design doc now treats `workspace.json` and `lsp-params-*.json` as the current format.

**Workspace sync slices**
Dependency source resolution/extraction and JDK source extraction are implemented in `lathe:sync`.
The server reads the resulting manifest for go-to-definition, hover origin labels, and external-source compilation.

**Lifecycle binding**
Both Maven goals declare default phases.
User POMs still need explicit executions,
but those executions can omit `<phase>`.

**`compileWith` simplification**
`LatheTextDocumentService.compileWith` is now a small dispatcher.
The reactor, external-source, publish, and error paths have already been split into focused helper methods,
so the old refactor item is closed.

## Near-Term

**Maven-managed server distribution**
Install the server binary under `~/.cache/lathe/servers/<version>/` via `lathe:sync`,
then update `~/.cache/lathe/current`.
This is the prerequisite for external adoption,
because users should not need to build the server from source.
Current source has `lathe-launcher.sh.template`,
but no sync-time installer, server assembly, `current` update logic, or invoker assertion for launcher installation.

This slice should add:

- server distribution assembly packaged with the Maven plugin release
- launcher installation/update logic in `lathe:sync`
- manifest fields for synced server version and compatible schema version
- invoker coverage proving a normal lifecycle build installs the launcher
- updated editor setup docs that launch `~/.cache/lathe/current/lathe-launcher.sh`

**Shim correctness drift**
Verified against `LatheCompiler.performCompile`.
Several known issues remain:

- Move params writing, class copying, generated-source copying, and lock cleanup into a true
  `finally` path around `javacCompiler.performCompile()`.
- Make silent javac failure surface as an `IOException` instead of being swallowed.
- Add an explicit stdout guard before starting the server,
  so accidental writes cannot corrupt the LSP stdio pipe.

The server's JUL logging currently goes through `ConsoleHandler`,
which writes to stderr,
but `LatheServer` still passes `System.out` directly to LSP4J and does not protect against accidental stdout writes.

## Medium-Term Verified Open

**Stale-POM detection**
Record POM fingerprints in `workspace.json` during `lathe:sync`.
When watched POM files change after the last sync,
prompt the user in the editor to run the documented lifecycle command.
Current source does not implement this yet.
`WorkspaceManifestData` has no POM fingerprint field,
`WorkspaceManifestWriter` does not write POM hashes,
and `WorkspaceWatcher` watches only `workspace.json` plus `lsp-params-*.json`.
`StubWorkspaceService.didChangeWatchedFiles` is still empty.

**Type indexes**
Build the shared JAR/JDK/reactor type index described in the design doc.
This unlocks reliable missing-import suggestions,
workspace symbols,
and broader classpath type discovery for non-JPMS projects.
Current source does not implement this yet.
There is no type-index package or cache writer,
and `LatheLanguageServer` does not advertise workspace-symbol, completion, or code-action capabilities.

**Module metadata in the manifest**
Add reactor module entries to `workspace.json` only after the params-file model is stable.
The params files should remain the compile source of truth;
manifest module data should support staleness, UX, and faster lookup rather than duplicate classpaths.
Current source does not implement this yet.
`WorkspaceManifestData` contains only schema version, workspace root, JDK source data,
and dependency source data.
`ModuleRegistry` still discovers modules by scanning `lsp-params-*.json`.

## Longer-Term

**Run, test, and debug**
Adopt the design in `lathe-run-test-debug.md` after distribution and stale-workspace handling are solid.

**Editor integrations**
Keep Neovim/VS Code clients thin.
They should launch the Maven-managed server distribution and ask the server for Lathe-specific state.

**Post-v1 language features**
Rename, inlay hints, signature help, and richer code actions come after the sync/distribution foundation.
