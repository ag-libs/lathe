# Lathe in Neovim

Open a Java file in a project you’ve built with Lathe and Neovim starts the language server.
Use **Go to Definition** on a method call to open its declaration, even in another module.

## Install

Use Neovim 0.12 or later on Linux or macOS.
Install `ag-libs/lathe.nvim` with the [lazy.nvim or built-in package manager instructions](../../../README.md#install-the-plugin).
If you use lazy.nvim, `ft = "java"` loads the plugin when you open a Java file, and `opts = {}` calls `require("lathe").setup()`.

### Set up a project

1. Follow the [first build](../../../README.md#first-build) for Maven, Gradle, or the OpenJDK checkout.
   The build installs the server and records the compiler settings for each module.
2. Open a Java source file and wait for the workspace to load.
   Try **Go to Definition** or start typing a method name and invoke completion.
3. After changing dependencies, compiler settings, or the set of modules, run `:LatheSync`.
   Ordinary Java edits are picked up automatically.
   Use `:LatheSyncOutput` to read the build output.

<details>
<summary>Set up a project from Neovim with a client that provides <code>:LatheInit</code></summary>

Run `:LatheInit` from a project file to start the first build.
Confirm the build root and Lathe version in the prompt.
Maven and Gradle projects can use this setup without changing their build files.
For OpenJDK, build the JDK first with `make jdk`.
In a development checkout, set `LATHE_VERSION` to the version to install.
See `:help :LatheInit` in clients that ship the command.

</details>

## Editing Java

Use **Find References** on a method to see its callers, or **Rename** to update a declaration and its uses across modules.
Compiler errors appear as you type.
Saving also runs the checks your build applies through compiler plugins and annotation processors. [Live and save diagnostics](../how-it-works.md#diagnostics-live-vs-on-save)

| When you want to… | Use |
| --- | --- |
| Resolve the missing imports in a file | `:LatheMissingImports`. Choose the intended type when several match. |
| Open a class’s test, or return from a test to its class | `:LatheGoToTest` |
| Find where a type is constructed | `:LatheInstances` |
| Browse a type’s superclasses and subtypes | `:LatheTypeHierarchy` |
| Open a resource in the project or a dependency | `:LatheResourceFind [name]` |

For **Go to Definition**, Neovim 0.12 uses `Ctrl-]` with an attached language server.
The demo maps `gd` to the same action through Telescope.
For completion as you type, the demo uses blink.cmp. [Example config and keymaps](../../../examples/nvim/README.md#keymaps) · [Java features](../features.md)

### Create a new type

Run `:LatheNew class` in a Java file to create a class in its package, or `:LatheNew test` to create its JUnit test in the test source tree.
To pick a different module or package, run `:LatheNew` and choose the kind, destination, and name.
Lathe opens the new file, or the existing file if the name is already taken.

## Run a `main`

Place the cursor inside a `main` method and run `:LatheRun`.
Program output opens in a split at the bottom of the screen.
Press Enter on a stack-trace frame in that split to open the source line.

Use `:LatheRunStop` to stop a run or debug session and `:LatheRunOutput` to reopen its output.
To reuse program arguments, JVM flags, or environment variables, save the target with `:LatheRunSave dev` and launch it again with `:LatheRun dev`. [Run configuration](../run-configuration.md)

## Tests (neotest)

Install [neotest](https://github.com/nvim-neotest/neotest) and register the Lathe adapter in your neotest configuration.
If you already have an `adapters` list, add Lathe alongside the others.

```lua
require("neotest").setup({ adapters = { require("lathe.neotest") } })
```

The [demo config](../../../examples/nvim/init.lua) includes the full lazy.nvim spec.

To run one JUnit test, put the cursor inside its method and enter this command.

```vim
:lua require("neotest").run.run()
```

The gutter shows whether it passed.
Use `:lua require("neotest").summary.toggle()` to browse results or `:LatheRunOutput` to see what the test printed.
If the module has no recorded test launch, run `:LatheSyncCaptureTest`, then try again.
Maven Failsafe integration tests run through Maven. [Supported tests and build settings](../test-capture.md#requirements-and-current-limits)

## Debug (nvim-dap)

Load [nvim-dap](https://github.com/mfussenegger/nvim-dap) before Lathe so the plugin can register `:LatheDebug`.
With lazy.nvim, add this line to the Lathe plugin spec.

```lua
dependencies = { "mfussenegger/nvim-dap" },
```

1. Put the cursor on a line you want to inspect and run `:lua require("dap").toggle_breakpoint()`.
2. Move the cursor inside the test or `main` method you want to run and enter `:LatheDebug`.
3. When execution stops at the breakpoint, inspect variables in your debugger UI or open the REPL with `:lua require("dap").repl.open()`.
   Use `:lua require("dap").continue()` to resume.

Program output appears in the same split as a normal run.
For a shortcut, bind `:LatheDebug` in your config, for example with `vim.keymap.set("n", "<leader>dd", "<cmd>LatheDebug<cr>")`.

## Formatting & indentation

For Java indentation, install the Java Tree-sitter parser (`:TSInstall java` with nvim-treesitter).
Lathe uses the project’s indentation settings when available and otherwise follows `.editorconfig` by default.

Run `:LatheFormat` to format the file with the project’s formatter.
Maven projects can use the formatter from Spotless.
Other projects can declare one in `lathe-style.json`. [Choose a project formatter](../installation.md#choosing-overriding-or-opting-out-of-the-formatter)

To format on save, set `format_on_save = true` in your existing Lathe setup options.
With lazy.nvim, that is `opts = { format_on_save = true }`.
For a project that declares no indentation style, you can set a personal fallback with `style = { indent = { profile = "google" } }` in the same options.

## Updating

Update `lathe.nvim` through your plugin manager.
For a server registered in Maven or Gradle, update the Lathe version in the build and run the build again.
For a project set up only through `:LatheInit`, rerun that command after updating the client.

## Verbose logging

If the server exits unexpectedly, use `:LatheStart` to restart it in the current buffer.
For a missing launcher, rerun the [first build](../../../README.md#first-build) from the project root.
To investigate a repeated failure, start Neovim with `LATHE_DEBUG=1` and reproduce the problem.
Use `:lua print(vim.lsp.log.get_filename())` to find the log. [Troubleshooting](../troubleshooting.md)
