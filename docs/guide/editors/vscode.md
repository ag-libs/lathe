# Lathe in VS Code

Lathe ships a VS Code extension — **Lathe for Java** — a thin client that launches Lathe's
build-derived language server from your project. You get the standard LSP surface (navigation,
completion, diagnostics, hover, symbols, rename). It installs from a GitHub release asset for now; a
Marketplace listing is planned.

The server itself is **not** bundled in the extension — it comes from your Maven build, exactly as for
the other editors. The extension just finds and launches it.

## Install

1. **Build your project with Lathe at least once** so the launcher exists. Any `mvn` build populates
   `.lathe/` (see the [installation guide](../installation.md)); the launcher lands at
   `.lathe/lathe-launcher.sh`.

2. **Download** the latest `lathe-<version>.vsix` from the
   [Releases page](https://github.com/ag-libs/lathe/releases).

3. **Install it:**
   ```bash
   code --install-extension lathe-<version>.vsix
   ```
   (or *Extensions → ⋯ → Install from VSIX…* in the UI).

4. **Open the reactor root** (the folder containing `.lathe/`) as the VS Code workspace and open a Java
   file. The extension launches the server automatically, resolving the project's JDK from
   `.lathe/java-home`.

If VS Code's built-in Java support (the Red Hat "Language Support for Java" extension) is installed,
disable it for this workspace — two servers attaching to `.java` files will conflict.

## What works

Resolved from your real Maven build, through VS Code's standard LSP UI:

| Feature                          | VS Code                                   |
|----------------------------------|-------------------------------------------|
| Go to definition (cross-module)  | `F12` · `Ctrl`-click                      |
| Find references                  | `Shift`+`F12`                             |
| Workspace symbols (reactor-wide) | `Ctrl`+`T`                                |
| Document symbols / outline       | `Ctrl`+`Shift`+`O` · Outline view         |
| Completion (with auto-import)    | automatic · `Ctrl`+Space                  |
| Hover / javadoc                  | hover · `Ctrl`+`K` `Ctrl`+`I`             |
| Signature help                   | automatic · `Ctrl`+`Shift`+Space          |
| Code actions (import, stubs, …)  | `Ctrl`+`.`                                |
| Rename (reactor-wide)            | `F2`                                      |
| Live diagnostics                 | Problems panel · squiggles                |

## Read-only dependency sources

Go-to-definition into a dependency or the JDK opens a source file Lathe extracts to its cache, marked
**read-only on disk** (no write bit). VS Code does not honor that by default — it opens the buffer as
editable and only fails at *save* time ("impossible to save"). Add this to your settings so VS Code
reflects the permission (dependency sources then open locked, with a lock icon, editing disabled):

```jsonc
{
  "files.readonlyFromPermissions": true
}
```

## If the wrong JDK is picked

The extension runs the launcher with the workspace folder as its working directory, so it reads
`.lathe/java-home` automatically — no configuration needed. To override (for example to run Lathe
against an [OpenJDK](../openjdk.md) checkout), set `LATHE_JAVA_HOME` in the environment VS Code is
launched from. To point at a working-tree server build, set `LATHE_SERVER_DIR` the same way.

## Not available yet

Run/test/debug, scaffolding (`:LatheNew`), and format-on-save are **[Neovim-client](neovim.md)**
features today — they use Lathe's custom client commands, not the standard LSP surface. Adding them to
the VS Code extension is [planned](../../planned/lathe-vscode-client.md).
