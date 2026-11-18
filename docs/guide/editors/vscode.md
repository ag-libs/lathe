# Lathe in VS Code and Cursor

Use **Lathe for Java** to follow a method into another module, rename it across the project, and see compiler errors in the Problems panel.
The preview extension works in VS Code 1.84 or later and in Cursor versions based on it.
Lathe's run, test, and debug controls are available in Neovim.

## Install

1. Run the [first build](../../../README.md#first-build) at the root of your project.
   This installs the language server and writes its launcher under `.lathe/`.
2. Download the `lathe-<version>.vsix` file from [Lathe releases](https://github.com/ag-libs/lathe/releases).
3. In the Extensions view, choose **⋯ → Install from VSIX…** and select the downloaded file.
   From the terminal, you can also run `code --install-extension` followed by the path to the file.
4. Open the project folder.
   If **Language Support for Java by Red Hat** is installed, disable it for this workspace so only one server handles Java files.
5. Open a Java source file and choose **Go to Definition** on a method call.
   Opening a module folder also works when the extension can find the project's `.lathe/` in a parent directory.

## What works

| Feature                          | VS Code                                   |
|----------------------------------|-------------------------------------------|
| Go to definition (cross-module)  | `F12` · `Ctrl`-click                      |
| Find references                  | `Shift`+`F12`                             |
| Workspace symbols (project-wide) | `Ctrl`+`T`                                |
| Document symbols / outline       | `Ctrl`+`Shift`+`O` · Outline view         |
| Completion (with auto-import)    | automatic · `Ctrl`+Space                  |
| Hover / javadoc                  | hover · `Ctrl`+`K` `Ctrl`+`I`             |
| Signature help                   | automatic · `Ctrl`+`Shift`+Space          |
| Code actions (import, extract variable / method, stubs, …) | `Ctrl`+`.`                                |
| Rename (project-wide)            | `F2`                                      |
| Live diagnostics                 | Problems panel · squiggles                |
| Formatting (the project's Spotless formatter on Maven, or the formatter its `lathe-style.json` declares) | `Shift`+`Alt`+`F` · `editor.formatOnSave` |

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
Build the project once (with `mvnw.cmd`, `mvn`, or `gradlew.bat`); the build writes `.lathe\lathe-launcher.cmd`, and the extension starts it through `cmd.exe`, so there is nothing to configure.
Set `LATHE_JAVA_HOME` or `LATHE_SERVER_DIR` as Windows environment variables, or with `set` in the `cmd` window you start `code` or `cursor` from.

## Build-file changes

After a build-file or module-structure change, the server shows a prompt to sync.
The extension cannot run the sync for you yet: choosing *Sync* does nothing, so run
`.lathe/lathe-sync.sh` (`.lathe\lathe-sync.cmd` on Windows) or your build in a terminal.
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
