# Lathe in VS Code (generic LSP bridge)

Lathe has no dedicated VS Code extension yet (it is planned). Until then, VS Code can still drive
Lathe's build-derived intelligence through a **generic LSP bridge** — a small extension that launches
any stdio language server from settings. You get the standard LSP surface (navigation, completion,
diagnostics, hover, symbols, rename); the Lathe-specific run/test/debug and scaffolding commands are
not available this way.

## Setup

1. Install a generic LSP bridge extension. [**Generic LSP Client**
   (`zsol.vscode-glspc`)](https://marketplace.visualstudio.com/items?itemName=zsol.vscode-glspc) is a
   good choice — it exposes a language filter and an environment-variable map, both of which Lathe uses.

2. Point it at Lathe's launcher for Java files. Add to the **workspace** `.vscode/settings.json` in a
   project Lathe has synced (any `mvn` build populates `.lathe/`):

   ```jsonc
   {
     "glspc.server.command": "/home/you/.cache/lathe/current/lathe-launcher.sh",
     "glspc.server.commandArguments": [],
     "glspc.server.languageId": ["java"],
     "files.readonlyFromPermissions": true
   }
   ```

   `glspc.server.languageId` is an **array** — `["java"]`, not `"java"`; a bare string silently
   matches nothing and the server never starts. `files.readonlyFromPermissions` is explained under
   [Read-only dependency sources](#read-only-dependency-sources).

3. Open the **reactor root** (the folder with `.lathe/`) as the VS Code workspace, then open a Java
   file. The bridge launches the server with that folder as its working directory, so Lathe resolves
   the project's JDK from `.lathe/java-home` automatically.

If VS Code's built-in Java support (the Red Hat "Language Support for Java" extension) is installed,
disable it for this workspace — two servers attaching to `.java` files will conflict, and the bridge
registers only one.

## What works

Through VS Code's standard LSP UI, resolved from your real Maven build:

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

Go-to-definition into a dependency or the JDK opens a source file Lathe extracts to its cache, which it
marks **read-only on disk** (no write bit). Neovim honors that automatically; VS Code does not — by
default it opens the buffer as editable and only fails at *save* time ("impossible to save"). Set
`files.readonlyFromPermissions: true` (in the settings block above) so VS Code reflects the permission:
dependency sources then open locked, with a lock icon in the title bar and editing disabled.

## If the wrong JDK is picked

The launcher reads `.lathe/java-home` relative to the server's working directory. If a bridge does not
launch the server in the workspace folder, Lathe falls back to `java` on `PATH` — which may be the
wrong JDK and produce spurious diagnostics. Pin it explicitly with the bridge's environment map (the
value is the line in `.lathe/java-home`):

```jsonc
{
  "glspc.server.environmentVariables": {
    "LATHE_JAVA_HOME": "/path/to/the/jdk/the/build/used"
  }
}
```

This is also how you run Lathe against an [OpenJDK](../openjdk.md) checkout, whose server must run on
the exploded build image rather than any JDK on `PATH`.

## Not available via a generic bridge

Run/test/debug, scaffolding (`:LatheNew`), and format-on-save are **[Neovim-client](neovim.md)**
features — they use Lathe's custom client commands, not the standard LSP surface. A dedicated VS Code
client that adds them is [planned](../../planned/lathe-vscode-client.md).
