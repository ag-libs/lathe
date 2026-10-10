# Lathe in Zed

Zed runs language servers that an extension registers for a language.
Lathe plugs into the slot Zed's official **Java** extension registers for Java (`jdtls`):
a project settings file points that slot at Lathe's launcher.
No Lathe plugin, no extra config beyond one file.

![Lathe in Zed — hover, cross-module nav, go to implementation, extract, rename, completion, live diagnostics](../../zed-tour-6d296f3f.gif)

## Setup

1. Install the **Java** extension (`zed: extensions` → search "Java").
   It provides the Java grammar and the `jdtls` server slot Lathe runs in.
2. Add `.zed/settings.json` at the reactor root (where `.lathe/` lives):

   ```json
   {
     "lsp": {
       "jdtls": {
         "binary": {
           "path": "/bin/sh",
           "arguments": ["-c", "exec .lathe/lathe-launcher.sh"]
         }
       }
     },
     "languages": {
       "Java": { "language_servers": ["jdtls"] }
     }
   }
   ```

3. Open the project folder in Zed and choose **Trust and Continue** when Zed asks.
   Zed opens unrecognized projects in Restricted Mode, which ignores `.zed/settings.json` and starts no
   language servers until the project is trusted.

The launcher is the symlink `lathe:sync` links at `<root>/.lathe/`, pinned to the server version this
project uses; any `mvn` build populates it.
Zed starts language servers with the worktree root as their working directory, so the relative path
resolves there (and the launcher finds `.lathe/java-home` and `.lathe/jvm.args` relative to it).
Setting `binary.path` replaces the Java extension's own launch command, so Eclipse JDT LS is never
downloaded or started.
Zed's UI and logs still label the server `jdtls`.

## What works

All through Zed's built-in editor features, resolved from your real Maven build:

| Feature                          | Zed command                                   |
|----------------------------------|-----------------------------------------------|
| Hover / javadoc                  | hover, or `editor: hover`                     |
| Go to definition (cross-module)  | `editor: go to definition`                    |
| Go to implementation             | `editor: go to implementation`                |
| Find references                  | `editor: find all references`                 |
| Workspace symbols (reactor-wide) | `project symbols: toggle`                     |
| Completion                       | as you type                                   |
| Rename                           | `editor: rename`                              |
| Extract variable / refactors     | `editor: toggle code actions`                 |
| Live diagnostics                 | inline · `diagnostics: deploy`                |

## Not available in Zed

Run/test/debug, scaffolding (`:LatheNew`), and format-on-save are **[Neovim-client](neovim.md)**
features — they use custom client commands, not the standard LSP surface.
The Java extension's debugger runs inside Eclipse JDT LS, so it does not work with Lathe in its slot.
