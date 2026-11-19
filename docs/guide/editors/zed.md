# Lathe in Zed

Use Zed's Java extension with Lathe to jump across modules, rename symbols, and see errors from the compiler your build uses.
A project setting tells Zed to start the Lathe launcher for Java files.

![Lathe in Zed — hover, cross-module nav, go to implementation, extract, rename, completion, live diagnostics](../../zed-tour-6d296f3f.gif)

## Setup

Complete the [first build](../../../README.md#first-build) so the project has a `.lathe/` launcher.

1. Install the **Java** extension (`zed: extensions` → search "Java").
   It provides the Java grammar and the `jdtls` server slot Lathe runs in.
2. Add `.zed/settings.json` at the root of the build (where `.lathe/` lives):

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

   On Windows, replace the `binary` entry in the example above with this entry.

   ```json
   "binary": {
     "path": "cmd.exe",
     "arguments": ["/d", "/s", "/c", ".lathe\\lathe-launcher.cmd"]
   }
   ```

3. Open the project folder in Zed and choose **Trust and Continue** when Zed asks.
   Zed opens unrecognized projects in Restricted Mode, which ignores `.zed/settings.json` and starts no
   language servers until the project is trusted.

Open a Java file and choose **Go to Definition** on a method call.
Zed launches the server from the worktree root, so open the folder that contains `.lathe/`.
The UI and logs display `jdtls`, the name registered by the Java extension, even though the configured launcher runs Lathe.

## What works

| Feature                          | Zed command                                   |
|----------------------------------|-----------------------------------------------|
| Hover / javadoc                  | hover, or `editor: hover`                     |
| Go to definition (cross-module)  | `editor: go to definition`                    |
| Go to implementation             | `editor: go to implementation`                |
| Find references                  | `editor: find all references`                 |
| Workspace symbols (project-wide) | `project symbols: toggle`                     |
| Completion                       | as you type                                   |
| Rename                           | `editor: rename`                              |
| Extract variable / method / refactors | `editor: toggle code actions`                 |
| Live diagnostics                 | inline · `diagnostics: deploy`                |
| Formatting                       | `editor: format` · format on save             |

## Not available in Zed

Run/test/debug and scaffolding (`:LatheNew`) are **[Neovim-client](neovim.md)**
features — they use custom client commands, not the standard LSP surface.
The Java extension's debugger runs inside Eclipse JDT LS, so it does not work with Lathe in its slot.
