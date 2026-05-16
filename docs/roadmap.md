# Lathe — Roadmap

## Current Position

Lathe is in the post-bootstrap sync phase.
The compiler shim, Maven plugin, JSON schemas, workspace manifest, dependency-source sync,
JDK-source sync, and server-side manifest loading are now in place.
Shim correctness (finally-guarded lifecycle, silent-failure surfacing, stdout guard) and
the IT verify infrastructure are also closed.

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
The next milestone is making `lathe:sync` generate and install the server launcher.

## Completed

- **JSON state format** — params and workspace state moved from ad hoc property files to shared JSON schema records in `lathe-core`.
- **Workspace sync slices** — dependency and JDK source resolution, extraction, and server-side manifest loading are implemented.
- **Lifecycle binding** — both Maven goals declare default phases; user POM executions can omit `<phase>`.
- **`compileWith` simplification** — `LatheTextDocumentService.compileWith` is a small dispatcher with focused helper methods for each path.
- **Shim correctness** — lock cleanup moved to `finally`; silent javac failure surfaces as `IOException`; `LatheServer.main` acquires stdout before any logging can write to it.
- **IT verify module** — dead `verify.sh` replaced by a `verify/` JUnit submodule that runs as part of the normal invoker lifecycle; `@property@` tokens pin the plugin version.

## Near-Term

**Maven-managed server distribution**
Install the server launcher under `~/.cache/lathe/servers/<version>/` via `lathe:sync`,
then update the `~/.cache/lathe/current` symlink.
This is the prerequisite for external adoption:
users must not need to build the server from source or hand-wire classpaths.

`lathe:sync` generates the launcher directly rather than extracting a bundled tarball:

1. Inject `${plugin.version}` into `SyncMojo` via `@Parameter(defaultValue = "${plugin.version}")`.
2. Skip generation if `~/.cache/lathe/servers/<version>/lathe-launcher.sh` already exists (idempotent).
3. Resolve `io.github.ag-libs:lathe-server:<version>` and all transitive runtime dependencies
   via the already-injected Aether `RepositorySystem`, using the same session repositories available in `SyncMojo`.
4. Collect the absolute `.m2` JAR paths from the resolved artifacts.
5. Render `lathe-launcher.sh` from a Java string template.
   `--module-path` accepts individual JAR files colon-separated — no staging `lib/` directory needed.
6. Write the script to `~/.cache/lathe/servers/<version>/lathe-launcher.sh` and set it executable.
7. Create or replace the `~/.cache/lathe/current` symlink pointing to the version directory.

The generated launcher shape:

```sh
#!/bin/sh
exec java \
  --add-exports jdk.compiler/com.sun.tools.javac.api=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.code=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.comp=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.file=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.main=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.model=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.parser=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.tree=com.google.googlejavaformat \
  --add-exports jdk.compiler/com.sun.tools.javac.util=com.google.googlejavaformat \
  --add-opens jdk.compiler/com.sun.tools.javac.code=com.google.googlejavaformat \
  --add-opens jdk.compiler/com.sun.tools.javac.comp=com.google.googlejavaformat \
  --module-path /abs/.m2/.../lathe-server.jar:/abs/.m2/.../lathe-core.jar:... \
  -m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer "$@"
```

The `--add-exports/--add-opens` flags are hardcoded in the template — they are stable across JDK 21+
and `google-java-format`'s internal javac access does not change between releases.
The module-qualified form (`=com.google.googlejavaformat`) is correct because all deps land on the module path.

Files to add or change:

- `LatheLayout` — add `SERVERS_DIR`, `CURRENT_LINK`, `LAUNCHER_SCRIPT`;
  add `serverVersionDir(String version)` and `currentLink()` helpers.
- `WorkspaceManifestData` — add `serverVersion` field.
- `WorkspaceManifestWriter` — write `serverVersion`.
- `ServerInstaller` (new class in `lathe-maven-plugin`) — steps 2–7 above.
- `SyncMojo` — inject plugin version, call `ServerInstaller.install()`.
- `MultiModuleTest` (IT verify) — assert launcher exists and is executable,
  `current` symlink exists, `workspace.json` contains `serverVersion`.
- Editor setup docs — update to launch `~/.cache/lathe/current/lathe-launcher.sh`.

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
and `LatheLanguageServer` does not advertise workspace-symbol, completion, or code-action capabilities.

**Module metadata in the manifest**
Add reactor module entries to `workspace.json` after the params-file model is stable,
to support staleness detection, UX hints, and faster server startup without duplicating classpaths.
`WorkspaceManifestData` currently holds only schema version, workspace root, JDK source, and dependency sources;
`ModuleRegistry` still discovers modules by scanning `lsp-params-*.json` at startup.

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
