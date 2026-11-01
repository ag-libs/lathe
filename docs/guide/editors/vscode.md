# Lathe in VS Code and Cursor

Lathe ships a VS Code extension — **Lathe for Java** — a thin client that launches Lathe's
build-derived language server from your project. The same extension runs in Cursor. You get the standard LSP surface (navigation,
completion, diagnostics, hover, symbols, rename). It installs from a GitHub release asset for now; a
Marketplace listing is planned.

The server itself is **not** bundled in the extension — it comes from your build, exactly as for
the other editors. The extension just finds and launches it.

## Install

The extension needs VS Code 1.84 or later (or a Cursor release built on it).
It activates when the workspace contains a `.lathe/` directory or a Java file opens.

1. **Build your project with Lathe at least once** so the launcher exists: a Lathe-enabled build (for Maven, the first build `mvn clean test -Dlathe.capture.only=true`) populates
   `.lathe/` (see the [installation guide](../installation.md)); the launcher lands at
   `.lathe/lathe-launcher.sh` (`.lathe\lathe-launcher.cmd` on Windows).

2. **Download** the latest `lathe-<version>.vsix` from the
   [Releases page](https://github.com/ag-libs/lathe/releases).

3. **Install it:**
   ```bash
   code --install-extension lathe-<version>.vsix
   ```
   (or *Extensions → ⋯ → Install from VSIX…* in the UI).

4. **Open the reactor root** (the folder containing `.lathe/`) as the VS Code workspace and open a Java
   file. The extension launches the server automatically, resolving the project's JDK from
   `.lathe/java-home`. A module folder opened on its own works too: the extension starts the launcher of
   the nearest `.lathe/` above it.

If VS Code's built-in Java support (the Red Hat "Language Support for Java" extension) is installed,
disable it for this workspace — two servers attaching to `.java` files will conflict.

## What works

Resolved from your real build, through VS Code's standard LSP UI:

| Feature                          | VS Code                                   |
|----------------------------------|-------------------------------------------|
| Go to definition (cross-module)  | `F12` · `Ctrl`-click                      |
| Find references                  | `Shift`+`F12`                             |
| Workspace symbols (reactor-wide) | `Ctrl`+`T`                                |
| Document symbols / outline       | `Ctrl`+`Shift`+`O` · Outline view         |
| Completion (with auto-import)    | automatic · `Ctrl`+Space                  |
| Hover / javadoc                  | hover · `Ctrl`+`K` `Ctrl`+`I`             |
| Signature help                   | automatic · `Ctrl`+`Shift`+Space          |
| Code actions (import, extract variable / method, stubs, …) | `Ctrl`+`.`                                |
| Rename (reactor-wide)            | `F2`                                      |
| Live diagnostics                 | Problems panel · squiggles                |
| Formatting (the project's Spotless formatter) | `Shift`+`Alt`+`F` · `editor.formatOnSave` |

## Read-only dependency sources

Go-to-definition into a dependency or the JDK opens a source file Lathe extracts to its cache.
The extension marks everything under `~/.cache/lathe/` read-only (it contributes a
`files.readonlyInclude` default for `**/.cache/lathe/**`), so these files open locked, with a lock icon.
Nothing to configure, unless you moved the cache with `-Dlathe.cache=<dir>`: then add that directory to
`files.readonlyInclude` yourself:

```jsonc
{
  "files.readonlyInclude": { "/path/to/lathe-cache/**": true }
}
```

## If the wrong JDK is picked

The extension runs the launcher from the workspace root (the nearest `.lathe/` at or above the folder), so it reads
`.lathe/java-home` automatically — no configuration needed, an [OpenJDK](../openjdk.md) checkout
included (its sync records the JDK it built). To override it, set `LATHE_JAVA_HOME` in the environment
VS Code is launched from. To point at a working-tree server build, set `LATHE_SERVER_DIR` the same way.

## Windows and Cursor

The extension works the same on Windows, in VS Code and in Cursor (install the `.vsix` with *Extensions → ⋯ → Install from VSIX…*).
Build the project once with `mvnw.cmd` or `mvn`; the build writes `.lathe\lathe-launcher.cmd`, and the extension starts it through `cmd.exe`, so there is nothing to configure.
Set `LATHE_JAVA_HOME` or `LATHE_SERVER_DIR` as Windows environment variables, or with `set` in the `cmd` window you start `code` or `cursor` from.

## Build-file changes

After a `pom.xml` or module-structure change, the server shows a prompt to sync.
The extension cannot run the sync for you yet: choosing *Sync* does nothing, so run
`.lathe/lathe-sync.sh` (`.lathe\lathe-sync.cmd` on Windows) or your Maven build in a terminal.
Source changes made outside the editor need no sync; the server compiles them in-process.

## Known issues

- **One Lathe project per window.** In a multi-root workspace each Lathe folder starts its own server,
  but the extension does not scope documents to their folder, so every server may receive every
  folder's Java files.
  Open each project in its own window for now.
- **"Add missing imports…" does nothing.** The whole-file action has no effect in VS Code yet; the single-type quick fixes (*Import 'java.util.List'*) work.

## Not available yet

Run/test/debug and scaffolding (`:LatheNew`) are **[Neovim-client](neovim.md)**
features today — they use Lathe's custom client commands, not the standard LSP surface. Adding them to
the VS Code extension is [planned](../../planned/lathe-vscode-client.md).
