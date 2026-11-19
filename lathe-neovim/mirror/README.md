# lathe.nvim

`lathe.nvim` connects Neovim to Lathe, a Java language server for Maven, Gradle, and the OpenJDK build.
Lathe reads the classpath and compiler options from your build so Neovim can complete Java names, show compiler errors as you edit, and jump to declarations in other modules.

[![Java editing, running, and testing in Neovim](https://raw.githubusercontent.com/ag-libs/lathe/main/docs/demo-038eec79.gif)](https://github.com/ag-libs/lathe#readme)

[Use the demo’s Neovim config](https://github.com/ag-libs/lathe/blob/main/examples/nvim/README.md)

## Install

Use Neovim 0.12 or later on Linux or macOS.
Install the plugin once with either package manager below, then [set up each project](#set-up-your-first-project) through its build.

### Built-in package manager

Add these lines to `~/.config/nvim/init.lua`, restart Neovim, and accept the install prompt.

```lua
vim.pack.add({ "https://github.com/ag-libs/lathe.nvim" })
require("lathe").setup()
```

### lazy.nvim

If your config imports `lua/plugins/`, save this as `~/.config/nvim/lua/plugins/lathe.lua`.

```lua
return {
  "ag-libs/lathe.nvim",
  ft = "java",
  cmd = "LatheStart",
  main = "lathe",
  opts = {},
}
```

Restart Neovim and run `:Lazy install`.
The `ft = "java"` setting loads the plugin when you open a Java file.

## Set up your first project

1. Run the first build from the project root using the instructions for [Maven](https://github.com/ag-libs/lathe/blob/main/docs/guide/maven.md), [Gradle](https://github.com/ag-libs/lathe/blob/main/docs/guide/gradle.md#setup), or an [OpenJDK checkout](https://github.com/ag-libs/lathe/blob/main/docs/guide/openjdk.md#setup).
   This installs the language server and records how your project compiles.
2. Open a Java source file in that project.
   Neovim starts Lathe automatically.
3. Once the workspace has loaded, use **Go to Definition** on a call to a method in your project.
   Neovim opens the method’s declaration.

For Java indentation, install the Java Tree-sitter parser (`:TSInstall java` with nvim-treesitter).
For keymaps, test results, and debugging, see the [Neovim guide](https://github.com/ag-libs/lathe/blob/main/docs/guide/editors/neovim.md).

## Updating

Update the client through your plugin manager.
To update a server registered in the build, bump the Lathe version in Maven or Gradle and build again.
[Updating Lathe](https://github.com/ag-libs/lathe/blob/main/docs/guide/editors/neovim.md#updating)

## Feedback

This repository is generated from the Neovim client in the main Lathe repository for each release.
Each mirror commit identifies its source revision.
[Lathe releases](https://github.com/ag-libs/lathe/releases)

Please report bugs or suggest fixes in [Lathe issues](https://github.com/ag-libs/lathe/issues).
External code contributions are currently closed.
[Contributing policy](https://github.com/ag-libs/lathe/blob/main/CONTRIBUTING.md#pull-requests)

## License

[Apache License 2.0](https://github.com/ag-libs/lathe.nvim/blob/main/LICENSE)
